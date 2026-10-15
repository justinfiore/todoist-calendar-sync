package todoistcaldavsync.planner.writeback

import spock.lang.Specification
import todoistcaldavsync.planner.domain.AppliedMapping
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds

import java.time.Instant

class CalendarWritebackDecisionSpec extends Specification {
    private static final Instant DUE = Instant.parse('2026-10-15T09:00:00Z')
    private static final Instant START = Instant.parse('2026-10-15T10:00:00Z')
    private static final Instant EARLIER = Instant.parse('2026-10-15T08:59:59.999999999Z')
    private static final Instant LATER = Instant.parse('2026-10-15T09:00:00.000000001Z')

    def "strict timestamp ordering selects the authoritative side at the boundary"() {
        expect:
        CalendarWritebackDecision.decide(DUE, taskUpdated, START, modified, recurring) == expected

        where:
        taskUpdated | modified    | recurring || expected
        EARLIER     | LATER       | false     || CalendarWritebackDecision.UPDATE_TODOIST
        LATER       | EARLIER     | false     || CalendarWritebackDecision.UPDATE_CALENDAR
        EARLIER     | LATER       | true      || CalendarWritebackDecision.GUARDED_RECURRING
    }

    def "equal times are a no-op regardless of modification clocks"() {
        expect:
        CalendarWritebackDecision.decide(DUE, null, DUE, null, false) == CalendarWritebackDecision.NOOP
    }

    def "different times with missing or tied timestamps are recorded and skipped"() {
        expect:
        CalendarWritebackDecision.decide(due, taskUpdated, start, modified, false) ==
            CalendarWritebackDecision.SKIP

        where:
        due   | taskUpdated | start | modified
        DUE   | null        | START | LATER
        DUE   | EARLIER     | START | null
        DUE   | LATER       | START | LATER
        null  | EARLIER     | START | LATER
        DUE   | EARLIER     | null  | LATER
    }

    def "echo match takes precedence over a newer calendar modification"() {
        expect:
        CalendarWritebackDecision.decide(DUE, EARLIER, START, LATER, false, true) ==
            CalendarWritebackDecision.NOOP
    }

    def "writeback enabled defaults off and accepts only a boolean value"() {
        expect:
        CalendarWritebackDecision.enabled(root) == expected

        where:
        root                                    || expected
        [:]                                     || false
        [calendarWriteback: [:]]                || false
        [calendarWriteback: [enabled: false]]   || false
        [calendarWriteback: [enabled: true]]    || true
    }

    def "writeback enabled rejects malformed configuration"() {
        when:
        CalendarWritebackDecision.enabled(root)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == 'calendarWriteback.enabled must be a boolean'

        where:
        root << [[calendarWriteback: true], [calendarWriteback: [enabled: 'true']]]
    }

    def "legacy UID uses unpadded lowercase Base32 hex and identifies only the configured task event"() {
        given:
        String uid = CalendarWritebackDecision.legacyUid('user-123', 'task-456')
        CalendarEvent candidate = event(uid, 'Synced')

        expect:
        uid == 'elpmashd64p36bbkc5pmmb9k6kr0'
        CalendarWritebackDecision.legacyCandidate(candidate, 'user-123', 'task-456', ['Synced'] as Set)
        !CalendarWritebackDecision.legacyCandidate(candidate, 'user-123', 'unknown', ['Synced'] as Set)
        !CalendarWritebackDecision.legacyCandidate(candidate, 'user-123', 'task-456', ['Other'] as Set)
        !CalendarWritebackDecision.legacyCandidate(null, 'user-123', 'task-456', ['Synced'] as Set)
    }

    def "planner candidate requires an owned output event mapped to exactly one task"() {
        given:
        String uid = ManagedEventIds.uidForBlock('block-1')
        CalendarEvent owned = event(uid, 'Planned', ManagedEventIds.buildDescription('block-1', 'plan-1'))
        CalendarEvent unowned = event(uid, 'Planned', 'personal event')
        CalendarEvent wrongCalendar = event(uid, 'Other', ManagedEventIds.buildDescription('block-1', 'plan-1'))

        expect:
        CalendarWritebackDecision.plannerCandidate(owned, 'Planned', [mapping('task-1', uid)])
        !CalendarWritebackDecision.plannerCandidate(unowned, 'Planned', [mapping('task-1', uid)])
        !CalendarWritebackDecision.plannerCandidate(wrongCalendar, 'Planned', [mapping('task-1', uid)])
        !CalendarWritebackDecision.plannerCandidate(owned, 'Planned', [mapping('task-1', 'unknown-uid')])
        !CalendarWritebackDecision.plannerCandidate(owned, 'Planned', [
            mapping('task-1', uid), mapping('task-2', uid)
        ])
    }

    def "echo matches an etag or a range not modified after the local write"() {
        given:
        Instant writtenAt = Instant.parse('2026-10-15T10:05:00Z')
        CalendarEvent matchingEtag = event('uid-1', 'Planned', '', 'etag-2', START,
            Instant.parse('2026-10-15T11:00:00Z'), writtenAt.plusSeconds(1))
        CalendarEvent matchingRange = event('uid-1', 'Planned', '', 'etag-new', START,
            Instant.parse('2026-10-15T11:00:00Z'), writtenAt)
        Map written = [etag: 'etag-2', start: START.toString(),
                       end: '2026-10-15T11:00:00Z', writtenAt: writtenAt.toString()]

        expect:
        CalendarWritebackDecision.echo(matchingEtag, written)
        CalendarWritebackDecision.echo(matchingRange, written)
        !CalendarWritebackDecision.echo(event('uid-1', 'Planned', '', 'etag-new', START,
            Instant.parse('2026-10-15T11:00:00Z'), writtenAt.plusNanos(1)), written)
        !CalendarWritebackDecision.echo(matchingRange, [:])
    }

    private static CalendarEvent event(String uid, String calendar, String description = '', String etag = null,
                                       Instant start = START,
                                       Instant end = Instant.parse('2026-10-15T11:00:00Z'),
                                       Instant modified = null) {
        CalendarEvent.builder().id(uid).uid(uid).title('Task').description(description)
            .calendarName(calendar).start(start).end(end).lastModified(modified).etag(etag).build()
    }

    private static AppliedMapping mapping(String taskId, String uid) {
        AppliedMapping.builder().taskId(taskId).eventUid(uid).planId('plan-1')
            .slotStart(START).slotEnd(Instant.parse('2026-10-15T11:00:00Z'))
            .appliedAt(Instant.parse('2026-10-15T08:00:00Z')).build()
    }
}
