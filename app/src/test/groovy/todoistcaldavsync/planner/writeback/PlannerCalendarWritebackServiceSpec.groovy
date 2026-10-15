package todoistcaldavsync.planner.writeback

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.recurrence.GuardedRecurringDueUpdateException
import todoistcaldavsync.planner.state.ApplicationStateStore

import java.nio.file.Files
import java.time.Instant

class PlannerCalendarWritebackServiceSpec extends Specification {
    private static final String CALENDAR = 'Planned'

    private static final class ExpiringGateway implements CalendarReadGateway, CalendarChangeGateway {
        final List<Map> states = []
        int calls

        @Override List<CalendarEvent> fetchEvents(Instant start, Instant end) { [] }
        @Override CalendarEvent findEventByUid(String uid) { null }
        @Override Map readChanges(String calendar, Map state, Set<String> candidates,
                                  Instant start, Instant end) {
            calls++
            states << new LinkedHashMap(state)
            if (calls == 1) throw new CalendarCursorExpiredException('gone')
            [events: [], deleted: [], state: new LinkedHashMap(state) + [cursor: 'replacement']]
        }
    }

    def 'cursor expiry retries a full read, retains echo and checkpoints only the classified retry'() {
        given:
        def directory = Files.createTempDirectory('planner-writeback-recovery-')
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'UTC',
            output_calendar: CALENDAR, calendarWriteback: [enabled: true],
            availability: [working_windows: [weekday: ['09:00-17:00']]]])
        def todoist = new InMemoryTodoistGateway([[id: 't1', content: 'Task',
            due: [date: '2026-08-17T09:00:00Z'], updated_at: '2026-08-16T10:00:00Z']])
        def applications = new ApplicationStateStore(directory)
        String uid = ManagedEventIds.uidForBlock('b1')
        applications.putMapping(AppliedMapping.builder().taskId('t1').blockId('b1').eventUid(uid)
            .slotStart(Instant.parse('2026-08-17T09:00:00Z'))
            .slotEnd(Instant.parse('2026-08-17T09:30:00Z'))
            .planId('p1').planVersion(1).planHash('h').appliedAt(Instant.parse('2026-08-16T09:00:00Z'))
            .calendarStatus(ApplyItemStatus.APPLIED).todoistStatus(ApplyItemStatus.APPLIED).build())
        def store = new CalendarWritebackStore(directory)
        store.saveCalendar(CALENDAR, [cursor: 'expired', stamps: [(uid): 'stamp-1']])
        CalendarEvent echoEvent = CalendarEvent.builder().id(uid).uid(uid).title('Task')
            .description(ManagedEventIds.buildDescription('b1', 'p1')).calendarName(CALENDAR)
            .start(Instant.parse('2026-08-17T09:00:00Z')).end(Instant.parse('2026-08-17T09:30:00Z'))
            .etag('etag-1').build()
        store.recordWrite(echoEvent, Instant.parse('2026-08-16T11:00:00Z'))
        def gateway = new ExpiringGateway()
        def service = new PlannerCalendarWritebackService(config, gateway, todoist, todoist,
            applications, store, { Instant.parse('2026-08-16T12:00:00Z') })

        when:
        def outcomes = service.pollAndApply(Instant.parse('2026-08-16T00:00:00Z'),
            Instant.parse('2026-08-18T00:00:00Z'))

        then:
        outcomes.empty
        gateway.calls == 2
        gateway.states[0].cursor == 'expired'
        !gateway.states[1].containsKey('cursor')
        gateway.states[1].stamps[uid] == 'stamp-1'
        store.calendar(CALENDAR).cursor == 'replacement'
        store.echo(CALENDAR, uid).etag == 'etag-1'
        todoist.dueUpdates.empty
    }

    def 'recurring planner writeback uses one guarded repair and never falls back to a datetime POST'() {
        given:
        def directory = Files.createTempDirectory('planner-recurring-writeback-')
        def config = PlannerConfig.fromMap(planner: [timezone: 'UTC', output_calendar: CALENDAR,
            calendarWriteback: [enabled: true],
            availability: [working_windows: [weekday: ['09:00-17:00']]]])
        Map raw = [id: 't1', content: 'Recurring task', description: 'Human notes', labels: ['work'],
            deadline: [date: '2026-08-20'], completed_count: 3, updated_at: '2026-08-16T09:00:00Z',
            due: [date: '2026-08-17T09:00:00Z', string: 'every weekday', is_recurring: true,
                  lang: 'en', timezone: 'UTC']]
        TodoistLifecycleGateway todoist = Mock()
        CalendarReadGateway calendar = Mock()
        List<String> commandIds = []
        int reads = 0
        todoist.fetchTask('t1') >> { reads++; new LinkedHashMap(raw) + [due: new LinkedHashMap(raw.due)] }
        todoist.updateRecurringDue('t1', _, '2026-08-17T11:00:00Z', _) >> { id, due, date, command ->
            commandIds << command
            assert due.string == 'every weekday'
            assert due.date == '2026-08-17T09:00:00Z'
            if (commandIds.size() == 2 && rejectRepair) {
                return new TodoistCommandResult(TodoistCommandState.REJECTED, command, 'rejected')
            }
            raw.due = new LinkedHashMap(due.syncTuple(date))
            if (commandIds.size() == 1) raw.due.string = 'damaged'
            new TodoistCommandResult(TodoistCommandState.COMMITTED, command)
        }
        String uid = ManagedEventIds.uidForBlock('recurring')
        CalendarEvent event = CalendarEvent.builder().id(uid).uid(uid).calendarName(CALENDAR).title('Task')
            .description(ManagedEventIds.buildDescription('recurring', 'p1'))
            .start(Instant.parse('2026-08-17T11:00:00Z')).end(Instant.parse('2026-08-17T11:30:00Z'))
            .lastModified(Instant.parse('2026-08-16T10:00:00Z')).build()
        AppliedMapping mapping = AppliedMapping.builder().taskId('t1').eventUid(uid).planId('p1')
            .slotStart(Instant.parse('2026-08-17T09:00:00Z')).slotEnd(Instant.parse('2026-08-17T09:30:00Z'))
            .appliedAt(Instant.parse('2026-08-16T08:00:00Z')).build()
        def service = new PlannerCalendarWritebackService(config, calendar, todoist, todoist,
            new ApplicationStateStore(directory), new CalendarWritebackStore(directory))
        Map result
        GuardedRecurringDueUpdateException failure

        when:
        try { result = service.applyOwnedMove(event, [mapping], raw) }
        catch (GuardedRecurringDueUpdateException e) { failure = e }

        then:
        rejectRepair ? failure != null && result == null : result.adopted == true && failure == null
        commandIds.size() == 2
        commandIds.unique().size() == 2
        reads == 3
        raw.description == 'Human notes'
        raw.labels == ['work']
        raw.deadline == [date: '2026-08-20']
        raw.completed_count == 3
        0 * todoist.updateTaskDue(_, _)
        0 * todoist.updateTaskDeadline(_, _)
        0 * todoist.updateLifecycleFields(_)

        where:
        rejectRepair << [false, true]
    }
}
