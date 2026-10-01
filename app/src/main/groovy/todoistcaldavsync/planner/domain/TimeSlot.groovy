package todoistcaldavsync.planner.domain

import java.time.Duration
import java.time.Instant
import java.util.Collections

/**
 * Immutable availability slot produced by AvailabilityCalculator.
 */
final class TimeSlot {
    final Instant start
    final Instant end
    final boolean softBlocked
    final List<String> softBlockerEventIds
    final List<String> softBlockerReasons
    final String windowName

    private TimeSlot(Builder b) {
        this.start = b.start
        this.end = b.end
        this.softBlocked = b.softBlocked
        this.softBlockerEventIds = Collections.unmodifiableList(new ArrayList<>(b.softBlockerEventIds ?: []))
        this.softBlockerReasons = Collections.unmodifiableList(new ArrayList<>(b.softBlockerReasons ?: []))
        this.windowName = b.windowName
    }

    static Builder builder() {
        new Builder()
    }

    Duration duration() {
        Duration.between(start, end)
    }

    long durationMinutes() {
        duration().toMinutes()
    }

    static final class Builder {
        private Instant start
        private Instant end
        private boolean softBlocked
        private List<String> softBlockerEventIds = []
        private List<String> softBlockerReasons = []
        private String windowName

        Builder start(Instant v) { this.start = v; this }
        Builder end(Instant v) { this.end = v; this }
        Builder softBlocked(boolean v) { this.softBlocked = v; this }
        Builder softBlockerEventIds(List<String> v) { this.softBlockerEventIds = v ?: []; this }
        Builder softBlockerReasons(List<String> v) { this.softBlockerReasons = v ?: []; this }
        Builder windowName(String v) { this.windowName = v; this }

        TimeSlot build() {
            if (start == null || end == null) {
                throw new IllegalArgumentException('TimeSlot start and end are required')
            }
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException('TimeSlot end must be after start')
            }
            return new TimeSlot(this)
        }
    }
}
