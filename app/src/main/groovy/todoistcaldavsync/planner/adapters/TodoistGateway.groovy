package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.recurrence.TodoistDue

import java.time.LocalDate

/**
 * Read-only Todoist gateway for Phase 1 capacity reporting.
 * Mutation methods intentionally absent from the read interface.
 */
interface TodoistGateway {
    /**
     * Fetch tasks visible to the planner adapter (raw or already normalized maps).
     * Implementations must not mutate Todoist.
     */
    List<Map> fetchTasks()
}

/**
 * Narrow read-only view used by capacity reporting. Confirms no write surface.
 */
interface TodoistReadGateway extends TodoistGateway {
    // marker: read-only
}

/**
 * Write surface deliberately separate — Phase 1 must not wire this into PlannerCli.
 */
interface TodoistWriteGateway {
    void updateTaskDue(String taskId, String dueDateTimeIso)
    void updateTaskDeadline(String taskId, String deadlineIso)
}

/** Guarded recurrence lifecycle boundary; ordinary planner code must not use it. */
interface TodoistLifecycleGateway extends TodoistReadGateway, TodoistWriteGateway {
    Map fetchTask(String taskId)
    TodoistCommandResult updateRecurringDue(String taskId, TodoistDue liveDue,
                                             String replacementDate, String commandId)
    TodoistCommandResult updateLifecycleFields(LifecycleMutation mutation)
    TodoistSyncPage syncItems(String syncToken)
}

final class LifecycleMutation {
    final String taskId
    final String commandId
    final String deadlineDate
    final List<String> labels
    final String description

    private LifecycleMutation(String taskId, String commandId, String deadlineDate,
                              List<String> labels, String description) {
        if (!taskId || !commandId) throw new IllegalArgumentException('taskId and commandId are required')
        if (deadlineDate != null) LocalDate.parse(deadlineDate)
        if (deadlineDate == null && labels == null && description == null) {
            throw new IllegalArgumentException('Lifecycle mutation requires at least one field')
        }
        this.taskId = taskId; this.commandId = commandId; this.deadlineDate = deadlineDate
        this.labels = labels == null ? null : Collections.unmodifiableList(new ArrayList<>(labels))
        this.description = description
    }

    static LifecycleMutation validated(String taskId, String commandId, String deadlineDate = null,
                                       List<String> labels = null, String description = null) {
        new LifecycleMutation(taskId, commandId, deadlineDate, labels, description)
    }
}

enum TodoistCommandState { COMMITTED, REJECTED, AMBIGUOUS }

final class TodoistCommandResult {
    final TodoistCommandState state
    final String commandId
    final String errorCode
    TodoistCommandResult(TodoistCommandState state, String commandId, String errorCode = null) {
        this.state = state; this.commandId = commandId; this.errorCode = errorCode
    }
}

final class TodoistSyncPage {
    final String syncToken
    final boolean fullSync
    final String fullSyncDateUtc
    final List<Map> items
    TodoistSyncPage(String syncToken, boolean fullSync, String fullSyncDateUtc, List<Map> items) {
        if (!syncToken) throw new IllegalArgumentException('Todoist Sync replacement token is required')
        this.syncToken = syncToken; this.fullSync = fullSync; this.fullSyncDateUtc = fullSyncDateUtc
        this.items = Collections.unmodifiableList((items ?: []).collect {
            Collections.unmodifiableMap(new LinkedHashMap(it))
        })
    }
}
