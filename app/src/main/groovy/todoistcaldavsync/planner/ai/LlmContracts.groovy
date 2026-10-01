package todoistcaldavsync.planner.ai

import java.time.Duration
import java.time.Instant

/** Provider-neutral boundary. Implementations have no planner/write-gateway authority. */
interface LlmGateway {
    LlmGatewayResult complete(LlmRequest request)
}

/** Immutable, minimum-data request. No planner domain objects or gateways cross this boundary. */
final class LlmRequest {
    final String correlationId
    final String suggestionType
    final int schemaVersion
    final String provider
    final String model
    final String planId
    final int planVersion
    final String planHash
    final String planningInputHash
    final Map<String, Object> context
    final Set<String> allowedTaskIds
    final Set<String> allowedEventIds
    final String expectedProposalId
    final Set<String> allowedFeedbackActions
    final Instant planningRangeStart
    final Instant planningRangeEnd
    final int maxTokens

    LlmRequest(Map values) {
        values = values ?: [:]
        this.correlationId = required(values.correlationId, 'correlationId')
        this.suggestionType = required(values.suggestionType, 'suggestionType')
        this.schemaVersion = (values.schemaVersion ?: 1) as int
        this.provider = required(values.provider ?: 'fixture', 'provider')
        this.model = required(values.model ?: 'fixture-v1', 'model')
        this.planId = required(values.planId, 'planId')
        this.planVersion = values.planVersion as int
        this.planHash = required(values.planHash, 'planHash')
        this.planningInputHash = required(values.planningInputHash, 'planningInputHash')
        this.context = AiValues.immutableMap(values.context instanceof Map ? values.context as Map : [:])
        this.allowedTaskIds = exactIdentifiers(values.allowedTaskIds, 'allowedTaskIds', 256)
        this.allowedEventIds = exactIdentifiers(values.allowedEventIds, 'allowedEventIds', 256)
        this.expectedProposalId = optional(values.expectedProposalId)
        this.allowedFeedbackActions = Collections.unmodifiableSet(new LinkedHashSet<>(values.allowedFeedbackActions ?: []))
        this.planningRangeStart = parseInstant(values.planningRangeStart, 'planningRangeStart')
        this.planningRangeEnd = parseInstant(values.planningRangeEnd, 'planningRangeEnd')
        this.maxTokens = (values.maxTokens ?: 1200) as int
        if (schemaVersion != 1) throw new IllegalArgumentException('schemaVersion must be 1')
        if (planVersion < 1) throw new IllegalArgumentException('planVersion must be >= 1')
        if (maxTokens < 1) throw new IllegalArgumentException('maxTokens must be positive')
        if (!(correlationId ==~ /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/)) throw new IllegalArgumentException('correlationId is invalid')
        if (!(planId ==~ /^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$/)) throw new IllegalArgumentException('planId is invalid')
        if (!(planHash ==~ /^[0-9a-f]{64}$/)) throw new IllegalArgumentException('planHash is invalid')
        if (!(planningInputHash ==~ /^[0-9a-f]{64}$/)) throw new IllegalArgumentException('planningInputHash is invalid')
        if (!(suggestionType in LlmSchemaValidator.TYPES)) throw new IllegalArgumentException('suggestionType is invalid')
        if (suggestionType == 'temporary_planning_overrides' &&
            (planningRangeStart == null || planningRangeEnd == null || !planningRangeEnd.isAfter(planningRangeStart))) {
            throw new IllegalArgumentException('temporary override requests require a positive planning range')
        }
        if (suggestionType == 'conversational_feedback_interpretation') {
            if (!(expectedProposalId ==~ /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/)) throw new IllegalArgumentException('conversational feedback requires valid expectedProposalId')
            if (!allowedFeedbackActions || !allowedFeedbackActions.every {
                it in ['APPROVE','APPLY_SAFE','REJECT','REQUEST_CHANGES'] as Set
            }) throw new IllegalArgumentException('conversational feedback requires valid allowedFeedbackActions')
        }
    }

    private static String required(def value, String name) {
        if (!(value instanceof CharSequence)) throw new IllegalArgumentException("${name} is required")
        String v = value.toString()
        if (v.isEmpty()) throw new IllegalArgumentException("${name} is required")
        v
    }
    private static String optional(def value) {
        if (value == null) return null
        if (!(value instanceof CharSequence)) throw new IllegalArgumentException('optional identity is invalid')
        String v = value.toString()
        v.isEmpty() ? null : v
    }
    private static Set<String> exactIdentifiers(def values, String name, int max) {
        Collection source=values instanceof Collection ? values as Collection : []
        Set<String> out=new LinkedHashSet<>()
        source.each { value ->
            if (!(value instanceof CharSequence)) throw new IllegalArgumentException("${name} contains an invalid identity")
            String id=value.toString()
            if (id.length()>max || !(id ==~ /^[A-Za-z0-9][A-Za-z0-9._:-]*$/)) {
                throw new IllegalArgumentException("${name} contains an invalid identity")
            }
            out.add(id)
        }
        Collections.unmodifiableSet(out)
    }
    private static Instant parseInstant(def value, String name) {
        if (value == null) return null
        if (value instanceof Instant) return value as Instant
        try { Instant.parse(value.toString()) }
        catch (Exception e) { throw new IllegalArgumentException("${name} must be an ISO instant") }
    }
}

