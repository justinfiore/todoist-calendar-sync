package todoistcaldavsync.planner.domain

import spock.lang.Specification

class ManagedEventIdsSpec extends Specification {

    def "uid is deterministic and marked as planner owned"() {
        when:
        def u1 = ManagedEventIds.uidForBlock('block-42')
        def u2 = ManagedEventIds.uidForBlock('block-42')
        def u3 = ManagedEventIds.uidForBlock('block-43')

        then:
        u1 == u2
        u1 != u3
        ManagedEventIds.isPlannerUid(u1)
        u1.startsWith(ManagedEventIds.UID_PREFIX)
        u1.contains(ManagedEventIds.UID_DOMAIN)
    }

    def "ownership marker embedded in description"() {
        when:
        def d = ManagedEventIds.buildDescription('b1', 'plan-9', 'extra')

        then:
        ManagedEventIds.hasOwnershipMarker(d)
        d.contains('block-id:b1')
        d.contains('plan-id:plan-9')
    }

    def "isOwned requires managed calendar name match when provided"() {
        given:
        def uid = ManagedEventIds.uidForBlock('b')
        def ev = CalendarEvent.builder()
            .id(uid).uid(uid).title('t')
            .description(ManagedEventIds.buildDescription('b', 'p'))
            .calendarName('Todoist Planned')
            .start(java.time.Instant.parse('2026-08-10T14:00:00Z'))
            .end(java.time.Instant.parse('2026-08-10T15:00:00Z'))
            .build()

        expect:
        ManagedEventIds.isOwned(ev, 'Todoist Planned')
        !ManagedEventIds.isOwned(ev, 'Work')
    }

    def "isOwned requires both planner UID and ownership marker"() {
        given:
        def uid = ManagedEventIds.uidForBlock('b')
        def start = java.time.Instant.parse('2026-08-10T14:00:00Z')
        def end = java.time.Instant.parse('2026-08-10T15:00:00Z')
        def noMarker = CalendarEvent.builder()
            .id(uid).uid(uid).title('t').description('plain')
            .calendarName('Todoist Planned').start(start).end(end).build()
        def noUid = CalendarEvent.builder()
            .id('x').uid('external@x.com').title('t')
            .description(ManagedEventIds.buildDescription('b', 'p'))
            .calendarName('Todoist Planned').start(start).end(end).build()
        def both = CalendarEvent.builder()
            .id(uid).uid(uid).title('t')
            .description(ManagedEventIds.buildDescription('b', 'p'))
            .calendarName('Todoist Planned').start(start).end(end).build()

        expect:
        !ManagedEventIds.isOwned(noMarker, 'Todoist Planned')
        !ManagedEventIds.isOwned(noUid, 'Todoist Planned')
        ManagedEventIds.isOwned(both, 'Todoist Planned')
    }

    def "isOwned never skips calendar verification for blank or null managed calendar name"() {
        given:
        def uid = ManagedEventIds.uidForBlock('b')
        def start = java.time.Instant.parse('2026-08-10T14:00:00Z')
        def end = java.time.Instant.parse('2026-08-10T15:00:00Z')
        def ev = CalendarEvent.builder()
            .id(uid).uid(uid).title('t')
            .description(ManagedEventIds.buildDescription('b', 'p'))
            .calendarName('Todoist Planned').start(start).end(end).build()

        expect:
        !ManagedEventIds.isOwned(ev, null)
        !ManagedEventIds.isOwned(ev, '')
        !ManagedEventIds.isOwned(ev, '   ')
    }
}
