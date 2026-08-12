package todoistcaldavsync.planner.domain

import todoistcaldavsync.planner.util.BoundedText

import java.time.Instant
import java.util.Collections
import java.util.Objects

/**
 * Append-only auditable decision for a proposal.
 * action: APPROVE | REJECT | APPLY_SAFE | REQUEST_CHANGES | STATUS | HELP
 * status: ACCEPTED | REJECTED_MALFORMED | REJECTED_AMBIGUOUS | REJECTED_STALE |
 *         REJECTED_UNAUTHORIZED | REJECTED_REPLAY_CONFLICT | REJECTED_WRONG_IDENTITY | IDEMPOTENT_REPLAY
 *
 * <p>Plan identity is action-specific: APPROVE / APPLY_SAFE / REJECT / REQUEST_CHANGES require
 * exact planId + planVersion&gt;=1 + real planHash (never the sentinel {@code none}).
 * HELP / STATUS may omit plan identity (null version/hash) without forging defaults.
 *
 * <p>Correlation is mandatory for all stored decisions (authorizing ACCEPTED/IDEMPOTENT_REPLAY
 * and audit HELP/STATUS/rejected): FeedbackParser always derives a nonblank bounded id, and
 * DecisionStore independently refuses ACCEPTED/REPLAY without correlation.
 */
final class DecisionRecord {
    static final Set<String> PLAN_BOUND_ACTIONS =
        ['APPROVE', 'REJECT', 'APPLY_SAFE', 'REQUEST_CHANGES'] as Set
    static final Set<String> NON_PLAN_ACTIONS = ['HELP', 'STATUS'] as Set
    /** Max code points for correlationId (nonblank, bounded). */
    static final int MAX_CORRELATION_ID_CODE_POINTS = 256
    static final Set<String> AUTHORIZING_ACCEPTED_LIKE =
        ['ACCEPTED', 'IDEMPOTENT_REPLAY'] as Set

    final String id
    final String proposalId
    final String planId
    final int planVersion
    final String planHash
    final String action
    final String status
    /** Non-secret stable actor identity. */
    final String actorId
    final String correlationId
    final String destination
    final String threadId
    final String messageId
    final Instant decidedAt
    final String reason
    final String previousDecisionId
    final String conflictStatus
    final Map<String, Object> metadata

