package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections

/**
 * Immutable plan snapshot. Phase 1 uses this for capacity diagnostics structure.
 */
final class Plan {
    final String id
    final int version
    final Instant createdAt
    final String mode
    final List<Task> tasks
    final List<TimeSlot> slots
    final List<PlanChange> changes
    final List<PlanningExplanation> explanations
    final Map<String, Object> metrics

    private Plan(Builder b) {
        this.id = b.id
        this.version = b.version
        this.createdAt = b.createdAt
        this.mode = b.mode
        this.tasks = Collections.unmodifiableList(new ArrayList<>(b.tasks ?: []))
        this.slots = Collections.unmodifiableList(new ArrayList<>(b.slots ?: []))
        this.changes = Collections.unmodifiableList(new ArrayList<>(b.changes ?: []))
        this.explanations = Collections.unmodifiableList(new ArrayList<>(b.explanations ?: []))
        this.metrics = Collections.unmodifiableMap(new LinkedHashMap<>(b.metrics ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    static final class Builder {
        private String id
        private int version = 1
        private Instant createdAt = Instant.now()
        private String mode = 'preview'
        private List<Task> tasks = []
        private List<TimeSlot> slots = []
        private List<PlanChange> changes = []
        private List<PlanningExplanation> explanations = []
        private Map<String, Object> metrics = [:]

        Builder id(String v) { this.id = v; this }
        Builder version(int v) { this.version = v; this }
        Builder createdAt(Instant v) { this.createdAt = v; this }
        Builder mode(String v) { this.mode = v; this }
        Builder tasks(List<Task> v) { this.tasks = v ?: []; this }
        Builder slots(List<TimeSlot> v) { this.slots = v ?: []; this }
        Builder changes(List<PlanChange> v) { this.changes = v ?: []; this }
        Builder explanations(List<PlanningExplanation> v) { this.explanations = v ?: []; this }
        Builder metrics(Map<String, Object> v) { this.metrics = v ?: [:]; this }

        Plan build() {
            if (!id) {
                throw new IllegalArgumentException('Plan id is required')
            }
            if (version < 1) {
                throw new IllegalArgumentException('Plan version must be >= 1')
            }
            if (createdAt == null) {
                throw new IllegalArgumentException('Plan createdAt is required')
            }
            if (!mode) {
                throw new IllegalArgumentException('Plan mode is required')
            }
            return new Plan(this)
        }
    }
}
