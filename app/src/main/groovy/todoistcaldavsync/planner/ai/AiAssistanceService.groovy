package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.function.Supplier

/**
 * Explicitly invoked enrichment side service. It returns suggestions only and has
 * no PlanStore, config store, DecisionStore, PlanApplier, messaging, or remote-write dependency.
 */
final class AiAssistanceService {
    private final PlannerConfig.AiConfig config
    private final LlmGateway gateway
    private final LlmContextBuilder contextBuilder
    private final LlmSchemaValidator validator
    private final Supplier<Instant> clock

    AiAssistanceService(PlannerConfig.AiConfig config, LlmGateway gateway,
                        LlmContextBuilder contextBuilder = null,
                        LlmSchemaValidator validator = new LlmSchemaValidator(),
                        Supplier<Instant> clock = { Instant.now() }) {
        if (config == null || !config.enabled) throw new IllegalArgumentException('AI must be explicitly enabled')
        if (gateway == null) throw new IllegalArgumentException('LLM gateway is required')
        this.config=config; this.gateway=gateway
        this.contextBuilder=contextBuilder ?: new LlmContextBuilder(config)
        this.validator=validator ?: new LlmSchemaValidator(); this.clock=clock ?: ({ Instant.now() } as Supplier)
    }

    /** Supplier is not evaluated when disabled, guaranteeing no network adapter construction. */
    static Optional<AiAssistanceService> create(PlannerConfig config, Supplier<LlmGateway> gatewayFactory,
                                                 Supplier<Instant> clock = { Instant.now() }) {
        if (config == null) throw new IllegalArgumentException('planner config is required')
        if (!config.ai.enabled) return Optional.empty()
        if (gatewayFactory == null) throw new IllegalArgumentException('gateway factory is required when AI is enabled')
        Optional.of(new AiAssistanceService(config.ai, gatewayFactory.get(), null, new LlmSchemaValidator(), clock))
    }

