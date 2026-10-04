package todoistcaldavsync.planner.domain

import java.util.Collections

/**
 * Task left out of the preview plan with an explicit reason.
 */
final class UnscheduledTask {
    final Task task
    final String reason
    final String code
    final Map<String, Object> metadata

    UnscheduledTask(Task task, String reason, String code = 'unscheduled', Map metadata = [:]) {
        if (task == null) {
            throw new IllegalArgumentException('task is required')
        }
        if (!reason) {
            throw new IllegalArgumentException('reason is required')
        }
        this.task = task
        this.reason = reason
        this.code = code ?: 'unscheduled'
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata ?: [:]))
    }
}