    private DecisionRecord(Builder b) {
        this.id = b.id
        this.proposalId = b.proposalId
        this.planId = b.planId
        this.planVersion = b.planVersion
        this.planHash = b.planHash
        this.action = b.action
        this.status = b.status
        this.actorId = b.actorId
        this.correlationId = b.correlationId
        this.destination = b.destination
        this.threadId = b.threadId
        this.messageId = b.messageId
        this.decidedAt = b.decidedAt
        this.reason = b.reason
        this.previousDecisionId = b.previousDecisionId
        this.conflictStatus = b.conflictStatus
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    /**
     * True only for status {@code ACCEPTED}. IDEMPOTENT_REPLAY is never accepted authorization.
     */
    boolean isAccepted() {
        status == 'ACCEPTED'
    }

    /** Exact first-time acceptance only (same as {@link #isAccepted()}). */
    boolean isExactAccepted() {
        status == 'ACCEPTED'
    }

    boolean isIdempotentReplay() {
        status == 'IDEMPOTENT_REPLAY'
    }

    /** True when this record is an idempotent replay (never authorizes apply). */
    boolean isReplayed() {
        status == 'IDEMPOTENT_REPLAY'
    }

    boolean isReplayConflict() {
        status == 'REJECTED_REPLAY_CONFLICT'
    }

    boolean isPlanBoundAction() {
        PLAN_BOUND_ACTIONS.contains(action)
    }

    boolean isNonPlanAction() {
        NON_PLAN_ACTIONS.contains(action)
    }

    /**
     * True when plan identity is fully present and not a forged sentinel.
     */
    boolean hasExactPlanIdentity() {
        planId && planVersion >= 1 && planHash && planHash != 'none'
    }

    /**
     * Convert exact valid APPROVE into Phase 3 Approval. Returns null otherwise.
     * Never synthesizes identity: requires non-null id, planId, planVersion>=1, planHash, decidedAt, actorId.
     */
    Approval toApproval() {
        if (action != 'APPROVE' || status != 'ACCEPTED') {
            return null
        }
        if (!id || !planId || planVersion < 1 || !planHash || decidedAt == null || !actorId) {
            return null
        }
        if (planHash == 'none') {
            return null
        }
        return Approval.builder()
            .id(id)
            .planId(planId)
            .planVersion(planVersion)
            .planHash(planHash)
            .approvedAt(decidedAt)
            .approvedBy(actorId)
            .metadata([
                proposalId   : proposalId,
                correlationId: correlationId,
                source       : 'messaging_decision',
                reason       : reason
            ])
            .build()
    }

    /**
     * Copy with a different durable id (store-assigned collision-free ids).
     */
    DecisionRecord withId(String newId) {
        if (newId == null || newId.trim().isEmpty()) {
            throw new IllegalArgumentException('DecisionRecord id is required')
        }
        if (newId == id) {
            return this
        }
        Map m = toMap()
        m.id = newId
        return fromMap(m)
    }

    Map<String, Object> toMap() {
        [
            id                 : id,
            proposalId         : proposalId,
            planId             : planId,
            planVersion        : planVersion,
            planHash           : planHash,
            action             : action,
            status             : status,
            actorId            : actorId,
            correlationId      : correlationId,
            destination        : destination,
            threadId           : threadId,
            messageId          : messageId,
            decidedAt          : decidedAt?.toString(),
            reason             : reason,
            previousDecisionId : previousDecisionId,
            conflictStatus     : conflictStatus,
            metadata           : metadata
        ]
    }

    static DecisionRecord fromMap(Map m) {
        if (m == null) {
            throw new IllegalArgumentException('DecisionRecord map is required')
        }
        Builder b = builder()
            .id(m.id?.toString())
            .proposalId(m.proposalId?.toString())
            .planId(m.planId?.toString())
            .action(m.action?.toString())
            .status(m.status?.toString())
            .actorId(m.actorId?.toString())
            .correlationId(m.correlationId?.toString())
            .destination(m.destination?.toString())
            .threadId(m.threadId?.toString())
            .messageId(m.messageId?.toString())
            .decidedAt(m.decidedAt != null ? Instant.parse(m.decidedAt.toString()) : null)
            .reason(m.reason?.toString())
            .previousDecisionId(m.previousDecisionId?.toString())
            .conflictStatus(m.conflictStatus?.toString())
            .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
        if (m.planVersion != null) {
            b.planVersion(m.planVersion as int)
        }
        if (m.planHash != null) {
            b.planHash(m.planHash.toString())
        }
        return b.build()
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) {
            return true
        }
        if (!(o instanceof DecisionRecord)) {
            return false
        }
        DecisionRecord other = (DecisionRecord) o
        return Objects.equals(id, other.id) &&
            Objects.equals(proposalId, other.proposalId) &&
            Objects.equals(planId, other.planId) &&
            planVersion == other.planVersion &&
            Objects.equals(planHash, other.planHash) &&
            Objects.equals(action, other.action) &&
            Objects.equals(status, other.status) &&
            Objects.equals(actorId, other.actorId) &&
            Objects.equals(correlationId, other.correlationId) &&
            Objects.equals(destination, other.destination) &&
            Objects.equals(threadId, other.threadId) &&
            Objects.equals(messageId, other.messageId) &&
            Objects.equals(decidedAt, other.decidedAt) &&
            Objects.equals(reason, other.reason) &&
            Objects.equals(previousDecisionId, other.previousDecisionId) &&
            Objects.equals(conflictStatus, other.conflictStatus) &&
            Objects.equals(metadata, other.metadata)
    }

    @Override
    int hashCode() {
        return Objects.hash(id, proposalId, planId, planVersion, planHash, action, status,
            actorId, correlationId, destination, threadId, messageId, decidedAt, reason,
            previousDecisionId, conflictStatus, metadata)
    }

