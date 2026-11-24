package todoistcaldavsync.planner.sync

import todoistcaldavsync.planner.adapters.ProjectionCalendarGateway
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ProjectionMetadata
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.state.ProjectionReplacementPermit

/** Create/verify replacements before retiring sources. Recovery never uses ordinary UID adoption. */
final class ProjectionReplacementService {
    private final ApplicationStateStore store
    private final ProjectionCalendarGateway calendar
    private final Closure taskPreflight

    ProjectionReplacementService(ApplicationStateStore store, ProjectionCalendarGateway calendar,
                                 Closure taskPreflight = null) {
        this.store = store
        this.calendar = calendar
        this.taskPreflight = taskPreflight
    }

    Map replace(List<CalendarEvent> sources, List<CalendarEvent> targets, Map departureEvidence = [:]) {
        store.withMutationLock {
            if (!sources) throw new IllegalArgumentException('verified replacement sources required')
            Map records = store.loadProjections()
            sources.each { CalendarEvent source ->
                Map record = records[ApplicationStateStore.projectionKey(source)]
                if (record != null && (record.status != 'current' ||
                    ProjectionMetadata.read(ProjectionMetadata.eventFromMap(record.event as Map)).value != ProjectionMetadata.read(source).value)) {
                    throw new IllegalStateException('replacement source disagrees with current local membership baseline')
                }
            }
            Map resources = [:]
            List<String> tasks = []
            (sources + targets).each { CalendarEvent event ->
                tasks.addAll(ProjectionMetadata.read(event).value.members*.taskId)
            }
            targets.each { resources[key(it)] = [action: 'create', event: ProjectionMetadata.eventMap(it)] }
            sources.each {
                if (resources.containsKey(key(it))) throw new IllegalArgumentException('replacement cannot overwrite its source')
                resources[key(it)] = [action: 'retire', event: ProjectionMetadata.eventMap(it)]
            }
            if (resources.size() != sources.size() + targets.size()) {
                throw new IllegalArgumentException('replacement resources must be distinct')
            }
            String id = UUID.randomUUID().toString()
            store.beginSyncOperation(id, [type: 'projection-replacement', taskIds: tasks.unique(),
                routingFingerprint: ProjectionMetadata.read(sources.first()).value.routingFingerprint,
                resources: resources, departureEvidence: departureEvidence])
            reconcile(id)
        }
    }

    Map resume(String operationId) {
        store.withMutationLock { reconcile(operationId) }
    }

    private Map reconcile(String id) {
        Map operation = store.loadSyncOperations()[id]
        if (operation == null || operation.intent.type != 'projection-replacement') {
            throw new IllegalArgumentException('replacement journal not found')
        }
        if (operation.status == 'verified') {
            store.commitSyncProjections(id)
            return operation
        }
        if (operation.outcomes.values().any { it.state == 'conflict' }) {
            throw new IllegalStateException('replacement has a recorded conflict; explicit reconciliation required')
        }
        Map resources = operation.intent.resources
        List<String> creates = resources.keySet().findAll { resources[it].action == 'create' }.toList()
        List<String> retires = resources.keySet().findAll { resources[it].action == 'retire' }.toList()
        ProjectionReplacementPermit permit = new ProjectionReplacementPermit(store, id)
        preflight(operation)
        // Revalidate every surviving source before any replacement creation, even after restart.
        retires.each { String resource ->
            CalendarEvent source = ProjectionMetadata.eventFromMap(resources[resource].event as Map)
            CalendarEvent live = calendar.readResource(source.calendarName, source.id)
            if (live == null) {
                if (!(operation.outcomes[resource].state in ['unknown', 'verified'])) {
                    conflict(id, resource, 'source vanished before journaled retirement')
                }
            } else {
                try { ProjectionMetadata.requireUnchanged(source, live) }
                catch (Exception e) { conflict(id, resource, e.message) }
            }
        }
        creates.each { String resource ->
            preflight(operation)
            Map outcome = store.loadSyncOperations()[id].outcomes[resource]
            CalendarEvent target = ProjectionMetadata.eventFromMap(resources[resource].event as Map)
            CalendarEvent live = calendar.readResource(target.calendarName, target.id)
            if (outcome.state == 'verified') {
                calendar.verifyReplacement(target, permit)
                ProjectionMetadata.requireUnchanged(ProjectionMetadata.eventFromMap(outcome.evidence.event as Map), live)
                return
            }
            if (live != null && outcome.state == 'pending') conflict(id, resource, 'target existed before creation attempt')
            if (live == null) {
                store.checkpointSyncResource(id, resource, 'unknown', [attempted: true])
                live = calendar.createReplacement(target, permit)
            }
            live = calendar.verifyReplacement(target, permit)
            if (!matches(target, live)) conflict(id, resource, 'replacement differs from immutable intent')
            store.checkpointSyncResource(id, resource, 'verified', [event: ProjectionMetadata.eventMap(live)])
        }
        retires.each { String resource ->
            preflight(operation)
            Map outcome = store.loadSyncOperations()[id].outcomes[resource]
            CalendarEvent source = ProjectionMetadata.eventFromMap(resources[resource].event as Map)
            CalendarEvent live = calendar.readResource(source.calendarName, source.id)
            if (outcome.state == 'verified') {
                if (live != null) throw new IllegalStateException('retired resource reappeared; reconciliation required')
                return
            }
            // A target edited since verification is never silently treated as a successful replacement.
            creates.each { String created ->
                Map verified = store.loadSyncOperations()[id].outcomes[created].evidence.event as Map
                CalendarEvent expected = ProjectionMetadata.eventFromMap(verified)
                calendar.verifyReplacement(ProjectionMetadata.eventFromMap(resources[created].event as Map), permit)
                ProjectionMetadata.requireUnchanged(expected, calendar.readResource(expected.calendarName, expected.id))
            }
            if (live != null) {
                ProjectionMetadata.requireUnchanged(source, live)
                store.checkpointSyncResource(id, resource, 'unknown', [attempted: true, etag: source.etag])
                calendar.retireProjection(source)
            }
            if (calendar.readResource(source.calendarName, source.id) != null) {
                throw new IllegalStateException('source retirement remains unverified')
            }
            store.checkpointSyncResource(id, resource, 'verified', [absent: true, id: source.id])
        }
        store.commitSyncProjections(id)
        store.loadSyncOperations()[id]
    }

    private void preflight(Map operation) {
        Map evidence = operation.intent.departureEvidence as Map
        if (evidence?.taskBaselines instanceof Map && evidence.taskBaselines) {
            if (taskPreflight == null) throw new IllegalStateException('task-bound replacement requires fresh task preflight')
            taskPreflight.call(evidence + [routingFingerprint: operation.intent.routingFingerprint])
        }
    }

    private void conflict(String id, String resource, String message) {
        store.checkpointSyncResource(id, resource, 'conflict', [reason: message])
        throw new IllegalStateException(message)
    }

    static boolean matches(CalendarEvent expected, CalendarEvent live) {
        live != null && live.etag && expected.id == live.id && expected.uid == live.uid &&
            expected.calendarName == live.calendarName && expected.title == live.title &&
            expected.description == live.description && expected.start == live.start &&
            expected.end == live.end && expected.allDay == live.allDay
    }

    static String key(CalendarEvent event) { 'calendar:' + event.calendarName + ':' + event.id }
}
