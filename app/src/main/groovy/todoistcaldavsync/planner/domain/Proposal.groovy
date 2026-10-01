package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections
import java.util.Objects

/**
 * Proposal identity bound to exact plan id/version/semantic hash.
 * Used in rendered messages and structured feedback commands.
 * Field-value equality (not identity-only).
 */
final class Proposal {
    final String id
    final String planId
    final int planVersion
    final String planHash
    final Instant createdAt
    final Map<String, Object> metadata

    private Proposal(Builder b) {
        this.id = b.id
        this.planId = b.planId
        this.planVersion = b.planVersion
        this.planHash = b.planHash
        this.createdAt = b.createdAt
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    /**
     * Deterministic proposal id from plan identity + hash prefix.
     */
    static String deriveId(String planId, int planVersion, String planHash) {
        if (!planId || !planHash) {
            throw new IllegalArgumentException('planId and planHash are required')
        }
        String hashPrefix = planHash.length() >= 12 ? planHash.substring(0, 12) : planHash
        String safePlan = planId.replaceAll(/[^A-Za-z0-9._-]/, '_')
        if (safePlan.length() > 32) {
            safePlan = safePlan.substring(0, 32)
        }
        return "prop-${safePlan}-v${planVersion}-${hashPrefix}"
    }

    /**
     * Deterministic proposal from plan identity. Requires non-null {@link Plan#createdAt}
     * (Plan builder already validates). No {@code Instant.now()} fallback.
     */
    static Proposal fromPlan(Plan plan) {
        if (plan == null) {
            throw new IllegalArgumentException('plan is required')
        }
        if (plan.createdAt == null) {
            throw new IllegalArgumentException('plan.createdAt is required')
        }
        String hash = PlanHash.compute(plan)
        builder()
            .id(deriveId(plan.id, plan.version, hash))
            .planId(plan.id)
            .planVersion(plan.version)
            .planHash(hash)
            .createdAt(plan.createdAt)
            .build()
    }

    Map<String, Object> toMap() {
        [
            id         : id,
            planId     : planId,
            planVersion: planVersion,
            planHash   : planHash,
            createdAt  : createdAt?.toString(),
            metadata   : metadata
        ]
    }

    static Proposal fromMap(Map m) {
        if (m == null) {
            throw new IllegalArgumentException('Proposal map is required')
        }
        builder()
            .id(m.id?.toString())
            .planId(m.planId?.toString())
            .planVersion(m.planVersion != null ? m.planVersion as int : 0)
            .planHash(m.planHash?.toString())
            .createdAt(m.createdAt != null ? Instant.parse(m.createdAt.toString()) : null)
            .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
            .build()
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) {
            return true
        }
        if (!(o instanceof Proposal)) {
            return false
        }
        Proposal other = (Proposal) o
        return Objects.equals(id, other.id) &&
            Objects.equals(planId, other.planId) &&
            planVersion == other.planVersion &&
            Objects.equals(planHash, other.planHash) &&
            Objects.equals(createdAt, other.createdAt) &&
            Objects.equals(metadata, other.metadata)
    }

    @Override
    int hashCode() {
        return Objects.hash(id, planId, planVersion, planHash, createdAt, metadata)
    }

    static final class Builder {
        private String id
        private String planId
        private int planVersion
        private String planHash
        private Instant createdAt
        private boolean createdAtSet
        private Map<String, Object> metadata = [:]

        Builder id(String v) { this.id = v; this }
        Builder planId(String v) { this.planId = v; this }
        Builder planVersion(int v) { this.planVersion = v; this }
        Builder planHash(String v) { this.planHash = v; this }
        Builder createdAt(Instant v) {
            this.createdAt = v
            this.createdAtSet = true
            this
        }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        Proposal build() {
            if (!id) {
                throw new IllegalArgumentException('Proposal id is required')
            }
            if (!planId) {
                throw new IllegalArgumentException('Proposal planId is required')
            }
            if (planVersion < 1) {
                throw new IllegalArgumentException('Proposal planVersion must be >= 1')
            }
            if (!planHash) {
                throw new IllegalArgumentException('Proposal planHash is required')
            }
            if (!createdAtSet || createdAt == null) {
                throw new IllegalArgumentException('Proposal createdAt is required')
            }
            return new Proposal(this)
        }
    }
}
