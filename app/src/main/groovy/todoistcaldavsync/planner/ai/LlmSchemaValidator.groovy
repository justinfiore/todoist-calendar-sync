package todoistcaldavsync.planner.ai

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import groovy.json.JsonOutput
import groovy.transform.PackageScope
import todoistcaldavsync.planner.domain.EventRole

import java.time.Duration
import java.time.Instant
import java.util.regex.Pattern

/** Resource-backed JSON Schema validation plus plan/request-bound semantic checks. */
class LlmSchemaValidator {
    static final int SCHEMA_VERSION = 1
    static final int MAX_SUGGESTIONS = 100
    static final int MAX_TEXT = 500
    static final int MAX_DEPTH = 8
    static final Duration MAX_OVERRIDE_LIFETIME = Duration.ofDays(31)
    static final Set<String> TYPES = Collections.unmodifiableSet([
        'task_suggestions', 'event_classification_suggestions',
        'temporary_planning_overrides', 'conversational_feedback_interpretation'
    ] as Set)
    static final Set<String> FEEDBACK_ACTIONS = Collections.unmodifiableSet(
        ['APPROVE','APPLY_SAFE','REJECT','REQUEST_CHANGES'] as Set)

    private static final Pattern CONTROL = Pattern.compile(/[\u0000-\u001F\u007F]/)
    private static final Pattern IDENTIFIER = Pattern.compile(/^[A-Za-z0-9][A-Za-z0-9._:-]*$/)
    private static final Pattern SAFE_LABEL = Pattern.compile(/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/)
    private static final Pattern SAFE_SUMMARY = Pattern.compile(/^[A-Za-z0-9][A-Za-z0-9 .,'():_-]{0,199}$/)
    private static final Pattern SHELL_META = Pattern.compile(/[;&|`$<>]/)
    private static final Pattern UNSAFE = Pattern.compile(
        /(?i)(ignore\s+(?:all\s+)?previous|system\s*prompt|assistant\s*:|prompt\s*injection|tool[_ -]?(?:call|use)|function[_ -]?call|\bcall\s+(?:a\s+)?tool\b|\/bin\/|\brm\s+-|\b(?:bash|powershell|cmd\.exe)\b|(?:^|\W)sh(?:\W|$)|\b(?:curl|wget|sudo|chmod|eval|execute|invoke)\b|fully[_ -]?automated|config(?:uration)?(?:\s|_|-)*(?:write|change|mutation|update)|\b(?:write|read|delete|remove|create|open|modify)\s+(?:a\s+)?(?:file|config|configuration|disk)|\bnetwork\s+(?:call|request|write)|file:\/\/|https?:\/\/|\bwebhook\b|<script|authorization\s*:|bearer\s+)/)
    private static final Map<AiSuggestionBundle,String> ISSUED =
        Collections.synchronizedMap(new WeakHashMap<AiSuggestionBundle,String>())

    ValidationResult validate(LlmRequest request, String json, Instant now = Instant.now()) {
        if (request == null) return ValidationResult.reject(LlmErrorClass.SCHEMA_REJECTED, 'request is required')
        if (now == null) return ValidationResult.reject(LlmErrorClass.SCHEMA_REJECTED, 'authoritative validation time is required')
        if (json == null || !json.trim()) return ValidationResult.reject(LlmErrorClass.MALFORMED_JSON, 'empty response')
        String trimmed = json.trim()
        if (trimmed.startsWith('```') || trimmed.endsWith('```')) {
            return ValidationResult.reject(LlmErrorClass.MALFORMED_JSON, 'markdown fences are forbidden')
        }
        if (trimmed ==~ /(?s).*(?<![A-Za-z])(NaN|Infinity|-Infinity)(?![A-Za-z]).*/) {
            return ValidationResult.reject(LlmErrorClass.SCHEMA_REJECTED, 'non-finite number is forbidden')
        }
        JsonSchemaContract.Result contract = JsonSchemaContract.validate(request.suggestionType, json)
        if (!contract.valid) {
            return ValidationResult.reject(contract.malformed ? LlmErrorClass.MALFORMED_JSON : LlmErrorClass.SCHEMA_REJECTED,
                contract.malformed ? 'response is not strict JSON' : 'response violates versioned JSON schema')
        }
        try {
            Object parsed = contract.value
            if (!(parsed instanceof Map)) fail('response must be an object')
            if (depth(parsed) > MAX_DEPTH) fail('response nesting is too deep')
            Map root = parsed as Map
            if (root.suggestionType != request.suggestionType || !(root.suggestionType in TYPES)) fail('suggestionType mismatch')
            if (root.correlationId != request.correlationId) {
                return ValidationResult.reject(LlmErrorClass.CORRELATION_MISMATCH, 'correlationId mismatch')
            }
            Set<String> ids = new LinkedHashSet<>()
            Set<String> logicalTargets = new LinkedHashSet<>()
            List<AiSuggestion> suggestions = []
            (root.suggestions as List).eachWithIndex { row, idx ->
                AiSuggestion suggestion = switch (request.suggestionType) {
                    case 'task_suggestions' -> parseTask(row as Map, request)
                    case 'event_classification_suggestions' -> parseEvent(row as Map, request)
                    case 'temporary_planning_overrides' -> parseOverride(row as Map, request, now)
                    case 'conversational_feedback_interpretation' -> parseFeedback(row as Map, request)
                    default -> throw new Rejected('unknown suggestion type')
                }
                if (!ids.add(suggestion.suggestionId)) fail("duplicate suggestionId at index ${idx}")
                logicalTargetKeys(suggestion).each { String key ->
                    if (!logicalTargets.add(key)) fail("duplicate logical target at index ${idx}")
                }
                suggestions << suggestion
            }
            if (request.suggestionType == 'conversational_feedback_interpretation' && suggestions.size() > 1) {
                fail('only one conversational interpretation is allowed')
            }
            String canonicalJson = canonicalResponse(request, suggestions)
            AiSuggestionBundle bundle = new AiSuggestionBundle(request.suggestionType, SCHEMA_VERSION,
                request.correlationId, request.planId, request.planVersion, request.planHash,
                request.planningInputHash, suggestions, AiValues.sha256(json), canonicalJson, request, now)
            ISSUED[bundle] = seal(bundle)
            ValidationResult.accept(bundle)
        } catch (UnsafeRejected ignored) {
            ValidationResult.reject(LlmErrorClass.UNSAFE_OUTPUT, 'response contains unsafe action material')
        } catch (Rejected ignored) {
            ValidationResult.reject(LlmErrorClass.SCHEMA_REJECTED, 'response failed semantic validation')
        } catch (Exception ignored) {
            ValidationResult.reject(LlmErrorClass.SCHEMA_REJECTED, 'response validation failed')
        }
    }

    /** Used by confirmation/application boundaries; fabricated or altered bundles fail closed. */
    static boolean isAuthentic(AiSuggestionBundle bundle) {
        if (bundle == null || ISSUED[bundle] == null || ISSUED[bundle] != seal(bundle)) return false
        try {
            ValidationResult again = new LlmSchemaValidator().validate(bundle.originalRequest, bundle.originalJson, bundle.validatedAt)
            again.accepted && fingerprint(again.bundle) == fingerprint(bundle)
        } catch (Exception ignored) { false }
    }

    /** Testable production resource-validation path. */
    static boolean resourceAccepts(String type, String json) {
        JsonSchemaContract.validate(type, json).valid
    }

    private static TaskSuggestion parseTask(Map m, LlmRequest r) {
        String id = identifier(m.suggestionId, 'suggestionId', 128)
        String taskId = identifier(m.taskId, 'taskId', 256)
        if (!r.allowedTaskIds.contains(taskId)) fail('taskId outside context')
        String kind = m.kind as String
        Object value
        if (kind == 'duration') value = integer(m.proposedValue)
        else {
            value = actionable(m.proposedValue, 200)
            if (kind in ['context','batch'] && !SAFE_LABEL.matcher(value as String).matches()) fail('invalid label value')
            if (kind == 'schedule_rationale' && !SAFE_SUMMARY.matcher(value as String).matches()) fail('invalid summary value')
        }
        List<String> evidence = (m.evidenceIds as List).collect { identifier(it, 'evidenceId', 256) }
        evidence.each { if (!r.allowedTaskIds.contains(it) && !r.allowedEventIds.contains(it)) fail('evidence outside context') }
        new TaskSuggestion(id, taskId, kind, value, finite(m.confidence), narrative(m.rationale, MAX_TEXT), evidence)
    }

    private static EventClassificationSuggestion parseEvent(Map m, LlmRequest r) {
        String id = identifier(m.suggestionId, 'suggestionId', 128)
        String eventId = identifier(m.eventId, 'eventId', 256)
        if (!r.allowedEventIds.contains(eventId)) fail('event outside context')
        EventRole role
        try { role = EventRole.fromConfig(m.suggestedRole as String) }
        catch (Exception ignored) { fail('invalid role'); return null }
        Map patch = null
        if (m.candidateRulePatch != null) {
            patch = new LinkedHashMap()
            (m.candidateRulePatch as Map).each { k, v ->
                if (k.toString().endsWith('Minutes')) patch[k.toString()] = integer(v)
                else if (k == 'role') {
                    EventRole.fromConfig(v as String)
                    patch[k.toString()] = v
                } else if (k == 'name') {
                    String name=actionable(v,200);if(!SAFE_SUMMARY.matcher(name).matches())fail('invalid rule name');patch[k.toString()]=name
                } else {
                    String regex=patchRegex(v);Pattern.compile(regex);patch[k.toString()]=regex
                }
            }
        }
        new EventClassificationSuggestion(id, eventId, role, finite(m.confidence), narrative(m.rationale, MAX_TEXT), patch)
    }

    private static TemporaryPlanningOverride parseOverride(Map m, LlmRequest r, Instant now) {
        String id = identifier(m.suggestionId, 'suggestionId', 128)
        if (m.planId != r.planId || integer(m.planVersion) != r.planVersion || m.planHash != r.planHash ||
            m.planningInputHash != r.planningInputHash) fail('plan mismatch')
        List<String> taskIds = (m.taskIds as List).collect { identifier(it, 'taskId', 256) }
        taskIds.each { if (!r.allowedTaskIds.contains(it)) fail('task outside context') }
        String type = m.overrideType as String
        Object value = type == 'duration_minutes' ? integer(m.value) : actionable(m.value, 64)
        Instant start = iso(m.rangeStart); Instant end = iso(m.rangeEnd); Instant expires = iso(m.expiresAt)
        if (!end.isAfter(start) || Duration.between(start,end) > Duration.ofDays(31)) fail('invalid range')
        if (r.planningRangeStart == null || r.planningRangeEnd == null ||
            start.isBefore(r.planningRangeStart) || end.isAfter(r.planningRangeEnd)) fail('range outside context')
        if (!expires.isAfter(now) || expires.isBefore(end) || Duration.between(now, expires) > MAX_OVERRIDE_LIFETIME) fail('invalid expiry')
        new TemporaryPlanningOverride(id, r.planId, r.planVersion, r.planHash, r.planningInputHash, taskIds, type, value,
            start, end, expires, finite(m.confidence), narrative(m.rationale, MAX_TEXT))
    }

    private static ProposedStructuredFeedback parseFeedback(Map m, LlmRequest r) {
        String id = identifier(m.suggestionId, 'suggestionId', 128)
        String proposal = identifier(m.proposalId, 'proposalId', 128)
        if (proposal != r.expectedProposalId || m.planId != r.planId || integer(m.planVersion) != r.planVersion ||
            m.planHash != r.planHash) fail('feedback identity mismatch')
        String action = m.action as String
        if (!r.allowedFeedbackActions.contains(action)) fail('feedback action not allowed')
        String reason = narrative(m.reason, MAX_TEXT)
        String command = canonicalCommand(action, r.expectedProposalId, r.planHash, reason)
        new ProposedStructuredFeedback(id, r.expectedProposalId, r.planId, r.planVersion, r.planHash,
            action, command, reason)
    }

    private static String canonicalCommand(String action, String proposal, String hash, String reason) {
        String verb = action.toLowerCase(Locale.ROOT).replace('_','-')
        String base = "${verb} ${proposal} ${hash}"
        String commandReason = reason?.replaceAll(/(?i)\b(?:approve|reject|apply[_ -]?safe|request[_ -]?changes|help|status)\b/, 'decision')
        commandReason ? "${base} ${commandReason}" : base
    }
    private static Collection<String> logicalTargetKeys(AiSuggestion suggestion) {
        if (suggestion instanceof TaskSuggestion) {
            TaskSuggestion s=suggestion as TaskSuggestion
            return ["task|${s.taskId}|${s.kind}"]
        }
        if (suggestion instanceof EventClassificationSuggestion) {
            return ["event|${(suggestion as EventClassificationSuggestion).eventId}"]
        }
        if (suggestion instanceof TemporaryPlanningOverride) {
            TemporaryPlanningOverride s=suggestion as TemporaryPlanningOverride
            return s.taskIds.collect { "override|${it}|${s.overrideType}" }
        }
        if (suggestion instanceof ProposedStructuredFeedback) return ['conversation']
        Collections.emptyList()
    }

    private static String identifier(def value, String name, int max) {
        String s = rawText(value, max)
        def redacted = AiRedactor.redactText(s, max)
        if (!IDENTIFIER.matcher(s).matches() || redacted.redactionCount || redacted.text != s) fail("invalid ${name}")
        s
    }
    private static String actionable(def value, int max) {
        String s = rawText(value, max)
        def redacted = AiRedactor.redactText(s, max)
        if (redacted.redactionCount || redacted.text != s || UNSAFE.matcher(s).find() || SHELL_META.matcher(s).find()) unsafe('unsafe actionable value')
        s
    }
    private static String patchRegex(def value) {
        String s=rawText(value,200);def redacted=AiRedactor.redactText(s,200)
        if(redacted.redactionCount||redacted.text!=s||UNSAFE.matcher(s).find())unsafe('unsafe patch value')
        s
    }
    private static String narrative(def value, int max) {
        String s = rawText(value, max)
        if (UNSAFE.matcher(s).find()) unsafe('unsafe narrative')
        AiRedactor.redactText(s, max).text
    }
    private static String rawText(def value, int max) {
        if (!(value instanceof String) || !value.trim() || value.length() > max || CONTROL.matcher(value).find()) fail('invalid string')
        value as String
    }
    private static int integer(def value) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger) ||
            (value as Number).longValue() < Integer.MIN_VALUE || (value as Number).longValue() > Integer.MAX_VALUE) fail('invalid integer')
        (value as Number).intValue()
    }
    private static double finite(def value) {
        if (!(value instanceof Number) || !Double.isFinite((value as Number).doubleValue())) fail('invalid number')
        (value as Number).doubleValue()
    }
    private static Instant iso(def value) {
        try { Instant.parse(value as String) } catch (Exception ignored) { fail('invalid instant'); null }
    }
    private static int depth(def value) {
        if (value instanceof Map) return 1 + ((value as Map).values().collect { depth(it) }.max() ?: 0)
        if (value instanceof Collection) return 1 + ((value as Collection).collect { depth(it) }.max() ?: 0)
        1
    }
    private static String seal(AiSuggestionBundle b) { AiValues.sha256(b.contentHash + '|' + fingerprint(b)) }
    private static String fingerprint(AiSuggestionBundle b) {
        JsonOutput.toJson([b.suggestionType,b.schemaVersion,b.correlationId,b.planId,b.planVersion,b.planHash,b.planningInputHash,
            b.suggestions.collect { suggestionMap(it) }])
    }
    private static Map suggestionMap(AiSuggestion s) {
        if (s instanceof TaskSuggestion) return [id:s.suggestionId,taskId:s.taskId,kind:s.kind,value:s.proposedValue,confidence:s.confidence,rationale:s.rationale,evidence:s.evidenceIds]
        if (s instanceof EventClassificationSuggestion) return [id:s.suggestionId,eventId:s.eventId,role:s.suggestedRole.name(),confidence:s.confidence,rationale:s.rationale,patch:s.candidateRulePatch]
        if (s instanceof TemporaryPlanningOverride) return [id:s.suggestionId,planId:s.planId,version:s.planVersion,hash:s.planHash,inputHash:s.planningInputHash,tasks:s.taskIds,type:s.overrideType,value:s.value,start:s.rangeStart.toString(),end:s.rangeEnd.toString(),expires:s.expiresAt.toString(),confidence:s.confidence,rationale:s.rationale]
        if (s instanceof ProposedStructuredFeedback) return [id:s.suggestionId,proposal:s.proposalId,planId:s.planId,version:s.planVersion,hash:s.planHash,action:s.action,command:s.proposedCommand,reason:s.reason]
        [:]
    }
    private static String canonicalResponse(LlmRequest request,List<AiSuggestion> suggestions) {
        List rows=suggestions.collect { AiSuggestion s ->
            if(s instanceof TaskSuggestion)return [suggestionId:s.suggestionId,taskId:s.taskId,kind:s.kind,
                proposedValue:s.proposedValue,confidence:s.confidence,rationale:s.rationale,evidenceIds:s.evidenceIds]
            if(s instanceof EventClassificationSuggestion)return [suggestionId:s.suggestionId,eventId:s.eventId,
                suggestedRole:s.suggestedRole.configValue,confidence:s.confidence,rationale:s.rationale,candidateRulePatch:s.candidateRulePatch]
            if(s instanceof TemporaryPlanningOverride)return [suggestionId:s.suggestionId,planId:s.planId,planVersion:s.planVersion,
                planHash:s.planHash,planningInputHash:s.planningInputHash,taskIds:s.taskIds,overrideType:s.overrideType,value:s.value,rangeStart:s.rangeStart.toString(),
                rangeEnd:s.rangeEnd.toString(),expiresAt:s.expiresAt.toString(),confidence:s.confidence,rationale:s.rationale]
            if(s instanceof ProposedStructuredFeedback)return [suggestionId:s.suggestionId,proposalId:s.proposalId,planId:s.planId,
                planVersion:s.planVersion,planHash:s.planHash,action:s.action,reason:s.reason]
            throw new Rejected()
        }
        JsonOutput.toJson([schemaVersion:SCHEMA_VERSION,suggestionType:request.suggestionType,
            correlationId:request.correlationId,suggestions:rows])
    }
    private static void fail(String ignored) { throw new Rejected() }
    private static void unsafe(String ignored) { throw new UnsafeRejected() }
    private static class Rejected extends RuntimeException {}
    private static class UnsafeRejected extends Rejected {}
}

/** Standards-compliant draft-2020-12 resource contract used in production. */
final class JsonSchemaContract {
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    static Result validate(String type, String json) {
        if (!(type in LlmSchemaValidator.TYPES)) return new Result(false, false, null)
        try {
            JsonNode node = StrictJson.readTree(json)
            InputStream input = LlmSchemaResources.open(type)
            Set errors = input.withCloseable { FACTORY.getSchema(it).validate(node) }
            Object value = StrictJson.toValue(node)
            new Result(errors.isEmpty(), false, value)
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            new Result(false, true, null)
        } catch (Exception ignored) {
            new Result(false, false, null)
        }
    }
    static final class Result {
        final boolean valid; final boolean malformed; final Object value
        Result(boolean valid, boolean malformed, Object value) { this.valid=valid; this.malformed=malformed; this.value=value }
    }
}

interface AiSuggestion { String getSuggestionId() }

final class TaskSuggestion implements AiSuggestion {
    final String suggestionId; final String taskId; final String kind; final Object proposedValue
    final double confidence; final String rationale; final List<String> evidenceIds
    @PackageScope TaskSuggestion(String id,String taskId,String kind,Object value,double confidence,String rationale,List evidence) {
        suggestionId=id;this.taskId=taskId;this.kind=kind;proposedValue=value;this.confidence=confidence;this.rationale=rationale
        evidenceIds=Collections.unmodifiableList(new ArrayList<>(evidence ?: []))
    }
}
final class EventClassificationSuggestion implements AiSuggestion {
    final String suggestionId; final String eventId; final EventRole suggestedRole; final double confidence
    final String rationale; final Map<String,Object> candidateRulePatch
    @PackageScope EventClassificationSuggestion(String id,String eventId,EventRole role,double confidence,String rationale,Map patch) {
        suggestionId=id;this.eventId=eventId;suggestedRole=role;this.confidence=confidence;this.rationale=rationale
        candidateRulePatch=patch==null?null:AiValues.immutableMap(patch)
    }
    boolean requiresPersistentPolicyConfirmation(){candidateRulePatch!=null}
}
final class TemporaryPlanningOverride implements AiSuggestion {
    final String suggestionId; final String planId; final int planVersion; final String planHash; final String planningInputHash; final List<String> taskIds
    final String overrideType; final Object value; final Instant rangeStart; final Instant rangeEnd; final Instant expiresAt
    final double confidence; final String rationale
    @PackageScope TemporaryPlanningOverride(String id,String planId,int version,String hash,String inputHash,List taskIds,String type,Object value,
        Instant start,Instant end,Instant expires,double confidence,String rationale) {
        suggestionId=id;this.planId=planId;planVersion=version;planHash=hash;planningInputHash=inputHash;this.taskIds=Collections.unmodifiableList(new ArrayList<>(taskIds))
        overrideType=type;this.value=value;rangeStart=start;rangeEnd=end;expiresAt=expires;this.confidence=confidence;this.rationale=rationale
    }
}
final class ProposedStructuredFeedback implements AiSuggestion {
    final String suggestionId; final String proposalId; final String planId; final int planVersion; final String planHash
    final String action; final String proposedCommand; final String reason
    @PackageScope ProposedStructuredFeedback(String id,String proposalId,String planId,int version,String hash,String action,String command,String reason) {
        suggestionId=id;this.proposalId=proposalId;this.planId=planId;planVersion=version;planHash=hash;this.action=action;proposedCommand=command;this.reason=reason
    }
}
final class AiSuggestionBundle {
    final String suggestionType; final int schemaVersion; final String correlationId; final String planId
    final int planVersion; final String planHash; final String planningInputHash; final List<AiSuggestion> suggestions; final String contentHash
    private final String originalJson; private final LlmRequest originalRequest; private final Instant validatedAt
    @PackageScope AiSuggestionBundle(String type,int version,String correlation,String planId,int planVersion,String hash,String inputHash,List suggestions,
        String contentHash,String originalJson,LlmRequest originalRequest,Instant validatedAt) {
        suggestionType=type;schemaVersion=version;correlationId=correlation;this.planId=planId;this.planVersion=planVersion;planHash=hash
        planningInputHash=inputHash
        this.suggestions=Collections.unmodifiableList(new ArrayList<>(suggestions ?: []));this.contentHash=contentHash
        this.originalJson=originalJson;this.originalRequest=originalRequest;this.validatedAt=validatedAt
    }
    AiSuggestion find(String id){suggestions.find{it.suggestionId==id}}
}
final class ValidationResult {
    final AiSuggestionBundle bundle; final LlmError error
    private ValidationResult(AiSuggestionBundle b,LlmError e){bundle=b;error=e}
    static ValidationResult accept(AiSuggestionBundle b){new ValidationResult(b,null)}
    static ValidationResult reject(LlmErrorClass c,String d){new ValidationResult(null,new LlmError(c,d))}
    boolean isAccepted(){bundle!=null&&error==null}
}
