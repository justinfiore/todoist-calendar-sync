package todoistcaldavsync.planner.domain

import spock.lang.Specification

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WeatherDomainEqualitySpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')
    Instant t0 = Instant.parse('2026-08-08T14:00:00Z')
    Instant t1 = t0 + Duration.ofHours(1)

    WeatherInterval interval(Map args = [:]) {
        WeatherInterval.builder()
            .start((args.start ?: t0) as Instant)
            .end((args.end ?: t1) as Instant)
            .precipitationProbability(args.containsKey('pp') ? args.pp as Double : 10d)
            .precipitationMm(args.containsKey('mm') ? args.mm as Double : 0d)
            .temperatureC(args.containsKey('temp') ? args.temp as Double : 20d)
            .windSpeedKph(args.containsKey('wind') ? args.wind as Double : 5d)
            .daylight(args.containsKey('day') ? args.day as Boolean : true)
            .condition(args.condition as String)
            .weatherCode(args.code as Integer)
            .confidence(args.conf as Double)
            .build()
    }

    WeatherForecast forecast(List<WeatherInterval> intervals = [interval()], Map extra = [:]) {
        WeatherForecast.builder()
            .provider(extra.provider ?: 'fixture')
            .issuedAt((extra.issuedAt ?: t0) as Instant)
            .retrievedAt((extra.retrievedAt ?: t0) as Instant)
            .latitude((extra.latitude != null ? extra.latitude : 40.71d) as double)
            .longitude((extra.longitude != null ? extra.longitude : -74.01d) as double)
            .timezone((extra.timezone ?: zone) as ZoneId)
            .intervals(intervals)
            .daylightByDate((extra.daylight ?: [:]) as Map)
            .metadata((extra.metadata ?: [:]) as Map)
            .build()
    }

    def "WeatherInterval equals hashCode and set membership"() {
        given:
        def a = interval()
        def b = interval()
        def c = interval(pp: 50d)

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
        a.hashCode() != c.hashCode()
        ([a] as Set).contains(b)
        !([a] as Set).contains(c)
        a != interval(start: t0 + Duration.ofMinutes(1))
        a != interval(temp: 21d)
    }

    def "WeatherForecast equals hashCode with defensive collection equality"() {
        given:
        def iv = interval()
        def day = LocalDate.of(2026, 8, 8)
        def dl = [(day): new WeatherForecast.DaylightWindow(day, t0, t0 + Duration.ofHours(12))]
        def a = forecast([iv], [daylight: dl, metadata: [k: 'v']])
        def b = forecast([interval()], [daylight: [
            (day): new WeatherForecast.DaylightWindow(day, t0, t0 + Duration.ofHours(12))
        ], metadata: [k: 'v']])
        def c = forecast([interval(pp: 99d)], [daylight: dl, metadata: [k: 'v']])
        def d = forecast([iv], [daylight: dl, metadata: [k: 'other']])

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
        a != d
        ([a] as Set).contains(b)
        !([a] as Set).contains(c)
    }

    def "WeatherForecast rejects non-finite coordinates"() {
        when:
        WeatherForecast.builder()
            .provider('x').issuedAt(t0).latitude(Double.NaN).longitude(-74d).timezone(zone)
            .build()

        then:
        thrown(IllegalArgumentException)

        when:
        WeatherForecast.builder()
            .provider('x').issuedAt(t0).latitude(40d).longitude(Double.POSITIVE_INFINITY).timezone(zone)
            .build()

        then:
        thrown(IllegalArgumentException)
    }

    def "WeatherInterval rejects non-finite numeric observations"() {
        when:
        WeatherInterval.builder().start(t0).end(t1).precipitationProbability(Double.NaN).build()
        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.toLowerCase().contains('finite') || e1.message.contains('precipitationProbability')

        when:
        WeatherInterval.builder().start(t0).end(t1).precipitationMm(Double.POSITIVE_INFINITY).build()
        then:
        thrown(IllegalArgumentException)

        when:
        WeatherInterval.builder().start(t0).end(t1).temperatureC(Double.NEGATIVE_INFINITY).build()
        then:
        thrown(IllegalArgumentException)

        when:
        WeatherInterval.builder().start(t0).end(t1).windSpeedKph(Double.NaN).build()
        then:
        thrown(IllegalArgumentException)

        when:
        WeatherInterval.builder().start(t0).end(t1).confidence(Double.NaN).build()
        then:
        thrown(IllegalArgumentException)
    }

    def "WeatherInterval accepts null optional and valid finite numerics then enforces ranges"() {
        when:
        def ok = WeatherInterval.builder()
            .start(t0).end(t1)
            .precipitationProbability(null)
            .precipitationMm(null)
            .temperatureC(null)
            .windSpeedKph(null)
            .confidence(null)
            .build()
        then:
        ok.precipitationProbability == null
        ok.temperatureC == null

        when:
        def finite = interval(pp: 0d, mm: 0d, temp: -5d, wind: 0d, conf: 0d)
        then:
        finite.precipitationProbability == 0d
        finite.confidence == 0d

        when:
        WeatherInterval.builder().start(t0).end(t1).precipitationProbability(101d).build()
        then:
        thrown(IllegalArgumentException)

        when:
        WeatherInterval.builder().start(t0).end(t1).precipitationMm(-0.1d).build()
        then:
        thrown(IllegalArgumentException)

        when:
        WeatherInterval.builder().start(t0).end(t1).windSpeedKph(-1d).build()
        then:
        thrown(IllegalArgumentException)

        when:
        WeatherInterval.builder().start(t0).end(t1).confidence(1.1d).build()
        then:
        thrown(IllegalArgumentException)
    }

    def "WeatherForecast normalizes reversed intervals and rejects overlap or duplicate starts"() {
        given:
        def a = interval(start: t0, end: t1)
        def b = interval(start: t1, end: t1 + Duration.ofHours(1), pp: 20d)
        def c = interval(start: t0 + Duration.ofMinutes(30), end: t1 + Duration.ofMinutes(30), pp: 30d)

        when:
        def normalized = WeatherForecast.builder()
            .provider('x').issuedAt(t0).latitude(40d).longitude(-74d).timezone(zone)
            .intervals([b, a])
            .build()

        then:
        normalized.intervals*.start == [t0, t1]
        normalized.intervals == WeatherForecast.builder()
            .provider('x').issuedAt(t0).latitude(40d).longitude(-74d).timezone(zone)
            .intervals([a, b])
            .build().intervals

        when:
        WeatherForecast.builder()
            .provider('x').issuedAt(t0).latitude(40d).longitude(-74d).timezone(zone)
            .intervals([a, c])
            .build()
        then:
        def overlapEx = thrown(IllegalArgumentException)
        overlapEx.message.toLowerCase().contains('overlap')

        when:
        def dup = interval(start: t0, end: t1, pp: 99d)
        WeatherForecast.builder()
            .provider('x').issuedAt(t0).latitude(40d).longitude(-74d).timezone(zone)
            .intervals([a, dup])
            .build()
        then:
        def dupEx = thrown(IllegalArgumentException)
        dupEx.message.toLowerCase().contains('duplicate')
    }

    def "WeatherEvaluation equals hashCode and inequality mutations"() {
        given:
        def a = WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_INFEASIBLE)
            .hardInfeasible(true)
            .scoreDelta(0L)
            .ruleId('r1')
            .ruleName('deck')
            .reason('rain')
            .provider('fixture')
            .forecastIssuedAt(t0)
            .forecastRetrievedAt(t0)
            .latitude(40.71d)
            .longitude(-74.01d)
            .observedField('precipitation_probability')
            .observedValue(80d)
            .threshold(15d)
            .relevantHours([t0])
            .alternativesSignal(true)
            .details([matchedLabels: ['outdoor']])
            .build()
        def b = WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_INFEASIBLE)
            .hardInfeasible(true)
            .scoreDelta(0L)
            .ruleId('r1')
            .ruleName('deck')
            .reason('rain')
            .provider('fixture')
            .forecastIssuedAt(t0)
            .forecastRetrievedAt(t0)
            .latitude(40.71d)
            .longitude(-74.01d)
            .observedField('precipitation_probability')
            .observedValue(80d)
            .threshold(15d)
            .relevantHours([t0])
            .alternativesSignal(true)
            .details([matchedLabels: ['outdoor']])
            .build()
        def c = WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_FEASIBLE)
            .hardInfeasible(false)
            .scoreDelta(35L)
            .ruleId('r1')
            .ruleName('deck')
            .reason('ok')
            .build()

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
        ([a] as Set).contains(b)
        !([a] as Set).contains(c)
        a != WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_INFEASIBLE)
            .hardInfeasible(true)
            .reason('rain')
            .observedValue(81d)
            .build()
    }

    def "DaylightWindow equality"() {
        given:
        def d = LocalDate.of(2026, 8, 8)
        def a = new WeatherForecast.DaylightWindow(d, t0, t1)
        def b = new WeatherForecast.DaylightWindow(d, t0, t1)
        def c = new WeatherForecast.DaylightWindow(d, t0, t1 + Duration.ofHours(1))

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }
}
