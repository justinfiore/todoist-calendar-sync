package todoistcaldavsync.planner.scheduling

import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.adapters.FixtureWeatherGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.domain.WeatherForecast
import todoistcaldavsync.planner.domain.WeatherInterval

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WeatherSchedulerSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')
    // Weekend window: Sat 2026-08-08 and Sun 2026-08-09
    Instant rangeStart = LocalDate.of(2026, 8, 8).atStartOfDay(zone).toInstant()
    Instant rangeEnd = LocalDate.of(2026, 8, 10).atStartOfDay(zone).toInstant()
    Instant now = LocalDate.of(2026, 8, 7).atTime(12, 0).atZone(zone).toInstant()
    Instant issued = Instant.parse('2026-08-07T12:00:00Z')

    PlannerConfig configWithWeather(boolean enabled = true, Map weatherExtra = [:]) {
        Map weather = [
            enabled   : enabled,
            provider  : 'fixture',
            latitude  : 40.71,
            longitude : -74.01,
            max_age   : 'P1D',
            fallback  : 'fail_closed',
            task_rules: [
                [
                    name        : 'deck-paint',
                    match_labels: ['paint', 'deck', 'outdoor'],
                    require     : [
                        precipitation_probability_max: 15,
                        precipitation_mm_max         : 0.5,
                        temperature_min_c            : 10,
                        wind_speed_kph_max           : 25
                    ],
                    preferred   : [daylight: true]
                ]
            ]
        ]
        weatherExtra.each { k, v -> weather[k] = v }
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [
                saturday: ['09:00-12:00'],
                sunday  : ['09:00-12:00']
            ]],
            tasks       : [default_duration_minutes: 60],
            batching    : [enabled: false],
            stability   : [
                freeze_within                        : 'PT48H',
                keep_manual_moves                    : true,
                minimum_buffer_between_blocks_minutes: 0,
                churn_penalty                        : 40
            ],
            weather     : weather
        ])
    }

    Task task(Map args) {
        Task.builder()
            .id(args.id as String)
            .content(args.content ?: args.id)
            .projectId(args.projectId as String)
            .projectName(args.projectName ?: args.projectId)
            .labels((args.labels ?: ['schedule']) as List)
            .priority(args.priority != null ? args.priority as int : 2)
            .deadline(args.deadline as Instant)
            .effectiveDuration(Duration.ofMinutes((args.minutes ?: 60) as long))
            .durationSource('test')
            .build()
    }

    TimeSlot slot(LocalDate day, String startLocal, String endLocal) {
        TimeSlot.builder()
            .start(day.atTime(java.time.LocalTime.parse(startLocal)).atZone(zone).toInstant())
            .end(day.atTime(java.time.LocalTime.parse(endLocal)).atZone(zone).toInstant())
            .windowName(day.dayOfWeek.toString().toLowerCase())
            .build()
    }

    List<TimeSlot> weekendSlots() {
        def sat = LocalDate.of(2026, 8, 8)
        def sun = LocalDate.of(2026, 8, 9)
        [slot(sat, '09:00', '12:00'), slot(sun, '09:00', '12:00')]
    }

    WeatherInterval hour(LocalDate day, int h, double precipProb, double precipMm = 0d,
                         double temp = 22d, double wind = 8d) {
        Instant s = day.atTime(h, 0).atZone(zone).toInstant()
        WeatherInterval.builder()
            .start(s).end(s + Duration.ofHours(1))
            .precipitationProbability(precipProb)
            .precipitationMm(precipMm)
            .temperatureC(temp)
            .windSpeedKph(wind)
            .daylight(true)
            .build()
    }

    WeatherForecast rainSaturdayClearSunday() {
        def sat = LocalDate.of(2026, 8, 8)
        def sun = LocalDate.of(2026, 8, 9)
        List<WeatherInterval> intervals = []
        (9..11).each { h -> intervals << hour(sat, h, 75d, 1.5d) }
        (9..11).each { h -> intervals << hour(sun, h, 5d, 0d) }
        Map dl = [
            (sat): new WeatherForecast.DaylightWindow(sat,
                sat.atTime(6, 0).atZone(zone).toInstant(),
                sat.atTime(20, 0).atZone(zone).toInstant()),
            (sun): new WeatherForecast.DaylightWindow(sun,
                sun.atTime(6, 0).atZone(zone).toInstant(),
                sun.atTime(20, 0).atZone(zone).toInstant())
        ]
        WeatherForecast.builder()
            .provider('fixture').issuedAt(issued).retrievedAt(issued)
            .latitude(40.71).longitude(-74.01).timezone(zone)
            .intervals(intervals).daylightByDate(dl).build()
    }

    WeatherForecast clearBothDays() {
        def sat = LocalDate.of(2026, 8, 8)
        def sun = LocalDate.of(2026, 8, 9)
        List<WeatherInterval> intervals = []
        (9..11).each { h -> intervals << hour(sat, h, 5d, 0d) }
        (9..11).each { h -> intervals << hour(sun, h, 5d, 0d) }
        Map dl = [
            (sat): new WeatherForecast.DaylightWindow(sat,
                sat.atTime(6, 0).atZone(zone).toInstant(),
                sat.atTime(20, 0).atZone(zone).toInstant()),
            (sun): new WeatherForecast.DaylightWindow(sun,
                sun.atTime(6, 0).atZone(zone).toInstant(),
                sun.atTime(20, 0).atZone(zone).toInstant())
        ]
        WeatherForecast.builder()
            .provider('fixture').issuedAt(issued).retrievedAt(issued)
            .latitude(40.71).longitude(-74.01).timezone(zone)
            .intervals(intervals).daylightByDate(dl).build()
    }

    def "rain invalidates deck painting and selects feasible indoor replacement in same slot"() {
        given:
        def config = configWithWeather(true)
        def scheduler = new DeterministicScheduler(config)
        def deadline = LocalDate.of(2026, 8, 10).atTime(18, 0).atZone(zone).toInstant()
        def tasks = [
            task(id: 'deck', content: 'Paint the Deck', labels: ['schedule', 'outdoor', 'paint', 'deck'],
                minutes: 90, priority: 3, deadline: deadline, projectId: 'home'),
            task(id: 'scouts', content: 'Scouts admin', labels: ['schedule', 'computer'],
                minutes: 90, priority: 2, deadline: deadline, projectId: 'scouts')
        ]
        def forecast = rainSaturdayClearSunday()

        when:
        def plan = scheduler.propose(tasks, weekendSlots(), rangeStart, rangeEnd, now, null, [] as Set, forecast)

        then:
        // Deck must not sit on rainy Saturday
        def deckBlock = plan.scheduledBlocks.find { it.taskIds.contains('deck') }
        deckBlock != null
        deckBlock.start.atZone(zone).toLocalDate() == LocalDate.of(2026, 8, 9)

        // Indoor takes Saturday capacity
        def indoorBlock = plan.scheduledBlocks.find { it.taskIds.contains('scouts') }
        indoorBlock != null
        indoorBlock.start.atZone(zone).toLocalDate() == LocalDate.of(2026, 8, 8)

        // Explicit indoor→outdoor replacement linkage (main rain/deck case)
        def indoorChange = plan.changes.find { it.taskId == 'scouts' }
        indoorChange != null
        indoorChange.metadata.replacesWeatherInvalidTaskId == 'deck'
        indoorChange.metadata.replacementReason == 'indoor_replacement_for_weather_invalid_slot'

        def deckChange = plan.changes.find { it.taskId == 'deck' }
        deckChange != null
        deckChange.metadata.replacedByIndoorTaskId == 'scouts'

        plan.explanations.any {
            it.code == 'indoor_weather_replacement' &&
                it.details?.outdoorTaskId == 'deck' &&
                it.details?.indoorTaskId == 'scouts'
        }

        indoorBlock.metadata?.replacesWeatherInvalidTaskId == 'deck'
        deckBlock.metadata?.replacedByIndoorTaskId == 'scouts'

        plan.humanDiff.contains('Indoor replacement for: deck')
        plan.humanDiff.contains('Replaced by indoor task: scouts') ||
            plan.humanDiff.contains('Indoor replacement for: deck')

        plan.humanDiff.toLowerCase().contains('precipitation') ||
            plan.changes.any { it.taskId == 'deck' && it.reason.toLowerCase().contains('precipitation') } ||
            plan.scheduledBlocks.any { it.taskIds.contains('deck') && it.reason.toLowerCase().contains('weather') }
    }

    def "non-outdoor tasks identical with weather disabled vs enabled benign forecast"() {
        given:
        def tasks = [
            task(id: 'a', labels: ['schedule', 'computer'], minutes: 60, priority: 3,
                deadline: rangeEnd, projectId: 'p'),
            task(id: 'b', labels: ['schedule', 'home'], minutes: 60, priority: 2,
                deadline: rangeEnd, projectId: 'p')
        ]
        def slots = weekendSlots()
        def disabled = new DeterministicScheduler(configWithWeather(false))
        def enabled = new DeterministicScheduler(configWithWeather(true))
        def forecast = clearBothDays()

        when:
        def planOff = disabled.propose(tasks, slots, rangeStart, rangeEnd, now)
        def planOn = enabled.propose(tasks, slots, rangeStart, rangeEnd, now, null, [] as Set, forecast)

        then:
        planOff.scheduledBlocks.collect { [it.taskIds, it.start, it.end] } ==
            planOn.scheduledBlocks.collect { [it.taskIds, it.start, it.end] }
        planOff.unscheduled*.task*.id == planOn.unscheduled*.task*.id
    }

    def "absent provider forecast with weather disabled does not break planning"() {
        given:
        def scheduler = new DeterministicScheduler(configWithWeather(false))
        def tasks = [task(id: 'x', labels: ['schedule'], minutes: 60, deadline: rangeEnd)]

        when:
        def plan = scheduler.propose(tasks, weekendSlots(), rangeStart, rangeEnd, now)

        then:
        plan.scheduledBlocks.size() == 1
        plan.unscheduled.isEmpty()
    }

    def "deterministic under reversed task and hour order including replacement linkage"() {
        given:
        def config = configWithWeather(true)
        def scheduler = new DeterministicScheduler(config)
        def deadline = rangeEnd
        def tasksFwd = [
            task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3, deadline: deadline),
            task(id: 'in1', labels: ['computer'], minutes: 60, priority: 2, deadline: deadline)
        ]
        def tasksRev = tasksFwd.reverse()
        def fc = rainSaturdayClearSunday()
        def intervalsRev = fc.intervals.reverse()
        def fcRev = WeatherForecast.builder()
            .provider(fc.provider).issuedAt(fc.issuedAt).retrievedAt(fc.retrievedAt)
            .latitude(fc.latitude).longitude(fc.longitude).timezone(fc.timezone)
            .intervals(intervalsRev).daylightByDate(fc.daylightByDate).build()

        when:
        def p1 = scheduler.propose(tasksFwd, weekendSlots(), rangeStart, rangeEnd, now, null, [] as Set, fc)
        def p2 = scheduler.propose(tasksRev, weekendSlots().reverse(), rangeStart, rangeEnd, now, null, [] as Set, fcRev)

        then:
        p1.scheduledBlocks.collect { [it.taskIds, it.start, it.end] } ==
            p2.scheduledBlocks.collect { [it.taskIds, it.start, it.end] }
        def link1 = p1.changes.find { it.metadata?.replacesWeatherInvalidTaskId }
        def link2 = p2.changes.find { it.metadata?.replacesWeatherInvalidTaskId }
        link1?.taskId == link2?.taskId
        link1?.metadata?.replacesWeatherInvalidTaskId == link2?.metadata?.replacesWeatherInvalidTaskId
        link1?.metadata?.replacesWeatherInvalidTaskId == 'deck'
    }

    def "frozen prior placement weather conflict is proposed not silently kept"() {
        given:
        def config = configWithWeather(true)
        def scheduler = new DeterministicScheduler(config)
        def deadline = rangeEnd
        def deck = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3, deadline: deadline)
        def satStart = LocalDate.of(2026, 8, 8).atTime(9, 0).atZone(zone).toInstant()
        def satEnd = satStart + Duration.ofMinutes(60)
        // Previous plan placed deck on Saturday (now rainy), marked frozen
        def previous = Plan.builder()
            .id('prev-1').version(1).createdAt(now).mode('preview')
            .tasks([deck])
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-deck')
                    .start(satStart).end(satEnd)
                    .taskIds(['deck'])
                    .title('Paint the Deck')
                    .frozen(true)
                    .reason('prior')
                    .build()
            ])
            .build()
        def forecast = rainSaturdayClearSunday()

        when:
        def plan = scheduler.propose([deck], weekendSlots(), rangeStart, rangeEnd, now,
            previous, [] as Set, forecast)

        then:
        // Must not keep Saturday placement
        def deckBlock = plan.scheduledBlocks.find { it.taskIds.contains('deck') }
        if (deckBlock != null) {
            assert deckBlock.start.atZone(zone).toLocalDate() != LocalDate.of(2026, 8, 8)
        }
        // Either moved to Sunday or unscheduled with weather reason — never silent keep on rain
        def keptSat = plan.changes.find {
            it.taskId == 'deck' && it.type == 'keep' && it.newStart == satStart
        }
        keptSat == null
        plan.explanations.any {
            it.code?.toString()?.contains('weather') || it.message?.toLowerCase()?.contains('precipitation')
        } || plan.changes.any {
            it.taskId == 'deck' && (it.type == 'move' || it.reason?.toLowerCase()?.contains('precipitation'))
        } || plan.unscheduled.any { it.task.id == 'deck' && it.code == 'weather_infeasible' }
    }

    def "manual prior weather conflict surfaced as proposal without silent keep"() {
        given:
        def config = configWithWeather(true)
        def scheduler = new DeterministicScheduler(config)
        def deck = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3, deadline: rangeEnd)
        def satStart = LocalDate.of(2026, 8, 8).atTime(9, 0).atZone(zone).toInstant()
        def satEnd = satStart + Duration.ofMinutes(60)
        def previous = Plan.builder()
            .id('prev-m').version(1).createdAt(now).mode('preview')
            .tasks([deck])
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-deck')
                    .start(satStart).end(satEnd)
                    .taskIds(['deck'])
                    .title('Deck')
                    .manualOverride(true)
                    .reason('manual')
                    .build()
            ])
            .build()

        when:
        def plan = scheduler.propose([deck], weekendSlots(), rangeStart, rangeEnd, now,
            previous, ['deck'] as Set, rainSaturdayClearSunday())

        then:
        plan.mode == 'preview'
        plan.changes.any { it.taskId == 'deck' && it.type != 'keep' } ||
            plan.unscheduled.any { it.task.id == 'deck' } ||
            plan.explanations.any { it.code?.toString()?.toLowerCase()?.contains('weather') }
        def keptSat = plan.changes.find {
            it.taskId == 'deck' && it.type == 'keep' && it.newStart == satStart
        }
        keptSat == null
    }

    def "forecast timestamp rule and replacement linkage exact in markdown and JSON"() {
        given:
        def scheduler = new DeterministicScheduler(configWithWeather(true))
        def deck = task(id: 'deck', content: 'Paint the Deck', labels: ['outdoor', 'deck'],
            minutes: 60, priority: 3, deadline: rangeEnd)
        def indoor = task(id: 'in', content: 'Indoor', labels: ['computer'],
            minutes: 60, priority: 2, deadline: rangeEnd)
        def forecast = rainSaturdayClearSunday()
        def humanIssued = DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a')
            .withLocale(Locale.US)
            .format(issued.atZone(zone))
        def humanRetrieved = DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a')
            .withLocale(Locale.US)
            .format(forecast.retrievedAt.atZone(zone))

        when:
        def plan = scheduler.propose([deck, indoor], weekendSlots(), rangeStart, rangeEnd, now,
            null, [] as Set, forecast)
        def md = plan.humanDiff
        def jsonText = PlanDiffFormatter.toJson(plan)
        def json = new JsonSlurper().parseText(jsonText)

        then:
        // Markdown: exact weather rule + 12-hour AM/PM timestamps in configured zone
        md.contains('Weather rule: deck-paint')
        md.contains("Forecast issued: ${humanIssued}")
        md.contains("Forecast retrieved: ${humanRetrieved}")
        md.contains('Indoor replacement for: deck')

        // JSON: exact ISO timestamps, rule id/name, evaluation result, replacement linkage
        def indoorChg = json.changes.find { it.taskId == 'in' }
        indoorChg != null
        indoorChg.metadata.replacesWeatherInvalidTaskId == 'deck'
        indoorChg.metadata.replacementReason == 'indoor_replacement_for_weather_invalid_slot'

        def deckWeather = (json.changes + json.explanations).collectMany { item ->
            def metas = []
            if (item.metadata?.weather instanceof Map) metas << item.metadata.weather
            if (item.metadata?.priorWeather instanceof Map) metas << item.metadata.priorWeather
            if (item.metadata?.replacedTaskWeather instanceof Map) metas << item.metadata.replacedTaskWeather
            if (item.details?.weather instanceof Map) metas << item.details.weather
            if (item.details?.forecastIssuedAt) metas << item.details
            if (item.details?.ruleName) metas << item.details
            metas
        }.find { it.ruleName == 'deck-paint' || it.forecastIssuedAt }
        deckWeather != null
        deckWeather.forecastIssuedAt == issued.toString()
        (deckWeather.forecastRetrievedAt == null || deckWeather.forecastRetrievedAt == forecast.retrievedAt.toString())
        deckWeather.ruleName == 'deck-paint' || indoorChg.metadata.replacedTaskWeather?.ruleName == 'deck-paint'

        def weatherEval = indoorChg.metadata.replacedTaskWeather ?: deckWeather
        weatherEval.result in ['INFEASIBLE', 'FEASIBLE', 'UNKNOWN', 'STALE']
        jsonText.contains('"forecastIssuedAt"')
        jsonText.contains(issued.toString())
        jsonText.contains('replacesWeatherInvalidTaskId')
    }

    def "proposal-only purity: scheduler API accepts only evaluator/forecast, no write gateways"() {
        given:
        def config = configWithWeather(true)
        def evaluator = new WeatherEvaluator(config)

        expect: 'constructors accept only PlannerConfig and optional WeatherEvaluator'
        DeterministicScheduler.constructors.every { Constructor c ->
            c.parameterTypes.every { Class p ->
                p == PlannerConfig || p == WeatherEvaluator
            }
        }

        and: 'no production field typed as write gateway or PlanApplier'
        DeterministicScheduler.declaredFields.every { Field f ->
            if (Modifier.isStatic(f.modifiers)) {
                return true
            }
            String n = f.type.name
            !n.contains('PlanApplier') &&
                !n.contains('WriteGateway') &&
                !n.contains('ManagedCalendar') &&
                !n.toLowerCase().contains('todoistwrite') &&
                !n.toLowerCase().contains('calendarwrite')
        }

        and: 'public methods never take write gateway / PlanApplier parameters'
        DeterministicScheduler.declaredMethods.every { Method m ->
            if (!Modifier.isPublic(m.modifiers)) {
                return true
            }
            m.parameterTypes.every { Class p ->
                String n = p.name
                !n.contains('PlanApplier') && !n.contains('WriteGateway') &&
                    !n.contains('ManagedCalendar')
            }
        }

        and: 'Phase 4 scheduler/evaluator/weather-adapter sources do not import write gateways'
        def roots = [
            'app/src/main/groovy/todoistcaldavsync/planner/scheduling',
            'app/src/main/groovy/todoistcaldavsync/planner/adapters/WeatherGateway.groovy',
            'app/src/main/groovy/todoistcaldavsync/planner/adapters/OpenMeteoWeatherGateway.groovy',
            'app/src/main/groovy/todoistcaldavsync/planner/adapters/FixtureWeatherGateway.groovy',
            'app/src/main/groovy/todoistcaldavsync/planner/domain/WeatherEvaluation.groovy',
            'app/src/main/groovy/todoistcaldavsync/planner/domain/WeatherForecast.groovy',
            'app/src/main/groovy/todoistcaldavsync/planner/domain/WeatherInterval.groovy'
        ]
        List<File> sources = []
        roots.each { r ->
            def f = new File(r)
            if (f.isDirectory()) {
                f.eachFileRecurse { if (it.name.endsWith('.groovy')) sources << it }
            } else if (f.isFile()) {
                sources << f
            }
        }
        sources.every { File src ->
            String text = src.getText('UTF-8')
            !text.contains('import todoistcaldavsync.planner.apply.PlanApplier') &&
                !text.contains('TodoistWriteGateway') &&
                !text.contains('CalendarWriteGateway') &&
                !text.contains('ManagedCalendarWriteGateway')
        }

        when: 'propose with pure evaluator + forecast only'
        def scheduler = new DeterministicScheduler(config, evaluator)
        def spyApplied = new boolean[1]
        // Boundary spy: would fail if any orchestration invoked apply
        def planApplierBoundary = new Object() {
            def apply(Object... args) {
                spyApplied[0] = true
                throw new AssertionError('PlanApplier must not be invoked during Phase 4 proposal')
            }
        }
        // ensure boundary object is "live" but never passed into scheduler
        assert planApplierBoundary != null
        def plan = scheduler.propose(
            [task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3, deadline: rangeEnd),
             task(id: 'in', labels: ['computer'], minutes: 60, priority: 2, deadline: rangeEnd)],
            weekendSlots(), rangeStart, rangeEnd, now, null, [] as Set, rainSaturdayClearSunday()
        )

        then:
        plan != null
        plan.mode == 'preview'
        !spyApplied[0]
        plan.changes.any { it.metadata?.replacesWeatherInvalidTaskId == 'deck' }
    }

    def "weather-infeasible unscheduled-only renders rule and timestamps without indoor replacement"() {
        given:
        // Only outdoor deck — rain all weekend so no feasible outdoor slot and no indoor replacement
        def scheduler = new DeterministicScheduler(configWithWeather(true))
        def deck = task(id: 'deck', content: 'Paint the Deck', labels: ['outdoor', 'deck'],
            minutes: 60, priority: 3, deadline: rangeEnd)
        def sat = LocalDate.of(2026, 8, 8)
        def sun = LocalDate.of(2026, 8, 9)
        List intervals = []
        (9..11).each { h ->
            intervals << hour(sat, h, 80d, 2d)
            intervals << hour(sun, h, 80d, 2d)
        }
        def forecast = WeatherForecast.builder()
            .provider('fixture')
            .issuedAt(issued)
            .retrievedAt(Instant.parse('2026-08-07T12:05:00Z'))
            .latitude(40.71d)
            .longitude(-74.01d)
            .timezone(zone)
            .intervals(intervals)
            .daylightByDate([
                (sat): new WeatherForecast.DaylightWindow(sat,
                    sat.atTime(6, 0).atZone(zone).toInstant(),
                    sat.atTime(20, 0).atZone(zone).toInstant()),
                (sun): new WeatherForecast.DaylightWindow(sun,
                    sun.atTime(6, 0).atZone(zone).toInstant(),
                    sun.atTime(20, 0).atZone(zone).toInstant())
            ])
            .build()
        def humanIssued = DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a')
            .withLocale(Locale.US).format(issued.atZone(zone))
        def humanRetrieved = DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a')
            .withLocale(Locale.US).format(forecast.retrievedAt.atZone(zone))

        when:
        def plan = scheduler.propose([deck], weekendSlots(), rangeStart, rangeEnd, now,
            null, [] as Set, forecast)
        def md = plan.humanDiff
        def jsonText = PlanDiffFormatter.toJson(plan)
        def json = new JsonSlurper().parseText(jsonText)

        then:
        plan.unscheduled.any { it.task.id == 'deck' && it.code == 'weather_infeasible' }
        !plan.changes.any { it.metadata?.replacesWeatherInvalidTaskId == 'deck' }
        !plan.scheduledBlocks.any { it.taskIds.contains('deck') }

        and: 'Markdown exact rule + both AM/PM timestamps even without PlanChange/replacement'
        md.contains('Weather rule: deck-paint')
        md.contains('Weather evaluation: INFEASIBLE') || md.contains('Weather evaluation: UNKNOWN')
        md.contains("Forecast issued: ${humanIssued}")
        md.contains("Forecast retrieved: ${humanRetrieved}")
        md.contains('Code: weather_infeasible')
        !md.contains('Indoor replacement for:')

        and: 'JSON exact ISO on unscheduled metadata'
        def u = json.unscheduled.find { it.task.id == 'deck' }
        u != null
        u.code == 'weather_infeasible'
        u.metadata.weather.ruleName == 'deck-paint'
        u.metadata.weather.ruleId == 'deck-paint'
        u.metadata.weather.forecastIssuedAt == issued.toString()
        u.metadata.weather.forecastRetrievedAt == forecast.retrievedAt.toString()
        u.metadata.weather.result in ['INFEASIBLE', 'UNKNOWN', 'STALE']
        jsonText.contains(issued.toString())
        jsonText.contains(forecast.retrievedAt.toString())
    }

    def "fully occupied day with rainy forecast keeps capacity binding reason not weather"() {
        given:
        def config = configWithWeather(true)
        def scheduler = new DeterministicScheduler(config)
        def deadline = rangeEnd
        // Fill each 3h weekend window with higher-priority indoor work (no placeable slot left)
        def fillSat = task(id: 'fill-sat', labels: ['computer'], minutes: 180, priority: 4, deadline: deadline)
        def fillSun = task(id: 'fill-sun', labels: ['computer'], minutes: 180, priority: 4, deadline: deadline)
        def deck = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 1, deadline: deadline)
        def forecast = rainSaturdayClearSunday()

        when:
        def plan = scheduler.propose([fillSat, fillSun, deck], weekendSlots(), rangeStart, rangeEnd, now,
            null, [] as Set, forecast)

        then:
        plan.scheduledBlocks.any { it.taskIds.contains('fill-sat') }
        plan.scheduledBlocks.any { it.taskIds.contains('fill-sun') }
        def u = plan.unscheduled.find { it.task.id == 'deck' }
        u != null
        u.code != 'weather_infeasible'
        u.code in ['no_capacity', 'no_slot', 'deadline', 'insufficient_capacity'] ||
            (u.reason?.toLowerCase()?.contains('capacity') ||
                u.reason?.toLowerCase()?.contains('fit') ||
                u.reason?.toLowerCase()?.contains('free'))
        u.metadata?.weather == null
        !plan.explanations.any {
            it.code == 'weather_unscheduled' && it.subjectId == 'deck'
        }
    }

    def "available capacity but rain still binds weather_infeasible"() {
        given:
        def config = configWithWeather(true)
        def scheduler = new DeterministicScheduler(config)
        def deck = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3, deadline: rangeEnd)
        def sat = LocalDate.of(2026, 8, 8)
        def sun = LocalDate.of(2026, 8, 9)
        List intervals = []
        (9..11).each { h ->
            intervals << hour(sat, h, 90d, 3d)
            intervals << hour(sun, h, 90d, 3d)
        }
        def forecast = WeatherForecast.builder()
            .provider('fixture').issuedAt(issued).retrievedAt(issued)
            .latitude(40.71d).longitude(-74.01d).timezone(zone)
            .intervals(intervals)
            .daylightByDate([
                (sat): new WeatherForecast.DaylightWindow(sat,
                    sat.atTime(6, 0).atZone(zone).toInstant(),
                    sat.atTime(20, 0).atZone(zone).toInstant()),
                (sun): new WeatherForecast.DaylightWindow(sun,
                    sun.atTime(6, 0).atZone(zone).toInstant(),
                    sun.atTime(20, 0).atZone(zone).toInstant())
            ])
            .build()

        when:
        def plan = scheduler.propose([deck], weekendSlots(), rangeStart, rangeEnd, now,
            null, [] as Set, forecast)

        then:
        def u = plan.unscheduled.find { it.task.id == 'deck' }
        u != null
        u.code == 'weather_infeasible'
        u.metadata?.weather != null
        u.reason?.toLowerCase()?.contains('precip') || u.metadata.weather.observedField != null
    }

    def "DST fall folds evaluated as distinct weather hours for outdoor task"() {
        given:
        def config = configWithWeather(true, [
            task_rules: [[
                name        : 'night-outdoor',
                match_labels: ['outdoor'],
                require     : [precipitation_probability_max: 20]
            ]]
        ])
        def zoneNy = zone
        // Working window covering the fold hours on fall-back day
        def fallConfig = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [sunday: ['00:30-02:30']]],
            tasks       : [default_duration_minutes: 30],
            batching    : [enabled: false],
            stability   : [minimum_buffer_between_blocks_minutes: 0, freeze_within: 'PT1H', churn_penalty: 0],
            weather     : [
                enabled   : true, provider: 'fixture', latitude: 40.71, longitude: -74.01,
                max_age   : 'P30D', fallback: 'fail_closed',
                task_rules: [[
                    name: 'fold', match_labels: ['outdoor'],
                    require: [precipitation_probability_max: 10]
                ]]
            ]
        ])
        def fixtureUrl = getClass().classLoader.getResource('planner/fixtures/weather-open-meteo-dst-fall.json')
        def file = fixtureUrl != null ? new File(fixtureUrl.toURI())
            : new File('app/src/test/resources/planner/fixtures/weather-open-meteo-dst-fall.json')
        if (!file.exists()) {
            file = new File('src/test/resources/planner/fixtures/weather-open-meteo-dst-fall.json')
        }
        def fc = FixtureWeatherGateway.fromFile(file, 40.71d, -74.01d, zoneNy, issued).fetchForecast(null, null)
        def folds = fc.intervals.findAll {
            it.start.atZone(zoneNy).toLocalTime().hour == 1
        }
        // Make first fold dry, second fold rainy — evaluator must see both distinctly
        def rebuilt = fc.intervals.collect { iv ->
            double pp = iv.precipitationProbability ?: 0d
            if (folds.size() == 2 && iv.start == folds[1].start) {
                pp = 80d
            }
            WeatherInterval.builder()
                .start(iv.start).end(iv.end)
                .precipitationProbability(pp)
                .precipitationMm(iv.precipitationMm)
                .temperatureC(iv.temperatureC)
                .windSpeedKph(iv.windSpeedKph)
                .daylight(iv.daylight)
                .build()
        }
        def forecast = WeatherForecast.builder()
            .provider(fc.provider).issuedAt(issued).retrievedAt(issued)
            .latitude(fc.latitude).longitude(fc.longitude).timezone(zoneNy)
            .intervals(rebuilt).daylightByDate(fc.daylightByDate).build()
        def evaluator = new WeatherEvaluator(fallConfig)
        def task = task(id: 'out', labels: ['outdoor'], minutes: 30)
        def firstFoldStart = folds[0].start
        def secondFoldStart = folds[1].start

        when:
        def e1 = evaluator.evaluate(task, firstFoldStart, firstFoldStart + Duration.ofMinutes(30), forecast, issued)
        def e2 = evaluator.evaluate(task, secondFoldStart, secondFoldStart + Duration.ofMinutes(30), forecast, issued)

        then:
        folds.size() == 2
        firstFoldStart != secondFoldStart
        !e1.hardInfeasible
        e2.hardInfeasible
        e2.observedField == 'precipitation_probability' || e2.reason.toLowerCase().contains('precip')
    }

    def "open-meteo rain fixture drives deck off Saturday"() {
        given:
        def fixtureUrl = getClass().classLoader.getResource('planner/fixtures/weather-open-meteo-rain.json')
        def file = fixtureUrl != null ? new File(fixtureUrl.toURI())
            : new File('src/test/resources/planner/fixtures/weather-open-meteo-rain.json')
        if (!file.exists()) {
            file = new File('app/src/test/resources/planner/fixtures/weather-open-meteo-rain.json')
        }
        def gw = FixtureWeatherGateway.fromFile(file, 40.71d, -74.01d, zone, issued)
        def forecast = gw.fetchForecast(rangeStart, rangeEnd)
        def scheduler = new DeterministicScheduler(configWithWeather(true))
        def deck = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3, deadline: rangeEnd)

        when:
        def plan = scheduler.propose([deck], weekendSlots(), rangeStart, rangeEnd, now,
            null, [] as Set, forecast)

        then:
        def block = plan.scheduledBlocks.find { it.taskIds.contains('deck') }
        block == null || block.start.atZone(zone).toLocalDate() == LocalDate.of(2026, 8, 9)
        if (block == null) {
            assert plan.unscheduled.any { it.task.id == 'deck' }
        }
    }

    WeatherForecast morningRainThenClear(LocalDate day = LocalDate.of(2026, 8, 8)) {
        List<WeatherInterval> intervals = [
            hour(day, 9, 80d, 2d),
            hour(day, 10, 5d, 0d),
            hour(day, 11, 5d, 0d)
        ]
        Map dl = [
            (day): new WeatherForecast.DaylightWindow(day,
                day.atTime(6, 0).atZone(zone).toInstant(),
                day.atTime(20, 0).atZone(zone).toInstant())
        ]
        WeatherForecast.builder()
            .provider('fixture').issuedAt(issued).retrievedAt(issued)
            .latitude(40.71).longitude(-74.01).timezone(zone)
            .intervals(intervals).daylightByDate(dl).build()
    }

    def "forecast boundary candidate starts outdoor after morning rain at 10:00"() {
        given:
        def config = configWithWeather(true)
        def scheduler = new DeterministicScheduler(config)
        def day = LocalDate.of(2026, 8, 8)
        def slots = [slot(day, '09:00', '12:00')]
        def deadline = day.atTime(18, 0).atZone(zone).toInstant()
        def deck = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3, deadline: deadline)
        def fc = morningRainThenClear(day)
        def fcRev = WeatherForecast.builder()
            .provider(fc.provider).issuedAt(fc.issuedAt).retrievedAt(fc.retrievedAt)
            .latitude(fc.latitude).longitude(fc.longitude).timezone(fc.timezone)
            .intervals(fc.intervals.reverse()).daylightByDate(fc.daylightByDate).build()
        Instant expected = day.atTime(10, 0).atZone(zone).toInstant()

        when:
        def plan = scheduler.propose([deck], slots, rangeStart, rangeEnd, now, null, [] as Set, fc)
        def planRev = scheduler.propose([deck], slots, rangeStart, rangeEnd, now, null, [] as Set, fcRev)

        then:
        def block = plan.scheduledBlocks.find { it.taskIds.contains('deck') }
        block != null
        block.start == expected
        block.end == expected + Duration.ofMinutes(60)
        planRev.scheduledBlocks.find { it.taskIds.contains('deck') }?.start == expected
    }

    def "90m outdoor leaves only clear 10-11 window unscheduled without inventing starts"() {
        given:
        def config = configWithWeather(true)
        def scheduler = new DeterministicScheduler(config)
        def day = LocalDate.of(2026, 8, 8)
        def slots = [slot(day, '09:00', '12:00')]
        // rain 09-10, clear 10-11, rain 11-12 — only 60m clear; 90m cannot fit
        List<WeatherInterval> intervals = [
            hour(day, 9, 80d, 2d),
            hour(day, 10, 5d, 0d),
            hour(day, 11, 80d, 2d)
        ]
        Map dl = [
            (day): new WeatherForecast.DaylightWindow(day,
                day.atTime(6, 0).atZone(zone).toInstant(),
                day.atTime(20, 0).atZone(zone).toInstant())
        ]
        def fc = WeatherForecast.builder()
            .provider('fixture').issuedAt(issued).retrievedAt(issued)
            .latitude(40.71).longitude(-74.01).timezone(zone)
            .intervals(intervals).daylightByDate(dl).build()
        def deck = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 90, priority: 3,
            deadline: day.atTime(18, 0).atZone(zone).toInstant())

        when:
        def plan = scheduler.propose([deck], slots, rangeStart, rangeEnd, now, null, [] as Set, fc)

        then:
        plan.scheduledBlocks.find { it.taskIds.contains('deck') } == null
        plan.unscheduled.any { it.task.id == 'deck' }
    }

    def "weather disabled keeps prior candidate starts without forecast boundaries"() {
        given:
        def day = LocalDate.of(2026, 8, 8)
        def slots = [slot(day, '09:00', '12:00')]
        def deck = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3,
            deadline: day.atTime(18, 0).atZone(zone).toInstant())
        def fc = morningRainThenClear(day)
        def off = new DeterministicScheduler(configWithWeather(false))
        def onClear = new DeterministicScheduler(configWithWeather(true))
        def clear = clearBothDays()

        when:
        def planOff = off.propose([deck], slots, rangeStart, rangeEnd, now, null, [] as Set, fc)
        def planOnClear = onClear.propose([deck], slots, rangeStart, rangeEnd, now, null, [] as Set, clear)

        then:
        // Disabled ignores rain and places at slot start (prior behavior)
        planOff.scheduledBlocks.find { it.taskIds.contains('deck') }?.start ==
            day.atTime(9, 0).atZone(zone).toInstant()
        // Enabled + clear also uses slot start (no need for interior forecast boundary)
        planOnClear.scheduledBlocks.find { it.taskIds.contains('deck') }?.start ==
            day.atTime(9, 0).atZone(zone).toInstant()
    }

    def "focus block weather-sensitive member offset aligns to forecast boundary"() {
        given:
        def day = LocalDate.of(2026, 8, 8)
        // Batch both tasks into one focus block
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [saturday: ['09:00-12:00']]],
            tasks       : [default_duration_minutes: 60],
            batching    : [enabled: true, min_tasks: 2, same_project_only: true],
            stability   : [
                freeze_within                        : 'PT48H',
                keep_manual_moves                    : true,
                minimum_buffer_between_blocks_minutes: 0,
                churn_penalty                        : 40
            ],
            weather     : [
                enabled   : true,
                provider  : 'fixture',
                latitude  : 40.71,
                longitude : -74.01,
                max_age   : 'P1D',
                fallback  : 'fail_closed',
                task_rules: [[
                    name        : 'deck-paint',
                    match_labels: ['outdoor', 'deck'],
                    require     : [
                        precipitation_probability_max: 15,
                        precipitation_mm_max         : 0.5,
                        temperature_min_c            : 10,
                        wind_speed_kph_max           : 25
                    ]
                ]]
            ]
        ])
        def scheduler = new DeterministicScheduler(config)
        def slots = [slot(day, '09:00', '12:00')]
        // Indoor 60m then outdoor 60m: outdoor needs start at 10:00 => block at 09:00 works for outdoor at 10
        // But if order is outdoor first then indoor, outdoor at 09 fails; need block start 10:00
        def outdoor = task(id: 'deck', labels: ['outdoor', 'deck'], minutes: 60, priority: 3,
            deadline: day.atTime(18, 0).atZone(zone).toInstant(), projectId: 'home')
        def indoor = task(id: 'admin', labels: ['computer'], minutes: 60, priority: 2,
            deadline: day.atTime(18, 0).atZone(zone).toInstant(), projectId: 'home')
        def fc = morningRainThenClear(day)

        when:
        def plan = scheduler.propose([outdoor, indoor], slots, rangeStart, rangeEnd, now, null, [] as Set, fc)

        then:
        def block = plan.scheduledBlocks.find { it.taskIds.contains('deck') }
        block != null
        // Outdoor member must not cover rainy 09:00 hour
        def deckStart = block.memberIntervals?.find { it.taskId == 'deck' }?.start ?: block.start
        !deckStart.isBefore(day.atTime(10, 0).atZone(zone).toInstant())
        deckStart.atZone(zone).toLocalTime().hour >= 10
    }
}
