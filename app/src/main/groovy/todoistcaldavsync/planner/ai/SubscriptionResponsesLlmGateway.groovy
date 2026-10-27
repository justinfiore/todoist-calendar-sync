package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.util.RetryAfter

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** Direct, tool-free public Responses/SSE transport for subscription OAuth credentials. */
final class SubscriptionResponsesLlmGateway implements LlmGateway {
    private final PlannerConfig.AiConfig config
    private final SubscriptionCredentialService credentials
    private final LlmHttpTransport transport
    private final LlmSchemaValidator validator
    private final URI endpoint

    SubscriptionResponsesLlmGateway(PlannerConfig.AiConfig config, SubscriptionCredentialService credentials,
                                    LlmHttpTransport transport = new JavaLlmHttpTransport(),
                                    LlmSchemaValidator validator = new LlmSchemaValidator()) {
        if ([config,credentials,transport,validator].any { it == null }) throw new IllegalArgumentException('gateway components are required')
        this.config=config;this.credentials=credentials;this.transport=transport;this.validator=validator
        endpoint = URI.create(config.provider == 'codex_subscription'
            ? 'https://api.openai.com/v1/responses' : 'https://api.x.ai/v1/responses')
    }

    @Override LlmGatewayResult complete(LlmRequest request) {
        try { contained(request) }
        catch (SubscriptionAuthException e) {
            LlmGatewayResult.failure(new LlmError(e.reauthenticationRequired ? LlmErrorClass.AUTHENTICATION : LlmErrorClass.COMPATIBILITY, e.code))
        } catch (Exception e) {
            // Injectable transports may wrap checked exceptions in proxy/reflection exceptions.
            Throwable cause = e
            for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.cause) {
                if (cause instanceof LlmBodyLimitException) {
                    return LlmGatewayResult.failure(new LlmError(LlmErrorClass.RESPONSE_TOO_LARGE, 'bounded response body exceeded')
                        .withResponseMetadata(cause.statusCode, cause.responseBytes, null, true))
                }
                if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
                    return fail(LlmErrorClass.TIMEOUT, 'subscription inference timed out')
                }
            }
            LlmGatewayResult.failure(new LlmError(LlmErrorClass.TRANSPORT, 'subscription inference failed safely'))
        }
    }

    private LlmGatewayResult contained(LlmRequest request) {
        if (!config.enabled) return fail(LlmErrorClass.DISABLED, 'AI is disabled')
        if (request == null || request.provider != config.provider || request.model != config.model) return fail(LlmErrorClass.CONFIGURATION, 'request provider/model mismatch')
        String expectedHost = config.provider == 'codex_subscription' ? 'api.openai.com' : 'api.x.ai'
        PlannerConfig.ProviderProfile profile = config.subscription.profile(config.provider == 'codex_subscription' ? 'codex' : 'grok')
        if (profile.allowedHosts != [expectedHost] as Set) return fail(LlmErrorClass.CONFIGURATION, 'subscription endpoint allowlist is not exact')
        Map schema = LlmSchemaResources.load(request.suggestionType)
        Map rootProperties = schema.get('properties')
        rootProperties.correlationId.put('const', request.correlationId)
        if (request.suggestionType in ['temporary_planning_overrides','conversational_feedback_interpretation']) {
            Map properties=rootProperties.suggestions.items.get('properties')
            properties.planId.put('const', request.planId)
            properties.planVersion.put('const', request.planVersion)
            properties.planHash.put('const', request.planHash)
            if (request.suggestionType == 'temporary_planning_overrides') properties.planningInputHash.put('const', request.planningInputHash)
            else properties.proposalId.put('const', request.expectedProposalId)
        }
        Map user = [correlationId:request.correlationId,suggestionType:request.suggestionType,
            schemaVersion:request.schemaVersion, plan:[id:request.planId,version:request.planVersion,
            hash:request.planHash,planningInputHash:request.planningInputHash],
            expectedProposalId:request.expectedProposalId,allowedFeedbackActions:request.allowedFeedbackActions as List,
            context:request.context]
        String instructions='Return only data matching the supplied JSON schema. Do not use or request tools.'
        if (request.suggestionType == 'temporary_planning_overrides') {
            user.constraints=[planningRangeStart:request.planningRangeStart.toString(),
                planningRangeEnd:request.planningRangeEnd.toString(),validationTime:request.requestedAt.toString(),
                maxOverrideExpiresAt:request.requestedAt.plus(LlmSchemaValidator.MAX_OVERRIDE_LIFETIME).toString()]
            instructions+=' Temporary overrides must stay inside constraints.planningRangeStart and planningRangeEnd. expiresAt must be at least rangeEnd, after validationTime, and no later than maxOverrideExpiresAt. If a requested change cannot satisfy these constraints, return no suggestions.'
        }
        Map payload = [model:request.model,
            instructions:instructions,
            input:[[role:'user',content:JsonOutput.toJson(user)]], store:false, stream:true,
            text:[format:[type:'json_schema',name:request.suggestionType+'_v1',strict:true,schema:schema]]]
        if (config.provider == 'grok_build_subscription') {
            payload.max_output_tokens=Math.min(request.maxTokens,config.maxTokens)
            // These model versions support low effort; do not guess capabilities for other models.
            if (request.model in ['grok-4.6','grok-4.7']) payload.reasoning=[effort:'low']
        }
        // Deliberately omit tools, tool_choice, previous_response_id and all preview-unsupported fields.
        byte[] body = JsonOutput.toJson(payload).getBytes(StandardCharsets.UTF_8)
        if (body.length > config.maxRequestBytes) return fail(LlmErrorClass.REQUEST_TOO_LARGE, 'bounded request body exceeded')
        String token = credentials.accessToken()
        LlmTransportResponse response
        try {
            response = transport.post(new LlmTransportRequest(endpoint,
                ['Authorization':"Bearer ${token}",'Content-Type':'application/json','Accept':'text/event-stream'],
                body,config.connectTimeout,config.requestTimeout,config.maxResponseBytes,false))
        } finally { token = null }
        if (response.body.length > config.maxResponseBytes) return LlmGatewayResult.failure(
            new LlmError(LlmErrorClass.RESPONSE_TOO_LARGE, 'bounded response body exceeded')
                .withResponseMetadata(response.statusCode, response.body.length, null, true))
        if (response.statusCode == 401) return fail(LlmErrorClass.AUTHENTICATION, 'subscription credential rejected', 401, null, response.body.length)
        if (response.statusCode == 403) return fail(LlmErrorClass.ENTITLEMENT, 'subscription entitlement unavailable', 403, null, response.body.length)
        if (response.statusCode == 429) {
            Long retry = RetryAfter.parseSeconds(response.headers, Instant.now())
            return fail(LlmErrorClass.RATE_LIMITED,'subscription usage limit reached',429,
                retry == null ? null : Duration.ofSeconds(retry), response.body.length)
        }
        if (response.statusCode < 200 || response.statusCode >= 300) return fail(LlmErrorClass.HTTP_STATUS,'provider returned non-success HTTP status',response.statusCode, null, response.body.length)
        SubscriptionSseResult parsed
        try { parsed = SubscriptionSseParser.parse(response.body) }
        catch (SubscriptionSseException e) {
            return LlmGatewayResult.failure(new LlmError(e.errorClass, e.code, response.statusCode, null, false,
                [reasonCode:e.code, responseBytes:response.body.length]))
        }
        ValidationResult validation = validator.validate(request, parsed.text)
        if (!validation.accepted) return LlmGatewayResult.failure(validation.error.withResponseMetadata(
            response.statusCode, response.body.length, parsed.text.getBytes(StandardCharsets.UTF_8).length))
        LlmGatewayResult.success(new LlmResponse(request.correlationId,request.suggestionType,
            request.schemaVersion,parsed.text,response.body.length,parsed.inputTokens,parsed.outputTokens))
    }

    private static LlmGatewayResult fail(LlmErrorClass kind, String detail, Integer status=null, Duration retry=null, Integer responseBytes=null) {
        LlmGatewayResult.failure(new LlmError(kind,detail,status,retry,false,[responseBytes:responseBytes]))
    }
}

