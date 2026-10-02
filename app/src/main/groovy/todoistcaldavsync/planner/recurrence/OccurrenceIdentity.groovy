package todoistcaldavsync.planner.recurrence

/** Stable native series/occurrence identity. */
final class OccurrenceIdentity implements Comparable<OccurrenceIdentity> {
    final String taskId
    final long completedCount

    OccurrenceIdentity(String taskId, long completedCount) {
        if (!taskId) throw new IllegalArgumentException('taskId is required')
        if (completedCount < 0) throw new IllegalArgumentException('completed_count must be non-negative')
        this.taskId = taskId
        this.completedCount = completedCount
    }

    String seriesKey() { taskId }
    String occurrenceKey() { "${taskId}:${completedCount}" }

    long advancementFrom(OccurrenceIdentity previous) {
        if (previous == null || previous.taskId != taskId) {
            throw new IllegalArgumentException('Occurrence identities must belong to the same series')
        }
        long delta = completedCount - previous.completedCount
        if (delta < 0) throw new IllegalStateException('completed_count regression')
        delta
    }

    @Override int compareTo(OccurrenceIdentity other) {
        (taskId <=> other.taskId) ?: (completedCount <=> other.completedCount)
    }

    @Override boolean equals(Object other) {
        other instanceof OccurrenceIdentity && taskId == other.taskId && completedCount == other.completedCount
    }

    @Override int hashCode() { 31 * taskId.hashCode() + Long.hashCode(completedCount) }
    @Override String toString() { occurrenceKey() }
}
