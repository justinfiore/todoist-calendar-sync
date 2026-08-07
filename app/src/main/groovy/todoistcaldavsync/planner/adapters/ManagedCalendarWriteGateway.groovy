package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds

/**
 * Decorator that enforces managed-output ownership on any {@link CalendarWriteGateway}.
 * Prevents accidental mutation of external calendars/events.
 * Writes require managed calendar AND planner UID AND ownership marker (never OR).
 * Deletes require live ownership verification against expected block metadata.
 */
class ManagedCalendarWriteGateway implements CalendarWriteGateway {
    private final CalendarWriteGateway delegate
    private final CalendarReadGateway read
    private final String managedCalendarName

    ManagedCalendarWriteGateway(CalendarWriteGateway delegate, String managedCalendarName) {
        this(delegate, delegate instanceof CalendarReadGateway ? (CalendarReadGateway) delegate : null,
            managedCalendarName)
    }

    ManagedCalendarWriteGateway(CalendarWriteGateway delegate, CalendarReadGateway read,
                                String managedCalendarName) {
        if (delegate == null) {
            throw new IllegalArgumentException('delegate is required')
        }
        if (!managedCalendarName) {
            throw new IllegalArgumentException('managedCalendarName is required')
        }
        this.delegate = delegate
        this.read = read
        this.managedCalendarName = managedCalendarName
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
        delegate.upsertEvent(event)
    }

    @Override
    void deleteOwnedEvent(String eventUid, String expectedBlockId) {
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
        if (event.calendarName != managedCalendarName) {
            throw new IllegalStateException(
                "Refusing write outside managed calendar '${managedCalendarName}': got '${event.calendarName}'")
        }
        // AND — never OR UID/marker
        if (!ManagedEventIds.isPlannerUid(event.uid) || !ManagedEventIds.hasOwnershipMarker(event.description)) {
            throw new IllegalStateException(
                "Refusing write without both planner UID and ownership marker: uid=${event.uid}")
        }
        if (!ManagedEventIds.isOwned(event, managedCalendarName)) {
            throw new IllegalStateException(
                "Refusing write of unowned event: uid=${event.uid}")
        }
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
