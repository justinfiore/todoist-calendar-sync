package todoistcaldavsync.planner.recurrence

import todoistcaldavsync.planner.domain.Task

enum LifecycleState {
    UNMARKED, INITIALIZED, ADVANCED, USER_EDITED, RECURRENCE_REMOVED,
    SENTINEL_MISSING, MARKER_MISSING, UNSUPPORTED, COUNT_REGRESSION
}

final class LifecycleClassification {
    final LifecycleState state
    final boolean writable
    final long skippedOccurrences
    final String reason
    LifecycleClassification(LifecycleState state, boolean writable, String reason = null,
                            long skippedOccurrences = 0) {
        this.state = state; this.writable = writable; this.reason = reason
        this.skippedOccurrences = skippedOccurrences
    }
}

/** Pure fail-closed classification of a live task against portable marker state. */
final class LifecycleClassifier {
    LifecycleClassification classify(Task live, String sentinelLabel) {
        if (live == null) return result(LifecycleState.UNSUPPORTED, 'task_absent')
        MarkerRead read = new LifecycleMarkerCodec().read(live.description)
        boolean sentinel = live.labels.any { it.equalsIgnoreCase(sentinelLabel ?: '') }
        if (read.status == MarkerStatus.UNSAFE || read.status == MarkerStatus.UNSUPPORTED) {
            return result(LifecycleState.UNSUPPORTED, read.reason)
        }
        if (read.status == MarkerStatus.ABSENT) {
            return sentinel ? result(LifecycleState.MARKER_MISSING, 'sentinel_without_marker') :
                new LifecycleClassification(LifecycleState.UNMARKED, true)
        }
        if (!sentinel) return result(LifecycleState.SENTINEL_MISSING, 'marker_without_sentinel')
        LifecycleMarker marker = read.marker
        if (marker.taskId != live.id) return result(LifecycleState.UNSUPPORTED, 'series_identity_mismatch')
        if (live.todoistDue == null || !live.todoistDue.recurring) {
            return result(LifecycleState.RECURRENCE_REMOVED, 'recurrence_removed')
        }
        if (!live.todoistDue.completeRecurrenceTuple()) return result(LifecycleState.UNSUPPORTED, 'incomplete_recurrence')
        if (live.completedCount < marker.completedCount) return result(LifecycleState.COUNT_REGRESSION, 'count_regression')
        if (live.completedCount > marker.completedCount) {
            return new LifecycleClassification(LifecycleState.ADVANCED, true, null,
                live.completedCount - marker.completedCount - 1L)
        }
        String fingerprint = LifecycleSupport.fingerprint(live.todoistDue)
        boolean knownDue = live.todoistDue.date == marker.lastVerifiedDue ||
            marker.lastPlannerDue != null && live.todoistDue.date == marker.lastPlannerDue
        if (fingerprint != marker.recurrenceFingerprint || !knownDue ||
            live.deadlineDate != marker.deadlineDate) {
            return result(LifecycleState.USER_EDITED, 'live_precondition_drift')
        }
        new LifecycleClassification(LifecycleState.INITIALIZED, true)
    }

    private static LifecycleClassification result(LifecycleState state, String reason) {
        new LifecycleClassification(state, false, reason)
    }
}
