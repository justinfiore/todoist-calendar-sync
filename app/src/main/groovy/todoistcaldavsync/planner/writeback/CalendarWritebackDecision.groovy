package todoistcaldavsync.planner.writeback

import java.time.Instant
import org.apache.commons.codec.binary.Base32
import todoistcaldavsync.planner.domain.*

/** Pure, shared conflict and candidate rules. Missing or tied clocks never authorize a write. */
final class CalendarWritebackDecision {
    static final String UPDATE_TODOIST = 'update_todoist'
    static final String GUARDED_RECURRING = 'guarded_recurring'
    static final String UPDATE_CALENDAR = 'update_calendar'
    static final String NOOP = 'noop'
    static final String SKIP = 'record_and_skip'

    static String decide(Instant due, Instant taskUpdated, Instant start, Instant modified,
                         boolean recurring, boolean echo = false) {
        if (due == start) return NOOP
        if (due == null || start == null || taskUpdated == null || modified == null || taskUpdated == modified) return echo ? NOOP : SKIP
        if (taskUpdated.isAfter(modified)) return UPDATE_CALENDAR
        // An echo blocks calendar-to-Todoist feedback, not a newer Todoist edit.
        if (echo) return NOOP
        recurring ? GUARDED_RECURRING : UPDATE_TODOIST
    }

    static boolean enabled(Map root) {
        if (!root.containsKey('calendarWriteback')) return false
        def section = root.calendarWriteback
        if (!(section instanceof Map) || (section.containsKey('enabled') && !(section.enabled instanceof Boolean))) {
            throw new IllegalArgumentException('calendarWriteback.enabled must be a boolean')
        }
        section.enabled == true
    }

    static String legacyUid(String userId, String taskId) {
        new Base32(true).encodeAsString("${userId}-${taskId}".getBytes('UTF-8')).replace('=', '').toLowerCase(Locale.ROOT)
    }

    static boolean legacyCandidate(CalendarEvent event, String userId, String taskId, Set<String> calendars) {
        event != null && calendars.contains(event.calendarName) && event.uid == legacyUid(userId, taskId)
    }

    static boolean plannerCandidate(CalendarEvent event, String outputCalendar, Collection<AppliedMapping> mappings) {
        ManagedEventIds.isOwned(event, outputCalendar) &&
            mappings.findAll { it.eventUid == event.uid }.collect { it.taskId }.unique().size() == 1
    }

    static boolean echo(CalendarEvent event, Map written) {
        if (!written) return false
        if (written.etag && event.etag == written.etag) return true
        event.start?.toString() == written.start && event.end?.toString() == written.end &&
            event.lastModified != null && written.writtenAt &&
            !event.lastModified.isAfter(Instant.parse(written.writtenAt.toString()))
    }
}
