package todoistcaldavsync.planner.sync

import todoistcaldavsync.planner.adapters.ProjectionCalendarGateway
import todoistcaldavsync.planner.adapters.TodoistScheduleGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.domain.ProjectionMetadata
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.state.PlanStore

import java.time.Duration
import java.time.Instant

/** Manual calendar transforms use immutable verified weights, never partly changed task durations. */
final class GroupScheduleTransform {
    private final ApplicationStateStore store
    private final ProjectionCalendarGateway calendar
    private final PlannerConfig config
    private final GuardedScheduleUpdate writer

    GroupScheduleTransform(ApplicationStateStore store, ProjectionCalendarGateway calendar,
                           TodoistScheduleGateway todoist, PlannerConfig config) {
        this.store = store
        this.calendar = calendar
        this.config = config
        this.writer = new GuardedScheduleUpdate(todoist, config)
    }

    Map apply(CalendarEvent edited, List<Task> tasks) {
        store.withMutationLock {
            ProjectionMetadata baseline = ProjectionMetadata.read(edited)
            if (baseline.value.historical || baseline.value.allDay || edited.allDay ||
                baseline.value.routingFingerprint != config.routing?.fingerprint) {
                throw new IllegalStateException('Historical, all-day, or changed-routing projection cannot drive a transform')
            }
            List<Map> layout = layout(baseline, edited.start, edited.end)
            boolean resize = Duration.between(edited.start, edited.end).toMinutes() !=
                baseline.value.members.sum { it.effectiveMinutes as long }
            Map<String, Task> byId = tasks.collectEntries { [(it.id): it] }
            if (byId.size() != tasks.size() || byId.keySet() != (baseline.value.members*.taskId as Set)) {
                throw new IllegalStateException('Complete unique member task inventory is required')
            }
            baseline.value.members.each { Map member ->
                Task task = byId[member.taskId]
                if (task.occurrenceIdentity().occurrenceKey() != member.occurrenceKey ||
                    task.todoistDue?.snapshotMap() != member.due || task.allDayDue ||
                    task.nativeDuration?.toMinutes() != member.nativeMinutes ||
                    task.effectiveDuration != Duration.ofMinutes(member.effectiveMinutes as long) ||
                    config.projectionDestination(task) != edited.calendarName ||
                    task.updatedAt == null || member.taskUpdated == null || edited.lastModified == null ||
                    !edited.lastModified.isAfter(task.updatedAt)) {
                    throw new IllegalStateException('Member schedule/occurrence/route changed or timestamps are unsafe')
                }
                writer.preflight(task)
            }
            ProjectionMetadata.requireUnchanged(edited, calendar.readResource(edited.calendarName, edited.id))
            CalendarEvent global = calendar.findEventByUid(edited.uid)
            ProjectionMetadata.requireUnchanged(edited, global)
            Map resources = [:]
            layout.each { Map member ->
                resources['task:' + member.taskId + ':due'] = [action: 'due', taskId: member.taskId,
                    start: member.start, commandId: UUID.randomUUID().toString()]
                resources['task:' + member.taskId + ':duration'] = [action: 'duration', taskId: member.taskId,
                    minutes: resize ? member.minutes : null, commandId: UUID.randomUUID().toString()]
            }
            resources[ProjectionReplacementService.key(edited)] = [action: 'calendar', revision: baseline.value.revision + 1]
            String id = UUID.randomUUID().toString()
            store.beginSyncOperation(id, [type: 'schedule-transform', taskIds: tasks*.id,
                resources: resources, event: ProjectionMetadata.eventMap(edited),
                tasks: tasks.collectEntries { [(it.id): PlanStore.taskToMap(it)] }, layout: layout])
            reconcile(id)
        }
    }

    Map resume(String operationId) { store.withMutationLock { reconcile(operationId) } }

