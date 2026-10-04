package todoistcaldavsync.planner.adapters

import groovy.json.JsonSlurper
import todoistcaldavsync.planner.domain.WeatherForecast
import todoistcaldavsync.planner.domain.WeatherInterval

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.function.Function

/**
 * Open-Meteo adapter. Provider JSON stays here; domain model is provider-neutral.
 * HTTP transport is injectable for fixture-backed tests (no network).
 */
class OpenMeteoWeatherGateway implements WeatherReadGateway {
    static final String DEFAULT_BASE_URL = 'https://api.open-meteo.com/v1/forecast'
    static final String PROVIDER_ID = 'open_meteo'
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10)
    /** Default max response body size (bytes) for HTTP transport and injected bodies. */
    static final long DEFAULT_MAX_RESPONSE_BYTES = 1_048_576L

    private final double latitude
    private final double longitude
    private final ZoneId timezone
    private final int forecastDays
    private final String baseUrl
    private final Duration timeout
    private final long maxResponseBytes
    private final Function<URI, HttpResult> transport
    private final Instant retrievedAtOverride

    OpenMeteoWeatherGateway(double latitude, double longitude, ZoneId timezone,
                            int forecastDays = 7,
                            String baseUrl = DEFAULT_BASE_URL,
                            Duration timeout = DEFAULT_TIMEOUT,
                            Function<URI, HttpResult> transport = null,
                            Instant retrievedAtOverride = null,
                            long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES) {
        requireFiniteCoord('latitude', latitude, -90d, 90d)
        requireFiniteCoord('longitude', longitude, -180d, 180d)
        if (timezone == null) {
            throw new IllegalArgumentException('timezone is required')
        }
        if (forecastDays < 1 || forecastDays > 16) {
            throw new IllegalArgumentException("forecastDays must be 1..16, got: ${forecastDays}")
        }
        Duration resolvedTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT
        if (resolvedTimeout.isZero() || resolvedTimeout.isNegative() || resolvedTimeout.toMillis() > Duration.ofMinutes(10).toMillis()) {
            throw new IllegalArgumentException(
                "timeout must be a finite positive duration up to 10 minutes, got: ${resolvedTimeout}")
        }
        if (maxResponseBytes <= 0L) {
            throw new IllegalArgumentException(
                "maxResponseBytes must be a positive bound, got: ${maxResponseBytes}")
        }
        this.latitude = latitude
        this.longitude = longitude
        this.timezone = timezone
        this.forecastDays = forecastDays
        this.baseUrl = (baseUrl ?: DEFAULT_BASE_URL).toString()
        this.timeout = resolvedTimeout
        this.maxResponseBytes = maxResponseBytes
        this.transport = transport ?: defaultTransport(this.timeout, this.maxResponseBytes)
        this.retrievedAtOverride = retrievedAtOverride
    }

    Duration getTimeout() {
        timeout
    }

    long getMaxResponseBytes() {
        maxResponseBytes
    }

    static void requireFiniteCoord(String name, double value, double min, double max) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("${name} must be finite, got: ${value}")
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("${name} out of range: ${value}")
        }
    }

    /**
     * Build the Open-Meteo request URI for the configured location/horizon.
     */
    URI buildRequestUri(Instant rangeStart = null, Instant rangeEnd = null) {
        int days = forecastDays
        if (rangeStart != null && rangeEnd != null && rangeEnd.isAfter(rangeStart)) {
            long spanDays = Duration.between(rangeStart, rangeEnd).toDays() + 1
            days = (int) Math.min(16L, Math.max((long) forecastDays, spanDays))
        }
        String tz = URLEncoder.encode(timezone.id, StandardCharsets.UTF_8)
        String hourly = URLEncoder.encode(
            'precipitation_probability,precipitation,weather_code,temperature_2m,wind_speed_10m,is_day',
            StandardCharsets.UTF_8)
        String daily = URLEncoder.encode('sunrise,sunset', StandardCharsets.UTF_8)
        String q = "latitude=${formatCoord(latitude)}" +
            "&longitude=${formatCoord(longitude)}" +
            "&timezone=${tz}" +
            "&forecast_days=${days}" +
            "&hourly=${hourly}" +
            "&daily=${daily}" +
            "&wind_speed_unit=kmh" +
            "&precipitation_unit=mm"
        String sep = baseUrl.contains('?') ? '&' : '?'
        return URI.create(baseUrl + sep + q)
    }

    @Override
    WeatherForecast fetchForecast(Instant rangeStart, Instant rangeEnd) {
        URI uri = buildRequestUri(rangeStart, rangeEnd)
        HttpResult result
        try {
            result = transport.apply(uri)
        } catch (WeatherGatewayException e) {
            throw e
        } catch (Exception e) {
            Throwable root = unwrapTransportCause(e)
            Exception cause = root instanceof Exception ? (Exception) root : e
            throw new WeatherGatewayException('TRANSPORT', safeTransportMessage(cause), cause)
        }
        if (result == null) {
            throw new WeatherGatewayException('TRANSPORT', 'Open-Meteo transport returned null')
        }
        if (result.statusCode < 200 || result.statusCode >= 300) {
            throw new WeatherGatewayException('HTTP_STATUS',
                "Open-Meteo HTTP ${result.statusCode}: ${truncate(result.body, 200)}")
        }
        if (result.body == null || result.body.trim().isEmpty()) {
            throw new WeatherGatewayException('CONTENT', 'Open-Meteo response body is empty')
        }
        enforceBodySizeLimit(result.body, maxResponseBytes)
        def parsed
        try {
            parsed = new JsonSlurper().parseText(result.body)
        } catch (Exception e) {
            throw new WeatherGatewayException('MALFORMED_JSON',
                "Open-Meteo response is not valid JSON: ${e.message}", e)
        }
        if (!(parsed instanceof Map)) {
            throw new WeatherGatewayException('SCHEMA', 'Open-Meteo root must be a JSON object')
        }
        Instant retrieved = retrievedAtOverride ?: Instant.now()
        return parsePayload(parsed as Map, retrieved)
    }

    /**
     * Parse a recorded Open-Meteo JSON map into the provider-neutral model.
     */
    WeatherForecast parsePayload(Map root, Instant retrievedAt) {
        if (root == null) {
            throw new WeatherGatewayException('SCHEMA', 'Open-Meteo payload must not be null')
        }
        ZoneId tz = timezone
        def tzRaw = root.timezone?.toString()
        if (tzRaw) {
            try {
                tz = ZoneId.of(tzRaw)
            } catch (Exception ignored) {
                // keep configured timezone; never invent UTC for labeled local times
            }
        }
        double lat = root.latitude != null ? toFiniteCoord('latitude', root.latitude, latitude) : latitude
        double lon = root.longitude != null ? toFiniteCoord('longitude', root.longitude, longitude) : longitude
        requireFiniteCoord('latitude', lat, -90d, 90d)
        requireFiniteCoord('longitude', lon, -180d, 180d)

        def hourly = root.hourly
        if (!(hourly instanceof Map)) {
            throw new WeatherGatewayException('SCHEMA', 'Open-Meteo payload missing hourly object')
        }
        List times = hourly.time instanceof List ? hourly.time as List : null
        if (!times) {
            throw new WeatherGatewayException('SCHEMA', 'Open-Meteo hourly.time is required')
        }
        List precipProb = listOrNull(hourly.precipitation_probability)
        List precip = listOrNull(hourly.precipitation)
        List codes = listOrNull(hourly.weather_code)
        List temps = listOrNull(hourly.temperature_2m)
        List winds = listOrNull(hourly.wind_speed_10m)
        List isDay = listOrNull(hourly.is_day)
        requireHourlyLength(times, precipProb, 'precipitation_probability')
        requireHourlyLength(times, precip, 'precipitation')
        requireHourlyLength(times, codes, 'weather_code')
        requireHourlyLength(times, temps, 'temperature_2m')
        requireHourlyLength(times, winds, 'wind_speed_10m')
        requireHourlyLength(times, isDay, 'is_day')

        // Fold-aware parse: repeated ambiguous local civil hours (DST fall-back) map to
        // distinct increasing instants (earlier offset first, then later). Explicit offsets win.
        Map<LocalDateTime, Integer> foldCounts = new HashMap<>()
        List<Instant> starts = new ArrayList<>(times.size())
        for (int i = 0; i < times.size(); i++) {
            starts << parseProviderLocalDateTime(times[i]?.toString(), tz, foldCounts)
        }
        for (int i = 1; i < starts.size(); i++) {
            if (!starts[i].isAfter(starts[i - 1])) {
                throw new WeatherGatewayException('SCHEMA',
                    "Open-Meteo hourly.time must be strictly increasing after DST fold resolution " +
                        "(index ${i}: ${starts[i]} is not after ${starts[i - 1]})")
            }
        }

        List<WeatherInterval> intervals = []
        for (int i = 0; i < starts.size(); i++) {
            Instant start = starts[i]
            Instant end = (i + 1 < starts.size()) ? starts[i + 1] : start + Duration.ofHours(1)
            if (!end.isAfter(start)) {
                throw new WeatherGatewayException('SCHEMA',
                    "Open-Meteo hourly bucket end must be after start at index ${i}: [${start}, ${end})")
            }
            Integer code = intAt(codes, i, 'weather_code')
            intervals << WeatherInterval.builder()
                .start(start)
                .end(end)
                .precipitationProbability(doubleAt(precipProb, i, 'precipitation_probability'))
                .precipitationMm(doubleAt(precip, i, 'precipitation'))
                .weatherCode(code)
                .condition(conditionForCode(code))
                .temperatureC(doubleAt(temps, i, 'temperature_2m'))
                .windSpeedKph(doubleAt(winds, i, 'wind_speed_10m'))
                .daylight(boolAt(isDay, i))
                .build()
        }
        // Preserve provider order (already strictly increasing); do not re-sort folds away.

        Map<LocalDate, WeatherForecast.DaylightWindow> daylight = new LinkedHashMap<>()
        def daily = root.daily
        if (daily instanceof Map && daily.time instanceof List) {
            List dTimes = daily.time as List
            List sunrises = listOrNull(daily.sunrise)
            List sunsets = listOrNull(daily.sunset)
            for (int i = 0; i < dTimes.size(); i++) {
                LocalDate date = parseLocalDate(dTimes[i]?.toString(), tz)
                if (date == null) {
                    continue
                }
                Instant sunrise = sunrises ? parseProviderLocalDateTime(sunrises[i]?.toString(), tz) : null
                Instant sunset = sunsets ? parseProviderLocalDateTime(sunsets[i]?.toString(), tz) : null
                if (sunrise != null && sunset != null) {
                    daylight[date] = new WeatherForecast.DaylightWindow(date, sunrise, sunset)
                }
            }
        }

        Instant issuedAt = intervals ? intervals.collect { it.start }.min() : (retrievedAt ?: Instant.EPOCH)
        // Prefer generationtime / explicit issued if present
        if (root.generated_at != null) {
            try {
                issuedAt = Instant.parse(root.generated_at.toString())
            } catch (Exception ignored) {
            }
        } else if (root.utc_offset_seconds != null && intervals) {
            // Open-Meteo does not always emit issued-at; use retrieval as issuance for freshness
            issuedAt = retrievedAt ?: issuedAt
        } else if (retrievedAt != null) {
            issuedAt = retrievedAt
        }

        return WeatherForecast.builder()
            .provider(PROVIDER_ID)
            .issuedAt(issuedAt)
            .retrievedAt(retrievedAt ?: Instant.now())
            .latitude(lat)
            .longitude(lon)
            .timezone(tz)
            .intervals(intervals)
            .daylightByDate(daylight)
            .metadata([
                baseUrl      : baseUrl,
                forecastDays : forecastDays,
                generationtime_ms: root.generationtime_ms
            ])
            .build()
    }

    static String formatCoord(double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("coordinate must be finite for URL formatting, got: ${v}")
        }
        // Trim trailing zeros for stable URLs without scientific notation
        String s = String.format(java.util.Locale.US, '%.6f', v)
        s = s.replaceAll(/0+$/, '').replaceAll(/\.$/, '')
        if (s.toLowerCase(Locale.ROOT).contains('nan') || s.toLowerCase(Locale.ROOT).contains('inf')) {
            throw new IllegalArgumentException("coordinate formatting produced non-finite token: ${s}")
        }
        return s
    }

    /**
     * Parse Open-Meteo local civil timestamps. Accepts:
     * - ISO offset/Z (Instant.parse) — explicit offsets remain authoritative
     * - local date-time without offset, interpreted in provider timezone (never forced UTC)
     *
     * DST fall-back: repeated ambiguous local civil times use successive valid offsets
     * (earlier offset first, later offset next). More repeats than valid offsets => SCHEMA.
     * DST spring gap: nonexistent civil times are rejected as SCHEMA (no silent shift).
     */
    static Instant parseProviderLocalDateTime(String raw, ZoneId zone) {
        return parseProviderLocalDateTime(raw, zone, null)
    }

    /**
     * @param foldCounts mutable per-local-datetime occurrence counters for ambiguous folds;
     *                   null disables fold sequencing (single occurrence uses earlier offset)
     */
    static Instant parseProviderLocalDateTime(String raw, ZoneId zone, Map<LocalDateTime, Integer> foldCounts) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new WeatherGatewayException('SCHEMA', 'timestamp value is blank')
        }
        if (zone == null) {
            throw new WeatherGatewayException('SCHEMA', 'timezone is required for local timestamps')
        }
        String s = raw.trim()
        // Instant with Z or explicit numeric offset — authoritative, no fold bookkeeping
        try {
            if (s.endsWith('Z') || s =~ /[+-]\d{2}:\d{2}$/ || s =~ /[+-]\d{4}$/) {
                return Instant.parse(s.contains('T') ? s : s)
            }
        } catch (DateTimeParseException ignored) {
        }
        try {
            if (s.contains('T') && (s.endsWith('Z') || s.indexOf('+', 10) > 0 || s.lastIndexOf('-') > 10)) {
                return Instant.parse(s)
            }
        } catch (DateTimeParseException ignored) {
        }
        // Local civil time in provider zone
        try {
            String normalized = s.replace(' ', 'T')
            if (normalized.length() == 10) {
                LocalDate d = LocalDate.parse(normalized)
                LocalDateTime startOfDay = d.atStartOfDay()
                return resolveLocalCivilInstant(startOfDay, zone, foldCounts, raw)
            }
            LocalDateTime ldt = LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            return resolveLocalCivilInstant(ldt, zone, foldCounts, raw)
        } catch (WeatherGatewayException e) {
            throw e
        } catch (DateTimeParseException e) {
            throw new WeatherGatewayException('SCHEMA', "Cannot parse timestamp '${raw}' in zone ${zone.id}", e)
        }
    }

    /**
     * Resolve local civil time via ZoneRules valid offsets.
     * Overlap: first occurrence → earlier offset, second → later; excess → SCHEMA.
     * Gap (empty valid offsets): SCHEMA — do not silently shift into existence.
     */
    static Instant resolveLocalCivilInstant(LocalDateTime ldt, ZoneId zone,
                                            Map<LocalDateTime, Integer> foldCounts, String raw) {
        List<ZoneOffset> valid = zone.rules.getValidOffsets(ldt)
        if (valid == null || valid.isEmpty()) {
            throw new WeatherGatewayException('SCHEMA',
                "Nonexistent local timestamp '${raw}' in zone ${zone.id} (DST gap); refusing silent shift")
        }
        if (valid.size() == 1) {
            if (foldCounts != null) {
                foldCounts.remove(ldt)
            }
            return ldt.atOffset(valid.get(0)).toInstant()
        }
        // Ambiguous overlap: offsets are ordered earlier-instant first by ZoneRules
        int occurrence = 0
        if (foldCounts != null) {
            occurrence = foldCounts.getOrDefault(ldt, 0)
            foldCounts.put(ldt, occurrence + 1)
        }
        if (occurrence >= valid.size()) {
            throw new WeatherGatewayException('SCHEMA',
                "Ambiguous local timestamp '${raw}' in zone ${zone.id} repeated more times " +
                    "(${occurrence + 1}) than valid offsets (${valid.size()})")
        }
        return ldt.atOffset(valid.get(occurrence)).toInstant()
    }

    private static void requireHourlyLength(List times, List field, String fieldName) {
        if (field == null) {
            return // truly absent optional → null semantics per index
        }
        if (field.size() != times.size()) {
            throw new WeatherGatewayException('SCHEMA',
                "Open-Meteo hourly.${fieldName} length mismatch: field=${fieldName} expected=${times.size()} actual=${field.size()}")
        }
    }

    private static double toFiniteCoord(String name, def raw, double fallback) {
        try {
            double v = raw as double
            if (!Double.isFinite(v)) {
                throw new WeatherGatewayException('SCHEMA',
                    "Open-Meteo ${name} must be finite, got: ${raw}")
            }
            return v
        } catch (WeatherGatewayException e) {
            throw e
        } catch (Exception e) {
            throw new WeatherGatewayException('SCHEMA',
                "Open-Meteo ${name} is not a finite number: ${raw}", e)
        }
    }

    static void enforceBodySizeLimit(String body, long maxBytes) {
        if (body == null) {
            return
        }
        long size = body.getBytes(StandardCharsets.UTF_8).length
        if (size > maxBytes) {
            throw new WeatherGatewayException('CONTENT',
                "Open-Meteo response exceeds max size (${size} > ${maxBytes} bytes)")
        }
    }

    private static LocalDate parseLocalDate(String raw, ZoneId zone) {
        if (!raw) {
            return null
        }
        try {
            String s = raw.trim()
            if (s.length() >= 10) {
                return LocalDate.parse(s.substring(0, 10))
            }
            return LocalDate.parse(s)
        } catch (Exception e) {
            try {
                return parseProviderLocalDateTime(raw, zone).atZone(zone).toLocalDate()
            } catch (Exception ignored) {
                return null
            }
        }
    }

    private static List listOrNull(def v) {
        v instanceof List ? v as List : null
    }

    /**
     * Read optional numeric observation at index. Absent list or explicit JSON null => null.
     * Present non-numeric / non-finite token => SCHEMA (never silently null).
     */
    private static Double doubleAt(List list, int i, String fieldName) {
        if (list == null || i >= list.size()) {
            return null
        }
        def raw = list[i]
        if (raw == null) {
            return null
        }
        if (raw instanceof Boolean || raw instanceof Map || raw instanceof List) {
            throw new WeatherGatewayException('SCHEMA',
                "Open-Meteo hourly.${fieldName}[${i}] expected finite number, got ${typeLabel(raw)}: ${raw}")
        }
        double v
        try {
            if (raw instanceof Number) {
                v = ((Number) raw).doubleValue()
            } else {
                String s = raw.toString().trim()
                if (s.isEmpty()) {
                    throw new WeatherGatewayException('SCHEMA',
                        "Open-Meteo hourly.${fieldName}[${i}] expected finite number, got empty string")
                }
                v = Double.parseDouble(s)
            }
        } catch (WeatherGatewayException e) {
            throw e
        } catch (Exception e) {
            throw new WeatherGatewayException('SCHEMA',
                "Open-Meteo hourly.${fieldName}[${i}] expected finite number, got ${typeLabel(raw)}: ${raw}", e)
        }
        if (!Double.isFinite(v)) {
            throw new WeatherGatewayException('SCHEMA',
                "Open-Meteo hourly.${fieldName}[${i}] must be finite, got: ${raw}")
        }
        return v
    }

    /**
     * Read optional integer observation at index. Absent list or explicit JSON null => null.
     * Present non-numeric / non-integer / out-of-int-range => SCHEMA (never silently null).
     */
    private static Integer intAt(List list, int i, String fieldName) {
        if (list == null || i >= list.size()) {
            return null
        }
        def raw = list[i]
        if (raw == null) {
            return null
        }
        if (raw instanceof Boolean || raw instanceof Map || raw instanceof List) {
            throw new WeatherGatewayException('SCHEMA',
                "Open-Meteo hourly.${fieldName}[${i}] expected integer, got ${typeLabel(raw)}: ${raw}")
        }
        try {
            if (raw instanceof Number) {
                double d = ((Number) raw).doubleValue()
                if (!Double.isFinite(d) || d != Math.rint(d)) {
                    throw new WeatherGatewayException('SCHEMA',
                        "Open-Meteo hourly.${fieldName}[${i}] expected integer, got: ${raw}")
                }
                if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                    throw new WeatherGatewayException('SCHEMA',
                        "Open-Meteo hourly.${fieldName}[${i}] integer out of range: ${raw}")
                }
                return (int) d
            }
            String s = raw.toString().trim()
            if (s.isEmpty()) {
                throw new WeatherGatewayException('SCHEMA',
                    "Open-Meteo hourly.${fieldName}[${i}] expected integer, got empty string")
            }
            // Reject decimals and non-numeric tokens
            if (!(s ==~ /-?\d+/)) {
                throw new WeatherGatewayException('SCHEMA',
                    "Open-Meteo hourly.${fieldName}[${i}] expected integer, got ${typeLabel(raw)}: ${raw}")
            }
            long lv = Long.parseLong(s)
            if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) {
                throw new WeatherGatewayException('SCHEMA',
                    "Open-Meteo hourly.${fieldName}[${i}] integer out of range: ${raw}")
            }
            return (int) lv
        } catch (WeatherGatewayException e) {
            throw e
        } catch (Exception e) {
            throw new WeatherGatewayException('SCHEMA',
                "Open-Meteo hourly.${fieldName}[${i}] expected integer, got ${typeLabel(raw)}: ${raw}", e)
        }
    }

    private static String typeLabel(def raw) {
        if (raw == null) {
            return 'null'
        }
        return raw.getClass().simpleName
    }

    private static Boolean boolAt(List list, int i) {
        if (list == null || i >= list.size() || list[i] == null) {
            return null
        }
        def v = list[i]
        if (v instanceof Boolean) {
            return v
        }
        try {
            int n = v as int
            return n != 0
        } catch (Exception e) {
            return null
        }
    }

    static String conditionForCode(Integer code) {
        if (code == null) {
            return null
        }
        // WMO weather interpretation codes (Open-Meteo)
        if (code == 0) return 'clear'
        if (code in 1..3) return 'partly_cloudy'
        if (code in 45..48) return 'fog'
        if (code in 51..67) return 'drizzle_or_rain'
        if (code in 71..77) return 'snow'
        if (code in 80..82) return 'rain_showers'
        if (code in 85..86) return 'snow_showers'
        if (code in 95..99) return 'thunderstorm'
        return "code_${code}"
    }

    /**
     * Package-visible request builder so tests can assert timeout (and headers) on the
     * default transport path without weakening encapsulation of the HTTP client itself.
     */
    static HttpRequest buildHttpRequest(URI uri, Duration timeout) {
        if (uri == null) {
            throw new IllegalArgumentException('uri is required')
        }
        Duration t = timeout != null ? timeout : DEFAULT_TIMEOUT
        return HttpRequest.newBuilder(uri)
            .timeout(t)
            .GET()
            .header('Accept', 'application/json')
            .build()
    }

    private static Function<URI, HttpResult> defaultTransport(Duration timeout, long maxResponseBytes) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        return { URI uri ->
            HttpRequest req = buildHttpRequest(uri, timeout)
            try {
                HttpResponse<String> resp = client.send(req, boundedBodyHandler(maxResponseBytes))
                new HttpResult(resp.statusCode(), resp.body())
            } catch (WeatherGatewayException e) {
                throw e
            } catch (java.io.IOException e) {
                // BodySubscriber may surface CONTENT oversize as IOException cause
                Throwable c = e
                while (c != null) {
                    if (c instanceof WeatherGatewayException) {
                        throw (WeatherGatewayException) c
                    }
                    c = c.cause
                }
                throw e
            }
        } as Function<URI, HttpResult>
    }

    /**
     * Body handler that enforces Content-Length precheck and a hard byte cap while reading
     * (covers chunked/unknown length). Exceeding the cap yields CONTENT classification.
     */
    static HttpResponse.BodyHandler<String> boundedBodyHandler(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive, got: ${maxBytes}")
        }
        return { HttpResponse.ResponseInfo info ->
            OptionalLong cl = info.headers().firstValueAsLong('Content-Length')
            if (cl.isPresent() && cl.asLong > maxBytes) {
                // Discard body bytes; fail at map completion with CONTENT (not TRANSPORT)
                return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.discarding(),
                    { ignored ->
                        throw new WeatherGatewayException('CONTENT',
                            "Open-Meteo Content-Length ${cl.asLong} exceeds max size ${maxBytes} bytes")
                    } as Function)
            }
            HttpResponse.BodySubscriber<InputStream> streamSub =
                HttpResponse.BodySubscribers.ofInputStream()
            return HttpResponse.BodySubscribers.mapping(streamSub, { InputStream inStream ->
                try {
                    return readBounded(inStream, maxBytes)
                } catch (WeatherGatewayException e) {
                    throw e
                } catch (Exception e) {
                    if (e instanceof RuntimeException && e.cause instanceof WeatherGatewayException) {
                        throw (WeatherGatewayException) e.cause
                    }
                    throw new WeatherGatewayException('TRANSPORT',
                        "Open-Meteo body read failure: ${e.message}", e)
                } finally {
                    try {
                        inStream?.close()
                    } catch (Exception ignored) {
                    }
                }
            } as Function)
        } as HttpResponse.BodyHandler<String>
    }

    /**
     * Read stream up to maxBytes; maxBytes+1 rejects with CONTENT.
     */
    static String readBounded(InputStream inStream, long maxBytes) {
        if (inStream == null) {
            return ''
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream()
        byte[] chunk = new byte[8192]
        long total = 0L
        int n
        while ((n = inStream.read(chunk)) >= 0) {
            if (n == 0) {
                continue
            }
            total += n
            if (total > maxBytes) {
                throw new WeatherGatewayException('CONTENT',
                    "Open-Meteo response exceeds max size (>${maxBytes} bytes)")
            }
            buf.write(chunk, 0, n)
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8)
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return ''
        }
        s.length() <= max ? s : s.substring(0, max) + '…'
    }

    /**
     * Unwrap reflective/Groovy wrapper exceptions so timeout/network causes surface.
     */
    static Throwable unwrapTransportCause(Throwable t) {
        Throwable cur = t
        int guard = 0
        while (cur != null && guard++ < 8) {
            if (cur instanceof java.lang.reflect.UndeclaredThrowableException && cur.cause != null) {
                cur = cur.cause
                continue
            }
            if (cur instanceof java.lang.reflect.InvocationTargetException && cur.cause != null) {
                cur = cur.cause
                continue
            }
            if (cur instanceof RuntimeException && cur.getClass().name == 'org.codehaus.groovy.runtime.InvokerInvocationException'
                && cur.cause != null) {
                cur = cur.cause
                continue
            }
            break
        }
        return cur ?: t
    }

    /**
     * Safe diagnostic for transport failures: classification-friendly text without
     * leaking full query strings, credentials, or secrets from nested causes.
     */
    private static String safeTransportMessage(Exception e) {
        String type = e?.getClass()?.simpleName ?: 'Exception'
        String raw = e?.message ?: ''
        // Strip query strings / credentials if a URL sneaks into the message
        String scrubbed = raw
            .replaceAll(/(?i)([?&](api_key|apikey|key|token|secret|password|auth)=)[^&\s]*/, '$1***')
            .replaceAll(/\?[^\s]*/, '?…')
        scrubbed = truncate(scrubbed, 160)
        String kind = 'failure'
        String lower = (type + ' ' + raw).toLowerCase(Locale.ROOT)
        if (lower.contains('timeout') || lower.contains('timed out') || type.toLowerCase(Locale.ROOT).contains('timeout')) {
            kind = 'timeout'
        } else if (lower.contains('unknownhost') || lower.contains('connect') || lower.contains('network')) {
            kind = 'network'
        }
        return "Open-Meteo transport ${kind} (${type}${scrubbed ? ': ' + scrubbed : ''})"
    }

    static final class HttpResult {
        final int statusCode
        final String body
        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode
            this.body = body
        }
    }

    static class WeatherGatewayException extends RuntimeException {
        final String classification
        WeatherGatewayException(String classification, String message, Throwable cause = null) {
            super(message, cause)
            this.classification = classification ?: 'UNKNOWN'
        }
    }
}
