package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections

/**
 * Explicit non-secret approval record binding a human decision to a plan identity.
 * Required before protected writes under approval_required (and for protected items).
 */
final class Approval {
    final String id
    final String planId
    final int planVersion
    final String planHash
    final Instant approvedAt
    /** Non-secret approver identity (handle, user id label) — never a credential. */
    final String approvedBy
    final Map<String, Object> metadata

    private Approval(Builder b) {
        this.id = b.id
        this.planId = b.planId
        this.planVersion = b.planVersion
        this.planHash = b.planHash
        this.approvedAt = b.approvedAt
        this.approvedBy = b.approvedBy
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    Map<String, Object> toMap() {
        [
            id         : id,
            planId     : planId,
            planVersion: planVersion,
            planHash   : planHash,
            approvedAt : approvedAt.toString(),
            approvedBy : approvedBy,
            metadata   : metadata
        ]
    }

    static Approval fromMap(Map m) {
        if (m == null) {
            throw new IllegalArgumentException('Approval map is required')
        }
        builder()
            .id(m.id?.toString())
            .planId(m.planId?.toString())
            .planVersion(m.planVersion != null ? m.planVersion as int : 0)
            .planHash(m.planHash?.toString())
            .approvedAt(m.approvedAt != null ? Instant.parse(m.approvedAt.toString()) : null)
            .approvedBy(m.approvedBy?.toString())
            .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
            .build()
    }

    static final class Builder {
        private String id
        private String planId
        private int planVersion
        private String planHash
        private Instant approvedAt
        private String approvedBy
        private Map<String, Object> metadata = [:]

        Builder id(String v) { this.id = v; this }
        Builder planId(String v) { this.planId = v; this }
        Builder planVersion(int v) { this.planVersion = v; this }
        Builder planHash(String v) { this.planHash = v; this }
        Builder approvedAt(Instant v) { this.approvedAt = v; this }
        Builder approvedBy(String v) { this.approvedBy = v; this }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        Approval build() {
            if (!id) {
                throw new IllegalArgumentException('Approval id is required')
            }
            if (!planId) {
                throw new IllegalArgumentException('Approval planId is required')
            }
            if (planVersion < 1) {
                throw new IllegalArgumentException('Approval planVersion must be >= 1')
            }
            if (!planHash) {
                throw new IllegalArgumentException('Approval planHash is required')
            }
            if (approvedAt == null) {
                throw new IllegalArgumentException('Approval approvedAt is required')
            }
            if (!approvedBy) {
                throw new IllegalArgumentException('Approval approvedBy is required')
            }
            return new Approval(this)
        }
    }
}