    static final class Builder {
        private String id
        private String proposalId
        private String planId
        private int planVersion
        private boolean planVersionSet
        private String planHash
        private String action
        private String status
        private String actorId
        private String correlationId
        private String destination
        private String threadId
        private String messageId
        private Instant decidedAt
        private String reason
        private String previousDecisionId
        private String conflictStatus
        private Map<String, Object> metadata = [:]

        Builder id(String v) { this.id = v; this }
        Builder proposalId(String v) { this.proposalId = v; this }
        Builder planId(String v) { this.planId = v; this }
        Builder planVersion(int v) {
            this.planVersion = v
            this.planVersionSet = true
            this
        }
        Builder planHash(String v) { this.planHash = v; this }
        Builder action(String v) { this.action = v; this }
        Builder status(String v) { this.status = v; this }
        Builder actorId(String v) { this.actorId = v; this }
        Builder correlationId(String v) { this.correlationId = v; this }
        Builder destination(String v) { this.destination = v; this }
        Builder threadId(String v) { this.threadId = v; this }
        Builder messageId(String v) { this.messageId = v; this }
        Builder decidedAt(Instant v) { this.decidedAt = v; this }
        Builder reason(String v) {
            this.reason = v != null ? BoundedText.sanitizeReason(v) : null
            this
        }
        Builder previousDecisionId(String v) { this.previousDecisionId = v; this }
        Builder conflictStatus(String v) { this.conflictStatus = v; this }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        DecisionRecord build() {
            if (!id) {
                throw new IllegalArgumentException('DecisionRecord id is required')
            }
            if (!action) {
                throw new IllegalArgumentException('DecisionRecord action is required')
            }
            if (!status) {
                throw new IllegalArgumentException('DecisionRecord status is required')
            }
            if (!actorId) {
                throw new IllegalArgumentException('DecisionRecord actorId is required')
            }
            if (decidedAt == null) {
                throw new IllegalArgumentException('DecisionRecord decidedAt is required')
            }
            // Bound reason even if set before reason() helper (defense)
            if (reason != null) {
                reason = BoundedText.sanitizeReason(reason)
            }
            correlationId = normalizeCorrelationId(correlationId)
            // Mandatory for all stored decisions (parser always derives; store also enforces
            // for ACCEPTED/IDEMPOTENT_REPLAY so deserialization cannot bypass).
            if (correlationId == null) {
                throw new IllegalArgumentException(
                    'DecisionRecord correlationId is required (nonblank, bounded)')
            }
            boolean acceptedLike = AUTHORIZING_ACCEPTED_LIKE.contains(status)
            if (acceptedLike && PLAN_BOUND_ACTIONS.contains(action)) {
                if (!planId) {
                    throw new IllegalArgumentException(
                        "DecisionRecord planId is required for accepted ${action}")
                }
                if (!planVersionSet || planVersion < 1) {
                    throw new IllegalArgumentException(
                        "DecisionRecord planVersion must be >= 1 for accepted ${action}")
                }
                if (!planHash || planHash == 'none') {
                    throw new IllegalArgumentException(
                        "DecisionRecord planHash is required (not 'none') for accepted ${action}")
                }
            }
            // Non-plan HELP/STATUS: leave planVersion at 0 and planHash null when unset —
            // never forge planVersion=1 / hash='none'
            if (!planVersionSet) {
                planVersion = 0
            }
            return new DecisionRecord(this)
        }

        /**
         * Trim, strip ISO controls, bound code points. Blank/null → null (caller rejects).
         */
        static String normalizeCorrelationId(String raw) {
            if (raw == null) {
                return null
            }
            String trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            StringBuilder sb = new StringBuilder(trimmed.length())
            int i = 0
            int len = trimmed.length()
            while (i < len) {
                int cp = trimmed.codePointAt(i)
                i += Character.charCount(cp)
                if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F) || Character.isISOControl(cp)) {
                    continue
                }
                sb.appendCodePoint(cp)
            }
            String cleaned = sb.toString().trim()
            if (cleaned.isEmpty()) {
                return null
            }
            return BoundedText.boundCodePoints(cleaned, MAX_CORRELATION_ID_CODE_POINTS)
        }
    }
}