    private Map reconcile(String id) {
        Map op = store.loadSyncOperations()[id]
        if (op == null || op.intent.type != 'schedule-transform') throw new IllegalArgumentException('transform journal not found')
        if (op.status == 'verified') {
            store.commitSyncProjections(id)
            return op
        }
        if (op.outcomes.values().any { it.state == 'conflict' }) throw new IllegalStateException('Transform conflict requires review')
        CalendarEvent edited = ProjectionMetadata.eventFromMap(op.intent.event as Map)
        String calendarKey = ProjectionReplacementService.key(edited)
        if (op.outcomes[calendarKey].state == 'unknown') {
            Map targetMap = op.outcomes[calendarKey].evidence.target as Map
            CalendarEvent target = ProjectionMetadata.eventFromMap(targetMap)
            CalendarEvent live = calendar.readResource(target.calendarName, target.id)
            if (ProjectionReplacementService.matches(target, live)) {
                verifyFinalTasks(op, id)
                store.checkpointSyncResource(id, calendarKey, 'verified', [event: ProjectionMetadata.eventMap(live)])
                store.commitSyncProjections(id)
                return store.loadSyncOperations()[id]
            }
        }
        ProjectionMetadata.requireUnchanged(edited, calendar.readResource(edited.calendarName, edited.id))
        if (ProjectionMetadata.read(edited).value.routingFingerprint != config.routing?.fingerprint) {
            throw new IllegalStateException('Routing changed during transform; reconcile before continuing')
        }
        // A later member may have changed during downtime: discover it before advancing any earlier one.
        op.intent.taskIds.each { String taskId ->
            String dueKey = 'task:' + taskId + ':due'
            String durationKey = 'task:' + taskId + ':duration'
            Task expected = PlanStore.taskFromMap(op.intent.tasks[taskId] as Map)
            String next = dueKey
            if (op.outcomes[dueKey].state == 'verified') {
                expected = PlanStore.taskFromMap(op.outcomes[dueKey].evidence.task as Map)
                next = durationKey
            }
            if (op.outcomes[durationKey].state == 'verified') {
                expected = PlanStore.taskFromMap(op.outcomes[durationKey].evidence.task as Map)
                writer.preflight(expected)
            } else if (op.outcomes[next].state != 'unknown' ||
                !intendedStep(expected, writer.readActive(taskId), op.intent.resources[next] as Map)) {
                writer.preflight(expected)
            }
        }
        List<Task> verified = []
        op.intent.layout.each { Map member ->
            String dueKey = 'task:' + member.taskId + ':due'
            String durationKey = 'task:' + member.taskId + ':duration'
            Map progress = store.loadSyncOperations()[id].outcomes
            if (progress[durationKey].state == 'verified') {
                Task done = PlanStore.taskFromMap(progress[durationKey].evidence.task as Map)
                writer.preflight(done)
                verified << done
                return
            }
            Task expected = PlanStore.taskFromMap(op.intent.tasks[member.taskId] as Map)
            if (progress[dueKey].state == 'verified') {
                expected = PlanStore.taskFromMap(progress[dueKey].evidence.task as Map)
            } else {
                expected = step(id, dueKey, expected, edited, op.intent.resources[dueKey] as Map)
            }
            verified << step(id, durationKey, expected, edited, op.intent.resources[durationKey] as Map)
        }
        ProjectionMetadata old = ProjectionMetadata.read(edited)
        List<Map> members = op.intent.layout.collect { Map target ->
            Task task = verified.find { it.id == target.taskId }
            [taskId: task.id, occurrenceKey: task.occurrenceIdentity().occurrenceKey(), start: target.start,
                end: target.end, due: task.todoistDue.snapshotMap(), nativeMinutes: task.nativeDuration?.toMinutes(),
                effectiveMinutes: target.minutes, taskUpdated: task.updatedAt?.toString()]
        }
        def metadata = new ProjectionMetadata(old.value + [revision: old.value.revision + 1, members: members])
        CalendarEvent target = CalendarEvent.builder().id(edited.id).uid(edited.uid).title(edited.title)
            .calendarName(edited.calendarName).start(edited.start).end(edited.end)
            .description(metadata.description(ProjectionMetadata.humanDescription(edited),
                ManagedEventIds.extractBlockId(edited.description), id)).build()
        verified.each { writer.preflight(it) }
        store.checkpointSyncResource(id, calendarKey, 'unknown', [target: ProjectionMetadata.eventMap(target)])
        CalendarEvent live = calendar.replaceProjection(edited, target)
        verifyFinalTasks(store.loadSyncOperations()[id], id)
        store.checkpointSyncResource(id, calendarKey, 'verified', [event: ProjectionMetadata.eventMap(live)])
        store.commitSyncProjections(id)
        store.loadSyncOperations()[id]
    }

