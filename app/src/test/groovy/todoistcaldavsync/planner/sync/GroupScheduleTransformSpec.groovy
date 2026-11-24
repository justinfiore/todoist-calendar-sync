package todoistcaldavsync.planner.sync

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir
import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.state.ApplicationStateStore

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class GroupScheduleTransformSpec extends Specification {
    @TempDir Path directory
    final Instant originalStart = Instant.parse('2027-03-13T08:30:00Z')
    final Instant movedStart = Instant.parse('2027-03-14T07:30:00Z')
    final PlannerConfig config = PlannerConfig.fromMap(planner: [timezone: 'America/New_York',
        routing: [mode: 'legacy_rules', labelsToInclude: ['work'],
            rules: [[rule: 'work', calendarName: 'Work']]],
        availability: [working_windows: [weekday: ['09:00-17:00']]]])

    private ProjectionMetadata baseline(List<Integer> weights) {
        Instant cursor = originalStart
        List<Map> members = weights.withIndex().collect { int minutes, int index ->
            String id = "t${index}"
            Map row = [taskId: id, occurrenceKey: id + ':0', start: cursor.toString(),
                end: cursor.plusSeconds(minutes * 60L).toString(), due: [date: cursor.toString()],
                effectiveMinutes: minutes, nativeMinutes: minutes, taskUpdated: '2027-03-13T06:00:00Z']
            cursor = cursor.plusSeconds(minutes * 60L)
            row
        }
        new ProjectionMetadata([schemaVersion: 1, uid: ManagedEventIds.uidForOccurrences(members*.occurrenceKey),
            destination: 'Work', revision: 1, routingFingerprint: config.routing.fingerprint,
            historical: false, allDay: false, members: members])
    }

    def 'constrained proportional allocations use exact remainders and persisted order'() {
        when:
        List<Map> rows = GroupScheduleTransform.layout(baseline(weights), movedStart, movedStart.plusSeconds(total * 60L))

        then:
        rows*.minutes == expected
        rows[0].start == '2027-03-14T07:30:00Z'
        rows[-1].end == movedStart.plusSeconds(total * 60L).toString()
        rows.collate(2, 1, false).every { it[0].end == it[1].start }

        where:
        weights       | total | expected
        [30, 60]      | 60    | [20, 40]
        [10, 10]      | 21    | [11, 10]
        [1, 1, 98]    | 3     | [1, 1, 1]
        [1, 2, 97]    | 5     | [1, 1, 3]
        [19, 41, 7]   | 67    | [19, 41, 7]
    }

    def 'invalid group spans fail before mutation is possible'() {
        when:
        GroupScheduleTransform.layout(baseline([30, 60, 15]), movedStart, movedStart.plusSeconds(seconds).plusNanos(nanos))

        then:
        thrown(IllegalArgumentException)

        where:
        seconds | nanos
        120L    | 0L
        181L    | 0L
        180L    | 1L
        0L      | 0L
    }

    def 'group drag or resize preserves recurrence and protected fields across DST and resumes an ambiguous later step once'() {
        given:
        Map<String, Map> raw = [:]
        Instant cursor = originalStart
        [30, 60].eachWithIndex { int minutes, int index ->
            String id = "t${index}"
            raw[id] = [id: id, content: 'Task ' + index, project_id: 'p', labels: ['work'], priority: 3,
                deadline: [date: '2027-03-20'], description: 'Human task note', completed_count: 0,
                updated_at: '2027-03-13T06:00:00Z', duration: [amount: minutes, unit: 'minute'],
                due: [date: index == 0 ? cursor.toString() : cursor.atZone(config.timezone).toLocalDateTime().toString(),
                    timezone: index == 0 ? 'America/New_York' : null, is_recurring: true, lang: 'en',
                    string: index == 0 ? 'every weekday' : 'every! day at 4am']]
            cursor = cursor.plusSeconds(minutes * 60L)
        }
        List<Task> originals = raw.values().collect { normalize(it) }
        List<Map> members = originals.collect { Task task ->
            [taskId: task.id, occurrenceKey: task.occurrenceIdentity().occurrenceKey(), start: task.dueTime.toString(),
                end: task.dueTime.plus(task.effectiveDuration).toString(), due: task.todoistDue.snapshotMap(),
                nativeMinutes: task.nativeDuration.toMinutes(), effectiveMinutes: task.effectiveDuration.toMinutes(),
                taskUpdated: task.updatedAt.toString()]
        }
        String uid = ManagedEventIds.uidForOccurrences(members*.occurrenceKey)
        def metadata = new ProjectionMetadata([schemaVersion: 1, uid: uid, destination: 'Work', revision: 1,
            routingFingerprint: config.routing.fingerprint, historical: false, allDay: false, members: members])
        CalendarEvent current = CalendarEvent.builder().id('group').uid(uid).title('Focus').calendarName('Work')
            .start(movedStart).end(movedStart.plusSeconds(total * 60L)).etag('"calendar-edit"')
            .lastModified(Instant.parse('2027-03-13T07:00:00Z'))
            .description(metadata.description('Human calendar note', 'group', 'plan')).build()
        CalendarEvent edited = current
        int clock = 0
        List<String> writes = []
        boolean interrupted = false
        TodoistScheduleGateway todoist = [
            fetchTask: { String id -> new JsonSlurper().parseText(JsonOutput.toJson(raw[id])) },
            updateRecurringDue: { String id, due, String replacement, String command ->
                writes << id + ':due'
                raw[id].due = new LinkedHashMap(due.syncTuple(replacement))
                raw[id].updated_at = Instant.parse('2027-03-14T06:00:00Z').plusSeconds(++clock).toString()
                if (id == 't1' && interrupt && !interrupted && !resize) {
                    interrupted = true
                    throw new IOException('simulated lost command response')
                }
                new TodoistCommandResult(TodoistCommandState.COMMITTED, command)
            },
            updateTaskDuration: { String id, int minutes, String command ->
                writes << id + ':duration'
                raw[id].duration = [amount: minutes, unit: 'minute']
                raw[id].updated_at = Instant.parse('2027-03-14T06:00:00Z').plusSeconds(++clock).toString()
                if (id == 't1' && interrupt && !interrupted) {
                    interrupted = true
                    throw new IOException('simulated lost duration response')
                }
                new TodoistCommandResult(TodoistCommandState.COMMITTED, command)
            }
        ] as TodoistScheduleGateway
        int calendarWrites = 0
        ProjectionCalendarGateway calendar = [
            readResource: { String destination, String id -> current },
            findEventByUid: { String identity -> current },
            replaceProjection: { CalendarEvent expected, CalendarEvent target ->
                ProjectionMetadata.requireUnchanged(expected, current)
                current = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(target) +
                    [etag: '"verified"', lastModified: '2027-03-14T06:30:00Z'])
                calendarWrites++
                current
            }
        ] as ProjectionCalendarGateway
        def store = new ApplicationStateStore(directory)
        def service = new GroupScheduleTransform(store, calendar, todoist, config)

        when:
        Map result = null
        Throwable failure = null
        try { result = service.apply(edited, originals) } catch (Throwable e) { failure = e }

        then:
        interrupt ? failure != null : failure == null
        !interrupt || (store.loadSyncOperations().values().first().status == 'pending' && calendarWrites == 0)

        when: 'a new instance reconciles any committed but unacknowledged task step'
        String id = store.loadSyncOperations().keySet().first()
        result = new GroupScheduleTransform(new ApplicationStateStore(directory), calendar, todoist, config).resume(id)

        then:
        result.status == 'verified'
        calendarWrites == 1
        writes.count { it == 't0:due' } == 1 && writes.count { it == 't1:due' } == 1
        writes.count { it.endsWith(':duration') } == (resize ? 2 : 0)
        List<Task> observed = ['t0', 't1'].collect { normalize(raw[it]) }
        observed*.dueTime == [movedStart, movedStart.plusSeconds((resize ? 20L : 30L) * 60L)]
        observed*.nativeDuration == expectedMinutes.collect { Duration.ofMinutes(it) }
        observed*.todoistDue*.timezone == ['America/New_York', null]
        observed*.todoistDue*.string == ['every weekday', 'every! day at 4am']
        observed.every { it.completedCount == 0 && it.deadlineDate.toString() == '2027-03-20' &&
            it.priority == 3 && it.description == 'Human task note' && it.labels == ['work'] }
        ProjectionMetadata.read(current).value.members*.effectiveMinutes == expectedMinutes
        ProjectionMetadata.read(current).value.revision == 2
        ProjectionMetadata.humanDescription(current) == 'Human calendar note'

        where:
        total | resize | interrupt | expectedMinutes
        90    | false  | false     | [30L, 60L]
        60    | true   | false     | [20L, 40L]
        90    | false  | true      | [30L, 60L]
        60    | true   | true      | [20L, 40L]
    }

    private Task normalize(Map raw) { Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone) }
}
