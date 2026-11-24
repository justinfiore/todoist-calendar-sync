package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.domain.ProjectionMetadata
import todoistcaldavsync.planner.state.ProjectionReplacementPermit

/**
 * Decorator that enforces managed-output ownership on any {@link CalendarWriteGateway}.
 * Prevents accidental mutation of external calendars/events.
 * Writes require managed calendar AND planner UID AND ownership marker (never OR).
 * Deletes require live ownership verification against expected block metadata.
 */
class ManagedCalendarWriteGateway implements ProjectionCalendarGateway {
    private final CalendarWriteGateway delegate
    private final CalendarReadGateway read
    private final String managedCalendarName
    final Set<String> managedCalendarNames

    ManagedCalendarWriteGateway(CalendarWriteGateway delegate, String managedCalendarName) {
        this(delegate, delegate instanceof CalendarReadGateway ? (CalendarReadGateway) delegate : null,
            managedCalendarName)
    }

    ManagedCalendarWriteGateway(CalendarWriteGateway delegate, CalendarReadGateway read,
                                Object managedCalendarName) {
        if (delegate == null) {
            throw new IllegalArgumentException('delegate is required')
        }
        if (!managedCalendarName) {
            throw new IllegalArgumentException('managedCalendarName is required')
        }
        this.delegate = delegate
        this.read = read
        if (managedCalendarName instanceof Collection) {
            if (managedCalendarName.any { !(it instanceof String) || !it.trim() }) {
                throw new IllegalArgumentException('managed destinations must be nonblank names')
            }
            this.managedCalendarName = null
            this.managedCalendarNames = Collections.unmodifiableSet(new LinkedHashSet<String>(managedCalendarName as Collection))
        } else {
            this.managedCalendarName = managedCalendarName.toString()
            this.managedCalendarNames = Collections.singleton(this.managedCalendarName)
        }
    }

    String getManagedCalendarName() {
        managedCalendarName
    }

    @Override
    void upsertEvent(CalendarEvent event) {
        if (event == null) {
            throw new IllegalArgumentException('event is required')
        }
        validateOwnedWrite(event)
        if (managedCalendarName == null) throw new IllegalStateException('routed writes require exact-resource operations')
        delegate.upsertEvent(event)
    }

    @Override
    void deleteOwnedEvent(String eventUid, String expectedBlockId) {
        if (managedCalendarName == null) throw new IllegalStateException('routed deletion requires exact-resource operations')
        if (!eventUid) {
            throw new IllegalArgumentException('eventUid is required')
        }
        if (!expectedBlockId) {
            throw new IllegalArgumentException('expectedBlockId is required')
        }
        CalendarEvent existing = requireLiveOwned(eventUid, expectedBlockId)
        // Re-verify calendar boundary before delegating
        if (existing.calendarName != managedCalendarName) {
            throw new IllegalStateException(
                "Refusing to delete event outside managed calendar: ${existing.calendarName}")
        }
        delegate.deleteOwnedEvent(eventUid, expectedBlockId)
    }

    private void validateOwnedWrite(CalendarEvent event) {
        if (!managedCalendarNames.contains(event.calendarName)) {
            throw new IllegalStateException(
                "Refusing write outside managed calendar '${managedCalendarName}': got '${event.calendarName}'")
        }
        // AND — never OR UID/marker
        if (!ManagedEventIds.isPlannerUid(event.uid) || !ManagedEventIds.hasOwnershipMarker(event.description)) {
            throw new IllegalStateException(
                "Refusing write without both planner UID and ownership marker: uid=${event.uid}")
        }
        if (!ManagedEventIds.isOwned(event, event.calendarName)) {
            throw new IllegalStateException(
                "Refusing write of unowned event: uid=${event.uid}")
        }
    }

    @Override
    List<CalendarEvent> fetchEvents(java.time.Instant start, java.time.Instant end) {
        if (read == null) throw new IllegalStateException('calendar read gateway is required')
        read.fetchEvents(start, end)
    }

