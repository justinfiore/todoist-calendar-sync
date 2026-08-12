package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections

/**
 * Immutable proposed or applied plan change (Phase 1: diagnostic/proposal only).
 */
final class PlanChange {
    final String id
    final String type
    final String taskId
    final Instant previousStart
    final Instant newStart
    final Instant previousEnd
    final Instant newEnd
    final String reason
    final Map<String, Object> metadata

    private PlanChange(Builder b) {
        this.id = b.id
        this.type = b.type
        this.taskId = b.taskId
        this.previousStart = b.previousStart
        this.newStart = b.newStart
        this.previousEnd = b.previousEnd
        this.newEnd = b.newEnd
        this.reason = b.reason
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    static final class Builder {
        private String id
        private String type
        private String taskId
        private Instant previousStart
        private Instant newStart
        private Instant previousEnd
        private Instant newEnd
        private String reason
        private Map<String, Object> metadata = [:]

        Builder id(String v) { this.id = v; this }
        Builder type(String v) { this.type = v; this }
        Builder taskId(String v) { this.taskId = v; this }
        Builder previousStart(Instant v) { this.previousStart = v; this }
        Builder newStart(Instant v) { this.newStart = v; this }
        Builder previousEnd(Instant v) { this.previousEnd = v; this }
        Builder newEnd(Instant v) { this.newEnd = v; this }
        Builder reason(String v) { this.reason = v; this }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        PlanChange build() {
            if (!id) {
                throw new IllegalArgumentException('PlanChange id is required')
            }
            if (!type) {
                throw new IllegalArgumentException('PlanChange type is required')
            }
            if (!reason) {
                throw new IllegalArgumentException('PlanChange reason is required')
            }
            return new PlanChange(this)
        }
    }
}