    AssistanceResult suggest(String type, String correlationId, Plan plan,
                             Collection<CalendarEvent> events = [], String feedbackText = null) {
        Instant started = clock.get()
        if (!config.allowedSuggestionTypes.contains(type)) {
            return AssistanceResult.rejected(new LlmError(LlmErrorClass.CONFIGURATION, 'suggestion type is not allowed'), null)
        }
        if (!(correlationId ==~ /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/)) {
            throw new IllegalArgumentException('correlationId is invalid')
        }
        if (plan == null) throw new IllegalArgumentException('plan is required')
        ContextBuildResult context
        try { context = contextBuilder.build(plan, events, feedbackText, plan.createdAt) }
        catch (Exception ignored) { return AssistanceResult.rejected(new LlmError(LlmErrorClass.REQUEST_TOO_LARGE, 'unable to build bounded AI context'), null) }
        String planHash = PlanHash.compute(plan)
        String planningInputHash
        try { planningInputHash = PlanningInputHash.compute(plan, events) }
        catch (Exception ignored) { return AssistanceResult.rejected(new LlmError(LlmErrorClass.CONFIGURATION, 'unable to bind planning input'), null) }
        String proposalId = type == 'conversational_feedback_interpretation' ? Proposal.fromPlan(plan).id : null
        Set<String> feedbackActions = type == 'conversational_feedback_interpretation'
            ? LlmSchemaValidator.FEEDBACK_ACTIONS : Collections.emptySet()
        Map requestContext = new LinkedHashMap(context.context)
        if (proposalId != null) requestContext.expectedFeedback = [proposalId:proposalId, planId:plan.id,
            planVersion:plan.version, planHash:planHash, allowedActions:feedbackActions as List]
        LlmRequest request
        try {
            request = new LlmRequest([
                correlationId: correlationId, suggestionType: type, schemaVersion: 1,
                provider: config.provider, model: config.model ?: 'fixture-v1',
                planId: plan.id, planVersion: plan.version, planHash: planHash,
                planningInputHash: planningInputHash,
                context: requestContext, allowedTaskIds: context.taskIds,
                allowedEventIds: context.eventIds, expectedProposalId: proposalId,
                allowedFeedbackActions: feedbackActions,
                planningRangeStart: plan.slots ? plan.slots.collect { it.start }.min() : null,
                planningRangeEnd: plan.slots ? plan.slots.collect { it.end }.max() : null,
                maxTokens: config.maxTokens
            ])
        } catch (Exception ignored) {
            return AssistanceResult.rejected(new LlmError(LlmErrorClass.CONFIGURATION, 'unable to construct bounded AI request'), null)
        }
        LlmGatewayResult gatewayResult
        try { gatewayResult = gateway.complete(request) }
        catch (Exception ignored) {
            Instant completed = clock.get()
            LlmError error = new LlmError(LlmErrorClass.TRANSPORT, 'LLM gateway failed safely', null, null, true)
            return AssistanceResult.rejected(error, receipt(request, context, started, completed, 0, null, null, error.errorClass, null))
        }
        if (gatewayResult == null) {
            Instant completed = clock.get()
            LlmError error = new LlmError(LlmErrorClass.TRANSPORT, 'LLM gateway returned no result', null, null, true)
            return AssistanceResult.rejected(error, receipt(request, context, started, completed, 0, null, null, error.errorClass, null))
        }
        Instant completed = clock.get()
        if (!gatewayResult.success) {
            return AssistanceResult.rejected(gatewayResult.error, receipt(request, context, started, completed,
                0, null, null, gatewayResult.error.errorClass, null))
        }
        // Revalidate at the authority-free service boundary even for validating adapters.
        ValidationResult validation
        try { validation = validator.validate(request, gatewayResult.response.jsonText, completed) }
        catch (Exception ignored) {
            LlmError error = new LlmError(LlmErrorClass.SCHEMA_REJECTED, 'LLM response validation failed safely')
            return AssistanceResult.rejected(error, receipt(request, context, started, completed,
                gatewayResult.response?.responseBytes ?: 0, gatewayResult.response?.promptTokens,
                gatewayResult.response?.completionTokens, error.errorClass, gatewayResult.response?.contentHash))
        }
        if (!validation.accepted) {
            return AssistanceResult.rejected(validation.error, receipt(request, context, started, completed,
                gatewayResult.response.responseBytes, gatewayResult.response.promptTokens,
                gatewayResult.response.completionTokens, validation.error.errorClass,
                gatewayResult.response.contentHash))
        }
        LlmAuditReceipt audit = receipt(request, context, started, completed,
            gatewayResult.response.responseBytes, gatewayResult.response.promptTokens,
            gatewayResult.response.completionTokens, null, validation.bundle.contentHash)
        AssistanceResult.accepted(validation.bundle, audit)
    }

    private LlmAuditReceipt receipt(LlmRequest request, ContextBuildResult context,
                                    Instant start, Instant end, int responseBytes,
                                    Integer promptTokens, Integer completionTokens,
                                    LlmErrorClass errorClass, String responseHash) {
        int requestBytes = JsonOutput.toJson(request.context).getBytes(StandardCharsets.UTF_8).length
        new LlmAuditReceipt([correlationId: request.correlationId, schemaType: request.suggestionType,
            schemaVersion: request.schemaVersion, provider: request.provider, model: request.model,
            planHash: request.planHash, planningInputHash: request.planningInputHash,
            startedAt: start, completedAt: end,
            requestBytes: requestBytes, responseBytes: responseBytes,
            redactionCount: context.redactionCount, omittedCount: context.omittedCount,
            promptTokens: promptTokens, completionTokens: completionTokens,
            outcome: errorClass == null ? 'ACCEPTED_SUGGESTIONS' : 'REJECTED',
            errorClass: errorClass, responseContentHash: responseHash])
    }
}

final class AssistanceResult {
    final AiSuggestionBundle bundle; final LlmError error; final LlmAuditReceipt audit
    private AssistanceResult(AiSuggestionBundle b,LlmError e,LlmAuditReceipt a) { bundle=b; error=e; audit=a }
    static AssistanceResult accepted(AiSuggestionBundle b,LlmAuditReceipt a) { new AssistanceResult(b,null,a) }
    static AssistanceResult rejected(LlmError e,LlmAuditReceipt a) { new AssistanceResult(null,e,a) }
    boolean isAccepted() { bundle != null && error == null }
}
