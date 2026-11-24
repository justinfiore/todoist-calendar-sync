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

import java.time.Instant

/** Reconcile current schedules, never proposed work times. Callers separately authorize sync. */
final class RoutedScheduleReconciler {
    private final ApplicationStateStore store
    private final ProjectionCalendarGateway calendar
    private final TodoistScheduleGateway todoist
    private final PlannerConfig config
    private final RoutedProjections projections
    private final GuardedScheduleUpdate writer

    RoutedScheduleReconciler(ApplicationStateStore store, ProjectionCalendarGateway calendar,
                             TodoistScheduleGateway todoist, PlannerConfig config) {
        this.store = store
        this.calendar = calendar
        this.todoist = todoist
        this.config = config
        this.projections = new RoutedProjections(config, calendar)
        this.writer = new GuardedScheduleUpdate(todoist, config)
    }

    Map project(Task task, boolean preview = false) {
        CalendarEvent target = projections.fromTask(task)
        if (target == null) return [status: task.todoistDue == null ? 'unscheduled' : 'ineligible_or_unroutable', taskId: task.id]
        if (store.loadProjections().values().any { record ->
            record.status in ['current', 'cancelled'] && ProjectionMetadata.read(ProjectionMetadata.eventFromMap(record.event as Map))
                .value.members.any { it.occurrenceKey == task.occurrenceIdentity().occurrenceKey() }
        }) return [status: 'existing_or_cancelled', taskId: task.id]
        if (calendar.findEventByUid(target.uid) != null) return [status: 'recovery_required', taskId: task.id]
        if (preview) return [status: 'preview_create', event: ProjectionMetadata.eventMap(target)]
        forward(null, target, [task])
    }

