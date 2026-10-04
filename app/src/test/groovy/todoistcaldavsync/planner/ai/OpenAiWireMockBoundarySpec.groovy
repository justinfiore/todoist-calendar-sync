package todoistcaldavsync.planner.ai

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class OpenAiWireMockBoundarySpec extends Specification {
    WireMockServer server
    def setup() { server = new WireMockServer(options().dynamicPort()); server.start() }
    def cleanup() { server.stop() }

    private PlannerConfig config() {
        PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'UTC',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            ai: [enabled: true, provider: 'openai_compatible',
                endpoint: 'https://api.openai.com/v1/chat/completions', model: 'gpt-fixture',
                secret_env: 'LLM_WIREMOCK_KEY', allowed_hosts: ['api.openai.com']]])
    }

    private LlmRequest request(PlannerConfig cfg) {
        new LlmRequest(correlationId: 'wiremock-1', suggestionType: 'task_suggestions', schemaVersion: 1,
            provider: cfg.ai.provider, model: cfg.ai.model, planId: 'plan-1', planVersion: 1,
            planHash: 'a' * 64, planningInputHash: 'b' * 64,
            context: [tasks: [[id: 'task-1', title: 'Safe task']]],
            allowedTaskIds: ['task-1'] as Set, allowedEventIds: [] as Set, maxTokens: 300)
    }

    def "OpenAI-compatible boundary sends bearer strict schema bounded request and parses response"() {
        given:
        String content = JsonOutput.toJson([schemaVersion: 1, suggestionType: 'task_suggestions',
            correlationId: 'wiremock-1', suggestions: []])
        String envelope = JsonOutput.toJson([choices: [[message: [role: 'assistant', content: content]]],
            usage: [prompt_tokens: 11, completion_tokens: 4]])
        server.stubFor(post(urlEqualTo('/v1/chat/completions')).willReturn(okJson(envelope)))
        def cfg = config()
        def gateway = new OpenAiCompatibleLlmGateway(cfg.ai, proxyTransport(), { name -> 'llm-secret-value' })

        when:
        def result = gateway.complete(request(cfg))

        then:
        result.success
        result.response.promptTokens == 11
        result.response.completionTokens == 4
        server.verify(postRequestedFor(urlEqualTo('/v1/chat/completions'))
            .withHeader('Authorization', equalTo('Bearer llm-secret-value'))
            .withHeader('Content-Type', equalTo('application/json'))
            .withRequestBody(matchingJsonPath('$.model', equalTo('gpt-fixture')))
            .withRequestBody(matchingJsonPath('$.temperature', equalTo('0')))
            .withRequestBody(matchingJsonPath('$.max_tokens', equalTo('300')))
            .withRequestBody(matchingJsonPath('$.response_format.type', equalTo('json_schema')))
            .withRequestBody(matchingJsonPath('$.response_format.json_schema.strict', equalTo('true'))))
        String sent = server.allServeEvents[0].request.bodyAsString
        !sent.contains('llm-secret-value')
        !sent.contains('"tools"')
        !sent.contains('"functions"')
    }

    def "OpenAI-compatible 429 returns bounded retry metadata and never retries a suggestion call"() {
        given:
        server.stubFor(post(urlEqualTo('/v1/chat/completions')).willReturn(aResponse().withStatus(429).withHeader('Retry-After', '17')))
        def cfg = config()
        def gateway = new OpenAiCompatibleLlmGateway(cfg.ai, proxyTransport(), { name -> 'secret' })

        when:
        def result = gateway.complete(request(cfg))

        then:
        !result.success
        result.error.errorClass == LlmErrorClass.RATE_LIMITED
        result.error.retryAfter == Duration.ofSeconds(17)
        result.error.retryable
        server.verify(1, postRequestedFor(urlEqualTo('/v1/chat/completions')))
        !result.error.detail.contains('secret')
    }

    private LlmHttpTransport proxyTransport() {
        return { LlmTransportRequest req ->
            assert req.endpoint.toString() == 'https://api.openai.com/v1/chat/completions'
            def builder = HttpRequest.newBuilder(URI.create("http://localhost:${server.port()}/v1/chat/completions"))
            req.headers.each { k, v -> builder.header(k, v) }
            def response = HttpClient.newHttpClient().send(
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(req.body)).build(),
                HttpResponse.BodyHandlers.ofByteArray())
            new LlmTransportResponse(response.statusCode(), response.headers().map(), response.body())
        } as LlmHttpTransport
    }
}
