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
        } catch (Exception ignored) {
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
        Map user = [correlationId:request.correlationId,suggestionType:request.suggestionType,
            schemaVersion:request.schemaVersion, plan:[id:request.planId,version:request.planVersion,
            hash:request.planHash,planningInputHash:request.planningInputHash],
            expectedProposalId:request.expectedProposalId,allowedFeedbackActions:request.allowedFeedbackActions as List,
            context:request.context]
        Map payload = [model:request.model,
            instructions:'Return only data matching the supplied JSON schema. Do not use or request tools.',
            input:[[role:'user',content:JsonOutput.toJson(user)]], store:false, stream:true,
            text:[format:[type:'json_schema',name:request.suggestionType+'_v1',strict:true,schema:schema]]]
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
        if (response.statusCode == 401) return fail(LlmErrorClass.AUTHENTICATION, 'subscription credential rejected', 401)
        if (response.statusCode == 403) return fail(LlmErrorClass.ENTITLEMENT, 'subscription entitlement unavailable', 403)
        if (response.statusCode == 429) {
            Long retry = RetryAfter.parseSeconds(response.headers, Instant.now())
            return fail(LlmErrorClass.RATE_LIMITED,'subscription usage limit reached',429,
                retry == null ? null : Duration.ofSeconds(retry))
        }
        if (response.statusCode < 200 || response.statusCode >= 300) return fail(LlmErrorClass.HTTP_STATUS,'provider returned non-success HTTP status',response.statusCode)
        SubscriptionSseResult parsed
        try { parsed = SubscriptionSseParser.parse(response.body) }
        catch (SubscriptionSseException e) { return fail(e.errorClass, e.code) }
        ValidationResult validation = validator.validate(request, parsed.text)
        if (!validation.accepted) return LlmGatewayResult.failure(validation.error)
        LlmGatewayResult.success(new LlmResponse(request.correlationId,request.suggestionType,
            request.schemaVersion,parsed.text,response.body.length,parsed.inputTokens,parsed.outputTokens))
    }

    private static LlmGatewayResult fail(LlmErrorClass kind, String detail, Integer status=null, Duration retry=null) {
        LlmGatewayResult.failure(new LlmError(kind,detail,status,retry,false))
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