    Map reconcile(CalendarEvent live, List<Task> inventory, boolean reverseEnabled, boolean preview = false,
                  Map<String, Map> items = [:]) {
        if (!(live.description ?: '').readLines().any { it.startsWith(ProjectionMetadata.PREFIX) }) {
            CalendarEvent target = singleUpgradeTarget(live, inventory)
            Task task = inventory.find { it.id == ProjectionMetadata.read(target).value.members[0].taskId }
            if (preview) return [status: 'preview_upgrade', event: ProjectionMetadata.eventMap(target)]
            return forward(live, target, [task], 'upgrade')
        }
        ProjectionMetadata metadata = ProjectionMetadata.read(live)
        if (metadata.value.historical) return [status: 'historical']
        if (metadata.value.routingFingerprint != config.routing.fingerprint) return conflict('routing snapshot changed')
        List<String> ids = metadata.value.members*.taskId
        if (store.loadSyncOperations().values().any { it.status != 'verified' && it.intent.taskIds.intersect(ids) }) {
            return conflict('pending operation requires recovery before schedule reconciliation')
        }
        Map record = store.loadProjections()[ApplicationStateStore.projectionKey(live)]
        if (record != null && record.status != 'current') return conflict('projection is retired or cancelled')
        if (record != null && ProjectionMetadata.read(ProjectionMetadata.eventFromMap(record.event as Map)).value != metadata.value) {
            return conflict('portable and local membership baselines disagree')
        }
        List<Task> tasks = ids.collect { id -> inventory.find { it.id == id } }
        boolean taskChanged = metadata.value.members.withIndex().any { Map member, int index ->
            Task task = tasks[index]
            task == null || task.occurrenceIdentity().occurrenceKey() != member.occurrenceKey ||
                task.todoistDue?.snapshotMap() != member.due || task.nativeDuration?.toMinutes() != member.nativeMinutes ||
                task.effectiveDuration.toMinutes() != member.effectiveMinutes ||
                config.projectionDestination(task) != live.calendarName
        }
        boolean calendarChanged = live.start.toString() != metadata.value.members[0].start ||
            live.end.toString() != metadata.value.members[-1].end || live.allDay != metadata.value.allDay
        if (record == null) {
            if (taskChanged || calendarChanged) return conflict('lost local baseline with disagreement; review recovery')
            if (!preview) {
                tasks.each { writer.preflight(it) }
                ProjectionMetadata.requireUnchanged(live, calendar.readResource(live.calendarName, live.id))
                ProjectionMetadata.requireUnchanged(live, calendar.findEventByUid(live.uid))
                store.commitProjections([live])
            }
            return [status: preview ? 'preview_recover' : 'recovered']
        }
        if (taskChanged && calendarChanged) return conflict('both schedules changed since verified baseline')
        if (!taskChanged && !calendarChanged) return [status: 'unchanged']
        if (calendarChanged) {
            if (!reverseEnabled) return [status: 'reverse_disabled']
            if (live.allDay != metadata.value.allDay || live.allDay) return conflict('date-only calendar conversion/edit requires explicit review')
            if (preview) return [status: 'preview_reverse', event: ProjectionMetadata.eventMap(live)]
            return new GroupScheduleTransform(store, calendar, todoist, config).apply(live, tasks)
        }
        // Membership departures and group edits are replacements; they never write a task schedule.
        Task task = tasks.size() == 1 ? tasks[0] : null
        if (task == null || task.occurrenceIdentity().occurrenceKey() != metadata.value.members[0].occurrenceKey ||
            task.todoistDue == null || config.projectionDestination(task) != live.calendarName) {
            return new ProjectionMembershipChange(store, calendar, todoist, config).apply(live, inventory, items, preview)
        }
        Map member = metadata.value.members[0]
        if (task.updatedAt == null || live.lastModified == null || member.taskUpdated == null ||
            !task.updatedAt.isAfter(live.lastModified) || !task.updatedAt.isAfter(Instant.parse(member.taskUpdated.toString()))) {
            return conflict('task schedule edit requires strictly newer timestamps')
        }
        Map updated = metadata.value + [revision: metadata.value.revision + 1,
            members: [projections.member(task)], allDay: task.allDayDue]
        def next = new ProjectionMetadata(updated)
        CalendarEvent target = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(live) + [etag: null,
            lastModified: null, start: updated.members[0].start, end: updated.members[0].end, allDay: task.allDayDue,
            description: next.description(ProjectionMetadata.humanDescription(live), live.uid, 'sync')])
        if (preview) return [status: 'preview_forward', event: ProjectionMetadata.eventMap(target)]
        forward(live, target, [task])
    }

    /** Exact cancellation receipts survive restart and suppress immediate creation loops. */
    Map cancel(CalendarEvent baseline, boolean preview = false) {
        if (calendar.readResource(baseline.calendarName, baseline.id) != null) return conflict('resource still exists')
        if (!preview) store.cancelProjection(baseline)
        [status: preview ? 'preview_cancel' : 'cancelled']
    }

    Map resume(String operationId) { store.withMutationLock { reconcileForward(operationId) } }

    /** Old mappings alone are insufficient: markers, live schedule, route and exact ownership must agree. */
    CalendarEvent singleUpgradeTarget(CalendarEvent source, List<Task> inventory) {
        List mappings = store.loadMappings().values().findAll { it.eventUid == source.uid }.toList()
        if (mappings.size() != 1 || !mappings[0].fullyApplied()) {
            throw new IllegalStateException('old projection membership requires one fully verified local mapping; restore state or review repair')
        }
        def mapping = mappings[0]
        Task task = inventory.find { it.id == mapping.taskId }
        CalendarEvent fresh = task == null ? null : projections.fromTask(task)
        if (fresh == null || mapping.occurrenceKey != task.occurrenceIdentity().occurrenceKey() ||
            mapping.slotStart != source.start || mapping.slotEnd != source.end ||
            fresh.calendarName != source.calendarName || fresh.start != source.start || fresh.end != source.end ||
            fresh.allDay != source.allDay || !ManagedEventIds.descriptionHasBlockId(source.description, mapping.blockId) ||
            !(source.uid in [ManagedEventIds.uidForBlock(mapping.blockId),
                ManagedEventIds.uidForOccurrences([mapping.occurrenceKey])])) {
            throw new IllegalStateException('old single-task mapping/schedule/route disagrees; review repair required')
        }
        ProjectionMetadata metadata = new ProjectionMetadata(ProjectionMetadata.read(fresh).value + [uid: source.uid])
        CalendarEvent target = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(source) + [etag: null,
            lastModified: null, description: metadata.description(source.description, mapping.blockId, mapping.planId)])
        ProjectionMetadata.requireSingleUpgrade(source, target)
        target
    }

    private Map forward(CalendarEvent expected, CalendarEvent target, List<Task> tasks, String action = null) {
        store.withMutationLock {
            tasks.each { writer.preflight(it) }
            String id = UUID.randomUUID().toString()
            store.beginSyncOperation(id, [type: 'schedule-forward', taskIds: tasks*.id,
                routingFingerprint: config.routing.fingerprint,
                tasks: tasks.collectEntries { [(it.id): PlanStore.taskToMap(it)] }, resources: [
                    (ProjectionReplacementService.key(target)): [action: action ?: (expected == null ? 'create' : 'update'),
                        event: ProjectionMetadata.eventMap(target), before: expected == null ? null : ProjectionMetadata.eventMap(expected)]]])
            reconcileForward(id)
        }
    }

    private Map reconcileForward(String id) {
        Map op = store.loadSyncOperations()[id]
        if (op?.intent?.type != 'schedule-forward') throw new IllegalArgumentException('forward schedule journal required')
        if (op.status == 'verified') { store.commitSyncProjections(id); return op }
        if (op.intent.routingFingerprint != config.routing.fingerprint || op.outcomes.values().any { it.state == 'conflict' }) {
            throw new IllegalStateException('forward operation conflict or routing drift requires review')
        }
        op.intent.tasks.values().each { writer.preflight(PlanStore.taskFromMap(it as Map)) }
        String resource = op.intent.resources.keySet().first()
        Map intent = op.intent.resources[resource]
        CalendarEvent target = ProjectionMetadata.eventFromMap(intent.event as Map)
        CalendarEvent live = calendar.readResource(target.calendarName, target.id)
        if (op.outcomes[resource].state == 'unknown' && ProjectionReplacementService.matches(target, live)) {
            ProjectionMetadata.requireUnchanged(live, calendar.findEventByUid(target.uid))
        } else {
            if (intent.action == 'create') {
                if (live != null) throw new IllegalStateException('unjournaled existing projection cannot be adopted')
                store.checkpointSyncResource(id, resource, 'unknown', [attempted: true])
                live = calendar.createProjection(target)
            } else {
                CalendarEvent before = ProjectionMetadata.eventFromMap(intent.before as Map)
                ProjectionMetadata.requireResourceUnchanged(before, live)
                ProjectionMetadata.requireResourceUnchanged(before, calendar.findEventByUid(before.uid))
                store.checkpointSyncResource(id, resource, 'unknown', [attempted: true])
                live = intent.action == 'upgrade' ? calendar.upgradeSingleProjection(before, target) : calendar.replaceProjection(before, target)
            }
            if (!ProjectionReplacementService.matches(target, live)) throw new IllegalStateException('forward schedule readback failed')
        }
        op.intent.tasks.values().each { writer.preflight(PlanStore.taskFromMap(it as Map)) }
        store.checkpointSyncResource(id, resource, 'verified', [event: ProjectionMetadata.eventMap(live)])
        store.commitSyncProjections(id)
        store.loadSyncOperations()[id]
    }

    private static Map conflict(String reason) { [status: 'conflict', reason: reason] }
}
