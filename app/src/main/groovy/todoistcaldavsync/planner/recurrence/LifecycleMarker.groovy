package todoistcaldavsync.planner.recurrence

import java.time.LocalDate

/** Portable v1 lifecycle authority stored in the Todoist description suffix. */
final class LifecycleMarker {
    static final int VERSION = 1

    final String taskId
    final long completedCount
    final String deadlineMode
    final String deadlineSource
    final LocalDate deadlineDate
    final LocalDate pendingLegacySourceDate
    final String lastVerifiedDue
    final String recurrenceFingerprint
    final String lastPlannerDue
    final long markerGeneration
    final String lastCommandId

    LifecycleMarker(Map values) {
        taskId = values.taskId?.toString()
        completedCount = values.completedCount as long
        deadlineMode = values.deadlineMode?.toString()
        deadlineSource = values.deadlineSource?.toString()
        deadlineDate = date(values.deadlineDate)
        pendingLegacySourceDate = date(values.pendingLegacySourceDate)
        lastVerifiedDue = values.lastVerifiedDue?.toString()
        recurrenceFingerprint = values.recurrenceFingerprint?.toString()
        lastPlannerDue = values.lastPlannerDue?.toString()
        markerGeneration = values.markerGeneration as long
        lastCommandId = values.lastCommandId?.toString()
        if (!taskId || completedCount < 0 || markerGeneration < 0 || !deadlineMode ||
            !deadlineSource || !recurrenceFingerprint || !lastCommandId) {
            throw new IllegalArgumentException('Lifecycle marker fields are incomplete')
        }
    }

    OccurrenceIdentity occurrenceIdentity() { new OccurrenceIdentity(taskId, completedCount) }

    Map<String, Object> canonicalMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>()
        out.schema_version = VERSION
        out.owner = 'smartplanner'
        out.task_id = taskId
        out.completed_count = completedCount
        out.deadline_mode = deadlineMode
        out.deadline_source = deadlineSource
        out.deadline_date = deadlineDate?.toString()
        out.pending_legacy_source_date = pendingLegacySourceDate?.toString()
        out.last_verified_due = lastVerifiedDue
        out.recurrence_fingerprint = recurrenceFingerprint
        out.last_planner_due = lastPlannerDue
        out.marker_generation = markerGeneration
        out.last_command_id = lastCommandId
        out
    }

    private static LocalDate date(Object value) {
        value == null || value.toString().isEmpty() ? null : LocalDate.parse(value.toString())
    }
}
