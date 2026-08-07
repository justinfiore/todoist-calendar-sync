package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections

/**
 * Immutable calendar event for planner classification and availability.
 */
final class CalendarEvent {
    final String id
    final String uid
    final String title
    final String description
    final String calendarName
    final Instant start
    final Instant end
    final boolean allDay
    final EventRole role
    final String matchedRuleName
    final String classificationReason
    final int bufferBeforeMinutes
    final int bufferAfterMinutes
    final boolean unknownCalendar

    private CalendarEvent(Builder b) {
        this.id = b.id
        this.uid = b.uid
        this.title = b.title
        this.description = b.description
        this.calendarName = b.calendarName
        this.start = b.start
        this.end = b.end
        this.allDay = b.allDay
        this.role = b.role
        this.matchedRuleName = b.matchedRuleName
        this.classificationReason = b.classificationReason
        this.bufferBeforeMinutes = b.bufferBeforeMinutes
        this.bufferAfterMinutes = b.bufferAfterMinutes
        this.unknownCalendar = b.unknownCalendar
    }

    static Builder builder() {
        new Builder()
    }

    CalendarEvent withClassification(EventRole role, String matchedRuleName, String reason,
                                     int bufferBeforeMinutes = 0, int bufferAfterMinutes = 0,
                                     boolean unknownCalendar = false) {
        return builder()
            .id(id)
            .uid(uid)
            .title(title)
            .description(description)
            .calendarName(calendarName)
            .start(start)
            .end(end)
            .allDay(allDay)
            .role(role)
            .matchedRuleName(matchedRuleName)
            .classificationReason(reason)
            .bufferBeforeMinutes(bufferBeforeMinutes)
            .bufferAfterMinutes(bufferAfterMinutes)
            .unknownCalendar(unknownCalendar)
            .build()
    }

    Instant bufferedStart() {
        start.minusSeconds(bufferBeforeMinutes * 60L)
    }

    Instant bufferedEnd() {
        end.plusSeconds(bufferAfterMinutes * 60L)
    }

    long durationMinutes() {
        java.time.Duration.between(start, end).toMinutes()
    }

    static final class Builder {
        private String id
        private String uid
        private String title
        private String description
        private String calendarName
        private Instant start
        private Instant end
        private boolean allDay
        private EventRole role
        private String matchedRuleName
        private String classificationReason
        private int bufferBeforeMinutes
        private int bufferAfterMinutes
        private boolean unknownCalendar

        Builder id(String v) { this.id = v; this }
        Builder uid(String v) { this.uid = v; this }
        Builder title(String v) { this.title = v; this }
        Builder description(String v) { this.description = v; this }
        Builder calendarName(String v) { this.calendarName = v; this }
        Builder start(Instant v) { this.start = v; this }
        Builder end(Instant v) { this.end = v; this }
        Builder allDay(boolean v) { this.allDay = v; this }
        Builder role(EventRole v) { this.role = v; this }
        Builder matchedRuleName(String v) { this.matchedRuleName = v; this }
        Builder classificationReason(String v) { this.classificationReason = v; this }
        Builder bufferBeforeMinutes(int v) { this.bufferBeforeMinutes = v; this }
        Builder bufferAfterMinutes(int v) { this.bufferAfterMinutes = v; this }
        Builder unknownCalendar(boolean v) { this.unknownCalendar = v; this }

        CalendarEvent build() {
            if (!id) {
                throw new IllegalArgumentException('CalendarEvent id is required')
            }
            if (title == null) {
                throw new IllegalArgumentException('CalendarEvent title is required')
            }
            if (!calendarName) {
                throw new IllegalArgumentException('CalendarEvent calendarName is required')
            }
            if (start == null || end == null) {
                throw new IllegalArgumentException('CalendarEvent start and end are required')
            }
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException('CalendarEvent end must be after start')
            }
            if (bufferBeforeMinutes < 0 || bufferAfterMinutes < 0) {
                throw new IllegalArgumentException('Buffers must be non-negative')
            }
            return new CalendarEvent(this)
        }
    }
}
