package todoistcaldavsync.planner.sync

import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ProjectionMetadata
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.recurrence.ItemsSyncStateStore
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.migration.MigrationState
import todoistcaldavsync.planner.writeback.CalendarWritebackStore

import java.time.Instant

/** Independent mirroring boundary. Feed checkpoints include a durable replay inbox, not just cursors. */
final class RoutedSyncCycle {
    private final PlannerConfig config
    private final ApplicationStateStore store
    private final ProjectionCalendarGateway calendar
    private final CalendarChangeGateway changes
    private final TodoistScheduleGateway todoist
    private final ItemsSyncStateStore items
    private final CalendarWritebackStore feeds
    private final RoutedScheduleReconciler schedules

    RoutedSyncCycle(PlannerConfig config, ApplicationStateStore store,
                    ProjectionCalendarGateway calendar, CalendarChangeGateway changes,
                    TodoistScheduleGateway todoist) {
        if (config.routing == null) throw new IllegalArgumentException('routed sync requires explicit routing')
        this.config = config
        this.store = store
        this.calendar = calendar
        this.changes = changes
        this.todoist = todoist
        this.items = new ItemsSyncStateStore(store.directory.resolve('sync'))
        this.feeds = new CalendarWritebackStore(store.directory.resolve('sync'))
        this.schedules = new RoutedScheduleReconciler(store, calendar, todoist, config)
    }

    Map run(Instant start, Instant end, boolean preview = false) {
        if (start == null || end == null || !end.isAfter(start)) throw new IllegalArgumentException('positive sync range required')
        store.withMutationLock { cycle(start, end, preview) }
    }

