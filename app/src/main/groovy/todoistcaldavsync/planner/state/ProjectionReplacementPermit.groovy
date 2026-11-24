package todoistcaldavsync.planner.state

import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ProjectionMetadata

/** A live journal capability, not a general bypass for duplicate identities. */
final class ProjectionReplacementPermit {
    private final ApplicationStateStore store
    final String operationId

    ProjectionReplacementPermit(ApplicationStateStore store, String operationId) {
        if (store == null || !operationId) throw new IllegalArgumentException('durable replacement operation required')
        this.store = store
        this.operationId = operationId
        operation()
    }

    void validate(CalendarEvent target, Collection<CalendarEvent> collisions) {
        Map op = operation()
        List<Map> resources = op.intent.resources.values().toList()
        Map targetIntent = resources.find { it.action == 'create' && it.event == ProjectionMetadata.eventMap(target) }
        if (targetIntent == null || !resources.any { it.action == 'retire' }) {
            throw new IllegalStateException('target is not bound to the pending replacement journal')
        }
        ProjectionMetadata.read(target)
        Set<String> seen = [] as Set
        collisions.each { CalendarEvent live ->
            String key = live.calendarName + '\u0000' + live.id
            if (!seen.add(key)) throw new IllegalStateException('duplicate replacement resource')
            if (live.id == target.id && live.calendarName == target.calendarName && live.uid == target.uid) {
                Map intended = new LinkedHashMap(targetIntent.event as Map)
                Map observed = new LinkedHashMap(ProjectionMetadata.eventMap(live))
                intended.remove('etag'); intended.remove('lastModified')
                observed.remove('etag'); observed.remove('lastModified')
                if (intended != observed || !live.etag) {
                    throw new IllegalStateException('replacement target changed; reconciliation required')
                }
                return
            }
            Map source = resources.find { it.action == 'retire' && it.event.id == live.id &&
                it.event.calendarName == live.calendarName && it.event.uid == live.uid }
            if (source == null) throw new IllegalStateException('unrelated identity collision; replacement refused')
            ProjectionMetadata.requireUnchanged(ProjectionMetadata.eventFromMap(source.event as Map), live)
        }
    }

    private Map operation() {
        Map op = store.loadSyncOperations()[operationId]
        if (op == null || op.status != 'pending' || op.intent.type != 'projection-replacement' ||
            op.outcomes.values().any { it.state == 'conflict' } ||
            op.intent.resources.values().any { !(it instanceof Map) || !(it.action in ['create', 'retire']) ||
                !(it.event instanceof Map) }) {
            throw new IllegalStateException('pending exact-resource replacement journal required')
        }
        op
    }
}
