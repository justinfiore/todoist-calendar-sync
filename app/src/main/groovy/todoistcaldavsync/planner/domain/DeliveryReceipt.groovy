package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections
import java.util.Objects

/**
 * Immutable delivery outcome. Never claims delivered on provider/store failure.
 * status: PENDING | ATTEMPT | DELIVERED | FAILED | UNKNOWN | NEEDS_RECONCILIATION |
 *         SKIPPED_DISABLED | SKIPPED_DUPLICATE | SKIPPED_NOT_DUE
 * Field-value equality (not identity-only).
 */
final class DeliveryReceipt {
    final String id
    final String idempotencyKey
    final String kind
    final String destination
    final String planId
    final Integer planVersion
    final String planHash
    final String proposalId
    final String status
    final String providerMessageId
    final String threadId
    final String channelId
    final Instant attemptedAt
    final Instant completedAt
    final String errorClassification
    final String errorMessage
    final Map<String, Object> metadata

    private DeliveryReceipt(Builder b) {
        this.id = b.id
        this.idempotencyKey = b.idempotencyKey
        this.kind = b.kind
        this.destination = b.destination
        this.planId = b.planId
        this.planVersion = b.planVersion
        this.planHash = b.planHash
        this.proposalId = b.proposalId
        this.status = b.status
        this.providerMessageId = b.providerMessageId
        this.threadId = b.threadId
        this.channelId = b.channelId
        this.attemptedAt = b.attemptedAt
        this.completedAt = b.completedAt
        this.errorClassification = b.errorClassification
        this.errorMessage = b.errorMessage
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    boolean isDelivered() {
        status == 'DELIVERED'
    }

    Map<String, Object> toMap() {
        [
            id                 : id,
            idempotencyKey     : idempotencyKey,
            kind               : kind,
            destination        : destination,
            planId             : planId,
            planVersion        : planVersion,
            planHash           : planHash,
            proposalId         : proposalId,
            status             : status,
            providerMessageId  : providerMessageId,
            threadId           : threadId,
            channelId          : channelId,
            attemptedAt        : attemptedAt?.toString(),
            completedAt        : completedAt?.toString(),
            errorClassification: errorClassification,
            errorMessage       : errorMessage,
            metadata           : metadata
        ]
    }

    static DeliveryReceipt fromMap(Map m) {
        if (m == null) {
            throw new IllegalArgumentException('DeliveryReceipt map is required')
        }
        builder()
            .id(m.id?.toString())
            .idempotencyKey(m.idempotencyKey?.toString())
            .kind(m.kind?.toString())
            .destination(m.destination?.toString())
            .planId(m.planId?.toString())
            .planVersion(m.planVersion != null ? m.planVersion as Integer : null)
            .planHash(m.planHash?.toString())
            .proposalId(m.proposalId?.toString())
            .status(m.status?.toString())
            .providerMessageId(m.providerMessageId?.toString())
            .threadId(m.threadId?.toString())
            .channelId(m.channelId?.toString())
            .attemptedAt(m.attemptedAt != null ? Instant.parse(m.attemptedAt.toString()) : null)
            .completedAt(m.completedAt != null ? Instant.parse(m.completedAt.toString()) : null)
            .errorClassification(m.errorClassification?.toString())
            .errorMessage(m.errorMessage?.toString())
            .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
            .build()
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) {
            return true
        }
        if (!(o instanceof DeliveryReceipt)) {
            return false
        }
        DeliveryReceipt other = (DeliveryReceipt) o
        return Objects.equals(id, other.id) &&
            Objects.equals(idempotencyKey, other.idempotencyKey) &&
            Objects.equals(kind, other.kind) &&
            Objects.equals(destination, other.destination) &&
            Objects.equals(planId, other.planId) &&
            Objects.equals(planVersion, other.planVersion) &&
            Objects.equals(planHash, other.planHash) &&
            Objects.equals(proposalId, other.proposalId) &&
            Objects.equals(status, other.status) &&
            Objects.equals(providerMessageId, other.providerMessageId) &&
            Objects.equals(threadId, other.threadId) &&
            Objects.equals(channelId, other.channelId) &&
            Objects.equals(attemptedAt, other.attemptedAt) &&
            Objects.equals(completedAt, other.completedAt) &&
            Objects.equals(errorClassification, other.errorClassification) &&
            Objects.equals(errorMessage, other.errorMessage) &&
            Objects.equals(metadata, other.metadata)
    }

    @Override
    int hashCode() {
        return Objects.hash(id, idempotencyKey, kind, destination, planId, planVersion,
            planHash, proposalId, status, providerMessageId, threadId, channelId,
            attemptedAt, completedAt, errorClassification, errorMessage, metadata)
    }

    static final class Builder {
        private String id
        private String idempotencyKey
        private String kind
        private String destination
        private String planId
        private Integer planVersion
        private String planHash
        private String proposalId
        private String status
        private String providerMessageId
        private String threadId
        private String channelId
        private Instant attemptedAt
        private Instant completedAt
        private String errorClassification
        private String errorMessage
        private Map<String, Object> metadata = [:]

        Builder id(String v) { this.id = v; this }
        Builder idempotencyKey(String v) { this.idempotencyKey = v; this }
        Builder kind(String v) { this.kind = v; this }
        Builder destination(String v) { this.destination = v; this }
        Builder planId(String v) { this.planId = v; this }
        Builder planVersion(Integer v) { this.planVersion = v; this }
        Builder planHash(String v) { this.planHash = v; this }
        Builder proposalId(String v) { this.proposalId = v; this }
        Builder status(String v) { this.status = v; this }
        Builder providerMessageId(String v) { this.providerMessageId = v; this }
        Builder threadId(String v) { this.threadId = v; this }
        Builder channelId(String v) { this.channelId = v; this }
        Builder attemptedAt(Instant v) { this.attemptedAt = v; this }
        Builder completedAt(Instant v) { this.completedAt = v; this }
        Builder errorClassification(String v) { this.errorClassification = v; this }
        Builder errorMessage(String v) { this.errorMessage = v; this }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        DeliveryReceipt build() {
            if (!id) {
                throw new IllegalArgumentException('DeliveryReceipt id is required')
            }
            if (!idempotencyKey) {
                throw new IllegalArgumentException('DeliveryReceipt idempotencyKey is required')
            }
            if (!status) {
                throw new IllegalArgumentException('DeliveryReceipt status is required')
            }
            if (attemptedAt == null) {
                throw new IllegalArgumentException('DeliveryReceipt attemptedAt is required')
            }
            return new DeliveryReceipt(this)
        }
    }
}