final class SubscriptionSseParser {
    private SubscriptionSseParser() {}
    static SubscriptionSseResult parse(byte[] body) {
        if (body == null) throw new SubscriptionSseException(LlmErrorClass.MALFORMED_JSON,'empty_stream')
        StringBuilder text = new StringBuilder(); boolean completed=false; Integer input=null; Integer output=null
        String wire = new String(body, StandardCharsets.UTF_8)
        wire.split(/\r?\n\r?\n/).each { block ->
            List<String> data = block.readLines().findAll { it.startsWith('data:') }.collect { it.substring(5).trim() }
            if (!data || data == ['[DONE]']) return
            Map event
            try { event = StrictJson.parseObject(data.join('\n').getBytes(StandardCharsets.UTF_8)) }
            catch (Exception ignored) { throw new SubscriptionSseException(LlmErrorClass.MALFORMED_JSON,'malformed_sse_event') }
            String type = event.type?.toString()
            if (type == 'response.output_text.delta') {
                if (!(event.delta instanceof String)) throw new SubscriptionSseException(LlmErrorClass.SCHEMA_REJECTED,'invalid_text_delta')
                text.append(event.delta)
            } else if (type?.contains('function_call') || type?.contains('tool_call') || type in ['response.output_item.added','response.output_item.done'] &&
                (event.item instanceof Map) && event.item.type in ['function_call','tool_call']) {
                throw new SubscriptionSseException(LlmErrorClass.UNSAFE_OUTPUT,'provider_attempted_tool_use')
            } else if (type == 'error') {
                throw new SubscriptionSseException(LlmErrorClass.HTTP_STATUS,'provider_stream_error')
            } else if (type == 'response.failed') {
                String code = event.response instanceof Map && event.response.error instanceof Map ? event.response.error.code?.toString() : null
                Set<String> entitlementCodes=['subscription_sharing_usage_limit_exceeded','subscription_sharing_usage_unavailable'] as Set
                LlmErrorClass kind = code in entitlementCodes ?
                    LlmErrorClass.ENTITLEMENT : LlmErrorClass.HTTP_STATUS
                throw new SubscriptionSseException(kind, code in entitlementCodes ? code : 'response_failed')
            } else if (type == 'response.incomplete') {
                throw new SubscriptionSseException(LlmErrorClass.SCHEMA_REJECTED,'response_incomplete')
            } else if (type == 'response.completed') {
                completed=true
                Map usage = event.response instanceof Map && event.response.usage instanceof Map ? event.response.usage as Map : [:]
                input = usage.input_tokens instanceof Number ? usage.input_tokens as Integer : null
                output = usage.output_tokens instanceof Number ? usage.output_tokens as Integer : null
            }
        }
        if (!completed) throw new SubscriptionSseException(LlmErrorClass.TRANSPORT,'stream_interrupted_before_completion')
        if (!text) throw new SubscriptionSseException(LlmErrorClass.SCHEMA_REJECTED,'completed_response_has_no_text')
        new SubscriptionSseResult(text.toString(),input,output)
    }
}

final class SubscriptionSseResult {
    final String text; final Integer inputTokens; final Integer outputTokens
    SubscriptionSseResult(String text,Integer inputTokens,Integer outputTokens) {
        this.text=text;this.inputTokens=inputTokens;this.outputTokens=outputTokens
    }
}
final class SubscriptionSseException extends RuntimeException {
    final LlmErrorClass errorClass; final String code
    SubscriptionSseException(LlmErrorClass errorClass,String code) { super(code);this.errorClass=errorClass;this.code=code }
}