    private Map cycle(Instant start, Instant end, boolean preview) {
        if (!preview) new MigrationState(store.directory).requireAuthority('sync')
        // Validate all durable state before any read/checkpoint; corruption must never reset ownership.
        store.loadProjections()
        Map operations = store.loadSyncOperations()
        if (operations.values().any { it.intent.type == 'routed-plan' && it.status != 'verified' }) {
            return report('pending', preview, [[status: 'recovery_required',
                reason: 'resume the exact authorized plan; sync authority cannot complete a pending plan']])
        }
        def inbox = items.load()
        Map persisted = feeds.load()
        if (!(persisted.calendars instanceof Map) || !(persisted.echoes instanceof Map)) {
            throw new IllegalStateException('corrupt routed calendar inbox; restore/reconcile before sync')
        }
        persisted.calendars.each { name, state ->
            if (!(state instanceof Map) || !(state.pendingEvents instanceof List) || !(state.pendingDeleted instanceof List)) {
                throw new IllegalStateException('unsupported routed calendar inbox; repair required')
            }
            state.pendingEvents.each { ProjectionMetadata.eventFromMap(it as Map) }
            state.pendingDeleted.each { if (!(it instanceof Map) || !it.id || !it.uid) throw new IllegalStateException('invalid cancellation inbox') }
        }
        Map<String, Map> staged = [:]
        // No cross-destination write can start unless every managed feed has been read successfully.
        config.routing.destinations.each { String destination ->
            Map previous = persisted.calendars[destination] ?: [pendingEvents: [], pendingDeleted: []]
            Map page
            try { page = changes.readChanges(destination, previous, null, start, end) }
            catch (CalendarCursorExpiredException ignored) {
                // Keep ownership and pending evidence; reset only the provider's disposable cursor/cache.
                page = changes.readChanges(destination, [resourceUids: previous.resourceUids ?: [:]], null, start, end)
            }
            Map byId = previous.pendingEvents.collectEntries { [(it.id): it] }
            Map deleted = previous.pendingDeleted.collectEntries { [(it.id): it] }
            page.events.each { CalendarEvent event ->
                byId[event.id] = ProjectionMetadata.eventMap(event)
                deleted.remove(event.id)
            }
            page.deleted.each { Map tombstone -> deleted[tombstone.id] = tombstone; byId.remove(tombstone.id) }
            staged[destination] = page.state + [pendingEvents: byId.values().toList(), pendingDeleted: deleted.values().toList()]
        }
        def page = todoist.syncItems(inbox.syncToken)
        if ((inbox.pendingItems + page.items).any { !(it.id instanceof String) || !it.id }) {
            throw new IllegalStateException('Todoist items inbox requires authoritative task identities')
        }
        if (!preview) {
            // Every cursor advances together with its retained evidence, before any remote mutation.
            staged.each { String destination, Map state -> feeds.saveCalendar(destination, state) }
            items.checkpoint(page)
        }
        Map<String, Map> evidence = (inbox.pendingItems + page.items).collectEntries { [(it.id?.toString()): it] }
        List<Map> results = []
        if (!config.calendarWritebackEnabled && operations.values().any {
            it.status != 'verified' && it.intent.type == 'schedule-transform'
        }) return report('pending', preview,
            [[status: 'recovery_required', reason: 'reverse authority is disabled for pending transform']])
        if (preview && operations.values().any { it.status != 'verified' || !store.syncProjectionsCommitted(it.id.toString()) }) {
            return report('pending', true, [[status: 'recovery_required']])
        }
        if (!preview) {
            operations.values().each { Map operation ->
                if (operation.status == 'verified') {
                    if (!store.syncProjectionsCommitted(operation.id.toString())) store.commitSyncProjections(operation.id.toString())
                } else {
                    switch (operation.intent.type) {
                        case 'schedule-forward': schedules.resume(operation.id.toString()); break
                        case 'schedule-transform': new GroupScheduleTransform(store, calendar, todoist, config).resume(operation.id.toString()); break
                        case 'projection-replacement': new ProjectionMembershipChange(store, calendar, todoist, config).resume(operation.id.toString()); break
                        default: throw new IllegalStateException('unsupported pending sync operation; repair required')
                    }
                }
            }
        }
        List<Task> tasks = todoist.fetchTasks().collect {
            Task.fromTodoistMap(it, config.durationResolver, config.manualLabel, config.timezone)
        }
        Map records = store.loadProjections()
        Map<String, CalendarEvent> candidates = [:]
        staged.each { String destination, Map state ->
            state.pendingEvents.each { Map event -> candidates[destination + '\u0000' + event.id] = ProjectionMetadata.eventFromMap(event) }
            state.pendingDeleted.each { Map tombstone ->
                String key = destination + '\u0000' + tombstone.id
                if (!records.containsKey(key)) results << [status: 'conflict', reason: 'cancellation has no verified membership baseline', destination: destination, resourceId: tombstone.id]
            }
        }
        records.each { String key, Map record ->
            if (record.status == 'current') candidates[key] = ProjectionMetadata.eventFromMap(record.event as Map)
        }
        Map<String, CalendarEvent> live = [:]
        Set<String> coverage = [] as Set
        // Discover/adopt all existing groups before creating individuals; malformed/duplicate groups freeze creation.
        candidates.each { String key, CalendarEvent candidate ->
            CalendarEvent current = calendar.readResource(candidate.calendarName, candidate.id)
            if (records[key]?.status in ['retired', 'cancelled']) {
                if (current != null) results << [status: 'conflict', reason: 'retired/cancelled resource reappeared; review required',
                    destination: candidate.calendarName, resourceId: candidate.id]
                return
            }
            if (current == null) {
                if (records[key]?.status == 'current') {
                    results << schedules.cancel(ProjectionMetadata.eventFromMap(records[key].event as Map), preview)
                } else results << [status: 'conflict', reason: 'missing discovered resource has no cancellation baseline']
                return
            }
            try {
                boolean portable = (current.description ?: '').readLines().any { it.startsWith(ProjectionMetadata.PREFIX) }
                def metadata = ProjectionMetadata.read(portable ? current : schedules.singleUpgradeTarget(current, tasks))
                if (!metadata.value.historical) metadata.value.members.each { Map member ->
                    if (!coverage.add(member.occurrenceKey.toString())) throw new IllegalStateException('duplicate active membership')
                }
                live[key] = current
            } catch (IllegalArgumentException | IllegalStateException failure) {
                results << [status: 'conflict', reason: failure.message, destination: candidate.calendarName, resourceId: candidate.id]
            }
        }
        if (!results.any { it.status == 'conflict' }) {
            live.each { String key, CalendarEvent event ->
                Map result = schedules.reconcile(event, tasks, config.calendarWritebackEnabled, preview, evidence)
                results << result + [destination: event.calendarName, resourceId: event.id]
            }
        }
        if (!results.any { it.status in ['conflict', 'reverse_disabled'] }) {
            // A reverse edit/replacement may have changed task snapshots; never project the pre-write inventory.
            if (!preview) tasks = todoist.fetchTasks().collect {
                Task.fromTodoistMap(it, config.durationResolver, config.manualLabel, config.timezone)
            }
            tasks.each { Task task ->
                if (preview && coverage.contains(task.occurrenceIdentity().occurrenceKey())) return
                results << schedules.project(task, preview)
            }
            if (!preview) {
                staged.each { String destination, Map state -> feeds.saveCalendar(destination, state + [pendingEvents: [], pendingDeleted: []]) }
                items.load().pendingItems.each { items.acknowledge(it.id.toString()) }
            }
        }
        report(results.any { it.status in ['conflict', 'reverse_disabled'] } ? 'conflict' : 'synchronized', preview, results)
    }

    private Map report(String status, boolean preview, List<Map> results) {
        [status: status, preview: preview,
            authorities: [forward: !preview, reverse: !preview && config.calendarWritebackEnabled, lifecycle: false, planning: false],
            pendingOperations: store.loadSyncOperations().values().count { it.status != 'verified' },
            results: results]
    }
}
