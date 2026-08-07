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

interface CalendarReadGateway extends CalendarGateway {
    // marker: read-only
}

/**
 * Write surface deliberately separate — Phase 1 must not wire this into PlannerCli.
 */
interface CalendarWriteGateway {
    void upsertEvent(CalendarEvent event)
    void deleteEvent(String eventId)
}
