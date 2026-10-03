package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic in-memory CalDAV-like gateway for Phase 3 tests.
 * Enforces managed-calendar ownership on write/delete when configured.
 * Never contacts remote systems.
 */
class InMemoryCalendarGateway implements CalendarReadGateway, CalendarWriteGateway {
    private final Map<String, CalendarEvent> eventsByUid = new ConcurrentHashMap<>()
    final List<CalendarEvent> upserts = new CopyOnWriteArrayList<>()
    final List<String> deletes = new CopyOnWriteArrayList<>()
    final List<Map> rejectedWrites = new CopyOnWriteArrayList<>()

    final String managedCalendarName
    final boolean enforceOwnership

    /** Optional: throw on Nth upsert (1-based). */
    Integer failUpsertOnCall
    String failUpsertMessage = 'simulated calendar upsert failure'
    private int upsertCallCount = 0

    /** Optional: throw on Nth owned delete (1-based). */
    Integer failDeleteOnCall
    String failDeleteMessage = 'simulated calendar delete failure'
    private int deleteCallCount = 0

    InMemoryCalendarGateway(String managedCalendarName = 'Todoist Planned',
                            boolean enforceOwnership = true,
                            List<CalendarEvent> seed = []) {
        this.managedCalendarName = managedCalendarName
        this.enforceOwnership = enforceOwnership
        (seed ?: []).each { putInternal(it) }
    }

    @Override
    List<CalendarEvent> fetchEvents(Instant rangeStart, Instant rangeEnd) {
        eventsByUid.values().findAll { ev ->
            // half-open overlap with [rangeStart, rangeEnd)
            ev.start < rangeEnd && ev.end > rangeStart
        }.toSorted { a, b -> a.start <=> b.start ?: (a.uid ?: a.id) <=> (b.uid ?: b.id) }
    }

    /**
     * Global UID lookup across all seeded calendars (not managed-calendar-scoped).
     */
    @Override
    CalendarEvent findEventByUid(String uid) {
        if (!uid) {
            return null
        }
        CalendarEvent byKey = eventsByUid[uid]
        if (byKey != null) {
            return byKey
        }
        return eventsByUid.values().find { it.uid == uid || it.id == uid }
    }

    CalendarEvent getByUid(String uid) {
        findEventByUid(uid)
    }

    List<CalendarEvent> allEvents() {
        new ArrayList<>(eventsByUid.values())
    }

    @Override
    void upsertEvent(CalendarEvent event) {
        upsertCallCount++
        if (event == null) {
            throw new IllegalArgumentException('event is required')
        }
        if (enforceOwnership) {
            validateOwnedWrite(event)
        }
        if (failUpsertOnCall != null && upsertCallCount == failUpsertOnCall) {
            throw new RuntimeException(failUpsertMessage)
        }
        // External UID collision: existing non-owned event with same uid
        CalendarEvent existing = eventsByUid[event.uid]
        if (existing != null && enforceOwnership && !ManagedEventIds.isOwned(existing, managedCalendarName)) {
            rejectedWrites << [op: 'upsert', uid: event.uid, reason: 'external_uid_collision']
            throw new IllegalStateException(
                "Refusing to overwrite external/unowned event uid=${event.uid}")
        }
        upserts << event
        putInternal(event)
    }

    @Override
    void deleteOwnedEvent(String eventUid, String expectedBlockId) {
        deleteCallCount++
        if (!eventUid) {
            throw new IllegalArgumentException('eventUid is required')
        }
        if (!expectedBlockId) {
            throw new IllegalArgumentException('expectedBlockId is required')
        }
        if (failDeleteOnCall != null && deleteCallCount == failDeleteOnCall) {
            throw new RuntimeException(failDeleteMessage)
        }
        CalendarEvent existing = eventsByUid.values().find {
            it.id == eventUid || it.uid == eventUid
        }
        if (existing == null) {
            // Idempotent missing delete after ownership was expected — record attempt, no-op
            deletes << eventUid
            return
        }
        if (enforceOwnership) {
            if (managedCalendarName && existing.calendarName != managedCalendarName) {
                rejectedWrites << [op: 'delete', uid: existing.uid, reason: 'wrong_calendar']
                throw new IllegalStateException(
                    "Refusing to delete event outside managed calendar: ${existing.calendarName}")
            }
            if (!ManagedEventIds.isOwned(existing, managedCalendarName)) {
                rejectedWrites << [op: 'delete', uid: existing.uid, reason: 'unowned']
                throw new IllegalStateException(
                    "Refusing to delete external/unowned event uid=${existing.uid}")
            }
            if (!ManagedEventIds.descriptionHasBlockId(existing.description, expectedBlockId)) {
                rejectedWrites << [op: 'delete', uid: existing.uid, reason: 'block_mismatch']
                throw new IllegalStateException(
                    "Refusing to delete event uid=${existing.uid}: block metadata mismatch expected=${expectedBlockId}")
            }
        }
        deletes << eventUid
        if (existing.uid) {
            eventsByUid.remove(existing.uid)
        } else {
            eventsByUid.entrySet().removeIf { it.value.id == eventUid }
        }
    }

    void seed(CalendarEvent event) {
        putInternal(event)
    }

    /**
     * Test-only: remove without ownership checks (simulates external deletion).
     */
    void forceRemove(String eventUid) {
        eventsByUid.remove(eventUid)
        eventsByUid.entrySet().removeIf { it.value.id == eventUid || it.value.uid == eventUid }
    }

    void resetCounters() {
        upsertCallCount = 0
        deleteCallCount = 0
        failUpsertOnCall = null
        failDeleteOnCall = null
    }

    int getUpsertCallCount() {
        upsertCallCount
    }

    int getDeleteCallCount() {
        deleteCallCount
    }

    private void validateOwnedWrite(CalendarEvent event) {
        if (managedCalendarName && event.calendarName != managedCalendarName) {
            rejectedWrites << [op: 'upsert', uid: event.uid, reason: 'wrong_calendar']
            throw new IllegalStateException(
                "Refusing write outside managed calendar '${managedCalendarName}': got '${event.calendarName}'")
        }
        // AND — never OR UID/marker
        if (!ManagedEventIds.isPlannerUid(event.uid) || !ManagedEventIds.hasOwnershipMarker(event.description)) {
            rejectedWrites << [op: 'upsert', uid: event.uid, reason: 'missing_ownership']
            throw new IllegalStateException(
                "Refusing write without both planner UID and ownership marker: uid=${event.uid}")
        }
        if (!ManagedEventIds.isOwned(event, managedCalendarName)) {
            rejectedWrites << [op: 'upsert', uid: event.uid, reason: 'unowned']
            throw new IllegalStateException(
                "Refusing write of unowned event: uid=${event.uid}")
        }
    }

    private void putInternal(CalendarEvent event) {
        String key = event.uid ?: event.id
        eventsByUid[key] = event
    }
}
