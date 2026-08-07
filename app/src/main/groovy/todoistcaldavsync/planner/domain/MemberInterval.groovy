package todoistcaldavsync.planner.domain

import java.time.Duration
import java.time.Instant

/**
 * Explicit per-member placement inside a multi-task focus block.
 * Ordering of member intervals matches {@link ScheduledBlock#taskIds}.
 */
final class MemberInterval {
    final String taskId
    final Instant start
    final Instant end

    MemberInterval(String taskId, Instant start, Instant end) {
        if (!taskId) {
            throw new IllegalArgumentException('MemberInterval taskId is required')
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException('MemberInterval start and end are required')
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException('MemberInterval end must be after start')
        }
        this.taskId = taskId
        this.start = start
        this.end = end
    }

    Duration duration() {
        Duration.between(start, end)
    }

    long durationMinutes() {
        duration().toMinutes()
    }

    Map<String, Object> toMap() {
        [taskId: taskId, start: start.toString(), end: end.toString()]
    }

    static MemberInterval fromMap(Map m) {
        if (m == null) {
            throw new IllegalArgumentException('MemberInterval map is required')
        }
        if (m.taskId == null || m.start == null || m.end == null) {
            throw new IllegalArgumentException("MemberInterval requires taskId/start/end, got: ${m}")
        }
        return new MemberInterval(
            m.taskId.toString(),
            Instant.parse(m.start.toString()),
            Instant.parse(m.end.toString())
        )
    }
}
