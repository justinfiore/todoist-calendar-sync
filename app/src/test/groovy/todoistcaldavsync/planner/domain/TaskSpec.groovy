package todoistcaldavsync.planner.domain

import spock.lang.Specification

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TaskSpec extends Specification {

    Task.DurationResolver resolver = new Task.DurationResolver(30, [t15: 15, t30: 30, t60: 60, t90: 90])
    ZoneId ny = ZoneId.of('America/New_York')

    def "is immutable — labels list cannot be modified"() {
        given:
        def task = Task.builder()
            .id('1')
            .content('X')
            .labels(['schedule', 't30'])
            .priority(1)
            .effectiveDuration(Duration.ofMinutes(30))
            .durationSource('default')
            .build()

        when:
        task.labels.add('hack')

        then:
        thrown(UnsupportedOperationException)
    }

    def "rejects missing id and non-positive duration"() {
        when:
        Task.builder().content('x').effectiveDuration(Duration.ofMinutes(30)).durationSource('default').build()

        then:
        thrown(IllegalArgumentException)

        when:
        Task.builder().id('1').content('x').effectiveDuration(Duration.ZERO).durationSource('default').build()

        then:
        thrown(IllegalArgumentException)
    }

    def "normalizes Todoist deadline separately from due time in planner timezone"() {
        given:
        def raw = [
            id      : 'a',
            content : 'Ship it',
            labels  : ['schedule'],
            priority: 3,
            deadline: [date: '2026-08-10'],
            due     : [date: '2026-08-06T09:00:00Z']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        // Date-only deadline = exclusive next local midnight America/New_York
        task.deadline == LocalDate.of(2026, 8, 11).atStartOfDay(ny).toInstant()
        task.dueTime == Instant.parse('2026-08-06T09:00:00Z')
        task.deadline != task.dueTime
        !task.allDayDue
    }

    def "date-only due is local start-of-day all-day; deadline exclusive next local midnight"() {
        given:
        def raw = [
            id      : 'b',
            content : 'All day',
            labels  : [],
            priority: 1,
            deadline: [date: '2026-08-07'],
            due     : [date: '2026-08-06']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        task.allDayDue
        task.dueTime == LocalDate.of(2026, 8, 6).atStartOfDay(ny).toInstant()
        task.deadline == LocalDate.of(2026, 8, 8).atStartOfDay(ny).toInstant()
        // EDT: local midnight 2026-08-06 is 04:00Z
        task.dueTime == Instant.parse('2026-08-06T04:00:00Z')
        task.deadline == Instant.parse('2026-08-08T04:00:00Z')
    }

    def "America/New_York evening working window on deadline day remains eligible"() {
        given:
        // Deadline date-only 2026-08-06 means exclusive end = 2026-08-07T04:00:00Z (EDT)
        def raw = [
            id      : 'eve',
            content : 'Due today',
            labels  : ['schedule'],
            priority: 1,
            deadline: [date: '2026-08-06']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)
        // Evening slot 16:00-17:00 ET on deadline day
        Instant eveningStart = LocalDate.of(2026, 8, 6).atTime(16, 0).atZone(ny).toInstant()
        Instant eveningEnd = LocalDate.of(2026, 8, 6).atTime(17, 0).atZone(ny).toInstant()

        then:
        task.deadline == LocalDate.of(2026, 8, 7).atStartOfDay(ny).toInstant()
        eveningEnd.isBefore(task.deadline) || eveningEnd == task.deadline
        eveningStart.isBefore(task.deadline)
        // UTC end-of-day would have been 2026-08-06T23:59:59Z = 19:59 ET — evening 16-17 would still work,
        // but prove full local day: 20:00-21:00 ET is after UTC EOD but before local exclusive midnight
        Instant lateEvening = LocalDate.of(2026, 8, 6).atTime(20, 0).atZone(ny).toInstant()
        lateEvening.isBefore(task.deadline)
        Instant utcEod = Instant.parse('2026-08-06T23:59:59Z')
        lateEvening.isAfter(utcEod)
    }

    def "DST spring-forward date-only deadline uses local calendar day"() {
        given:
        // 2026-03-08 is DST spring forward in America/New_York (02:00 -> 03:00)
        def raw = [
            id      : 'dst',
            content : 'DST day',
            labels  : [],
            priority: 1,
            deadline: [date: '2026-03-08'],
            due     : [date: '2026-03-08']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        task.allDayDue
        task.dueTime == LocalDate.of(2026, 3, 8).atStartOfDay(ny).toInstant()
        task.deadline == LocalDate.of(2026, 3, 9).atStartOfDay(ny).toInstant()
        // EST before spring forward: midnight Mar 8 = 05:00Z; next midnight Mar 9 EDT = 04:00Z
        task.dueTime == Instant.parse('2026-03-08T05:00:00Z')
        task.deadline == Instant.parse('2026-03-09T04:00:00Z')
    }

    def "DST fall-back date-only due uses local start-of-day"() {
        given:
        // 2026-11-01 is DST fall back in America/New_York
        def raw = [
            id      : 'fall',
            content : 'Fall back',
            labels  : [],
            priority: 1,
            due     : [date: '2026-11-01']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        task.allDayDue
        task.dueTime == LocalDate.of(2026, 11, 1).atStartOfDay(ny).toInstant()
        task.dueTime == Instant.parse('2026-11-01T04:00:00Z') // still EDT until 2am
    }

    def "preserves offset and Z instants exactly"() {
        expect:
        Task.parseFlexibleInstant('2026-08-06T10:00:00-0400', false, ny) == Instant.parse('2026-08-06T14:00:00Z')
        Task.parseFlexibleInstant('2026-08-06T10:00:00-04:00', false, ny) == Instant.parse('2026-08-06T14:00:00Z')
        Task.parseFlexibleInstant('2026-08-06T14:00:00Z', false, ny) == Instant.parse('2026-08-06T14:00:00Z')
        Task.parseFlexibleInstant('2026-08-06T14:00:00Z', true, ny) == Instant.parse('2026-08-06T14:00:00Z')
    }

    def "duration precedence: native overrides label overrides default"() {
        expect:
        Task.fromTodoistMap(raw, resolver).durationSource == source
        Task.fromTodoistMap(raw, resolver).effectiveDuration == Duration.ofMinutes(minutes)

        where:
        raw                                                                                          | source       | minutes
        [id: '1', content: 'n', labels: ['t15'], priority: 1, duration: [amount: 45, unit: 'minute']] | 'native'     | 45
        [id: '2', content: 'l', labels: ['schedule', 't90'], priority: 1]                            | 'label:t90'  | 90
        [id: '3', content: 'd', labels: ['schedule'], priority: 1]                                   | 'default'    | 30
        [id: '4', content: 'h', labels: [], priority: 1, duration: [amount: 2, unit: 'hour']]         | 'native'     | 120
    }

    def "duration label lookup is case-insensitive"() {
        given:
        def caseResolver = new Task.DurationResolver(30, [t60: 60, DeepWork: 120])
        def raw = [id: '1', content: 'x', labels: ['T60', 'other'], priority: 1]
        def raw2 = [id: '2', content: 'y', labels: ['deepwork'], priority: 1]

        when:
        def t1 = Task.fromTodoistMap(raw, caseResolver)
        def t2 = Task.fromTodoistMap(raw2, caseResolver)

        then:
        t1.durationSource == 'label:T60'
        t1.effectiveDuration.toMinutes() == 60
        t2.durationSource == 'label:deepwork'
        t2.effectiveDuration.toMinutes() == 120
    }

    def "label duration picks first configured label in task label order"() {
        given:
        def raw = [id: '1', content: 'x', labels: ['foo', 't15', 't60'], priority: 1]

        when:
        def task = Task.fromTodoistMap(raw, resolver)

        then:
        task.durationSource == 'label:t15'
        task.effectiveDuration.toMinutes() == 15
    }

    def "manual label marks task manual"() {
        expect:
        Task.fromTodoistMap([id: '1', content: 'm', labels: ['manual', 'schedule'], priority: 1], resolver).manual
        !Task.fromTodoistMap([id: '2', content: 'n', labels: ['schedule'], priority: 1], resolver).manual
    }

    def "parses offset due times without colon"() {
        given:
        def raw = [
            id: '1', content: 'x', labels: [], priority: 1,
            due: [date: '2026-08-06T10:00:00-0400']
        ]

        expect:
        Task.fromTodoistMap(raw, resolver, 'manual', ny).dueTime == Instant.parse('2026-08-06T14:00:00Z')
    }

    def "rejects invalid priority"() {
        when:
        Task.fromTodoistMap([id: '1', content: 'x', labels: [], priority: 9], resolver)

        then:
        thrown(IllegalArgumentException)
    }

    def "zone-less local datetime uses nested due.timezone when present"() {
        given:
        // 10:00 America/Los_Angeles in August = 17:00Z (PDT)
        def raw = [
            id      : 'tz-nested',
            content : 'West coast call',
            labels  : [],
            priority: 1,
            due     : [date: '2026-08-06T10:00:00', timezone: 'America/Los_Angeles']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        task.dueTime == Instant.parse('2026-08-06T17:00:00Z')
        !task.allDayDue
    }

    def "zone-less local datetime uses nested deadline.timezone when present"() {
        given:
        def raw = [
            id      : 'tz-dl',
            content : 'Deadline local',
            labels  : [],
            priority: 1,
            deadline: [date: '2026-08-06T18:00:00', timezone: 'America/Chicago']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        // 18:00 CDT = 23:00Z
        task.deadline == Instant.parse('2026-08-06T23:00:00Z')
    }

    def "zone-less bare T datetime without nested tz uses planner timezone"() {
        given:
        def raw = [
            id      : 'tz-planner',
            content : 'Local bare',
            labels  : [],
            priority: 1,
            due     : [date: '2026-08-06T10:00:00']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        // 10:00 EDT = 14:00Z — must NOT force UTC (which would be 10:00Z)
        task.dueTime == Instant.parse('2026-08-06T14:00:00Z')
        task.dueTime != Instant.parse('2026-08-06T10:00:00Z')
    }

    def "string due without map uses planner timezone for zone-less T datetime"() {
        given:
        def raw = [
            id      : 'tz-str',
            content : 'String due',
            labels  : [],
            priority: 1,
            due     : '2026-08-06T09:30:00'
        ]

        expect:
        Task.fromTodoistMap(raw, resolver, 'manual', ny).dueTime == Instant.parse('2026-08-06T13:30:00Z')
    }

    def "explicit offset and Z still preserved exactly over nested timezone"() {
        given:
        def withZ = [
            id: 'z', content: 'Z', labels: [], priority: 1,
            due: [date: '2026-08-06T14:00:00Z', timezone: 'America/Los_Angeles']
        ]
        def withOffset = [
            id: 'o', content: 'O', labels: [], priority: 1,
            due: [date: '2026-08-06T10:00:00-04:00', timezone: 'America/Los_Angeles']
        ]

        expect:
        Task.fromTodoistMap(withZ, resolver, 'manual', ny).dueTime == Instant.parse('2026-08-06T14:00:00Z')
        Task.fromTodoistMap(withOffset, resolver, 'manual', ny).dueTime == Instant.parse('2026-08-06T14:00:00Z')
    }

    def "date-only due/deadline ignore nested timezone and use planner zone local day semantics"() {
        given:
        def raw = [
            id      : 'date-only-nested-tz',
            content : 'All day',
            labels  : [],
            priority: 1,
            due     : [date: '2026-08-06', timezone: 'America/Los_Angeles'],
            deadline: [date: '2026-08-07', timezone: 'America/Los_Angeles']
        ]

        when:
        def task = Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        task.allDayDue
        task.dueTime == LocalDate.of(2026, 8, 6).atStartOfDay(ny).toInstant()
        task.deadline == LocalDate.of(2026, 8, 8).atStartOfDay(ny).toInstant()
    }

    def "rejects invalid nested Todoist timezone with useful error"() {
        given:
        def raw = [
            id: 'bad-tz', content: 'x', labels: [], priority: 1,
            due: [date: '2026-08-06T10:00:00', timezone: 'Not/ARealZone']
        ]

        when:
        Task.fromTodoistMap(raw, resolver, 'manual', ny)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('timezone')
        e.message.contains('Not/ARealZone')
    }

    def "DST gap: spring-forward zone-less local time uses Java ZoneId resolution"() {
        given:
        // 2026-03-08 02:30 does not exist in America/New_York (spring forward 02:00->03:00).
        // java.time LocalDateTime.atZone(ZoneId) gap policy: shifts forward into valid offset
        // => 2026-03-08T03:30-04:00[America/New_York] = 07:30Z
        def gapLocal = '2026-03-08T02:30:00'

        when:
        def instant = Task.parseFlexibleInstant(gapLocal, false, ny)
        def documented = java.time.LocalDateTime.parse(gapLocal).atZone(ny).toInstant()

        then:
        instant == documented
        instant == Instant.parse('2026-03-08T07:30:00Z')
        instant.atZone(ny).toLocalTime() == java.time.LocalTime.of(3, 30)
    }

    def "DST overlap: fall-back zone-less local time uses Java ZoneId earlier-offset policy"() {
        given:
        // 2026-11-01 01:30 occurs twice in America/New_York (EDT then EST).
        // java.time LocalDateTime.atZone(ZoneId) overlap policy: earlier offset (EDT, -04:00).
        def overlapLocal = '2026-11-01T01:30:00'

        when:
        def instant = Task.parseFlexibleInstant(overlapLocal, false, ny)
        def documented = java.time.LocalDateTime.parse(overlapLocal).atZone(ny).toInstant()

        then:
        instant == documented
        // Earlier offset EDT: 01:30-04:00 = 05:30Z (later EST would be 06:30Z)
        instant == Instant.parse('2026-11-01T05:30:00Z')
    }
}
