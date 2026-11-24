package todoistcaldavsync.planner.sync

import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.adapters.TodoistScheduleGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.recurrence.GuardedRecurringDueUpdate

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Independently verified steps. The caller journals each step before using this write port. */
final class GuardedScheduleUpdate {
    private final TodoistScheduleGateway gateway
    private final PlannerConfig config

    GuardedScheduleUpdate(TodoistScheduleGateway gateway, PlannerConfig config) {
        this.gateway = gateway
        this.config = config
    }

    Task due(Task expected, Instant target, String commandId, boolean allowNewDue = false) {
        Task live = preflight(expected)
        if (target == null || live.allDayDue || (live.todoistDue == null && !allowNewDue)) {
            throw new IllegalStateException('Timed schedule required; date-only conversion is not authorized')
        }
        if (live.dueTime == target) return live
        Task verified
        if (live.todoistDue?.recurring) {
            // A floating recurrence stays floating in the configured account timezone, including DST.
            String replacement = live.todoistDue.timezone == null ?
                DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(target.atZone(config.timezone).toLocalDateTime()) : target.toString()
            verified = GuardedRecurringDueUpdate.update(gateway, config, live.id, replacement, commandId, live)
        } else {
            gateway.updateTaskDue(live.id, target.toString())
            verified = readActive(live.id)
        }
        if (verified.dueTime != target || verified.allDayDue ||
            verified.nativeDuration != live.nativeDuration || !unchanged(live, verified)) {
            throw new IllegalStateException('Due write postconditions failed; reconciliation required')
        }
        verified
    }

    Task duration(Task expected, long minutes, String commandId) {
        if (minutes < 1 || minutes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException('Native duration must be positive representable whole minutes')
        }
        Task live = preflight(expected)
        if (live.todoistDue == null || live.allDayDue) {
            throw new IllegalStateException('Native-duration write requires an active timed schedule')
        }
        Duration target = Duration.ofMinutes(minutes)
        if (live.nativeDuration == target) return live
        def result = gateway.updateTaskDuration(live.id, (int) minutes, commandId)
        if (result == null || result.state == TodoistCommandState.REJECTED) {
            throw new IllegalStateException('Native-duration command rejected; reconciliation required')
        }
        // Ambiguous commands are never replayed: a fresh observation may prove this step instead.
        Task verified = readActive(live.id)
        if (verified.nativeDuration != target || verified.todoistDue.snapshotMap() != live.todoistDue.snapshotMap() ||
            !unchanged(live, verified)) {
            throw new IllegalStateException('Native-duration postconditions failed; reconciliation required')
        }
        verified
    }

    Task preflight(Task expected) {
        if (expected == null) throw new IllegalArgumentException('Verified task baseline required')
        Task live = readActive(expected.id)
        if (!unchanged(expected, live) || live.nativeDuration != expected.nativeDuration ||
            live.effectiveDuration != expected.effectiveDuration ||
            live.todoistDue?.snapshotMap() != expected.todoistDue?.snapshotMap() ||
            live.updatedAt != expected.updatedAt) {
            throw new IllegalStateException('Task changed since verified baseline; reconciliation required')
        }
        live
    }

    Task readActive(String id) {
        Map raw = gateway.fetchTask(id)
        if (raw == null || raw.checked == true || raw.checked == 1 || raw.is_completed == true ||
            raw.is_deleted == true || raw.is_deleted == 1 || raw.completed_at != null) {
            throw new IllegalStateException('Task unavailable or no longer active; reconcile lifecycle first')
        }
        Task task = Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone)
        if (task.id != id) throw new IllegalStateException('Task read returned a different identity')
        task
    }

    private static boolean unchanged(Task before, Task after) {
        before.id == after.id && before.content == after.content && before.projectId == after.projectId &&
            before.deadline == after.deadline && before.deadlineDate == after.deadlineDate &&
            before.labels.toSet() == after.labels.toSet() && before.priority == after.priority &&
            before.description == after.description && before.completedCount == after.completedCount &&
            (before.todoistDue?.recurrenceFingerprintInput() ?: '') == (after.todoistDue?.recurrenceFingerprintInput() ?: '')
    }
}
