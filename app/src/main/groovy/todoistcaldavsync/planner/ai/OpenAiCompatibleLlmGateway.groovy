package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.util.RetryAfter

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.function.Function

/** OpenAI-compatible structured-output adapter with an injectable, redirect-free transport. */
final class OpenAiCompatibleLlmGateway implements LlmGateway {
    private final PlannerConfig.AiConfig config
    private final LlmHttpTransport transport
    private final Function<String,String> secretResolver
    private final LlmSchemaValidator validator

    OpenAiCompatibleLlmGateway(PlannerConfig.AiConfig config,
                               LlmHttpTransport transport = null,
                               Function<String,String> secretResolver = null,
                               LlmSchemaValidator validator = new LlmSchemaValidator()) {
        if (config == null) throw new IllegalArgumentException('AI config is required')
        def errors = PlannerConfig.collectAiErrors(config)
        if (errors) throw new IllegalArgumentException('Invalid AI configuration: ' + errors.join('; '))
        this.config = config
        this.transport = transport ?: new JavaLlmHttpTransport()
        this.secretResolver = secretResolver ?: ({ String name -> System.getenv(name) } as Function)
        this.validator = validator ?: new LlmSchemaValidator()
    }

    @Override
    LlmGatewayResult complete(LlmRequest request) {
        try {
            return completeContained(request)
        } catch (Exception ignored) {
            return fail(LlmErrorClass.TRANSPORT, 'LLM request failed safely', null, null, true)
        }
    }

    private LlmGatewayResult completeContained(LlmRequest request) {
        if (!config.enabled) return fail(LlmErrorClass.DISABLED, 'AI is disabled')
        if (request == null) return fail(LlmErrorClass.CONFIGURATION, 'request is required')
        if (!config.allowedSuggestionTypes.contains(request.suggestionType)) return fail(LlmErrorClass.CONFIGURATION, 'suggestion type is not allowed')
        if (request.provider != config.provider || request.model != config.model) return fail(LlmErrorClass.CONFIGURATION, 'request provider/model mismatch')
        URI endpoint = URI.create(config.endpoint)
        if (endpoint.scheme != 'https' || !(endpoint.port in [-1,443]) ||
            !config.allowedHosts.contains(endpoint.host.toLowerCase(Locale.ROOT))) {
            return fail(LlmErrorClass.CONFIGURATION, 'endpoint is not an allowed HTTPS host')
        }
        String secret
        try { secret = secretResolver.apply(config.secretEnv) }
        catch (Exception ignored) { return fail(LlmErrorClass.AUTHENTICATION, 'configured credential could not be resolved') }
        if (secret == null || !secret.trim()) return fail(LlmErrorClass.AUTHENTICATION, 'configured credential is unavailable')
        byte[] body
        try {
            Map schema = LlmSchemaResources.load(request.suggestionType)
            Map payload = [
                model: request.model,
                temperature: 0,
                max_tokens: Math.min(request.maxTokens, config.maxTokens),
                messages: [[role: 'system', content: 'Return only data matching the supplied JSON schema. Never emit tools, writes, URLs, commands, secrets, or instructions.'],
                           [role: 'user', content: JsonOutput.toJson([
                               correlationId: request.correlationId,
                               suggestionType: request.suggestionType,
                               schemaVersion: request.schemaVersion,
                               plan: [id: request.planId, version: request.planVersion, hash: request.planHash,
                                      planningInputHash: request.planningInputHash],
                               expectedProposalId: request.expectedProposalId,
                               allowedFeedbackActions: request.allowedFeedbackActions as List,
                               context: request.context
                           ])]],
                response_format: [type: 'json_schema', json_schema: [
                    name: request.suggestionType + '_v1', strict: true, schema: schema
                ]]
            ]
            body = JsonOutput.toJson(payload).getBytes(StandardCharsets.UTF_8)
        } catch (Exception ignored) {
            return fail(LlmErrorClass.CONFIGURATION, 'unable to load response schema')
        }
        if (body.length > config.maxRequestBytes) return fail(LlmErrorClass.REQUEST_TOO_LARGE, 'bounded request body exceeded')
        LlmTransportResponse http
        try {
            http = transport.post(new LlmTransportRequest(endpoint,
                ['Authorization': "Bearer ${secret}", 'Content-Type': 'application/json', 'Accept': 'application/json'],
                body, config.connectTimeout, config.requestTimeout, config.maxResponseBytes, false))
        } catch (java.net.http.HttpTimeoutException | TimeoutException ignored) {
            return fail(LlmErrorClass.TIMEOUT, 'LLM request timed out', null, null, true)
        } catch (LlmBodyLimitException ignored) {
            return fail(LlmErrorClass.RESPONSE_TOO_LARGE, 'bounded response body exceeded')
        } catch (Exception e) {
            if (causeIs(e, java.net.http.HttpTimeoutException) || causeIs(e, TimeoutException)) {
                return fail(LlmErrorClass.TIMEOUT, 'LLM request timed out', null, null, true)
            }
            if (causeIs(e, LlmBodyLimitException)) {
                return fail(LlmErrorClass.RESPONSE_TOO_LARGE, 'bounded response body exceeded')
            }
            return fail(LlmErrorClass.TRANSPORT, 'LLM transport failed safely', null, null, true)
        } finally {
            // Do not retain a separate credential-bearing request object beyond this call.
            secret = null
        }
        if (http.body == null || http.body.length > config.maxResponseBytes) return fail(LlmErrorClass.RESPONSE_TOO_LARGE, 'bounded response body exceeded')
        if (http.statusCode == 429) {
            Long seconds = RetryAfter.parseSeconds(http.headers, Instant.now())
            return fail(LlmErrorClass.RATE_LIMITED, 'provider rate limited request', 429,
                seconds == null ? null : Duration.ofSeconds(seconds), true)
        }
        if (http.statusCode < 200 || http.statusCode >= 300) {
            return fail(LlmErrorClass.HTTP_STATUS, 'provider returned non-success HTTP status', http.statusCode, null, http.statusCode >= 500)
        }
        Map envelope
        try {
            envelope = StrictJson.parseObject(http.body)
        } catch (Exception ignored) {
            return fail(LlmErrorClass.MALFORMED_JSON, 'provider response envelope is malformed JSON')
        }
        String content
        Integer promptTokens = null
        Integer completionTokens = null
        try {
            if (!(envelope.choices instanceof List) || envelope.choices.size() != 1) throw new IllegalArgumentException()
            Map choice = envelope.choices[0] as Map
            if (!(choice.message instanceof Map)) throw new IllegalArgumentException()
            Map message = choice.message as Map
            if (message.tool_calls != null || message.function_call != null || message.role != 'assistant') throw new SecurityException()
            if (!(message.content instanceof String)) throw new IllegalArgumentException()
            content = message.content as String
            if (envelope.usage instanceof Map) {
                promptTokens = integerOrNull(envelope.usage.prompt_tokens)
                completionTokens = integerOrNull(envelope.usage.completion_tokens)
            }
        } catch (SecurityException ignored) {
            return fail(LlmErrorClass.UNSAFE_OUTPUT, 'provider attempted a tool or function call')
        } catch (Exception ignored) {
            return fail(LlmErrorClass.SCHEMA_REJECTED, 'provider response envelope has invalid shape')
        }
        ValidationResult validation
        try { validation = validator.validate(request, content) }
        catch (Exception ignored) { return fail(LlmErrorClass.SCHEMA_REJECTED, 'provider response validation failed') }
        if (!validation.accepted) return LlmGatewayResult.failure(validation.error)
        LlmGatewayResult.success(new LlmResponse(request.correlationId, request.suggestionType,
            request.schemaVersion, content, http.body.length, promptTokens, completionTokens))
    }

