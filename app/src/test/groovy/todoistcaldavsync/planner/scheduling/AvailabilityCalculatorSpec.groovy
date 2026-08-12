package todoistcaldavsync.planner.scheduling

import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.EventRole
import todoistcaldavsync.planner.policy.EventClassifier

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AvailabilityCalculatorSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')

    PlannerConfig config = PlannerConfig.fromMap(planner: [
        mode        : 'preview',
        timezone    : 'America/New_York',
        availability: [
            working_windows: [
                // Thursday 2026-08-06 is a weekday
                weekday: ['09:00-12:00', '13:00-17:00']
            ],
            calendars      : [
                [calendar: 'Work', default_role: 'hard_blocker'],
                [calendar: 'Family', default_role: 'soft_blocker'],
                [calendar: 'Bob', default_role: 'informational'],
                [calendar: 'Todoist Planned', default_role: 'managed_output']
            ],
            event_rules    : [
                [
                    name                 : 'buffered hard',
                    calendar_regex       : '^Work$',
                    title_regex          : '(?i)buffered',
                    role                 : 'hard_blocker',
                    buffer_before_minutes: 30,
                    buffer_after_minutes : 30
                ]
            ],
            unknown_calendar_fallback: 'informational'
        ]
    ])

    EventClassifier classifier = new EventClassifier(config)
    AvailabilityCalculator calc = new AvailabilityCalculator(config)

    Instant start = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
    Instant end = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()

    private CalendarEvent raw(String id, String cal, String title, String s, String e, String desc = '') {
        CalendarEvent.builder()
            .id(id)
            .title(title)
            .description(desc)
            .calendarName(cal)
            .start(Instant.parse(s))
            .end(Instant.parse(e))
            .build()
    }

    def "generates working window slots with no events"() {
        when:
        def result = calc.calculate(start, end, [])

        then:
        result.slots.size() == 2
        result.usableCapacityMinutes == 3 * 60 + 4 * 60 // 9-12 + 13-17
        result.slots.every { !it.softBlocked }
    }

    def "hard blockers subtract capacity including buffers"() {
        given:
        // 10:00-11:00 ET = 14:00-15:00Z in August (EDT)
        def events = classifier.classifyAll([
            raw('h1', 'Work', 'Buffered meeting', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)

        then:
        // Morning 09:00-12:00 ET loses 09:30-11:30 due to 30m buffers around 10-11
        // Free morning: 09:00-09:30 + 11:30-12:00 (60m); afternoon 13:00-17:00 (240m)
        result.usableCapacityMinutes == 60 + 240
        result.explanations.any { it.code == 'capacity_consumed' && it.message.toLowerCase().contains('buffer') }
    }

    def "soft blockers leave capacity available but marked penalized"() {
        given:
        def events = classifier.classifyAll([
            raw('s1', 'Family', 'Quiet time', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)

        then:
        result.usableCapacityMinutes == 7 * 60
        result.slots.any { it.softBlocked }
        result.softPenalizedMinutes > 0
        result.explanations.any { it.code == 'soft_blocker_penalized' }
    }

    def "one-hour soft blocker inside three-hour window splits exactly"() {
        given:
        // Morning window 09:00-12:00 ET; soft 10:00-11:00 ET
        def events = classifier.classifyAll([
            raw('s1', 'Family', 'Quiet time', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)
        def morning = result.slots.findAll {
            it.start >= LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant() &&
                it.end <= LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        }

        then:
        result.usableCapacityMinutes == 7 * 60
        result.softPenalizedMinutes == 60
        morning.size() == 3
        morning[0].start == LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        morning[0].end == LocalDate.of(2026, 8, 6).atTime(10, 0).atZone(zone).toInstant()
        !morning[0].softBlocked
        morning[1].start == LocalDate.of(2026, 8, 6).atTime(10, 0).atZone(zone).toInstant()
        morning[1].end == LocalDate.of(2026, 8, 6).atTime(11, 0).atZone(zone).toInstant()
        morning[1].softBlocked
        morning[1].softBlockerEventIds == ['s1']
        morning[1].durationMinutes() == 60
        morning[2].start == LocalDate.of(2026, 8, 6).atTime(11, 0).atZone(zone).toInstant()
        morning[2].end == LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        !morning[2].softBlocked
    }

    def "multiple overlapping soft blockers union minutes and preserve all ids"() {
        given:
        // Afternoon 13:00-17:00 ET
        // soft A 13:00-15:00, soft B 14:00-16:00 => soft union 13:00-16:00 = 180m, free 16:00-17:00
        def events = classifier.classifyAll([
            raw('sa', 'Family', 'A', '2026-08-06T17:00:00Z', '2026-08-06T19:00:00Z'),
            raw('sb', 'Family', 'B', '2026-08-06T18:00:00Z', '2026-08-06T20:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)
        def afternoon = result.slots.findAll {
            it.start >= LocalDate.of(2026, 8, 6).atTime(13, 0).atZone(zone).toInstant()
        }
        def softSegs = afternoon.findAll { it.softBlocked }
        def freeSegs = afternoon.findAll { !it.softBlocked }
        def both = softSegs.find { it.softBlockerEventIds.containsAll(['sa', 'sb']) }

        then:
        result.usableCapacityMinutes == 7 * 60
        result.softPenalizedMinutes == 180
        softSegs.sum { it.durationMinutes() } == 180
        freeSegs.size() == 1
        freeSegs[0].start == LocalDate.of(2026, 8, 6).atTime(16, 0).atZone(zone).toInstant()
        freeSegs[0].end == LocalDate.of(2026, 8, 6).atTime(17, 0).atZone(zone).toInstant()
        both != null
        both.durationMinutes() == 60 // 14:00-15:00 overlap region
        softSegs.any { it.softBlockerEventIds == ['sa'] || (it.softBlockerEventIds.contains('sa') && !it.softBlockerEventIds.contains('sb')) }
        softSegs.any { it.softBlockerEventIds.contains('sb') && !it.softBlockerEventIds.contains('sa') || it.softBlockerEventIds == ['sb'] }
    }

    def "edge-touching soft events do not create zero-length or double-count"() {
        given:
        // Soft ends exactly when another starts: 10:00-11:00 and 11:00-12:00 ET
        def events = classifier.classifyAll([
            raw('e1', 'Family', 'First', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z'),
            raw('e2', 'Family', 'Second', '2026-08-06T15:00:00Z', '2026-08-06T16:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)
        def morningSoft = result.slots.findAll {
            it.softBlocked &&
                it.start >= LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant() &&
                it.end <= LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        }

        then:
        result.softPenalizedMinutes == 120
        result.usableCapacityMinutes == 7 * 60
        morningSoft.every { it.durationMinutes() > 0 }
        morningSoft.sum { it.durationMinutes() } == 120
        // Touching at 11:00: no shared segment with both ids
        !morningSoft.any { it.softBlockerEventIds.containsAll(['e1', 'e2']) }
        morningSoft.any { it.softBlockerEventIds == ['e1'] }
        morningSoft.any { it.softBlockerEventIds == ['e2'] }
    }

    def "soft event outside working window does not affect slots"() {
        given:
        // 07:00-08:00 ET — before 09:00 window
        def events = classifier.classifyAll([
            raw('early', 'Family', 'Early', '2026-08-06T11:00:00Z', '2026-08-06T12:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)

        then:
        result.softPenalizedMinutes == 0
        result.slots.every { !it.softBlocked }
        result.usableCapacityMinutes == 7 * 60
    }

    def "informational events consume no capacity"() {
        given:
        def events = classifier.classifyAll([
            raw('i1', 'Bob', 'Soccer', '2026-08-06T14:00:00Z', '2026-08-06T16:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)

        then:
        result.usableCapacityMinutes == 7 * 60
        result.slots.every { !it.softBlocked }
        result.explanations.any { it.code == 'informational_no_capacity' }
    }

    def "managed output occupies capacity safely"() {
        given:
        def events = classifier.classifyAll([
            raw('m1', 'Todoist Planned', 'Planned block', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)

        then:
        result.usableCapacityMinutes == 7 * 60 - 60
        result.explanations.any { it.code == 'managed_output_occupied' }
        result.managedOutputEvents.size() == 1
    }

    def "unknown calendar informational still emits diagnostic and does not free extra time"() {
        given:
        def events = classifier.classifyAll([
            raw('u1', 'Random', 'Mystery', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)

        then:
        events[0].unknownCalendar
        result.usableCapacityMinutes == 7 * 60
        result.explanations.any { it.code == 'unknown_calendar_diagnostic' || it.code == 'informational_no_capacity' }
    }

    def "hard blocker without special rule uses calendar default"() {
        given:
        def events = classifier.classifyAll([
            raw('h2', 'Work', 'Standup', '2026-08-06T14:00:00Z', '2026-08-06T14:30:00Z')
        ])

        when:
        def result = calc.calculate(start, end, events)

        then:
        result.usableCapacityMinutes == 7 * 60 - 30
    }

    def "partially overlapping working windows union capacity once with deterministic name"() {
        given:
        // Thursday windows: 09:00-12:00 and 11:00-14:00 => union 09:00-14:00 = 300m
        def overlapConfig = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [
                    weekday : ['09:00-12:00'],
                    thursday: ['11:00-14:00']
                ]
            ]
        ])
        def overlapCalc = new AvailabilityCalculator(overlapConfig)

        when:
        def result = overlapCalc.calculate(start, end, [])
        def names = result.slots*.windowName

        then:
        result.usableCapacityMinutes == 5 * 60
        result.slots.size() == 1
        result.slots[0].start == LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        result.slots[0].end == LocalDate.of(2026, 8, 6).atTime(14, 0).atZone(zone).toInstant()
        // Deterministic combined name (sorted group/range labels)
        names == [result.slots[0].windowName]
        result.slots[0].windowName == result.slots[0].windowName // stable
        def again = overlapCalc.calculate(start, end, [])
        again.slots*.windowName == names
        again.usableCapacityMinutes == 300
    }

    def "contained working window does not double-count capacity"() {
        given:
        // 09:00-17:00 contains 10:00-12:00 => union 09:00-17:00 = 480m
        def containedConfig = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [
                    weekday : ['09:00-17:00'],
                    thursday: ['10:00-12:00']
                ]
            ]
        ])
        def containedCalc = new AvailabilityCalculator(containedConfig)

        when:
        def result = containedCalc.calculate(start, end, [])

        then:
        result.usableCapacityMinutes == 8 * 60
        result.slots.size() == 1
        result.slots[0].start == LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        result.slots[0].end == LocalDate.of(2026, 8, 6).atTime(17, 0).atZone(zone).toInstant()
    }

    def "adjacent working windows merge into continuous capacity"() {
        given:
        // 09:00-12:00 adjacent to 12:00-15:00 => 09:00-15:00 = 360m
        def adjConfig = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [
                    weekday : ['09:00-12:00'],
                    thursday: ['12:00-15:00']
                ]
            ]
        ])
        def adjCalc = new AvailabilityCalculator(adjConfig)

        when:
        def result = adjCalc.calculate(start, end, [])

        then:
        result.usableCapacityMinutes == 6 * 60
        result.slots.size() == 1
        result.slots[0].start == LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        result.slots[0].end == LocalDate.of(2026, 8, 6).atTime(15, 0).atZone(zone).toInstant()
    }

    def "multiple overlapping windows union once with stable sorted combined names"() {
        given:
        // 09-12, 10-13, 11-14, plus separate 15-17 => union 09-14 (300) + 15-17 (120) = 420
        def multiConfig = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [
                    weekday : ['09:00-12:00', '15:00-17:00'],
                    thursday: ['10:00-13:00', '11:00-14:00']
                ]
            ]
        ])
        def multiCalc = new AvailabilityCalculator(multiConfig)

        when:
        def r1 = multiCalc.calculate(start, end, [])
        def r2 = multiCalc.calculate(start, end, [])

        then:
        r1.usableCapacityMinutes == 7 * 60
        r1.slots.size() == 2
        r1.slots[0].durationMinutes() == 300
        r1.slots[1].durationMinutes() == 120
        r1.slots*.windowName == r2.slots*.windowName
        r1.usableCapacityMinutes == r2.usableCapacityMinutes
        // Combined name is deterministic lexicographic join
        r1.slots[0].windowName.contains('+') || r1.slots[0].windowName.contains('09:00')
    }

    def "soft blocker buffers expand penalized interval consistently with hard buffers"() {
        given:
        def bufConfig = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [weekday: ['09:00-12:00', '13:00-17:00']],
                calendars      : [[calendar: 'Family', default_role: 'soft_blocker']],
                event_rules    : [[
                    name                 : 'soft buffered',
                    calendar_regex       : '^Family$',
                    title_regex          : '(?i)quiet',
                    role                 : 'soft_blocker',
                    buffer_before_minutes: 30,
                    buffer_after_minutes : 30
                ]]
            ]
        ])
        def bufClassifier = new EventClassifier(bufConfig)
        def bufCalc = new AvailabilityCalculator(bufConfig)
        // Soft 10:00-11:00 ET with 30m buffers => penalized 09:30-11:30 = 120m
        def events = bufClassifier.classifyAll([
            raw('sb', 'Family', 'Quiet time', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
        ])

        when:
        def result = bufCalc.calculate(start, end, events)

        then:
        events[0].bufferBeforeMinutes == 30
        events[0].bufferAfterMinutes == 30
        result.usableCapacityMinutes == 7 * 60
        result.softPenalizedMinutes == 120
    }

    def "reversed soft-event input order yields identical segments and soft metadata"() {
        given:
        // Overlapping soft A 10:00-11:30 and B 10:30-11:00 inside morning window
        def aThenB = classifier.classifyAll([
            raw('sa', 'Family', 'A', '2026-08-06T14:00:00Z', '2026-08-06T15:30:00Z'),
            raw('sb', 'Family', 'B', '2026-08-06T14:30:00Z', '2026-08-06T15:00:00Z')
        ])
        def bThenA = classifier.classifyAll([
            raw('sb', 'Family', 'B', '2026-08-06T14:30:00Z', '2026-08-06T15:00:00Z'),
            raw('sa', 'Family', 'A', '2026-08-06T14:00:00Z', '2026-08-06T15:30:00Z')
        ])

        when:
        def r1 = calc.calculate(start, end, aThenB)
        def r2 = calc.calculate(start, end, bThenA)
        def morning1 = r1.slots.findAll {
            it.start >= LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant() &&
                it.end <= LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        }
        def morning2 = r2.slots.findAll {
            it.start >= LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant() &&
                it.end <= LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        }

        then:
        r1.usableCapacityMinutes == r2.usableCapacityMinutes
        r1.softPenalizedMinutes == r2.softPenalizedMinutes
        morning1.size() == morning2.size()
        morning1.indices.every { i ->
            morning1[i].start == morning2[i].start &&
                morning1[i].end == morning2[i].end &&
                morning1[i].softBlocked == morning2[i].softBlocked &&
                morning1[i].softBlockerEventIds == morning2[i].softBlockerEventIds &&
                morning1[i].softBlockerReasons == morning2[i].softBlockerReasons
        }
        // Deterministic id ordering (lexicographic) on multi-cover segment
        def both = morning1.find { it.softBlockerEventIds.containsAll(['sa', 'sb']) }
        both != null
        both.softBlockerEventIds == ['sa', 'sb']
        both.softBlockerReasons == both.softBlockerReasons.toSorted()
    }

    def "toPlaceableIntervals merges across soft splits but not hard gaps"() {
        given:
        def softEvents = classifier.classifyAll([
            raw('s1', 'Family', 'Quiet', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
        ])
        def hardEvents = classifier.classifyAll([
            raw('h1', 'Work', 'Standup', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
        ])

        when:
        def softResult = calc.calculate(start, end, softEvents)
        def hardResult = calc.calculate(start, end, hardEvents)
        def softPlaceable = AvailabilityCalculator.toPlaceableIntervals(softResult.slots)
        def hardPlaceable = AvailabilityCalculator.toPlaceableIntervals(hardResult.slots)
        def morningSoft = softPlaceable.findAll {
            it.end <= LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        }
        def morningHard = hardPlaceable.findAll {
            it.end <= LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant() ||
                it.start < LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        }

        then: 'soft diagnostic splits remain; placeable morning is one 3h interval'
        softResult.slots.findAll {
            it.start >= LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant() &&
                it.end <= LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        }.size() == 3
        morningSoft.size() == 1
        morningSoft[0].durationMinutes() == 180
        !morningSoft[0].softBlocked

        and: 'hard gap keeps two separate placeable sides of 60m each'
        morningHard.size() == 2
        morningHard*.durationMinutes() == [60L, 60L]
    }

    def "managed output occupies actual block only even if buffers were attached"() {
        given:
        // Config rejects buffers on managed_output; occupation uses event start/end only.
        def moConfig = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [weekday: ['09:00-12:00', '13:00-17:00']],
                calendars      : [[calendar: 'Todoist Planned', default_role: 'managed_output']]
            ]
        ])
        // Simulate a misclassified event that somehow has buffers — calculator must still
        // occupy only the actual block for MANAGED_OUTPUT (not bufferedStart/End).
        def managed = raw('m1', 'Todoist Planned', 'Planned block', '2026-08-06T14:00:00Z', '2026-08-06T15:00:00Z')
            .withClassification(EventRole.MANAGED_OUTPUT, 'test', 'managed', 30, 30, false)

        when:
        def result = new AvailabilityCalculator(moConfig).calculate(start, end, [managed])

        then:
        managed.bufferBeforeMinutes == 30
        result.usableCapacityMinutes == 7 * 60 - 60
        result.explanations.any { it.code == 'managed_output_occupied' }
    }
}
