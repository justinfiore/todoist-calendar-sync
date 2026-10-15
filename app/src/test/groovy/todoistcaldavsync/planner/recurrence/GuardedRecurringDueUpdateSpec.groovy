package todoistcaldavsync.planner.recurrence

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.PatternLayout
import org.apache.log4j.WriterAppender
import spock.lang.Specification
import todoistcaldavsync.planner.adapters.TodoistCommandResult
import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.adapters.TodoistLifecycleGateway
import todoistcaldavsync.planner.config.PlannerConfig

class GuardedRecurringDueUpdateSpec extends Specification {
    private final PlannerConfig config = PlannerConfig.fromMap(planner: [timezone: 'America/New_York',
        availability: [working_windows: [weekday: ['09:00-17:00']]]])

    def "incomplete prewrite tuple is skipped without any mutation"() {
        given:
        Map raw = recurringRaw()
        raw.due.remove('timezone')
        int writes = 0
        TodoistLifecycleGateway gateway = gateway(
            { String ignored -> copy(raw) },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                writes++
                throw new AssertionError('must not write an incomplete tuple')
            })

        when:
        GuardedRecurringDueUpdate.update(gateway, config, 'task-1', '2026-10-06T14:00:00Z')

        then:
        def error = thrown(GuardedRecurringDueUpdateException)
        error.outcome == GuardedRecurringDueOutcome.UNSUCCESSFUL
        error.message.contains('incomplete prewrite tuple')
        error.message.contains('timezone=<missing>')
        writes == 0
    }

    def "first attempt success returns verified task without retry and preserves all guarded state"() {
        given:
        Map raw = recurringRaw()
        List<Map> calls = []
        TodoistLifecycleGateway gateway = gateway(
            { String ignored -> copy(raw) },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                calls << [originalDate: due.date, tuple: new LinkedHashMap(due.syncTuple(replacement)), id: commandId]
                raw.due = new LinkedHashMap(due.syncTuple(replacement))
                new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
            })

        when:
        def verified = GuardedRecurringDueUpdate.update(gateway, config, 'task-1',
            '2026-10-06T14:00:00Z', '11111111-1111-1111-1111-111111111111')

        then:
        verified.todoistDue.date == '2026-10-06T14:00:00Z'
        verified.todoistDue.string == 'every weekday'
        verified.todoistDue.lang == 'en'
        verified.todoistDue.timezone == 'America/New_York'
        verified.deadlineDate.toString() == '2026-10-10'
        verified.labels == ['smartplanner-seen', 'work']
        verified.description == 'Human description'
        verified.completedCount == 4
        calls.size() == 1
        calls[0].originalDate == '2026-10-05T09:00:00'
        calls[0].tuple == [date: '2026-10-06T14:00:00Z', string: 'every weekday',
            is_recurring: true, lang: 'en', timezone: 'America/New_York']
    }

    def "failed initial verification is repaired once with original tuple and a new command id"() {
        given:
        Map raw = recurringRaw()
        List<Map> calls = []
        TodoistLifecycleGateway gateway = gateway(
            { String ignored -> copy(raw) },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                calls << [date: due.date, string: due.string, timezone: due.timezone, id: commandId]
                raw.due = new LinkedHashMap(due.syncTuple(replacement))
                if (calls.size() == 1) raw.due.string = 'provider-damaged-expression'
                new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
            })

        when:
        def verified = GuardedRecurringDueUpdate.update(gateway, config, 'task-1',
            '2026-10-06T14:00:00Z', '22222222-2222-2222-2222-222222222222')

        then:
        verified.todoistDue.string == 'every weekday'
        calls.size() == 2
        calls*.date == ['2026-10-05T09:00:00', '2026-10-05T09:00:00']
        calls*.string == ['every weekday', 'every weekday']
        calls*.timezone == ['America/New_York', 'America/New_York']
        calls[0].id == '22222222-2222-2222-2222-222222222222'
        calls[1].id != calls[0].id
        UUID.fromString(calls[1].id as String)
    }

    def "initial rejected command fails without unsafe verification retry or fallback"() {
        given:
        Map raw = recurringRaw()
        int writes = 0
        int reads = 0
        TodoistLifecycleGateway gateway = gateway(
            { String ignored -> reads++; copy(raw) },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                writes++
                new TodoistCommandResult(TodoistCommandState.REJECTED, commandId, 'INVALID_ARGUMENT')
            })

        when:
        GuardedRecurringDueUpdate.update(gateway, config, 'task-1', '2026-10-06T14:00:00Z')

        then:
        def error = thrown(GuardedRecurringDueUpdateException)
        error.outcome == GuardedRecurringDueOutcome.UNSUCCESSFUL
        error.message.contains('initial recurring due command rejected')
        writes == 1
        reads == 1
    }

    def "rejected repair logs presence-sensitive original intended and latest due diagnostics"() {
        given:
        Map raw = recurringRaw()
        int writes = 0
        StringWriter logs = new StringWriter()
        WriterAppender appender = attachLogs(logs)
        TodoistLifecycleGateway gateway = gateway(
            { String ignored -> copy(raw) },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                writes++
                if (writes == 1) {
                    raw.due = [date: replacement, string: null, is_recurring: true, lang: 'fr']
                    return new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
                }
                new TodoistCommandResult(TodoistCommandState.REJECTED, commandId, 'REPAIR_REJECTED')
            })

        when:
        GuardedRecurringDueUpdate.update(gateway, config, 'task-1', '2026-10-06T14:00:00Z')

        then:
        def error = thrown(GuardedRecurringDueUpdateException)
        error.outcome == GuardedRecurringDueOutcome.UNSUCCESSFUL
        writes == 2
        logs.toString().contains("task=task-1 title='Daily review'")
        logs.toString().contains("originalDue={date='2026-10-05T09:00:00'")
        logs.toString().contains("intendedDue={date='2026-10-06T14:00:00Z'")
        logs.toString().contains('latestRereadDue=')
        logs.toString().contains('string: expected=\'every weekday\', actual=<null> (unexpected difference)')
        logs.toString().contains('timezone: expected=\'America/New_York\', actual=<missing> (unexpected difference)')
        logs.toString().contains('date: expected=')
        logs.toString().contains('(intended date change)')

        cleanup:
        detachLogs(appender)
    }

    def "unavailable final verification records ambiguity last observation and enforces one retry"() {
        given:
        Map original = recurringRaw()
        Map damaged = recurringRaw()
        damaged.due.string = 'changed after first write'
        int reads = 0
        int writes = 0
        StringWriter logs = new StringWriter()
        WriterAppender appender = attachLogs(logs)
        TodoistLifecycleGateway gateway = gateway(
            { String ignored ->
                reads++
                if (reads == 1) return copy(original)
                if (reads == 2) return copy(damaged)
                null
            },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                writes++
                new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
            })

        when:
        GuardedRecurringDueUpdate.update(gateway, config, 'task-1', '2026-10-06T14:00:00Z')

        then:
        def error = thrown(GuardedRecurringDueUpdateException)
        error.outcome == GuardedRecurringDueOutcome.AMBIGUOUS
        writes == 2
        reads == 3
        logs.toString().contains('latest reread unavailable')
        logs.toString().contains('last available observation=')
        logs.toString().contains("actual='changed after first write' (unexpected difference)")

        cleanup:
        detachLogs(appender)
    }

    def "failed repair is logged and never followed by a second repair"() {
        given:
        Map raw = recurringRaw()
        int writes = 0
        StringWriter logs = new StringWriter()
        WriterAppender appender = attachLogs(logs)
        TodoistLifecycleGateway gateway = gateway(
            { String ignored -> copy(raw) },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                writes++
                if (writes == 1) {
                    raw.due.date = '2026-10-07T09:00:00'
                    return new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
                }
                throw new IOException('repair transport failed')
            })

        when:
        GuardedRecurringDueUpdate.update(gateway, config, 'task-1', '2026-10-06T14:00:00Z')

        then:
        def error = thrown(GuardedRecurringDueUpdateException)
        error.outcome == GuardedRecurringDueOutcome.UNSUCCESSFUL
        writes == 2
        logs.toString().contains('repair command failed')
        logs.toString().contains('repair transport failed')
        logs.toString().contains("actual='2026-10-07T09:00:00' (intended date change)")

        cleanup:
        detachLogs(appender)
    }

    def "unavailable initial verification repairs the intended date once then verifies"() {
        given:
        Map raw = recurringRaw()
        int reads = 0
        int writes = 0
        TodoistLifecycleGateway gateway = gateway(
            { String ignored -> ++reads == 2 ? null : copy(raw) },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                writes++
                raw.due = new LinkedHashMap(due.syncTuple(replacement))
                new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
            })

        when:
        def verified = GuardedRecurringDueUpdate.update(gateway, config, 'task-1', '2026-10-06T14:00:00Z')

        then:
        verified.todoistDue.date == '2026-10-06T14:00:00Z'
        writes == 2
        reads == 3
    }

    def "non-due guarded failure stops dependent work without an unauthorized due repair"() {
        given:
        Map raw = recurringRaw()
        int writes = 0
        TodoistLifecycleGateway gateway = gateway(
            { String ignored -> copy(raw) },
            { String ignored, TodoistDue due, String replacement, String commandId ->
                writes++
                raw.due = new LinkedHashMap(due.syncTuple(replacement))
                raw.labels = ['unexpected-label']
                new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
            })

        when:
        GuardedRecurringDueUpdate.update(gateway, config, 'task-1', '2026-10-06T14:00:00Z')

        then:
        def error = thrown(GuardedRecurringDueUpdateException)
        error.message.contains('non-due guarded postconditions failed')
        writes == 1
    }

    private static TodoistLifecycleGateway gateway(Closure fetch, Closure update) {
        [
            fetchTask: fetch,
            updateRecurringDue: update,
            fetchTasks: { -> throw new AssertionError('collection read not expected') },
            updateLifecycleFields: { mutation -> throw new AssertionError('marker mutation not expected') },
            updateTaskDue: { String ignored, String value -> throw new AssertionError('datetime POST used') },
            updateTaskDeadline: { String ignored, String value -> throw new AssertionError('deadline write used') },
            syncItems: { String ignored -> throw new AssertionError('sync poll not expected') }
        ] as TodoistLifecycleGateway
    }

    private static Map recurringRaw() {
        [id: 'task-1', content: 'Daily review', labels: ['smartplanner-seen', 'work'], priority: 2,
            due: [date: '2026-10-05T09:00:00', string: 'every weekday', is_recurring: true,
                lang: 'en', timezone: 'America/New_York'], deadline: [date: '2026-10-10'],
            description: 'Human description', completed_count: 4,
            created_at: '2026-10-01T12:00:00Z', updated_at: '2026-10-02T12:00:00Z']
    }

    private static Map copy(Map value) {
        new JsonSlurper().parseText(JsonOutput.toJson(value)) as Map
    }

    private static WriterAppender attachLogs(StringWriter writer) {
        Logger logger = Logger.getLogger(GuardedRecurringDueUpdate)
        WriterAppender appender = new WriterAppender(new PatternLayout('%m%n'), writer)
        logger.addAppender(appender)
        logger.level = Level.ERROR
        appender
    }

    private static void detachLogs(WriterAppender appender) {
        Logger.getLogger(GuardedRecurringDueUpdate).removeAppender(appender)
        appender?.close()
    }
}