final class LlmResponse {
    final String correlationId
    final String schemaType
    final int schemaVersion
    final String jsonText
    final int responseBytes
    final Integer promptTokens
    final Integer completionTokens
    final String contentHash

    LlmResponse(String correlationId, String schemaType, int schemaVersion, String jsonText,
                int responseBytes, Integer promptTokens = null, Integer completionTokens = null) {
        this.correlationId = correlationId
        this.schemaType = schemaType
        this.schemaVersion = schemaVersion
        this.jsonText = jsonText
        this.responseBytes = responseBytes
        this.promptTokens = promptTokens
        this.completionTokens = completionTokens
        this.contentHash = AiValues.sha256(jsonText ?: '')
    }
}

enum LlmErrorClass {
    DISABLED, CONFIGURATION, AUTHENTICATION, RATE_LIMITED, TIMEOUT, TRANSPORT,
    HTTP_STATUS, REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE, MALFORMED_JSON,
    SCHEMA_REJECTED, CORRELATION_MISMATCH, UNSAFE_OUTPUT
}

/** Redacted classified error. detail must never include request/response bodies or credentials. */
final class LlmError {
    final LlmErrorClass errorClass
    final String detail
    final Integer statusCode
    final Duration retryAfter
    final boolean retryable

    LlmError(LlmErrorClass errorClass, String detail, Integer statusCode = null,
             Duration retryAfter = null, boolean retryable = false) {
        this.errorClass = errorClass
        this.detail = AiRedactor.redactText((detail ?: errorClass?.name() ?: 'LLM error'), 300).text
        this.statusCode = statusCode
        this.retryAfter = retryAfter
        this.retryable = retryable
    }

    @Override
    String toString() { "LlmError(${errorClass?.name() ?: 'UNKNOWN'})" }
}

final class LlmGatewayResult {
    final LlmResponse response
    final LlmError error
    private LlmGatewayResult(LlmResponse response, LlmError error) {
        this.response = response
        this.error = error
    }
    static LlmGatewayResult success(LlmResponse response) {
        if (response == null) throw new IllegalArgumentException('response is required')
        new LlmGatewayResult(response, null)
    }
    static LlmGatewayResult failure(LlmError error) {
        if (error == null) throw new IllegalArgumentException('error is required')
        new LlmGatewayResult(null, error)
    }
    boolean isSuccess() { response != null && error == null }
}

/** Metadata-only audit receipt. Raw prompt and model content are intentionally absent. */
final class LlmAuditReceipt {
    final String correlationId
    final String schemaType
    final int schemaVersion
    final String provider
    final String model
    final String planHash
    final String planningInputHash
    final Instant startedAt
    final Instant completedAt
    final int requestBytes
    final int responseBytes
    final int redactionCount
    final int omittedCount
    final Integer promptTokens
    final Integer completionTokens
    final String outcome
    final LlmErrorClass errorClass
    final String responseContentHash

    LlmAuditReceipt(Map v) {
        v = v ?: [:]
        correlationId = auditText(v.correlationId, 128)
        schemaType = auditText(v.schemaType, 128)
        schemaVersion = (v.schemaVersion ?: 1) as int
        provider = auditText(v.provider, 128)
        model = auditText(v.model, 128)
        planHash = auditText(v.planHash, 64)
        planningInputHash = auditText(v.planningInputHash, 64)
        startedAt = v.startedAt
        completedAt = v.completedAt
        requestBytes = (v.requestBytes ?: 0) as int
        responseBytes = (v.responseBytes ?: 0) as int
        redactionCount = (v.redactionCount ?: 0) as int
        omittedCount = (v.omittedCount ?: 0) as int
        promptTokens = v.promptTokens as Integer
        completionTokens = v.completionTokens as Integer
        outcome = auditText(v.outcome, 64)
        errorClass = v.errorClass as LlmErrorClass
        responseContentHash = auditText(v.responseContentHash, 64)
    }

    private static String auditText(def value, int max) {
        value == null ? null : AiRedactor.redactText(value.toString(), max).text
    }

    Map<String, Object> toMap() {
        [correlationId: correlationId, schemaType: schemaType, schemaVersion: schemaVersion,
         provider: provider, model: model, planHash: planHash, planningInputHash: planningInputHash,
         startedAt: startedAt?.toString(), completedAt: completedAt?.toString(),
         requestBytes: requestBytes, responseBytes: responseBytes,
         redactionCount: redactionCount, omittedCount: omittedCount,
         promptTokens: promptTokens, completionTokens: completionTokens,
         outcome: outcome, errorClass: errorClass?.name(), responseContentHash: responseContentHash]
    }

    @Override
    String toString() { groovy.json.JsonOutput.toJson(toMap()) }
}

/** Small defensive value helper shared by the AI package. */
final class AiValues {
    private AiValues() {}
    static Map<String, Object> immutableMap(Map input) {
        Map out = new LinkedHashMap()
        (input ?: [:]).each { k, v -> out[k.toString()] = immutable(v) }
        Collections.unmodifiableMap(out)
    }
    static Object immutable(Object value) {
        if (value instanceof Map) return immutableMap(value as Map)
        if (value instanceof Collection) return Collections.unmodifiableList((value as Collection).collect { immutable(it) })
        value
    }
    static String sha256(String text) {
        def md = java.security.MessageDigest.getInstance('SHA-256')
        md.digest((text ?: '').getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .collect { String.format('%02x', it & 0xff) }.join()
    }
}
