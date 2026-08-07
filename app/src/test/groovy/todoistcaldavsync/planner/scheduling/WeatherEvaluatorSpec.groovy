package todoistcaldavsync.planner.scheduling

import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.WeatherEvaluation
import todoistcaldavsync.planner.domain.WeatherForecast
import todoistcaldavsync.planner.domain.WeatherInterval

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WeatherEvaluatorSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')
    Instant issued = Instant.parse('2026-08-07T12:00:00Z')
    Instant now = Instant.parse('2026-08-07T14:00:00Z')

    PlannerConfig weatherConfig(Map extras = [:]) {
        Map weather = [
            enabled  : true,
            provider : 'fixture',
            latitude : 40.71,
            longitude: -74.01,
            max_age  : 'PT12H',
            fallback : 'fail_closed',
            task_rules: [
                [
                    name        : 'deck-paint',
                    match_labels: ['paint', 'deck'],
                    require     : [
                        precipitation_probability_max: 15,
                        precipitation_mm_max         : 0,
                        temperature_min_c            : 10,
                        wind_speed_kph_max           : 20
                    ],
                    preferred   : [daylight: true]
                ],
                [
                    name        : 'outdoor-dry',
                    match_labels: ['outdoor'],
                    require     : [
                        precipitation_probability_max: 25,
                        precipitation_mm_max         : 0.5,
                        wind_speed_kph_max           : 25
                    ]
                ]
            ]
        ]
        extras.each { k, v -> weather[k] = v }
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            weather     : weather
        ])
    }

    Task task(String id, List labels, int minutes = 60) {
        Task.builder()
            .id(id)
            .content(id)
            .labels(labels)
            .priority(2)
            .effectiveDuration(Duration.ofMinutes(minutes))
            .durationSource('test')
            .build()
    }

    WeatherInterval hour(String localStart, Map vals) {
        Instant s = LocalDate.parse(localStart.substring(0, 10))
            .atTime(java.time.LocalTime.parse(localStart.substring(11)))
            .atZone(zone).toInstant()
        // Explicit null for a key means missing observation; absent key defaults daylight to true.
        Boolean daylight = vals.containsKey('daylight')
            ? (vals.daylight == null ? null : vals.daylight as Boolean)
            : true
        WeatherInterval.builder()
            .start(s)
            .end(s + Duration.ofHours(1))
            .precipitationProbability(vals.containsKey('precipProb') ? vals.precipProb as Double : null)
            .precipitationMm(vals.containsKey('precipMm') ? vals.precipMm as Double : null)
            .temperatureC(vals.containsKey('temp') ? vals.temp as Double : null)
            .windSpeedKph(vals.containsKey('wind') ? vals.wind as Double : null)
            .daylight(daylight)
            .confidence(vals.containsKey('confidence') ? vals.confidence as Double : null)
            .build()
    }

    WeatherForecast forecast(List<WeatherInterval> intervals, Map daylight = [:]) {
        Map<LocalDate, WeatherForecast.DaylightWindow> dl = new LinkedHashMap<>()
        daylight.each { String dateStr, List times ->
            LocalDate d = LocalDate.parse(dateStr)
            Instant rise = d.atTime(java.time.LocalTime.parse(times[0])).atZone(zone).toInstant()
            Instant set = d.atTime(java.time.LocalTime.parse(times[1])).atZone(zone).toInstant()
            dl[d] = new WeatherForecast.DaylightWindow(d, rise, set)
        }
        WeatherForecast.builder()
            .provider('fixture')
            .issuedAt(issued)
            .retrievedAt(issued)
            .latitude(40.71)
            .longitude(-74.01)
            .timezone(zone)
            .intervals(intervals)
            .daylightByDate(dl)
            .build()
    }

    Instant local(String dateTime) {
        LocalDate.parse(dateTime.substring(0, 10))
            .atTime(java.time.LocalTime.parse(dateTime.substring(11)))
            .atZone(zone).toInstant()
    }

    def "disabled weather is not applicable"() {
        given:
        def config = PlannerConfig.fromMap(planner: [
            mode: 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather: [enabled: false]
        ])
        def evaluator = new WeatherEvaluator(config)
        def t = task('deck', ['paint', 'deck'])

        expect:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), null, now)
        we.result == WeatherEvaluation.RESULT_NOT_APPLICABLE
        !we.hardInfeasible
    }

    def "non-matching labels are not applicable"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('indoor', ['computer'])
        def fc = forecast([hour('2026-08-08T10:00', [precipProb: 80d, precipMm: 2d, temp: 20d, wind: 10d])])

        expect:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)
        we.result == WeatherEvaluation.RESULT_NOT_APPLICABLE
    }

    def "rain invalidates deck painting above precip probability threshold"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['paint', 'deck'])
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 75d, precipMm: 1.5d, temp: 20d, wind: 10d])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_INFEASIBLE
        we.ruleName == 'deck-paint'
        we.observedField == 'precipitation_probability'
        we.observedValue == 75d
        we.threshold == 15d
        we.reason.contains('75')
        we.reason.contains('15')
        we.forecastIssuedAt == issued
        we.alternativesSignal
    }

    def "precipitation probability inclusive max: at max feasible, just above infeasible"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        def at = forecast([hour('2026-08-08T10:00', [precipProb: 15d, precipMm: 0d, temp: 20d, wind: 10d])],
            ['2026-08-08': ['06:00', '20:00']])
        def above = forecast([hour('2026-08-08T10:00', [precipProb: 15.01d, precipMm: 0d, temp: 20d, wind: 10d])],
            ['2026-08-08': ['06:00', '20:00']])

        expect:
        !evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), at, now).hardInfeasible
        evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), above, now).hardInfeasible
    }

    def "precipitation_mm_max inclusive: at max feasible, just over infeasible"() {
        given:
        // outdoor-dry rule: precip_mm_max=0.5
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('out', ['outdoor'])
        def start = local('2026-08-08T10:00')
        def end = local('2026-08-08T11:00')
        def at = forecast([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0.5d, temp: 20d, wind: 5d])])
        def over = forecast([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0.51d, temp: 20d, wind: 5d])])

        expect:
        !evaluator.evaluate(t, start, end, at, now).hardInfeasible
        def weOver = evaluator.evaluate(t, start, end, over, now)
        weOver.hardInfeasible
        weOver.observedField == 'precipitation_mm'
        weOver.threshold == 0.5d
    }

    def "temperature_max_c inclusive: at max feasible, just over infeasible"() {
        given:
        def cfg = weatherConfig(task_rules: [[
            name        : 'hot-limit',
            match_labels: ['deck'],
            require     : [
                precipitation_probability_max: 100,
                precipitation_mm_max         : 100,
                temperature_min_c            : 0,
                temperature_max_c            : 30,
                wind_speed_kph_max           : 100
            ]
        ]])
        def evaluator = new WeatherEvaluator(cfg)
        def t = task('deck', ['deck'])
        def start = local('2026-08-08T10:00')
        def end = local('2026-08-08T11:00')
        def at = forecast([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 30d, wind: 5d])])
        def over = forecast([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 30.01d, wind: 5d])])

        expect:
        !evaluator.evaluate(t, start, end, at, now).hardInfeasible
        def weOver = evaluator.evaluate(t, start, end, over, now)
        weOver.hardInfeasible
        weOver.observedField == 'temperature_max_c'
        weOver.threshold == 30d
    }

    def "temperature_min_c inclusive: at min feasible, just below infeasible"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        def start = local('2026-08-08T10:00')
        def end = local('2026-08-08T11:00')
        def dl = ['2026-08-08': ['06:00', '20:00']]

        expect:
        !evaluator.evaluate(t, start, end,
            forecast([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 10d, wind: 5d])], dl), now
        ).hardInfeasible
        def below = evaluator.evaluate(t, start, end,
            forecast([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 9.99d, wind: 5d])], dl), now
        )
        below.hardInfeasible
        below.observedField == 'temperature_min_c'
    }

    def "wind inclusive max boundary retained"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        def start = local('2026-08-08T10:00')
        def end = local('2026-08-08T11:00')
        def dl = ['2026-08-08': ['06:00', '20:00']]

        expect:
        evaluator.evaluate(t, start, end,
            forecast([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 20d, wind: 20.01d])], dl), now
        ).hardInfeasible
        !evaluator.evaluate(t, start, end,
            forecast([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 20d, wind: 20d])], dl), now
        ).hardInfeasible
    }

    def "preferred daylight only: night is feasible with soft penalty, day scores higher"() {
        given:
        // default deck-paint: preferred.daylight=true, require.daylight absent
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T05:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: false]),
            hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: true])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def night = evaluator.evaluate(t, local('2026-08-08T05:00'), local('2026-08-08T06:00'), fc, now)
        def day = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        !night.hardInfeasible
        night.result == WeatherEvaluation.RESULT_FEASIBLE
        night.scoreDelta == 0L
        night.details.preferredDaylightMet == false
        night.reason.toLowerCase().contains('preferred daylight') || night.reason.toLowerCase().contains('daylight')
        !day.hardInfeasible
        day.scoreDelta > night.scoreDelta
        day.details.preferredDaylightMet == true
    }

    def "require daylight true: night is hard infeasible"() {
        given:
        def cfg = weatherConfig(task_rules: [[
            name        : 'require-day',
            match_labels: ['deck'],
            require     : [
                precipitation_probability_max: 100,
                precipitation_mm_max         : 100,
                temperature_min_c            : 0,
                wind_speed_kph_max           : 100,
                daylight                     : true
            ]
        ]])
        def evaluator = new WeatherEvaluator(cfg)
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T05:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: false]),
            hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: true])
        ], ['2026-08-08': ['06:00', '20:00']])

        expect:
        evaluator.evaluate(t, local('2026-08-08T05:00'), local('2026-08-08T06:00'), fc, now).hardInfeasible
        !evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now).hardInfeasible
    }

    def "require daylight false and preferred absent: night is feasible without daylight penalty"() {
        given:
        def cfg = weatherConfig(task_rules: [[
            name        : 'no-day-req',
            match_labels: ['deck'],
            require     : [
                precipitation_probability_max: 100,
                precipitation_mm_max         : 100,
                temperature_min_c            : 0,
                wind_speed_kph_max           : 100,
                daylight                     : false
            ]
        ]])
        def evaluator = new WeatherEvaluator(cfg)
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T05:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: false])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T05:00'), local('2026-08-08T06:00'), fc, now)

        then:
        !we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_FEASIBLE
        we.details.preferredDaylightMet == null
    }

    def "neither require nor preferred daylight: night feasible"() {
        given:
        def cfg = weatherConfig(task_rules: [[
            name        : 'neither-day',
            match_labels: ['deck'],
            require     : [
                precipitation_probability_max: 100,
                precipitation_mm_max         : 100,
                temperature_min_c            : 0,
                wind_speed_kph_max           : 100
            ]
        ]])
        def evaluator = new WeatherEvaluator(cfg)
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T05:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: false])
        ], ['2026-08-08': ['06:00', '20:00']])

        expect:
        !evaluator.evaluate(t, local('2026-08-08T05:00'), local('2026-08-08T06:00'), fc, now).hardInfeasible
    }

    def "daylight exact sunrise/sunset and night boundaries for require daylight"() {
        given:
        def cfg = weatherConfig(task_rules: [[
            name        : 'req-day-bounds',
            match_labels: ['deck'],
            require     : [
                precipitation_probability_max: 100,
                precipitation_mm_max         : 100,
                temperature_min_c            : 0,
                wind_speed_kph_max           : 100,
                daylight                     : true
            ]
        ]])
        def evaluator = new WeatherEvaluator(cfg)
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T05:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: false]),
            hour('2026-08-08T06:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: true]),
            hour('2026-08-08T19:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: true]),
            hour('2026-08-08T20:00', [precipProb: 0d, precipMm: 0d, temp: 15d, wind: 5d, daylight: false])
        ], ['2026-08-08': ['06:00', '20:00']])

        expect:
        !evaluator.evaluate(t, local('2026-08-08T06:00'), local('2026-08-08T07:00'), fc, now).hardInfeasible
        evaluator.evaluate(t, local('2026-08-08T05:30'), local('2026-08-08T06:30'), fc, now).hardInfeasible
        !evaluator.evaluate(t, local('2026-08-08T19:00'), local('2026-08-08T20:00'), fc, now).hardInfeasible
        evaluator.evaluate(t, local('2026-08-08T19:30'), local('2026-08-08T20:30'), fc, now).hardInfeasible
    }

    def "multi-hour task fails when any overlapping bucket violates"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'], 120)
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 5d, precipMm: 0d, temp: 20d, wind: 5d]),
            hour('2026-08-08T11:00', [precipProb: 50d, precipMm: 1d, temp: 20d, wind: 5d])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T12:00'), fc, now)

        then:
        we.hardInfeasible
        we.observedField == 'precipitation_probability'
        we.relevantHours.size() == 2
    }

    def "stale forecast fail-closed blocks; fail-open allows"() {
        given:
        def staleIssued = now - Duration.ofHours(24)
        def fc = WeatherForecast.builder()
            .provider('fixture').issuedAt(staleIssued).retrievedAt(staleIssued)
            .latitude(40.71).longitude(-74.01).timezone(zone)
            .intervals([hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 20d, wind: 5d])])
            .daylightByDate([(LocalDate.of(2026, 8, 8)):
                                 new WeatherForecast.DaylightWindow(LocalDate.of(2026, 8, 8),
                                     local('2026-08-08T06:00'), local('2026-08-08T20:00'))])
            .build()
        def t = task('deck', ['deck'])
        def closed = new WeatherEvaluator(weatherConfig(max_age: 'PT6H', fallback: 'fail_closed'))
        def open = new WeatherEvaluator(weatherConfig(max_age: 'PT6H', fallback: 'fail_open'))

        expect:
        closed.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now).hardInfeasible
        closed.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now).result == WeatherEvaluation.RESULT_STALE
        !open.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now).hardInfeasible
        open.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now).result == WeatherEvaluation.RESULT_STALE
    }

    def "missing bucket coverage fail-closed cannot silently pass"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'], 120)
        // only covers first hour
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 20d, wind: 5d])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T12:00'), fc, now)

        then:
        we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_UNKNOWN
        we.reason.toLowerCase().contains('coverage') || we.reason.toLowerCase().contains('missing')
    }

    def "absent forecast fail-closed blocks weather-sensitive task"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), null, now)

        then:
        we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_UNKNOWN
    }

    def "first matching rule wins deterministically"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        // deck label matches deck-paint first (stricter 15%) before outdoor (25%)
        def t = task('deck', ['outdoor', 'deck'])
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 20d, precipMm: 0d, temp: 20d, wind: 5d])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        we.hardInfeasible
        we.ruleName == 'deck-paint'
    }

    def "clear forecast is feasible with suitability bonus"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 5d, precipMm: 0d, temp: 20d, wind: 5d, confidence: 0.9d])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        !we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_FEASIBLE
        we.scoreDelta > 0
        we.provider == 'fixture'
    }

    def "continuous coverage helper accepts abutting hours"() {
        given:
        def a = hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 20d, wind: 5d])
        def b = hour('2026-08-08T11:00', [precipProb: 0d, precipMm: 0d, temp: 20d, wind: 5d])

        expect:
        WeatherEvaluator.hasContinuousCoverage([a, b], a.start, b.end)
        !WeatherEvaluator.hasContinuousCoverage([a], a.start, b.end)
    }

    def "null precipitationProbability with required threshold is hard infeasible fail-closed"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: null, precipMm: 0d, temp: 20d, wind: 5d])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_UNKNOWN
        we.ruleName == 'deck-paint'
        we.observedField == 'precipitation_probability'
        we.observedValue == null
        we.threshold == 15d
        we.details.missingObservation == true
        we.details.missingField == 'precipitation_probability'
        we.reason.toLowerCase().contains('missing')
        we.reason.toLowerCase().contains('precipitation')
        we.reason.toLowerCase().contains('fail-closed')
        !we.reason.toLowerCase().contains('exceeded')
    }

    def "null precipitationMm with required threshold is hard infeasible fail-closed"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('out', ['outdoor'])
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 0d, precipMm: null, temp: 20d, wind: 5d])
        ])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_UNKNOWN
        we.observedField == 'precipitation_mm'
        we.details.missingObservation == true
        we.reason.toLowerCase().contains('missing')
        we.reason.toLowerCase().contains('fail-closed')
    }

    def "null windSpeed with required threshold is hard infeasible fail-closed"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 20d, wind: null])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        we.hardInfeasible
        we.observedField == 'wind_speed_kph'
        we.details.missingObservation == true
        we.reason.toLowerCase().contains('missing')
        we.reason.toLowerCase().contains('wind')
        we.reason.toLowerCase().contains('fail-closed')
    }

    def "null temperature with required min/max is hard infeasible fail-closed"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: null, wind: 5d])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        we.hardInfeasible
        we.observedField == 'temperature_c'
        we.details.missingObservation == true
        we.reason.toLowerCase().contains('missing')
        we.reason.toLowerCase().contains('temperature')
        we.reason.toLowerCase().contains('fail-closed')
    }

    def "null daylight with require.daylight is hard infeasible fail-closed missing observation"() {
        given:
        def cfg = weatherConfig(task_rules: [[
            name        : 'require-day',
            match_labels: ['deck'],
            require     : [
                precipitation_probability_max: 100,
                precipitation_mm_max         : 100,
                temperature_min_c            : 0,
                wind_speed_kph_max           : 100,
                daylight                     : true
            ]
        ]])
        def evaluator = new WeatherEvaluator(cfg)
        def t = task('deck', ['deck'])
        // No sunrise/sunset windows; hourly daylight null
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: 0d, precipMm: 0d, temp: 20d, wind: 5d, daylight: null])
        ], [:])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_UNKNOWN
        we.observedField == 'daylight'
        we.details.missingObservation == true
        we.reason.toLowerCase().contains('missing')
        we.reason.toLowerCase().contains('daylight')
        we.reason.toLowerCase().contains('fail-closed')
    }

    def "missing observation fail-open allows scheduling with explicit explanation not silent pass"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig(fallback: 'fail_open'))
        def t = task('deck', ['deck'])
        def fc = forecast([
            hour('2026-08-08T10:00', [precipProb: null, precipMm: 0d, temp: 20d, wind: 5d])
        ], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        !we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_UNKNOWN
        we.ruleName == 'deck-paint'
        we.observedField == 'precipitation_probability'
        we.details.missingObservation == true
        we.details.fallback == 'fail_open'
        we.reason.toLowerCase().contains('missing')
        we.reason.toLowerCase().contains('fail-open')
        we.reason.toLowerCase().contains('does not treat missing')
        we.scoreDelta == 0L
    }

    def "multi-hour interval fails when only one bucket has null required observation"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'], 120)
        def good = hour('2026-08-08T10:00', [precipProb: 5d, precipMm: 0d, temp: 20d, wind: 5d])
        def missing = hour('2026-08-08T11:00', [precipProb: null, precipMm: 0d, temp: 20d, wind: 5d])
        def fc = forecast([good, missing], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T12:00'), fc, now)

        then:
        we.hardInfeasible
        we.result == WeatherEvaluation.RESULT_UNKNOWN
        we.observedField == 'precipitation_probability'
        we.details.missingObservation == true
        we.details.bucketStart == missing.start.toString()
        we.relevantHours.size() == 2
        we.reason.toLowerCase().contains('missing')
        we.reason.toLowerCase().contains('fail-closed')
    }

    def "defensive non-finite observation is hard fail-closed even if model somehow allows it"() {
        given:
        def evaluator = new WeatherEvaluator(weatherConfig())
        def t = task('deck', ['deck'])
        // Bypass WeatherInterval.Builder finite check via reflection to simulate rogue gateway
        def iv = hour('2026-08-08T10:00', [precipProb: 5d, precipMm: 0d, temp: 20d, wind: 5d])
        def field = WeatherInterval.getDeclaredField('precipitationProbability')
        field.accessible = true
        field.set(iv, Double.NaN)
        def fc = forecast([iv], ['2026-08-08': ['06:00', '20:00']])

        when:
        def we = evaluator.evaluate(t, local('2026-08-08T10:00'), local('2026-08-08T11:00'), fc, now)

        then:
        we.hardInfeasible
        we.observedField == 'precipitation_probability'
        we.reason.toLowerCase().contains('non-finite') || we.reason.toLowerCase().contains('invalid')
    }
}
