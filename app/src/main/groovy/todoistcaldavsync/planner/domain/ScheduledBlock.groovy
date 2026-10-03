package todoistcaldavsync.planner.domain

import java.time.Duration
import java.time.Instant
import java.util.Collections

/**
 * Immutable scheduled placement: single task or multi-task project focus block.
 * Multi-task focus blocks carry deterministic {@link #memberIntervals} in taskIds order.
 */
final class ScheduledBlock {
    final String id
    final Instant start
    final Instant end
    final List<String> taskIds
    final List<MemberInterval> memberIntervals
    final String projectId
    final String projectName
    final String title
    final boolean focusBlock
    final boolean frozen
    final boolean manualOverride
    final String reason
    final Map<String, Object> metadata

    private ScheduledBlock(Builder b) {
        this.id = b.id
        this.start = b.start
        this.end = b.end
        this.taskIds = Collections.unmodifiableList(new ArrayList<>(b.taskIds ?: []))
        this.memberIntervals = Collections.unmodifiableList(new ArrayList<>(b.memberIntervals ?: []))
        this.projectId = b.projectId
        this.projectName = b.projectName
        this.title = b.title
        this.focusBlock = b.focusBlock
        this.frozen = b.frozen
        this.manualOverride = b.manualOverride
        this.reason = b.reason
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    Duration duration() {
        Duration.between(start, end)
    }

    long durationMinutes() {
        duration().toMinutes()
    }

    /**
     * Interval for a member task. Prefer explicit memberIntervals; otherwise whole block.
     */
    MemberInterval intervalFor(String taskId) {
        def found = memberIntervals.find { it.taskId == taskId }
        if (found != null) {
            return found
        }
        if (taskIds.contains(taskId)) {
            return new MemberInterval(taskId, start, end)
        }
        return null
    }

    static final class Builder {
        private String id
        private Instant start
        private Instant end
        private List<String> taskIds = []
        private List<MemberInterval> memberIntervals = []
        private String projectId
        private String projectName
        private String title
        private boolean focusBlock
        private boolean frozen
        private boolean manualOverride
        private String reason
        private Map<String, Object> metadata = [:]

        Builder id(String v) { this.id = v; this }
        Builder start(Instant v) { this.start = v; this }
        Builder end(Instant v) { this.end = v; this }
        Builder taskIds(List<String> v) { this.taskIds = v ?: []; this }
        Builder memberIntervals(List<MemberInterval> v) { this.memberIntervals = v ?: []; this }
        Builder projectId(String v) { this.projectId = v; this }
        Builder projectName(String v) { this.projectName = v; this }
        Builder title(String v) { this.title = v; this }
        Builder focusBlock(boolean v) { this.focusBlock = v; this }
        Builder frozen(boolean v) { this.frozen = v; this }
        Builder manualOverride(boolean v) { this.manualOverride = v; this }
        Builder reason(String v) { this.reason = v; this }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        ScheduledBlock build() {
            if (!id) {
                throw new IllegalArgumentException('ScheduledBlock id is required')
            }
            if (start == null || end == null) {
                throw new IllegalArgumentException('ScheduledBlock start and end are required')
            }
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException('ScheduledBlock end must be after start')
            }
            if (!taskIds) {
                throw new IllegalArgumentException('ScheduledBlock taskIds must not be empty')
            }
            if (!title) {
                throw new IllegalArgumentException('ScheduledBlock title is required')
            }
            if (memberIntervals) {
                if (memberIntervals.size() != taskIds.size()) {
                    throw new IllegalArgumentException(
                        "ScheduledBlock memberIntervals size (${memberIntervals.size()}) must match taskIds (${taskIds.size()})")
                }
                for (int i = 0; i < taskIds.size(); i++) {
                    if (memberIntervals[i].taskId != taskIds[i]) {
                        throw new IllegalArgumentException(
                            "ScheduledBlock memberIntervals order must match taskIds at index ${i}")
                    }
                }
                if (memberIntervals[0].start != start) {
                    throw new IllegalArgumentException('First memberInterval start must equal block start')
                }
                if (memberIntervals[-1].end != end) {
                    throw new IllegalArgumentException('Last memberInterval end must equal block end')
                }
                Instant cursor = start
                memberIntervals.each { mi ->
                    if (mi.start != cursor) {
                        throw new IllegalArgumentException(
                            "Member intervals must be contiguous; expected start ${cursor} for ${mi.taskId}")
                    }
                    if (mi.end.isAfter(end)) {
                        throw new IllegalArgumentException("Member interval for ${mi.taskId} exceeds block end")
                    }
                    cursor = mi.end
                }
                if (cursor != end) {
                    throw new IllegalArgumentException('Member intervals total must equal block duration')
                }
            }
            return new ScheduledBlock(this)
        }
    }
}
