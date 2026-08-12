package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.CalendarEvent

import java.time.Instant

/**
 * Read-only calendar gateway for Phase 1 capacity reporting.
 */
interface CalendarGateway {
    /**
     * Fetch events in [rangeStart, rangeEnd). Must not mutate calendars.
     */
    List<CalendarEvent> fetchEvents(Instant rangeStart, Instant rangeEnd)
}

/**
 * Read-only calendar access used by apply/reconcile paths.
 * <p>
 * <b>UID lookup contract:</b> {@link #findEventByUid(String)} MUST search
 * <em>globally across all calendars accessible to the integration</em>, not only
 * the managed/output calendar. Wrong-calendar and cross-calendar UID collisions
 * are safety-critical: a same-UID event on any accessible calendar must be found
 * so apply can refuse overwrite rather than treating the UID as missing.
 * <p>
 * Production CalDAV (or other) implementations in later phases must honor this
 * global lookup. If a backend cannot guarantee all-calendar UID search, it must
 * expose an explicit all-calendar ownership/collision query instead of a
 * single-calendar-scoped {@code findEventByUid}.
 */
interface CalendarReadGateway extends CalendarGateway {
    /**
     * Locate an event by UID (or id) across <em>all</em> accessible calendars.
     * Returns {@code null} only when no accessible calendar contains the UID.
     * Must not mutate calendars.
     *
     * @param uid event UID (or stable id) to resolve
     * @return matching event from any accessible calendar, or null if absent
     */
    CalendarEvent findEventByUid(String uid)
}

/**
 * Write surface deliberately separate — Phase 1 must not wire this into PlannerCli.
 * Deletion requires expected ownership metadata; raw ID-only delete is not part of the API.
 */
interface CalendarWriteGateway {
    void upsertEvent(CalendarEvent event)

    /**
     * Delete a managed event only after live read confirms planner ownership and
     * expected block metadata. Never deletes external/unowned events.
     *
     * @param eventUid          UID of the event to delete
     * @param expectedBlockId   block id that must appear in ownership metadata
     */
    void deleteOwnedEvent(String eventUid, String expectedBlockId)
}
