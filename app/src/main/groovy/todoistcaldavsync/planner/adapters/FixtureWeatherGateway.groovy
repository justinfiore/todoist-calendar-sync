package todoistcaldavsync.planner.adapters

import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import todoistcaldavsync.planner.domain.WeatherForecast
import todoistcaldavsync.planner.domain.WeatherInterval

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fixture-backed read-only weather gateway. Never contacts remote systems.
 * Accepts either:
 * - recorded Open-Meteo JSON (parsed via OpenMeteoWeatherGateway)
 * - provider-neutral YAML/JSON forecast maps
 */
class FixtureWeatherGateway implements WeatherReadGateway {
    private final WeatherForecast forecast
    private int fetchCount = 0

    FixtureWeatherGateway(WeatherForecast forecast) {
        if (forecast == null) {
            throw new IllegalArgumentException('forecast is required')
        }
        this.forecast = forecast
    }

    static FixtureWeatherGateway of(WeatherForecast forecast) {
        new FixtureWeatherGateway(forecast)
    }

    static FixtureWeatherGateway fromFile(File file,
                                          double latitude = 0d,
                                          double longitude = 0d,
                                          ZoneId timezone = ZoneId.of('UTC'),
                                          Instant retrievedAt = null) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Weather fixture not found: ${file}")
        }
        def name = file.name.toLowerCase()
        def parsed
        if (name.endsWith('.json')) {
            parsed = new JsonSlurper().parse(file)
        } else {
            parsed = new YamlSlurper().parse(file)
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException('Weather fixture root must be a map')
        }
        Map root = parsed as Map
        Instant retrieved = retrievedAt ?: Instant.parse('2026-08-07T12:00:00Z')

        // Open-Meteo recorded shape
        if (root.hourly instanceof Map || root.containsKey('generationtime_ms') ||
            (root.provider?.toString()?.equalsIgnoreCase('open_meteo') && root.hourly)) {
            def adapter = new OpenMeteoWeatherGateway(
                root.latitude != null ? root.latitude as double : latitude,
                root.longitude != null ? root.longitude as double : longitude,
                root.timezone ? ZoneId.of(root.timezone.toString()) : timezone,
                7,
                OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
                OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
                { uri -> new OpenMeteoWeatherGateway.HttpResult(200, '{}') },
                retrieved
            )
            return new FixtureWeatherGateway(adapter.parsePayload(root, retrieved))
        }

        return new FixtureWeatherGateway(parseNeutral(root, latitude, longitude, timezone, retrieved))
    }

    static WeatherForecast parseNeutral(Map root, double latitude, double longitude,
                                        ZoneId timezone, Instant retrievedAt) {
        String provider = (root.provider ?: 'fixture').toString()
        Instant issuedAt = root.issued_at ? Instant.parse(root.issued_at.toString())
            : (root.issuedAt ? Instant.parse(root.issuedAt.toString()) : retrievedAt)
        Instant retrieved = root.retrieved_at ? Instant.parse(root.retrieved_at.toString())
            : (root.retrievedAt ? Instant.parse(root.retrievedAt.toString()) : retrievedAt)
        ZoneId tz = root.timezone ? ZoneId.of(root.timezone.toString()) : timezone
        double lat = root.latitude != null ? root.latitude as double : latitude
        double lon = root.longitude != null ? root.longitude as double : longitude

        List<WeatherInterval> intervals = []
        def rawIntervals = root.intervals ?: root.hourly_intervals ?: root.hours
        if (rawIntervals instanceof List) {
            rawIntervals.each { entry ->
                if (!(entry instanceof Map)) {
                    return
                }
                Map m = entry as Map
                Instant start = Instant.parse((m.start ?: m.time).toString())
                Instant end = m.end ? Instant.parse(m.end.toString()) : start + java.time.Duration.ofHours(1)
                intervals << WeatherInterval.builder()
                    .start(start)
                    .end(end)
                    .precipitationProbability(m.precipitation_probability != null
                        ? m.precipitation_probability as double
                        : (m.precipitationProbability != null ? m.precipitationProbability as double : null))
                    .precipitationMm(m.precipitation_mm != null
                        ? m.precipitation_mm as double
                        : (m.precipitationMm != null ? m.precipitationMm as double
                        : (m.precipitation != null ? m.precipitation as double : null)))
                    .weatherCode(m.weather_code != null ? m.weather_code as int
                        : (m.weatherCode != null ? m.weatherCode as int : null))
                    .condition(m.condition?.toString())
                    .temperatureC(m.temperature_c != null ? m.temperature_c as double
                        : (m.temperatureC != null ? m.temperatureC as double : null))
                    .windSpeedKph(m.wind_speed_kph != null ? m.wind_speed_kph as double
                        : (m.windSpeedKph != null ? m.windSpeedKph as double : null))
                    .daylight(m.daylight != null ? Boolean.valueOf(m.daylight.toString()) : null)
                    .confidence(m.confidence != null ? m.confidence as double : null)
                    .build()
            }
        }

        Map<LocalDate, WeatherForecast.DaylightWindow> daylight = new LinkedHashMap<>()
        def rawDaylight = root.daylight ?: root.daily
        if (rawDaylight instanceof List) {
            rawDaylight.each { entry ->
                if (!(entry instanceof Map)) {
                    return
                }
                Map m = entry as Map
                LocalDate date = LocalDate.parse((m.date ?: m.time).toString().substring(0, 10))
                Instant sunrise = Instant.parse(m.sunrise.toString())
                Instant sunset = Instant.parse(m.sunset.toString())
                daylight[date] = new WeatherForecast.DaylightWindow(date, sunrise, sunset)
            }
        } else if (rawDaylight instanceof Map && rawDaylight.time instanceof List) {
            // open-meteo daily subset already handled above; keep neutral map form
        }

        return WeatherForecast.builder()
            .provider(provider)
            .issuedAt(issuedAt)
            .retrievedAt(retrieved)
            .latitude(lat)
            .longitude(lon)
            .timezone(tz)
            .intervals(intervals)
            .daylightByDate(daylight)
            .build()
    }

    int getFetchCount() {
        fetchCount
    }

    @Override
    WeatherForecast fetchForecast(Instant rangeStart, Instant rangeEnd) {
        fetchCount++
        return forecast
    }
}
