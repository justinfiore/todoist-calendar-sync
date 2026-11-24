package todoistcaldavsync.planner.sync

import todoistcaldavsync.planner.adapters.CalendarReadGateway
import todoistcaldavsync.planner.adapters.ProjectionCalendarGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.state.PlanStore

import java.time.Instant

/** Read-only planning baseline. A proposal never becomes evidence of a current placement. */
final class RoutedPlanningInputs {
    private final PlannerConfig config
    private final ApplicationStateStore store
    private final CalendarReadGateway calendar

    RoutedPlanningInputs(PlannerConfig config, ApplicationStateStore store, CalendarReadGateway calendar) {
        this.config = config
        this.store = store
        this.calendar = calendar
    }

    /** Re-read the approved source, not its proposed placements, before the first dependent write. */
    void requireSource(Plan plan, List<Task> inventory) {
        Map source = plan.metrics.routedSource as Map
        if (source?.schemaVersion != 1 || source.routingFingerprint != config.routing.fingerprint ||
            !(source.tasks instanceof List) || !(source.projections instanceof List) ||
            !(source.projectionState instanceof Map)) {
            throw new IllegalStateException('missing or changed routed source; request a fresh proposal')
        }
        Instant start = Instant.parse(source.rangeStart.toString()), end = Instant.parse(source.rangeEnd.toString())
        Set<String> ids = source.tasks*.id as Set
        if (ids.size() != source.tasks.size() || ids != (plan.tasks*.id as Set) || !end.isAfter(start)) {
            throw new IllegalStateException('routed source does not bind the planned tasks/range')
        }
        Map fresh = prepare(inventory, calendar.fetchEvents(start, end), start, end, plan.createdAt,
            [] as Set, ids).source as Map
        if (fresh != source) throw new IllegalStateException('routed source changed after proposal; request fresh review')
    }

