package todoistcaldavsync.planner.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale

/**
 * Append-only application receipt for one apply attempt of a plan.
 * Machine JSON uses ISO instants; {@link #toHumanSummary} uses 12-hour AM/PM.
 */
final class ApplicationReceipt {
    final String id
    final String planId
    final int planVersion
    final String planHash
    final String mode
    final String approvalId
    final Instant startedAt
    final Instant finishedAt
    final ApplyItemStatus overallStatus
    final List<AppliedMapping> items
    final List<Map<String, Object>> drifts
    final List<String> errors
    final Map<String, Object> metadata

    private ApplicationReceipt(Builder b) {
        this.id = b.id
        this.planId = b.planId
        this.planVersion = b.planVersion
        this.planHash = b.planHash
        this.mode = b.mode
        this.approvalId = b.approvalId
        this.startedAt = b.startedAt
        this.finishedAt = b.finishedAt
        this.overallStatus = b.overallStatus
        this.items = Collections.unmodifiableList(new ArrayList<>(b.items ?: []))
        this.drifts = Collections.unmodifiableList(new ArrayList<>(b.drifts ?: []))
        this.errors = Collections.unmodifiableList(new ArrayList<>(b.errors ?: []))
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    boolean success() {
        overallStatus == ApplyItemStatus.APPLIED || overallStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
    }

    boolean wroteAnything() {
        items.any { it.calendarStatus == ApplyItemStatus.APPLIED || it.todoistStatus == ApplyItemStatus.APPLIED }
    }

    Map<String, Object> toMap() {
        [
            schemaVersion : 1,
            id            : id,
            planId        : planId,
            planVersion   : planVersion,
            planHash      : planHash,
            mode          : mode,
            approvalId    : approvalId,
            startedAt     : startedAt?.toString(),
            finishedAt    : finishedAt?.toString(),
            overallStatus : overallStatus?.wire,
            items         : items.collect { it.toMap() },
            drifts        : drifts,
            errors        : errors,
            metadata      : metadata
        ]
    }

    static ApplicationReceipt fromMap(Map m) {
        if (m == null) {
            throw new IllegalArgumentException('ApplicationReceipt map is required')
        }
        builder()
            .id(m.id?.toString())
            .planId(m.planId?.toString())
            .planVersion(m.planVersion != null ? m.planVersion as int : 1)
            .planHash(m.planHash?.toString())
            .mode(m.mode?.toString())
            .approvalId(m.approvalId?.toString())
            .startedAt(m.startedAt != null ? Instant.parse(m.startedAt.toString()) : null)
            .finishedAt(m.finishedAt != null ? Instant.parse(m.finishedAt.toString()) : null)
            .overallStatus(m.overallStatus != null ? ApplyItemStatus.fromWire(m.overallStatus.toString()) : ApplyItemStatus.PENDING)
            .items((m.items ?: []).collect { AppliedMapping.fromMap(it as Map) })
            .drifts((m.drifts ?: []).collect { new LinkedHashMap<>(it as Map) })
            .errors((m.errors ?: []).collect { it.toString() })
            .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
            .build()
    }

    /**
     * Human-facing summary with 12-hour AM/PM local times.
     */
    String toHumanSummary(ZoneId zone = ZoneId.of('UTC')) {
        def fmt = DateTimeFormatter.ofPattern('h:mm a').withLocale(Locale.US).withZone(zone ?: ZoneId.of('UTC'))
        def sb = new StringBuilder()
        sb.append("Application receipt ${id}\n")
        sb.append("Plan: ${planId} v${planVersion}\n")
        sb.append("Status: ${overallStatus.wire}\n")
        if (startedAt != null) {
            sb.append("Started: ${fmt.format(startedAt)}\n")
        }
        if (finishedAt != null) {
            sb.append("Finished: ${fmt.format(finishedAt)}\n")
        }
        items.each { AppliedMapping it ->
            sb.append("- task ${it.taskId}: cal=${it.calendarStatus.wire} todoist=${it.todoistStatus.wire}")
            if (it.slotStart != null) {
                sb.append(" @ ${fmt.format(it.slotStart)}")
            }
            sb.append('\n')
        }
        drifts.each { d ->
            sb.append("DRIFT: ${d}\n")
        }
        errors.each { e ->
            sb.append("ERROR: ${e}\n")
        }
        return sb.toString()
    }

    static final class Builder {
        private String id
        private String planId
        private int planVersion = 1
        private String planHash
        private String mode
        private String approvalId
        private Instant startedAt
        private Instant finishedAt
        private ApplyItemStatus overallStatus = ApplyItemStatus.PENDING
        private List<AppliedMapping> items = []
        private List<Map<String, Object>> drifts = []
        private List<String> errors = []
        private Map<String, Object> metadata = [:]

        Builder id(String v) { this.id = v; this }
        Builder planId(String v) { this.planId = v; this }
        Builder planVersion(int v) { this.planVersion = v; this }
        Builder planHash(String v) { this.planHash = v; this }
        Builder mode(String v) { this.mode = v; this }
        Builder approvalId(String v) { this.approvalId = v; this }
        Builder startedAt(Instant v) { this.startedAt = v; this }
        Builder finishedAt(Instant v) { this.finishedAt = v; this }
        Builder overallStatus(ApplyItemStatus v) { this.overallStatus = v; this }
        Builder items(List<AppliedMapping> v) { this.items = v ?: []; this }
        Builder drifts(List<Map<String, Object>> v) { this.drifts = v ?: []; this }
        Builder errors(List<String> v) { this.errors = v ?: []; this }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        ApplicationReceipt build() {
            if (!id) {
                throw new IllegalArgumentException('ApplicationReceipt id is required')
            }
            if (!planId) {
                throw new IllegalArgumentException('ApplicationReceipt planId is required')
            }
            if (startedAt == null) {
                throw new IllegalArgumentException('ApplicationReceipt startedAt is required')
            }
            if (overallStatus == null) {
                throw new IllegalArgumentException('ApplicationReceipt overallStatus is required')
            }
            return new ApplicationReceipt(this)
        }
    }
}
