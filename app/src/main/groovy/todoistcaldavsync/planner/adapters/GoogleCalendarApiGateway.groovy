package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.CalendarProviderConfig
import todoistcaldavsync.planner.domain.CalendarEvent

import java.time.Instant
import java.time.ZoneId

/**
 * Compile-time composition seam for the selected Google provider.
 * OAuth and Calendar API behavior deliberately belong to later task groups;
 * this boundary performs no credential resolution, I/O, or provider calls.
 */
final class GoogleCalendarApiGateway implements CalendarReadGateway, CalendarWriteGateway {
    final CalendarProviderConfig.GoogleCalendarApiConfig config
    final String managedCalendarName
    final ZoneId timezone

    GoogleCalendarApiGateway(Map options) {
        if (!(options?.config instanceof CalendarProviderConfig.GoogleCalendarApiConfig)) {
            throw new IllegalArgumentException('validated Google calendar provider config is required')
        }
        this.config = options.config as CalendarProviderConfig.GoogleCalendarApiConfig
        this.managedCalendarName = options.managedCalendarName?.toString()
        this.timezone = options.timezone instanceof ZoneId ? options.timezone as ZoneId :
            ZoneId.of((options.timezone ?: 'UTC').toString())
        if (!managedCalendarName || !config.calendars.any {
            it.name == managedCalendarName && it.role == 'managed_output'
        }) {
            throw new IllegalArgumentException('managed calendar must match the configured Google managed_output mapping')
        }
    }

    @Override
    List<CalendarEvent> fetchEvents(Instant rangeStart, Instant rangeEnd) {
        throw unavailable()
    }

    @Override
    CalendarEvent findEventByUid(String uid) {
        throw unavailable()
    }

    @Override
    void upsertEvent(CalendarEvent event) {
        throw unavailable()
    }

    @Override
    void deleteOwnedEvent(String eventUid, String expectedBlockId) {
        throw unavailable()
    }

    private static UnsupportedOperationException unavailable() {
        new UnsupportedOperationException('Google Calendar API operations are not implemented in task group 1')
    }
}
