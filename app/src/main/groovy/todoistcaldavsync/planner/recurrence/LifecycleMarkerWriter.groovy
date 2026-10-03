package todoistcaldavsync.planner.recurrence

import todoistcaldavsync.planner.adapters.LifecycleMutation
import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.adapters.TodoistLifecycleGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task

/** Bounded whole-description write/rebase with stable command identity. */
final class LifecycleMarkerWriter {
    static final int MAX_ATTEMPTS = 3
    private final TodoistLifecycleGateway gateway
    private final PlannerConfig config
    private final LifecycleMarkerCodec codec = new LifecycleMarkerCodec()

    LifecycleMarkerWriter(TodoistLifecycleGateway gateway, PlannerConfig config) {
        if (gateway == null || config == null) throw new IllegalArgumentException('gateway and config are required')
        this.gateway = gateway; this.config = config
    }

    Task write(Task expected, LifecycleMarker intended, String deadlineDate = null,
               List<String> intendedLabels = null) {
        if (expected == null || intended == null || intended.taskId != expected.id) {
            throw new IllegalArgumentException('expected task and matching marker are required')
        }
        String prefixSource = expected.description
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String merged = codec.merge(prefixSource, intended)
            def outcome = gateway.updateLifecycleFields(LifecycleMutation.validated(
                expected.id, intended.lastCommandId, deadlineDate, intendedLabels, merged))
            if (outcome.state == TodoistCommandState.REJECTED) {
                throw new LifecycleConflictException('Todoist rejected lifecycle marker write')
            }
            Task live = read(expected.id)
            if (live != null && live.description == merged && preconditionsMatch(expected, live,
                deadlineDate, intendedLabels)) return live

            MarkerRead marker = codec.read(live?.description)
            if (live == null || !transitionPreconditionsMatch(expected, live, deadlineDate, intendedLabels) ||
                marker.status == MarkerStatus.UNSAFE || marker.status == MarkerStatus.UNSUPPORTED ||
                !markerIsExpectedOrIntended(marker, expected.lifecycleMarker, intended)) {
                throw new LifecycleConflictException('Lifecycle state changed during marker write')
            }
            if (attempt == MAX_ATTEMPTS) {
                throw new LifecycleConflictException('Lifecycle marker rebase retries exhausted')
            }
            prefixSource = live.description
        }
        throw new LifecycleConflictException('Lifecycle marker write failed closed')
    }

    private Task read(String id) {
        Map raw = gateway.fetchTask(id)
        raw == null ? null : Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone)
    }

    private boolean preconditionsMatch(Task expected, Task live, String deadlineDate, List<String> labels) {
        basePreconditionsMatch(expected, live) &&
            live.deadlineDate?.toString() == (deadlineDate ?: expected.deadlineDate?.toString()) &&
            live.labels.toSet() == (labels == null ? expected.labels.toSet() : labels.toSet())
    }

    private static boolean transitionPreconditionsMatch(Task expected, Task live, String deadlineDate,
                                                        List<String> labels) {
        if (!basePreconditionsMatch(expected, live)) return false
        Set expectedLabels = expected.labels.toSet()
        Set targetLabels = labels == null ? expectedLabels : labels.toSet()
        String expectedDeadline = expected.deadlineDate?.toString()
        String targetDeadline = deadlineDate ?: expectedDeadline
        (live.labels.toSet() == expectedLabels || live.labels.toSet() == targetLabels) &&
            (live.deadlineDate?.toString() == expectedDeadline || live.deadlineDate?.toString() == targetDeadline)
    }

    private static boolean markerIsExpectedOrIntended(MarkerRead read, LifecycleMarker expected,
                                                       LifecycleMarker intended) {
        if (read.status == MarkerStatus.ABSENT) return expected == null
        if (read.status != MarkerStatus.VALID) return false
        LifecycleMarker actual = read.marker
        sameCommand(actual, intended) || expected != null && sameCommand(actual, expected)
    }

    private static boolean sameCommand(LifecycleMarker a, LifecycleMarker b) {
        a?.markerGeneration == b?.markerGeneration && a?.lastCommandId == b?.lastCommandId
    }

    private static boolean basePreconditionsMatch(Task expected, Task live) {
        expected.completedCount == live.completedCount &&
            expected.todoistDue?.date == live.todoistDue?.date &&
            expected.todoistDue?.recurrenceFingerprintInput() == live.todoistDue?.recurrenceFingerprintInput()
    }
}

final class LifecycleConflictException extends RuntimeException {
    LifecycleConflictException(String message) { super(message) }
}