    @Override
    CalendarEvent findEventByUid(String uid) {
        if (read == null) throw new IllegalStateException('calendar read gateway is required')
        read.findEventByUid(uid)
    }

    @Override
    String projectionTitle(String destination, String title) {
        requireManagedDestination(destination)
        projectionDelegate().projectionTitle(destination, title)
    }

    @Override
    String projectionResourceId(String destination, String uid) {
        requireManagedDestination(destination)
        projectionDelegate().projectionResourceId(destination, uid)
    }

    @Override
    String newProjectionResourceId(String destination, String uid) {
        requireManagedDestination(destination)
        projectionDelegate().newProjectionResourceId(destination, uid)
    }

    @Override
    CalendarEvent readResource(String destination, String id) {
        requireManagedDestination(destination)
        projectionDelegate().readResource(destination, id)
    }

    @Override
    CalendarEvent createProjection(CalendarEvent target) {
        validateOwnedWrite(target)
        ProjectionMetadata.read(target)
        projectionDelegate().createProjection(target)
    }

    @Override
    CalendarEvent createReplacement(CalendarEvent target, ProjectionReplacementPermit permit) {
        validateOwnedWrite(target)
        ProjectionMetadata.read(target)
        projectionDelegate().createReplacement(target, permit)
    }

    @Override
    CalendarEvent verifyReplacement(CalendarEvent target, ProjectionReplacementPermit permit) {
        validateOwnedWrite(target)
        projectionDelegate().verifyReplacement(target, permit)
    }

    @Override
    CalendarEvent replaceProjection(CalendarEvent expected, CalendarEvent target) {
        validateOwnedWrite(target)
        validateOwnedWrite(expected)
        ProjectionMetadata.requireUnchanged(expected, readResource(expected.calendarName, expected.id))
        projectionDelegate().replaceProjection(expected, target)
    }

    @Override
    CalendarEvent upgradeSingleProjection(CalendarEvent expected, CalendarEvent target) {
        validateOwnedWrite(target)
        validateOwnedWrite(expected)
        ProjectionMetadata.requireSingleUpgrade(expected, target)
        projectionDelegate().upgradeSingleProjection(expected, target)
    }

    @Override
    void retireProjection(CalendarEvent expected) {
        validateOwnedWrite(expected)
        ProjectionMetadata.requireUnchanged(expected, readResource(expected.calendarName, expected.id))
        projectionDelegate().retireProjection(expected)
    }

    private ProjectionCalendarGateway projectionDelegate() {
        if (!(delegate instanceof ProjectionCalendarGateway)) {
            throw new IllegalStateException('provider does not support exact-resource projection operations')
        }
        delegate as ProjectionCalendarGateway
    }

    private void requireManagedDestination(String destination) {
        if (!managedCalendarNames.contains(destination)) throw new IllegalStateException('destination is not explicitly managed')
    }

    private CalendarEvent requireLiveOwned(String eventUid, String expectedBlockId) {
        if (read == null) {
            throw new IllegalStateException(
                'CalendarReadGateway is required to verify ownership before delete')
        }
        // Global across all accessible calendars (see CalendarReadGateway contract)
        CalendarEvent existing = read.findEventByUid(eventUid)
        if (existing == null) {
            throw new IllegalStateException("Cannot delete missing event uid=${eventUid}")
        }
        if (!ManagedEventIds.isOwned(existing, managedCalendarName)) {
            throw new IllegalStateException(
                "Refusing to delete external/unowned event uid=${existing.uid}")
        }
        if (!ManagedEventIds.descriptionHasBlockId(existing.description, expectedBlockId)) {
            throw new IllegalStateException(
                "Refusing to delete event uid=${eventUid}: block metadata mismatch expected=${expectedBlockId}")
        }
        return existing
    }
}