    Map prepare(List<Task> inventory, List<CalendarEvent> events, Instant start, Instant end, Instant now,
                Set<String> frozenIds = [] as Set, Set<String> planningIds = null) {
        if (store.loadSyncOperations().values().any {
            it.status != 'verified' || !store.syncProjectionsCommitted(it.id.toString())
        }) throw new IllegalStateException('pending routed operation blocks planning; reconcile first')
        Map records = store.loadProjections()
        Map<String, Task> tasks = inventory.collectEntries { [(it.id): it] }
        Map<String, CalendarEvent> owned = [:]
        List<CalendarEvent> blockers = []
        List<ScheduledBlock> current = []
        Set<String> coverage = [] as Set
        List<CalendarEvent> reads = new ArrayList<>(events)
        // A bounded list may omit an existing projection of a task that planning could move into the horizon.
        records.values().findAll { it.status == 'current' }.each { Map record ->
            CalendarEvent baseline = ProjectionMetadata.eventFromMap(record.event as Map)
            if (!reads.any { it.id == baseline.id && it.calendarName == baseline.calendarName }) {
                if (!(calendar instanceof ProjectionCalendarGateway)) throw new IllegalStateException('exact routed reads required')
                CalendarEvent live = (calendar as ProjectionCalendarGateway).readResource(baseline.calendarName, baseline.id)
                if (live == null) throw new IllegalStateException('missing current projection; reconcile cancellation before planning')
                reads << live
            }
        }
        reads.each { CalendarEvent event ->
            if (!config.routing.destinations.contains(event.calendarName) ||
                !(ManagedEventIds.isPlannerUid(event.uid) || ManagedEventIds.hasOwnershipMarker(event.description) ||
                    (event.description ?: '').contains(ProjectionMetadata.PREFIX))) {
                blockers << event
                return
            }
            ProjectionMetadata metadata = ProjectionMetadata.read(event)
            String key = ApplicationStateStore.projectionKey(event)
            if (owned.containsKey(key)) throw new IllegalStateException('duplicate routed resource in planning inputs')
            ProjectionMetadata.requireUnchanged(event, calendar.findEventByUid(event.uid))
            if (metadata.value.historical) { blockers << event; return }
            if (metadata.value.routingFingerprint != config.routing.fingerprint ||
                (records[key] != null && (records[key].status != 'current' ||
                    ProjectionMetadata.read(ProjectionMetadata.eventFromMap(records[key].event as Map)).value != metadata.value))) {
                throw new IllegalStateException('routed membership/routing drift; reconcile before planning')
            }
            metadata.value.members.each { Map member ->
                Task task = tasks[member.taskId]
                if (!coverage.add(member.occurrenceKey.toString()) || task == null ||
                    task.occurrenceIdentity().occurrenceKey() != member.occurrenceKey ||
                    task.todoistDue?.snapshotMap() != member.due ||
                    task.nativeDuration?.toMinutes() != member.nativeMinutes ||
                    task.effectiveDuration.toMinutes() != member.effectiveMinutes ||
                    config.projectionDestination(task) != event.calendarName) {
                    throw new IllegalStateException('task/occurrence schedule drift; reconcile before planning')
                }
            }
            if (event.start.toString() != metadata.value.members[0].start ||
                event.end.toString() != metadata.value.members[-1].end || event.allDay != metadata.value.allDay) {
                throw new IllegalStateException('calendar schedule drift; reconcile before planning')
            }
            owned[key] = event
            boolean replaceable = !event.allDay && metadata.value.members.every {
                config.planningEligible(tasks[it.taskId]) && (planningIds == null || planningIds.contains(it.taskId))
            }
            if (replaceable) {
                current << block(event.uid, metadata.value.members as List, tasks, event.title,
                    metadata.value.members.any { frozenIds.contains(it.taskId) } ||
                        (!event.start.isBefore(start) && !event.start.isAfter(now.plus(config.stability.freezeWithin))))
            } else if (!event.allDay && metadata.value.members.any {
                config.planningEligible(tasks[it.taskId]) && (planningIds == null || planningIds.contains(it.taskId))
            }) {
                // Do not partially reschedule a projection that still contains a protected/excluded member.
                current << block(event.uid, metadata.value.members as List, tasks, event.title, true)
            } else {
                // An included manual/date-only task still occupies time even on an informational destination.
                blockers << event.withClassification(EventRole.HARD_BLOCKER, 'retained_projection', 'Retained current task placement')
            }
        }
        List<Task> candidates = inventory.findAll {
            config.planningEligible(it) && (planningIds == null || planningIds.contains(it.id))
        }
        inventory.findAll { Task task ->
            task.todoistDue != null && config.projectionDestination(task) != null &&
                !coverage.contains(task.occurrenceIdentity().occurrenceKey()) && !candidates.contains(task)
        }.each { Task task ->
            Instant until = task.allDayDue ? task.todoistDue.localDate(config.timezone).plusDays(1)
                .atStartOfDay(config.timezone).toInstant() : task.dueTime.plus(task.effectiveDuration)
            blockers << CalendarEvent.builder().id('retained-' + task.id).title(task.content)
                .calendarName(config.projectionDestination(task)).start(task.dueTime).end(until)
                .allDay(task.allDayDue).role(EventRole.HARD_BLOCKER).build()
        }
        candidates.findAll { it.todoistDue != null && !coverage.contains(it.occurrenceIdentity().occurrenceKey()) }.each { Task task ->
            if (records.values().any { Map record -> record.status == 'cancelled' &&
                ProjectionMetadata.read(ProjectionMetadata.eventFromMap(record.event as Map)).value.members.any {
                    it.occurrenceKey == task.occurrenceIdentity().occurrenceKey()
                } }) throw new IllegalStateException('cancelled task placement requires review before planning')
            if (!task.allDayDue) {
                Map member = [taskId: task.id, start: task.dueTime.toString(), end: task.dueTime.plus(task.effectiveDuration).toString()]
                current << block('current-' + task.id, [member], tasks, task.content, frozenIds.contains(task.id) ||
                    (!task.dueTime.isBefore(start) && !task.dueTime.isAfter(now.plus(config.stability.freezeWithin))))
            }
        }
        List<ScheduledBlock> retained = current.findAll { ScheduledBlock block ->
            block.frozen || config.stability.keepManualMoves ||
                (!block.start.isBefore(start) && !block.start.isAfter(now.plus(config.stability.freezeWithin)))
        }
        Set<String> retainedIds = retained.collectMany { it.taskIds } as Set
        retained.each { ScheduledBlock block ->
            blockers << CalendarEvent.builder().id(block.id).title(block.title).calendarName('retained task placement')
                .start(block.start).end(block.end).role(EventRole.HARD_BLOCKER).build()
        }
        Plan previous = Plan.builder().id('reconciled-current').createdAt(now).tasks(candidates)
            .scheduledBlocks(current.findAll { !it.taskIds.any { retainedIds.contains(it) } }).build()
        List<Task> bound = inventory.findAll { candidates.contains(it) || retainedIds.contains(it.id) }
        [tasks: candidates.findAll { !retainedIds.contains(it.id) }, allTasks: bound,
            events: blockers, retained: retained, previous: previous,
            source: [schemaVersion: 1, routingFingerprint: config.routing.fingerprint,
                rangeStart: start.toString(), rangeEnd: end.toString(),
                tasks: bound.toSorted { it.id }.collect { PlanStore.taskToMap(it) },
                calendarInputs: reads.collect { ProjectionMetadata.eventMap(it) }.toSorted {
                    [it.calendarName, it.id, it.uid, it.start].join('\u0000')
                },
                projections: owned.keySet().toSorted().collect { ProjectionMetadata.eventMap(owned[it]) },
                projectionState: records]]
    }

    private static ScheduledBlock block(String id, List<Map> members, Map<String, Task> tasks, String title, boolean frozen) {
        Task first = tasks[members[0].taskId]
        ScheduledBlock.builder().id(id).title(title).taskIds(members*.taskId)
            .start(Instant.parse(members[0].start.toString())).end(Instant.parse(members[-1].end.toString()))
            .memberIntervals(members.collect { new MemberInterval(it.taskId.toString(), Instant.parse(it.start.toString()), Instant.parse(it.end.toString())) })
            .projectId(first.projectId).projectName(first.projectName).focusBlock(members.size() > 1)
            .manualOverride(true).frozen(frozen).reason('Reconciled current schedule; proposal does not authorize a move')
            .metadata([source: 'routed_sync']).build()
    }
}
