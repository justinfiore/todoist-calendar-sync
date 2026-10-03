package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections
import java.util.Objects

/**
 * Immutable outbound message. Provider-neutral; adapters map to transport payloads.
 * Never carries secrets. Body is plain text (mrkdwn escaping is adapter responsibility).
 * Field-value equality (not identity-only).
 */
final class Message {
    /** daily_summary | weekly_summary | medium_horizon_summary | capacity_risk_alert | proposal | approval_status */
    final String kind
    final String subject
    final String body
    final String destination
    final String planId
    final Integer planVersion
    final String planHash
    final String proposalId
    final String idempotencyKey
    final Instant createdAt
    final Map<String, Object> metadata

    private Message(Builder b) {
        this.kind = b.kind
        this.subject = b.subject
        this.body = b.body
        this.destination = b.destination
        this.planId = b.planId
        this.planVersion = b.planVersion
        this.planHash = b.planHash
        this.proposalId = b.proposalId
        this.idempotencyKey = b.idempotencyKey
        this.createdAt = b.createdAt
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    /**
     * Immutable copy with a different delivery idempotency key (and optional metadata merge).
     * Used by MessagingService to attach occurrence-scoped keys without mutating the rendered Message.
     */
    Message withDeliveryKey(String newKey, Map<String, Object> extraMetadata = null) {
        if (!newKey) {
            throw new IllegalArgumentException('idempotencyKey is required')
        }
        Map<String, Object> meta = new LinkedHashMap<>(metadata ?: [:])
        if (extraMetadata) {
            meta.putAll(extraMetadata)
        }
        return builder()
            .kind(kind)
            .subject(subject)
            .body(body)
            .destination(destination)
            .planId(planId)
            .planVersion(planVersion)
            .planHash(planHash)
            .proposalId(proposalId)
            .idempotencyKey(newKey)
            .createdAt(createdAt)
            .metadata(meta)
            .build()
    }

    Map<String, Object> toMap() {
        [
            kind           : kind,
            subject        : subject,
            body           : body,
            destination    : destination,
            planId         : planId,
            planVersion    : planVersion,
            planHash       : planHash,
            proposalId     : proposalId,
            idempotencyKey : idempotencyKey,
            createdAt      : createdAt?.toString(),
            metadata       : metadata
        ]
    }

    static Message fromMap(Map m) {
        if (m == null) {
            throw new IllegalArgumentException('Message map is required')
        }
        builder()
            .kind(m.kind?.toString())
            .subject(m.subject?.toString())
            .body(m.body?.toString())
            .destination(m.destination?.toString())
            .planId(m.planId?.toString())
            .planVersion(m.planVersion != null ? m.planVersion as Integer : null)
            .planHash(m.planHash?.toString())
            .proposalId(m.proposalId?.toString())
            .idempotencyKey(m.idempotencyKey?.toString())
            .createdAt(m.createdAt != null ? Instant.parse(m.createdAt.toString()) : null)
            .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
            .build()
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) {
            return true
        }
        if (!(o instanceof Message)) {
            return false
        }
        Message other = (Message) o
        return Objects.equals(kind, other.kind) &&
            Objects.equals(subject, other.subject) &&
            Objects.equals(body, other.body) &&
            Objects.equals(destination, other.destination) &&
            Objects.equals(planId, other.planId) &&
            Objects.equals(planVersion, other.planVersion) &&
            Objects.equals(planHash, other.planHash) &&
            Objects.equals(proposalId, other.proposalId) &&
            Objects.equals(idempotencyKey, other.idempotencyKey) &&
            Objects.equals(createdAt, other.createdAt) &&
            Objects.equals(metadata, other.metadata)
    }

    @Override
    int hashCode() {
        return Objects.hash(kind, subject, body, destination, planId, planVersion,
            planHash, proposalId, idempotencyKey, createdAt, metadata)
    }

    static final class Builder {
        private String kind
        private String subject
        private String body
        private String destination
        private String planId
        private Integer planVersion
        private String planHash
        private String proposalId
        private String idempotencyKey
        private Instant createdAt
        private boolean createdAtSet
        private Map<String, Object> metadata = [:]

        Builder kind(String v) { this.kind = v; this }
        Builder subject(String v) { this.subject = v; this }
        Builder body(String v) { this.body = v; this }
        Builder destination(String v) { this.destination = v; this }
        Builder planId(String v) { this.planId = v; this }
        Builder planVersion(Integer v) { this.planVersion = v; this }
        Builder planHash(String v) { this.planHash = v; this }
        Builder proposalId(String v) { this.proposalId = v; this }
        Builder idempotencyKey(String v) { this.idempotencyKey = v; this }
        Builder createdAt(Instant v) {
            this.createdAt = v
            this.createdAtSet = true
            this
        }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        Message build() {
            if (!kind) {
                throw new IllegalArgumentException('Message kind is required')
            }
            if (body == null) {
                throw new IllegalArgumentException('Message body is required')
            }
            if (!destination) {
                throw new IllegalArgumentException('Message destination is required')
            }
            if (!idempotencyKey) {
                throw new IllegalArgumentException('Message idempotencyKey is required')
            }
            if (!createdAtSet || createdAt == null) {
                throw new IllegalArgumentException('Message createdAt is required')
            }
            return new Message(this)
        }
    }
}