    private Task step(String id, String key, Task expected, CalendarEvent event, Map intent) {
        ProjectionMetadata.requireUnchanged(event, calendar.readResource(event.calendarName, event.id))
        Map outcome = store.loadSyncOperations()[id].outcomes[key]
        Task current = writer.readActive(expected.id)
        if (outcome.state == 'unknown' && intendedStep(expected, current, intent)) {
            store.checkpointSyncResource(id, key, 'verified', [task: PlanStore.taskToMap(current)])
            return current
        }
        writer.preflight(expected)
        store.checkpointSyncResource(id, key, 'unknown', [attempted: true])
        Task verified = intent.action == 'due' ? writer.due(expected, Instant.parse(intent.start.toString()), intent.commandId.toString()) :
            intent.minutes == null ? writer.preflight(expected) : writer.duration(expected, intent.minutes as long, intent.commandId.toString())
        store.checkpointSyncResource(id, key, 'verified', [task: PlanStore.taskToMap(verified)])
        verified
    }

    static boolean intendedStep(Task expected, Task actual, Map intent) {
        Map before = new LinkedHashMap(PlanStore.taskToMap(expected))
        Map after = new LinkedHashMap(PlanStore.taskToMap(actual))
        before.remove('updatedAt'); after.remove('updatedAt')
        before.remove('projectName'); after.remove('projectName')
        if (intent.action == 'due') {
            if (actual.dueTime != Instant.parse(intent.start.toString()) || actual.allDayDue ||
                (actual.todoistDue?.recurrenceFingerprintInput() ?: '') != (expected.todoistDue?.recurrenceFingerprintInput() ?: '')) return false
            ['dueTime', 'todoistDue'].each { before.remove(it); after.remove(it) }
        } else if (intent.minutes != null) {
            if (actual.nativeDuration != Duration.ofMinutes(intent.minutes as long) || actual.durationSource != 'native') return false
            ['nativeDuration', 'effectiveMinutes', 'durationSource'].each { before.remove(it); after.remove(it) }
        }
        before == after
    }

    private void verifyFinalTasks(Map op, String id) {
        op.intent.taskIds.each { String taskId ->
            Map evidence = store.loadSyncOperations()[id].outcomes['task:' + taskId + ':duration'].evidence as Map
            writer.preflight(PlanStore.taskFromMap(evidence.task as Map))
        }
    }

    static List<Map> layout(ProjectionMetadata baseline, Instant start, Instant end) {
        if (baseline.value.historical || baseline.value.allDay || start == null || end == null) {
            throw new IllegalArgumentException('Current timed projection intervals required')
        }
        Duration span = Duration.between(start, end)
        if (span.negative || span.zero || span.nano != 0 || span.seconds % 60 != 0) {
            throw new IllegalArgumentException('Transform span must be positive whole minutes')
        }
        List<Long> weights = baseline.value.members.collect { it.effectiveMinutes as long }
        long total = span.toMinutes()
        if (total < weights.size()) throw new IllegalArgumentException('Transform is shorter than one minute per member')
        List<BigInteger> allocations = weights.collect { 0G }
        List<Integer> remaining = (0..<weights.size()).toList()
        BigInteger available = BigInteger.valueOf(total)
        while (remaining) {
            BigInteger sum = remaining.sum { BigInteger.valueOf(weights[it]) } as BigInteger
            List<Integer> constrained = remaining.findAll { available * weights[it] < sum }
            if (constrained) {
                constrained.each { allocations[it] = 1G; available -= 1G }
                remaining.removeAll(constrained)
                continue
            }
            Map<Integer, BigInteger> remainders = [:]
            remaining.each { int index ->
                BigInteger[] parts = (available * weights[index]).divideAndRemainder(sum)
                allocations[index] = parts[0]
                remainders[index] = parts[1]
            }
            int extras = (available - remaining.sum { allocations[it] }) as int
            List<Integer> order = remaining.toSorted { a, b -> (remainders[b] <=> remainders[a]) ?: (a <=> b) }
            order.take(extras).each { allocations[it] += 1G }
            break
        }
        Instant cursor = start
        baseline.value.members.withIndex().collect { Map member, int index ->
            long minutes = allocations[index].longValueExact()
            if (minutes > Integer.MAX_VALUE) throw new IllegalArgumentException('Native-duration allocation exceeds provider integer range')
            Instant next = cursor.plusSeconds(Math.multiplyExact(minutes, 60L))
            Map target = [taskId: member.taskId, occurrenceKey: member.occurrenceKey,
                start: cursor.toString(), end: next.toString(), minutes: minutes]
            cursor = next
            target
        }
    }
}
