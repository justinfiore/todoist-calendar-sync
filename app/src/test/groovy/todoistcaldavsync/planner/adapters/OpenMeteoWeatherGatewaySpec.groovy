package todoistcaldavsync.planner.adapters

import spock.lang.Specification
import todoistcaldavsync.planner.domain.WeatherForecast

import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import java.io.ByteArrayInputStream

class OpenMeteoWeatherGatewaySpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')
    Instant retrieved = Instant.parse('2026-08-07T12:00:00Z')

    private File fixture(String name) {
        def url = getClass().classLoader.getResource("planner/fixtures/${name}")
        if (url != null) {
            return new File(url.toURI())
        }
        def candidates = [
            new File("app/src/test/resources/planner/fixtures/${name}"),
            new File("src/test/resources/planner/fixtures/${name}")
        ]
        def f = candidates.find { it.exists() }
        if (f == null) {
            throw new IllegalStateException("fixture not found: ${name}")
        }
        return f
    }

    def "builds correct URL query for lat lon timezone horizon"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.7128d, -74.0060d, zone, 7)

        when:
        def uri = gw.buildRequestUri()

        then:
        uri.host == 'api.open-meteo.com'
        uri.path == '/v1/forecast'
        def q = uri.query
        q.contains('latitude=40.7128')
        q.contains('longitude=-74.006')
        // URI may keep or decode %2F; both forms identify America/New_York
        q.contains('timezone=America') && q.contains('New_York')
        q.contains('forecast_days=7')
        q.contains('hourly=')
        q.contains('precipitation_probability')
        q.contains('daily=')
        q.contains('sunrise')
        q.contains('wind_speed_unit=kmh')
        !q.toLowerCase().contains('api_key')
        !q.toLowerCase().contains('secret')
    }

    def "parses recorded clear Open-Meteo payload without network"() {
        given:
        def body = fixture('weather-open-meteo-clear.json').text
        def captured = new AtomicReference<URI>()
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
            { URI u -> captured.set(u); new OpenMeteoWeatherGateway.HttpResult(200, body) },
            retrieved)

        when:
        WeatherForecast fc = gw.fetchForecast(
            Instant.parse('2026-08-08T00:00:00Z'), Instant.parse('2026-08-10T00:00:00Z'))

        then:
        captured.get() != null
        fc.provider == 'open_meteo'
        fc.timezone.id == 'America/New_York'
        fc.intervals.size() >= 10
        fc.intervals.every { it.precipitationProbability != null }
        fc.daylightByDate.size() == 2
        fc.intervals[0].start.atZone(zone).toLocalDate().toString() == '2026-08-08'
        // local civil time 09:00 America/New_York, not forced UTC
        fc.intervals[0].start.atZone(zone).toLocalTime().toString().startsWith('09:00')
    }

    def "parses rain fixture with high Saturday precip"() {
        given:
        def gw = FixtureWeatherGateway.fromFile(fixture('weather-open-meteo-rain.json'),
            40.71d, -74.01d, zone, retrieved)

        when:
        def fc = gw.fetchForecast(null, null)
        def satMorning = fc.intervals.find {
            it.start.atZone(zone).toLocalDate().toString() == '2026-08-08' &&
                it.start.atZone(zone).hour == 10
        }

        then:
        satMorning != null
        satMorning.precipitationProbability == 75d
        satMorning.precipitationMm == 1.8d
    }

    def "HTTP non-2xx is classified"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
            { URI u -> new OpenMeteoWeatherGateway.HttpResult(503, 'unavailable') },
            retrieved)

        when:
        gw.fetchForecast(null, null)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'HTTP_STATUS'
    }

    def "malformed JSON is classified"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
            { URI u -> new OpenMeteoWeatherGateway.HttpResult(200, 'not-json{{{') },
            retrieved)

        when:
        gw.fetchForecast(null, null)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'MALFORMED_JSON'
    }

    def "missing hourly schema is classified"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
            { URI u -> new OpenMeteoWeatherGateway.HttpResult(200, '{"latitude":1}') },
            retrieved)

        when:
        gw.fetchForecast(null, null)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'SCHEMA'
    }

    def "DST spring local times keep zone offset (skip 02:00)"() {
        given:
        def gw = FixtureWeatherGateway.fromFile(fixture('weather-open-meteo-dst-spring.json'),
            40.71d, -74.01d, zone, retrieved)

        when:
        def fc = gw.fetchForecast(null, null)
        def times = fc.intervals.collect { it.start.atZone(zone).toLocalTime().toString() }

        then:
        times.contains('00:00')
        times.contains('01:00')
        times.contains('03:00')
        // 03:00 EDT is after spring-forward
        def three = fc.intervals.find { it.start.atZone(zone).toLocalTime().hour == 3 }
        three != null
        three.start.atZone(zone).offset.totalSeconds == -4 * 3600
    }

    def "DST fall fixture preserves both 01:00 folds as distinct instants and hourly buckets"() {
        given:
        def gw = FixtureWeatherGateway.fromFile(fixture('weather-open-meteo-dst-fall.json'),
            40.71d, -74.01d, zone, retrieved)

        when:
        def fc = gw.fetchForecast(null, null)
        def folds = fc.intervals.findAll {
            it.start.atZone(zone).toLocalDate().toString() == '2026-11-01' &&
                it.start.atZone(zone).toLocalTime().hour == 1
        }

        then:
        fc.intervals.size() == 6
        fc.timezone.id == 'America/New_York'
        folds.size() == 2
        // First fold: EDT -04:00; second: EST -05:00
        folds[0].start.atZone(zone).offset.totalSeconds == -4 * 3600
        folds[1].start.atZone(zone).offset.totalSeconds == -5 * 3600
        folds[0].start.isBefore(folds[1].start)
        folds[0].end == folds[1].start
        folds[1].end.isAfter(folds[1].start)
        // Strictly increasing bucket starts across whole series
        def starts = fc.intervals*.start
        starts == starts.toSorted()
        starts.toUnique().size() == starts.size()
        // Distinct temperatures preserved per fold (7.0 then 7.5)
        folds[0].temperatureC == 7.0d
        folds[1].temperatureC == 7.5d
    }

    def "DST fall reversed or out-of-order local times are SCHEMA after fold resolution"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)
        def payload = [
            latitude : 40.71, longitude: -74.01, timezone: 'America/New_York',
            hourly   : [
                time                     : ['2026-11-01T02:00', '2026-11-01T01:00', '2026-11-01T01:00'],
                precipitation_probability: [0, 0, 0]
            ]
        ]

        when:
        gw.parsePayload(payload, retrieved)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'SCHEMA'
        e.message.toLowerCase().contains('increasing') || e.message.toLowerCase().contains('not after')
    }

    def "DST fall more than two repeated 01:00 folds is SCHEMA"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)
        def payload = [
            latitude : 40.71, longitude: -74.01, timezone: 'America/New_York',
            hourly   : [
                time                     : ['2026-11-01T01:00', '2026-11-01T01:00', '2026-11-01T01:00'],
                precipitation_probability: [0, 0, 0]
            ]
        ]

        when:
        gw.parsePayload(payload, retrieved)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'SCHEMA'
        e.message.toLowerCase().contains('ambiguous') || e.message.toLowerCase().contains('valid offset')
    }

    def "DST spring gap nonexistent civil timestamp is SCHEMA not silent shift"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)
        def payload = [
            latitude : 40.71, longitude: -74.01, timezone: 'America/New_York',
            hourly   : [
                time                     : ['2026-03-08T01:00', '2026-03-08T02:30', '2026-03-08T03:00'],
                precipitation_probability: [0, 0, 0]
            ]
        ]

        when:
        gw.parsePayload(payload, retrieved)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'SCHEMA'
        e.message.toLowerCase().contains('nonexistent') || e.message.toLowerCase().contains('gap')
    }

    def "explicit offset timestamps remain authoritative and increasing when ordered correctly"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)
        def payload = [
            latitude : 40.71, longitude: -74.01, timezone: 'America/New_York',
            hourly   : [
                time                     : [
                    '2026-11-01T01:00:00-04:00',
                    '2026-11-01T01:00:00-05:00',
                    '2026-11-01T02:00:00-05:00'
                ],
                precipitation_probability: [1, 2, 3]
            ]
        ]

        when:
        def fc = gw.parsePayload(payload, retrieved)

        then:
        fc.intervals.size() == 3
        fc.intervals[0].start == Instant.parse('2026-11-01T05:00:00Z')
        fc.intervals[1].start == Instant.parse('2026-11-01T06:00:00Z')
        fc.intervals[2].start == Instant.parse('2026-11-01T07:00:00Z')
        fc.intervals[0].precipitationProbability == 1d
        fc.intervals[1].precipitationProbability == 2d
    }

    def "explicit offset reversed folds yield SCHEMA for non-increasing series"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)
        def payload = [
            latitude : 40.71, longitude: -74.01, timezone: 'America/New_York',
            hourly   : [
                time                     : [
                    '2026-11-01T01:00:00-05:00',
                    '2026-11-01T01:00:00-04:00'
                ],
                precipitation_probability: [1, 2]
            ]
        ]

        when:
        gw.parsePayload(payload, retrieved)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'SCHEMA'
    }

    def "hourly optional array shorter or longer than time is SCHEMA with field expected actual"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)

        when: 'shorter'
        gw.parsePayload([
            latitude: 40.71, longitude: -74.01, timezone: 'America/New_York',
            hourly  : [
                time                     : ['2026-08-08T10:00', '2026-08-08T11:00'],
                precipitation_probability: [10]
            ]
        ], retrieved)

        then:
        def shortEx = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        shortEx.classification == 'SCHEMA'
        shortEx.message.contains('precipitation_probability')
        shortEx.message.contains('expected=2')
        shortEx.message.contains('actual=1')

        when: 'longer'
        gw.parsePayload([
            latitude: 40.71, longitude: -74.01, timezone: 'America/New_York',
            hourly  : [
                time                     : ['2026-08-08T10:00', '2026-08-08T11:00'],
                precipitation_probability: [10, 20, 30]
            ]
        ], retrieved)

        then:
        def longEx = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        longEx.classification == 'SCHEMA'
        longEx.message.contains('expected=2')
        longEx.message.contains('actual=3')
    }

    def "rejects non-finite constructor coordinates and never puts NaN in URL"() {
        when:
        new OpenMeteoWeatherGateway(Double.NaN, -74.01d, zone)

        then:
        thrown(IllegalArgumentException)

        when:
        new OpenMeteoWeatherGateway(40.71d, Double.POSITIVE_INFINITY, zone)

        then:
        thrown(IllegalArgumentException)

        when:
        new OpenMeteoWeatherGateway(40.71d, Double.NEGATIVE_INFINITY, zone)

        then:
        thrown(IllegalArgumentException)

        when:
        def s = OpenMeteoWeatherGateway.formatCoord(Double.NaN)

        then:
        thrown(IllegalArgumentException)
    }

    def "payload non-finite coordinates are SCHEMA"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)

        when:
        gw.parsePayload([
            latitude: 'NaN', longitude: -74.01, timezone: 'America/New_York',
            hourly  : [time: ['2026-08-08T10:00']]
        ], retrieved)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'SCHEMA'
        e.message.toLowerCase().contains('finite') || e.message.toLowerCase().contains('latitude')
    }

    def "injected transport body over maxResponseBytes is CONTENT before parse"() {
        given:
        long limit = 64L
        String over = 'x' * 65
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
            { URI u -> new OpenMeteoWeatherGateway.HttpResult(200, over) },
            retrieved,
            limit)

        when:
        gw.fetchForecast(null, null)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'CONTENT'
        e.message.toLowerCase().contains('max size') || e.message.toLowerCase().contains('exceeds')
    }

    def "injected transport body at exact maxResponseBytes is accepted when valid JSON"() {
        given:
        // Minimal valid payload padded to exact limit
        String core = '{"latitude":40.71,"longitude":-74.01,"timezone":"America/New_York","hourly":{"time":["2026-08-08T10:00"]}}'
        long limit = core.getBytes('UTF-8').length
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
            { URI u -> new OpenMeteoWeatherGateway.HttpResult(200, core) },
            retrieved,
            limit)

        when:
        def fc = gw.fetchForecast(null, null)

        then:
        fc.intervals.size() == 1
    }

    def "readBounded rejects limit+1 and accepts exact limit"() {
        when:
        def ok = OpenMeteoWeatherGateway.readBounded(
            new ByteArrayInputStream('abcd'.getBytes('UTF-8')), 4L)

        then:
        ok == 'abcd'

        when:
        OpenMeteoWeatherGateway.readBounded(
            new ByteArrayInputStream('abcde'.getBytes('UTF-8')), 4L)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'CONTENT'
    }

    def "non-positive maxResponseBytes rejected at construction"() {
        when:
        new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
            null, retrieved, 0L)

        then:
        thrown(IllegalArgumentException)

        when:
        new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            OpenMeteoWeatherGateway.DEFAULT_TIMEOUT,
            null, retrieved, -1L)

        then:
        thrown(IllegalArgumentException)
    }

    def "default transport request path forwards HttpRequest.timeout"() {
        given:
        Duration configured = Duration.ofMillis(3210)
        URI uri = URI.create('https://api.open-meteo.com/v1/forecast?latitude=1&longitude=2')

        when:
        def req = OpenMeteoWeatherGateway.buildHttpRequest(uri, configured)

        then:
        req.timeout().isPresent()
        req.timeout().get() == configured
        req.timeout().get().toMillis() == 3210L
        req.method() == 'GET'
    }

    def "configured finite timeout is forwarded to transport request"() {
        given:
        Duration configured = Duration.ofMillis(2500)
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            configured,
            { URI u ->
                // Assert the package-visible builder seam used by default transport
                def req = OpenMeteoWeatherGateway.buildHttpRequest(u, configured)
                assert req.timeout().get() == configured
                new OpenMeteoWeatherGateway.HttpResult(200, '''
                    {"latitude":40.71,"longitude":-74.01,"timezone":"America/New_York",
                     "hourly":{"time":["2026-08-08T10:00"]}}
                ''')
            },
            retrieved)

        expect:
        gw.timeout == configured
        gw.timeout.toMillis() == 2500L
        // Default transport path builder carries the same timeout
        OpenMeteoWeatherGateway.buildHttpRequest(gw.buildRequestUri(), gw.timeout)
            .timeout().get() == configured
    }

    def "parseProviderLocalDateTime never treats bare local as UTC"() {
        when:
        def ny = OpenMeteoWeatherGateway.parseProviderLocalDateTime('2026-08-08T10:00', zone)
        def utc = OpenMeteoWeatherGateway.parseProviderLocalDateTime('2026-08-08T10:00:00Z', zone)

        then:
        ny.atZone(zone).hour == 10
        ny != utc
        utc.atZone(ZoneId.of('UTC')).hour == 10
    }

    def "missing optional hourly fields become null not crash"() {
        given:
        def payload = [
            latitude : 40.71,
            longitude: -74.01,
            timezone : 'America/New_York',
            hourly   : [
                time: ['2026-08-08T10:00', '2026-08-08T11:00']
                // no other fields
            ]
        ]
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)

        when:
        def fc = gw.parsePayload(payload, retrieved)

        then:
        fc.intervals.size() == 2
        fc.intervals[0].precipitationProbability == null
        fc.intervals[0].temperatureC == null
    }

    def "explicit JSON null optional hourly values map to null observations"() {
        given:
        def payload = [
            latitude : 40.71,
            longitude: -74.01,
            timezone : 'America/New_York',
            hourly   : [
                time                     : ['2026-08-08T10:00'],
                precipitation_probability: [null],
                precipitation            : [null],
                weather_code             : [null],
                temperature_2m           : [null],
                wind_speed_10m           : [null]
            ]
        ]
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)

        when:
        def fc = gw.parsePayload(payload, retrieved)

        then:
        fc.intervals.size() == 1
        fc.intervals[0].precipitationProbability == null
        fc.intervals[0].precipitationMm == null
        fc.intervals[0].weatherCode == null
        fc.intervals[0].temperatureC == null
        fc.intervals[0].windSpeedKph == null
    }

    def "present non-finite hourly numeric is SCHEMA with field and index"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)
        def base = { String field, def bad ->
            [
                latitude : 40.71, longitude: -74.01, timezone: 'America/New_York',
                hourly   : [
                    time                     : ['2026-08-08T10:00', '2026-08-08T11:00'],
                    precipitation_probability: field == 'precipitation_probability' ? [10d, bad] : [10d, 5d],
                    precipitation            : field == 'precipitation' ? [0d, bad] : [0d, 0d],
                    weather_code             : [0, 0],
                    temperature_2m           : field == 'temperature_2m' ? [20d, bad] : [20d, 21d],
                    wind_speed_10m           : field == 'wind_speed_10m' ? [5d, bad] : [5d, 6d]
                ]
            ]
        }

        expect:
        ['precipitation_probability', 'precipitation', 'temperature_2m', 'wind_speed_10m'].each { field ->
            [Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 'NaN', 'Infinity', 'not-a-number', true].each { bad ->
                try {
                    gw.parsePayload(base(field, bad), retrieved)
                    assert false: "expected SCHEMA for ${field}=${bad}"
                } catch (OpenMeteoWeatherGateway.WeatherGatewayException e) {
                    assert e.classification == 'SCHEMA'
                    assert e.message.contains(field)
                    assert e.message.contains('[1]') || e.message.contains('index')
                    assert e.message.toLowerCase().contains('finite') ||
                        e.message.toLowerCase().contains('number') ||
                        e.message.toLowerCase().contains('expected')
                }
            }
        }
    }

    def "present malformed weather_code is SCHEMA not null"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)
        def payload = { def code ->
            [
                latitude : 40.71, longitude: -74.01, timezone: 'America/New_York',
                hourly   : [
                    time        : ['2026-08-08T10:00'],
                    weather_code: [code],
                    precipitation_probability: [0d]
                ]
            ]
        }

        expect:
        ['x', true, 1.5d, '1.5', Double.NaN].each { bad ->
            try {
                gw.parsePayload(payload(bad), retrieved)
                assert false: "expected SCHEMA for weather_code=${bad}"
            } catch (OpenMeteoWeatherGateway.WeatherGatewayException e) {
                assert e.classification == 'SCHEMA'
                assert e.message.contains('weather_code')
                assert e.message.contains('[0]')
            }
        }

        when:
        def ok = gw.parsePayload(payload(61), retrieved)

        then:
        ok.intervals[0].weatherCode == 61
    }

    def "valid integer and double hourly values parse"() {
        given:
        def payload = [
            latitude : 40.71, longitude: -74.01, timezone: 'America/New_York',
            hourly   : [
                time                     : ['2026-08-08T10:00'],
                precipitation_probability: [12.5],
                precipitation            : ['0.25'],
                weather_code             : [3],
                temperature_2m           : [-1],
                wind_speed_10m           : [8]
            ]
        ]
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone)

        when:
        def fc = gw.parsePayload(payload, retrieved)

        then:
        fc.intervals[0].precipitationProbability == 12.5d
        fc.intervals[0].precipitationMm == 0.25d
        fc.intervals[0].weatherCode == 3
        fc.intervals[0].temperatureC == -1d
        fc.intervals[0].windSpeedKph == 8d
    }

    def "fixture gateway fetch is deterministic and network-free"() {
        given:
        def gw = FixtureWeatherGateway.fromFile(fixture('weather-open-meteo-clear.json'),
            40.71d, -74.01d, zone, retrieved)

        when:
        def a = gw.fetchForecast(null, null)
        def b = gw.fetchForecast(null, null)

        then:
        gw.fetchCount == 2
        a.intervals.size() == b.intervals.size()
        a.intervals*.start == b.intervals*.start
        a.intervals*.precipitationProbability == b.intervals*.precipitationProbability
    }

    def "transport timeout is classified TRANSPORT with safe diagnostic and cause"() {
        given:
        def secretUri = URI.create(
            'https://api.open-meteo.com/v1/forecast?latitude=40.71&longitude=-74.01&api_key=super-secret-token-xyz')
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            Duration.ofSeconds(3),
            { URI u ->
                throw new HttpTimeoutException(
                    "request timed out contacting ${secretUri}")
            },
            retrieved)

        when:
        gw.fetchForecast(null, null)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'TRANSPORT'
        e.cause instanceof HttpTimeoutException
        e.message.toLowerCase().contains('timeout') || e.message.toLowerCase().contains('transport')
        !e.message.contains('super-secret-token-xyz')
        !e.message.contains('api_key=super-secret')
        // safe endpoint host may appear, but not full secret query
        e.message.contains('Open-Meteo')
    }

    def "transport network exception is classified TRANSPORT without secret leakage"() {
        given:
        def gw = new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL,
            Duration.ofSeconds(5),
            { URI u ->
                throw new java.net.ConnectException(
                    'Connection refused to https://api.open-meteo.com/v1/forecast?token=leaked-secret')
            },
            retrieved)

        when:
        gw.fetchForecast(null, null)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'TRANSPORT'
        e.cause instanceof java.net.ConnectException
        !e.message.contains('leaked-secret')
        !e.message.contains('token=leaked')
        e.message.toLowerCase().contains('network') || e.message.toLowerCase().contains('transport')
    }

    def "zero negative and unbounded timeouts are rejected"() {
        when:
        new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL, Duration.ZERO)

        then:
        thrown(IllegalArgumentException)

        when:
        new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL, Duration.ofSeconds(-1))

        then:
        thrown(IllegalArgumentException)

        when:
        new OpenMeteoWeatherGateway(40.71d, -74.01d, zone, 7,
            OpenMeteoWeatherGateway.DEFAULT_BASE_URL, Duration.ofDays(365))

        then:
        thrown(IllegalArgumentException)
    }
}
