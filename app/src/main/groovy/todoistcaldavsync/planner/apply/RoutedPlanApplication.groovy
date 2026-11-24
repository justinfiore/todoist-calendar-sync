package todoistcaldavsync.planner.apply

import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.migration.MigrationState
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.state.PlanStore
import todoistcaldavsync.planner.sync.*

import java.time.Duration
import java.time.Instant

/** Exact approved placements share the sync writer lease, journal, and portable membership. */
final class RoutedPlanApplication {
    private final PlannerConfig config
    private final ApplicationStateStore store
    private final ProjectionCalendarGateway calendar
    private final TodoistReadGateway read
    private final GuardedScheduleUpdate writer

    RoutedPlanApplication(PlannerConfig config, ApplicationStateStore store, ProjectionCalendarGateway calendar,
                          TodoistScheduleGateway todoist, TodoistReadGateway read) {
        this.config = config
        this.store = store
        this.calendar = calendar
        this.read = read
        this.writer = new GuardedScheduleUpdate(todoist, config)
    }

    Map apply(Plan plan, List<ScheduledBlock> selected, Approval approval) {
        store.withMutationLock {
            new MigrationState(store.directory).requireAuthority('planning')
            String hash = PlanHash.compute(plan), id = 'plan-' + hash
            Map op = store.loadSyncOperations()[id]
            if (op == null) {
                List<Task> inventory = read.fetchTasks().collect {
                    Task.fromTodoistMap(it, config.durationResolver, config.manualLabel, config.timezone)
                }
                new RoutedPlanningInputs(config, store, calendar).requireSource(plan, inventory)
                if (!selected) return [status: 'empty', events: []]
                Map resources = [:], tasks = [:]
                Set<String> ids = [] as Set
                List<CalendarEvent> sources = (plan.metrics.routedSource.projections as List).collect {
                    ProjectionMetadata.eventFromMap(it as Map)
                }
                def projections = new RoutedProjections(config, calendar)
                selected.each { ScheduledBlock block ->
                    List<Task> members = block.taskIds.collect { taskId -> inventory.find { it.id == taskId } }
                    if (members.any { it == null || it.allDayDue }) {
                        throw new IllegalStateException('approved block requires active timed members')
                    }
                    Set<String> destinations = members.collect { config.projectionDestination(it) } as Set
                    if (destinations.size() != 1 || destinations.contains(null)) {
                        throw new IllegalStateException('approved block requires eligible timed members in exactly one destination')
                    }
                    block.taskIds.each { String taskId ->
                        Task task = members.find { it.id == taskId }
                        MemberInterval interval = block.intervalFor(taskId)
                        Instant start = interval?.start ?: block.start, end = interval?.end ?: block.end
                        if (!ids.add(taskId) || Duration.between(start, end) != task.effectiveDuration) {
                            throw new IllegalStateException('approved membership/duration is inconsistent')
                        }
                        tasks[taskId] = PlanStore.taskToMap(task)
                        resources['task:' + taskId + ':due'] = [action: 'due', taskId: taskId,
                            start: start.toString(), commandId: UUID.randomUUID().toString()]
                    }
                    // Bound portability and consecutive geometry before any task moves, including undated tasks.
                    String uid = ManagedEventIds.uidForOccurrences(members*.occurrenceIdentity()*.occurrenceKey())
                    CalendarEvent before = sources.find { it.uid == uid && it.calendarName == destinations.first() }
                    // Retained blocks carry the provider title, not a newly constructed planner title.
                    String title = block.metadata.source == 'routed_sync' && block.id == before?.uid ?
                        block.title : calendar.projectionTitle(destinations.first(), block.title)
                    projections.fromMembers(members.collect { Task task ->
                        MemberInterval interval = block.intervalFor(task.id)
                        [taskId: task.id, occurrenceKey: task.occurrenceIdentity().occurrenceKey(),
                            start: interval.start.toString(), end: interval.end.toString(),
                            due: task.todoistDue?.snapshotMap() ?: [date: interval.start.toString()],
                            nativeMinutes: task.nativeDuration?.toMinutes(), effectiveMinutes: task.effectiveDuration.toMinutes(),
                            taskUpdated: task.updatedAt?.toString()]
                    }, destinations.first(), title, 'Approved plan ' + plan.id)
                    String resourceId = before?.id ?: calendar.newProjectionResourceId(destinations.first(), uid)
                    String key = 'calendar:' + destinations.first() + ':' + resourceId
                    resources[key] = [action: before == null ? 'create' : 'update', uid: uid, id: resourceId,
                        destination: destinations.first(), title: title, taskIds: block.taskIds,
                        before: before == null ? null : ProjectionMetadata.eventMap(before),
                        revision: before == null ? 1 : ProjectionMetadata.read(before).value.revision + 1]
                }
                sources.findAll { ProjectionMetadata.read(it).value.members*.taskId.intersect(ids as List) }.each { CalendarEvent source ->
                    if (!(ProjectionMetadata.read(source).value.members*.taskId).every { ids.contains(it) }) {
                        throw new IllegalStateException('partial authorization cannot replace a group containing withheld members')
                    }
                    String key = ProjectionReplacementService.key(source)
                    if (!resources.containsKey(key)) resources[key] = [action: 'retire', event: ProjectionMetadata.eventMap(source)]
                }
                // Reject an unrelated same-UID resource before changing any task.
                resources.values().findAll { it.action in ['create', 'update'] }.each { Map target ->
                    CalendarEvent existing = calendar.findEventByUid(target.uid.toString())
                    if (target.before == null) {
                        if (existing != null) throw new IllegalStateException('unrelated UID collision blocks approved apply')
                    } else ProjectionMetadata.requireUnchanged(ProjectionMetadata.eventFromMap(target.before as Map), existing)
                }
                op = store.beginSyncOperation(id, [type: 'routed-plan', planHash: hash, planId: plan.id,
                    approvalId: approval?.id, routingFingerprint: config.routing.fingerprint,
                    taskIds: ids as List, tasks: tasks, resources: resources])
            }
            if (op.intent.planHash != hash || op.intent.routingFingerprint != config.routing.fingerprint ||
                (selected.collectMany { it.taskIds } as Set) != (op.intent.taskIds as Set) ||
                op.outcomes.values().any { it.state == 'conflict' }) {
                throw new IllegalStateException('approved operation scope/routing changed; review required')
            }
            boolean alreadyApplied = op.status == 'verified'
            preflight(op)
            op.intent.taskIds.each { String taskId ->
                String key = 'task:' + taskId + ':due'
                Map current = store.loadSyncOperations()[id]
                if (current.outcomes[key].state == 'verified') return
                Map step = current.intent.resources[key]
                Task expected = PlanStore.taskFromMap(current.intent.tasks[taskId] as Map)
                Task live = writer.readActive(taskId)
                if (current.outcomes[key].state == 'unknown' && GroupScheduleTransform.intendedStep(expected, live, step)) {
                    store.checkpointSyncResource(id, key, 'verified', [task: PlanStore.taskToMap(live)])
                    return
                }
                preflight(current)
                store.checkpointSyncResource(id, key, 'unknown', [attempted: true])
                Task verified = writer.due(expected, Instant.parse(step.start.toString()), step.commandId.toString(), true)
                store.checkpointSyncResource(id, key, 'verified', [task: PlanStore.taskToMap(verified)])
            }
            op.intent.resources.findAll { key, value -> value.action in ['create', 'update'] }.each { String key, Map targetIntent ->
                Map current = store.loadSyncOperations()[id]
                preflight(current)
                Map outcome = current.outcomes[key]
                if (outcome.state == 'verified') return
                CalendarEvent target
                if (outcome.evidence?.target instanceof Map) {
                    target = ProjectionMetadata.eventFromMap(outcome.evidence.target as Map)
                } else {
                    def projections = new RoutedProjections(config, calendar)
                    List<Map> members = targetIntent.taskIds.collect { String taskId ->
                        projections.member(PlanStore.taskFromMap(current.outcomes['task:' + taskId + ':due'].evidence.task as Map))
                    }
                    CalendarEvent generated = projections.fromMembers(members, targetIntent.destination.toString(),
                        targetIntent.title.toString(), 'Approved plan ' + plan.id, false, targetIntent.revision as long)
                    target = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(generated) + [id: targetIntent.id])
                }
                CalendarEvent live = calendar.readResource(target.calendarName, target.id)
                if (outcome.state == 'unknown' && ProjectionReplacementService.matches(target, live)) {
                    ProjectionMetadata.requireUnchanged(live, calendar.findEventByUid(target.uid))
                } else {
                    store.checkpointSyncResource(id, key, 'unknown', [target: ProjectionMetadata.eventMap(target)])
                    live = targetIntent.action == 'create' ? calendar.createProjection(target) :
                        calendar.replaceProjection(ProjectionMetadata.eventFromMap(targetIntent.before as Map), target)
                    if (!ProjectionReplacementService.matches(target, live)) throw new IllegalStateException('approved calendar readback failed')
                }
                verifyTasks(store.loadSyncOperations()[id])
                store.checkpointSyncResource(id, key, 'verified', [event: ProjectionMetadata.eventMap(live)])
            }
            op.intent.resources.findAll { key, value -> value.action == 'retire' }.each { String key, Map resource ->
                Map current = store.loadSyncOperations()[id]
                preflight(current)
                if (current.outcomes[key].state == 'verified') return
                CalendarEvent before = ProjectionMetadata.eventFromMap(resource.event as Map)
                if (calendar.readResource(before.calendarName, before.id) != null) {
                    store.checkpointSyncResource(id, key, 'unknown', [attempted: true])
                    calendar.retireProjection(before)
                }
                if (calendar.readResource(before.calendarName, before.id) != null) throw new IllegalStateException('approved retirement unverified')
                store.checkpointSyncResource(id, key, 'verified', [absent: true])
            }
            op = store.loadSyncOperations()[id]
            preflight(op)
            store.commitSyncProjections(id)
            [status: alreadyApplied ? 'idempotent' : 'applied', operationId: id,
                events: op.outcomes.values().findAll { it.evidence?.event instanceof Map }.collect {
                    ProjectionMetadata.eventFromMap(it.evidence.event as Map)
                }]
        }
    }

    /** Verify every member and surviving source/target before advancing any other resource. */
    private void preflight(Map op) {
        op.intent.taskIds.each { String taskId ->
            String key = 'task:' + taskId + ':due'
            Task before = PlanStore.taskFromMap(op.intent.tasks[taskId] as Map)
            Map outcome = op.outcomes[key]
            if (outcome.state == 'verified') writer.preflight(PlanStore.taskFromMap(outcome.evidence.task as Map))
            else if (outcome.state != 'unknown' || !GroupScheduleTransform.intendedStep(before, writer.readActive(taskId), op.intent.resources[key] as Map)) {
                writer.preflight(before)
            }
        }
        op.intent.resources.findAll { key, value -> value.action != 'due' }.each { String key, Map resource ->
            Map outcome = op.outcomes[key]
            CalendarEvent expected = resource.action == 'retire' ? ProjectionMetadata.eventFromMap(resource.event as Map) :
                resource.before == null ? null : ProjectionMetadata.eventFromMap(resource.before as Map)
            String destination = expected?.calendarName ?: resource.destination.toString()
            String resourceId = expected?.id ?: resource.id.toString()
            CalendarEvent live = calendar.readResource(destination, resourceId)
            if (outcome.state == 'verified') {
                if (resource.action == 'retire') {
                    if (live != null) throw new IllegalStateException('retired approved resource reappeared')
                } else {
                    CalendarEvent verified = ProjectionMetadata.eventFromMap(outcome.evidence.event as Map)
                    ProjectionMetadata.requireUnchanged(verified, live)
                    ProjectionMetadata.requireUnchanged(verified, calendar.findEventByUid(verified.uid))
                }
            } else if (resource.action == 'retire' && outcome.state == 'unknown' && live == null) {
                return
            } else if (outcome.state == 'unknown' && outcome.evidence?.target instanceof Map &&
                ProjectionReplacementService.matches(ProjectionMetadata.eventFromMap(outcome.evidence.target as Map), live)) {
                ProjectionMetadata.requireUnchanged(live, calendar.findEventByUid(live.uid))
            } else if (expected != null) {
                ProjectionMetadata.requireUnchanged(expected, live)
                ProjectionMetadata.requireUnchanged(expected, calendar.findEventByUid(expected.uid))
            } else if (live != null || calendar.findEventByUid(resource.uid.toString()) != null) {
                throw new IllegalStateException('unrelated resource appeared during approved apply')
            }
        }
    }

    private void verifyTasks(Map op) {
        op.intent.taskIds.each { String taskId ->
            writer.preflight(PlanStore.taskFromMap(op.outcomes['task:' + taskId + ':due'].evidence.task as Map))
        }
    }
}
