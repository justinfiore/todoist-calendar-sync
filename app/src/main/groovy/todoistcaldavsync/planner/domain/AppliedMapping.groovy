package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections

/**
 * Stable task ↔ managed-event ↔ plan mapping persisted after (or during partial) apply.
 */
final class AppliedMapping {
    final String taskId
    final String blockId
    final String eventUid
    final Instant slotStart
    final Instant slotEnd
    final String planId
    final int planVersion
    final String planHash
    final String approvalId
    final Instant approvalTime
    final Instant appliedAt
    final ApplyItemStatus calendarStatus
    final ApplyItemStatus todoistStatus
    final String calendarError
    final String todoistError
    final Map<String, Object> metadata

    private AppliedMapping(Builder b) {
        this.taskId = b.taskId
        this.blockId = b.blockId
        this.eventUid = b.eventUid
        this.slotStart = b.slotStart
        this.slotEnd = b.slotEnd
        this.planId = b.planId
        this.planVersion = b.planVersion
        this.planHash = b.planHash
        this.approvalId = b.approvalId
        this.approvalTime = b.approvalTime
        this.appliedAt = b.appliedAt
        this.calendarStatus = b.calendarStatus
        this.todoistStatus = b.todoistStatus
        this.calendarError = b.calendarError
        this.todoistError = b.todoistError
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    boolean calendarApplied() {
        calendarStatus == ApplyItemStatus.APPLIED || calendarStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
    }

    boolean todoistApplied() {
        todoistStatus == ApplyItemStatus.APPLIED || todoistStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
    }

    boolean fullyApplied() {
        calendarApplied() && todoistApplied()
    }

    boolean needsReconciliation() {
        calendarStatus == ApplyItemStatus.UNKNOWN || todoistStatus == ApplyItemStatus.UNKNOWN ||
            (calendarApplied() && !todoistApplied())
    }

    AppliedMapping withStatuses(ApplyItemStatus cal, ApplyItemStatus td,
                                String calErr = null, String tdErr = null,
                                Instant at = null) {
        builder()
            .taskId(taskId)
            .blockId(blockId)
            .eventUid(eventUid)
            .slotStart(slotStart)
            .slotEnd(slotEnd)
            .planId(planId)
            .planVersion(planVersion)
            .planHash(planHash)
            .approvalId(approvalId)
            .approvalTime(approvalTime)
            .appliedAt(at ?: appliedAt)
            .calendarStatus(cal)
            .todoistStatus(td)
            .calendarError(calErr)
            .todoistError(tdErr)
            .metadata(new LinkedHashMap<>(metadata))
            .build()
    }

    Map<String, Object> toMap() {
        [
            taskId         : taskId,
            blockId        : blockId,
            eventUid       : eventUid,
            slotStart      : slotStart?.toString(),
            slotEnd        : slotEnd?.toString(),
            planId         : planId,
            planVersion    : planVersion,
            planHash       : planHash,
            approvalId     : approvalId,
            approvalTime   : approvalTime?.toString(),
            appliedAt      : appliedAt?.toString(),
            calendarStatus : calendarStatus?.wire,
            todoistStatus  : todoistStatus?.wire,
            calendarError  : calendarError,
            todoistError   : todoistError,
            metadata       : metadata
        ]
    }

    static AppliedMapping fromMap(Map m) {
        if (m == null) {
            throw new IllegalArgumentException('AppliedMapping map is required')
        }
        builder()
            .taskId(m.taskId?.toString())
            .blockId(m.blockId?.toString())
            .eventUid(m.eventUid?.toString())
            .slotStart(m.slotStart != null ? Instant.parse(m.slotStart.toString()) : null)
            .slotEnd(m.slotEnd != null ? Instant.parse(m.slotEnd.toString()) : null)
            .planId(m.planId?.toString())
            .planVersion(m.planVersion != null ? m.planVersion as int : 1)
            .planHash(m.planHash?.toString())
            .approvalId(m.approvalId?.toString())
            .approvalTime(m.approvalTime != null ? Instant.parse(m.approvalTime.toString()) : null)
            .appliedAt(m.appliedAt != null ? Instant.parse(m.appliedAt.toString()) : null)
            .calendarStatus(m.calendarStatus != null ? ApplyItemStatus.fromWire(m.calendarStatus.toString()) : ApplyItemStatus.PENDING)
            .todoistStatus(m.todoistStatus != null ? ApplyItemStatus.fromWire(m.todoistStatus.toString()) : ApplyItemStatus.PENDING)
            .calendarError(m.calendarError?.toString())
            .todoistError(m.todoistError?.toString())
            .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
            .build()
    }

    static final class Builder {
        private String taskId
        private String blockId
        private String eventUid
        private Instant slotStart
        private Instant slotEnd
        private String planId
        private int planVersion = 1
        private String planHash
        private String approvalId
        private Instant approvalTime
        private Instant appliedAt
        private ApplyItemStatus calendarStatus = ApplyItemStatus.PENDING
        private ApplyItemStatus todoistStatus = ApplyItemStatus.PENDING
        private String calendarError
        private String todoistError
        private Map<String, Object> metadata = [:]

        Builder taskId(String v) { this.taskId = v; this }
        Builder blockId(String v) { this.blockId = v; this }
        Builder eventUid(String v) { this.eventUid = v; this }
        Builder slotStart(Instant v) { this.slotStart = v; this }
        Builder slotEnd(Instant v) { this.slotEnd = v; this }
        Builder planId(String v) { this.planId = v; this }
        Builder planVersion(int v) { this.planVersion = v; this }
        Builder planHash(String v) { this.planHash = v; this }
        Builder approvalId(String v) { this.approvalId = v; this }
        Builder approvalTime(Instant v) { this.approvalTime = v; this }
        Builder appliedAt(Instant v) { this.appliedAt = v; this }
        Builder calendarStatus(ApplyItemStatus v) { this.calendarStatus = v; this }
        Builder todoistStatus(ApplyItemStatus v) { this.todoistStatus = v; this }
        Builder calendarError(String v) { this.calendarError = v; this }
        Builder todoistError(String v) { this.todoistError = v; this }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        AppliedMapping build() {
            if (!taskId) {
                throw new IllegalArgumentException('AppliedMapping taskId is required')
            }
            if (!eventUid) {
                throw new IllegalArgumentException('AppliedMapping eventUid is required')
            }
            if (!planId) {
                throw new IllegalArgumentException('AppliedMapping planId is required')
            }
            if (slotStart == null || slotEnd == null) {
                throw new IllegalArgumentException('AppliedMapping slotStart and slotEnd are required')
            }
            if (appliedAt == null) {
                throw new IllegalArgumentException('AppliedMapping appliedAt is required')
            }
            if (calendarStatus == null || todoistStatus == null) {
                throw new IllegalArgumentException('AppliedMapping statuses are required')
            }
            return new AppliedMapping(this)
        }
    }
}