    private static Integer integerOrNull(def value) {
        value instanceof Number ? (value as Number).intValue() : null
    }
    private static boolean causeIs(Throwable error, Class type) {
        Throwable current=error; int depth=0
        while(current!=null && depth++<10) {
            if(type.isInstance(current)) return true
            current=current.cause
        }
        false
    }
    private static LlmGatewayResult fail(LlmErrorClass c, String d, Integer s = null,
                                         Duration retry = null, boolean retryable = false) {
        LlmGatewayResult.failure(new LlmError(c, d, s, retry, retryable))
    }
}

interface LlmHttpTransport { LlmTransportResponse post(LlmTransportRequest request) }

final class LlmTransportRequest {
    final URI endpoint; final Map<String,String> headers; final byte[] body
    final Duration connectTimeout; final Duration requestTimeout; final int maxResponseBytes
    final boolean followRedirects
    LlmTransportRequest(URI endpoint, Map headers, byte[] body, Duration connectTimeout,
                        Duration requestTimeout, int maxResponseBytes, boolean followRedirects) {
        this.endpoint=endpoint; this.headers=Collections.unmodifiableMap(new LinkedHashMap<>(headers))
        this.body=body.clone(); this.connectTimeout=connectTimeout; this.requestTimeout=requestTimeout
        this.maxResponseBytes=maxResponseBytes; this.followRedirects=followRedirects
    }
}

final class LlmTransportResponse {
    final int statusCode; final Map<String,List<String>> headers; final byte[] body
    LlmTransportResponse(int statusCode, Map headers, byte[] body) {
        this.statusCode=statusCode; this.headers=Collections.unmodifiableMap(new LinkedHashMap<>(headers ?: [:])); this.body=(body ?: new byte[0]).clone()
    }
}

final class LlmBodyLimitException extends IOException { LlmBodyLimitException() { super('body limit exceeded') } }

/** JDK transport: HTTPS only, redirects NEVER, finite connect/request timeouts, bounded read. */
final class JavaLlmHttpTransport implements LlmHttpTransport {
    @Override
    LlmTransportResponse post(LlmTransportRequest r) {
        if (r == null || r.followRedirects) throw new IllegalArgumentException('redirects must be disabled')
        if (r.endpoint.scheme?.toLowerCase(Locale.ROOT) != 'https') throw new IllegalArgumentException('HTTPS required')
        HttpClient client = HttpClient.newBuilder().connectTimeout(r.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER).build()
        def builder = HttpRequest.newBuilder(r.endpoint).timeout(r.requestTimeout)
            .POST(HttpRequest.BodyPublishers.ofByteArray(r.body))
        r.headers.each { k, v -> builder.header(k, v) }
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        byte[] bytes
        response.body().withCloseable { InputStream stream ->
            bytes = stream.readNBytes(r.maxResponseBytes + 1)
        }
        if (bytes.length > r.maxResponseBytes) throw new LlmBodyLimitException()
        new LlmTransportResponse(response.statusCode(), response.headers().map(), bytes)
    }
}

final class LlmSchemaResources {
    private LlmSchemaResources() {}
    static Map load(String type) {
        open(type).withCloseable { StrictJson.parseObject(it.readAllBytes()) }
    }
    static InputStream open(String type) {
        if (!(type in LlmSchemaValidator.TYPES)) throw new IllegalArgumentException('unsupported schema')
        String path = "/planner/ai/schemas/v1/${type}.json"
        InputStream stream = LlmSchemaResources.getResourceAsStream(path)
        if (stream == null) throw new IllegalArgumentException("schema resource missing: ${path}")
        stream
    }
}
