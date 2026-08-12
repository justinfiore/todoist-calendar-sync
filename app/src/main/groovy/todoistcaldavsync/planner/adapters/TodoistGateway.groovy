package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.Task

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
