package todoistcaldavsync.planner.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification
import todoistcaldavsync.planner.domain.Message

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class ExternalProviderWireMockSpec extends Specification {
    WireMockServer server
    Instant now = Instant.parse('2026-08-13T12:00:00Z')

    def setup() { server = new WireMockServer(options().dynamicPort()); server.start() }
    def cleanup() { server.stop() }

    private String fixture(String name) { getClass().classLoader.getResource("planner/fixtures/${name}").text }

    def "Open-Meteo boundary sends explicit location fields and parses fixture response"() {
        given:
        server.stubFor(get(urlPathEqualTo('/v1/forecast')).willReturn(okJson(fixture('weather-open-meteo-clear.json'))))
        def gateway = new OpenMeteoWeatherGateway(40.7128d, -74.006d, ZoneId.of('America/New_York'), 7,
            "http://localhost:${server.port()}/v1/forecast")

        when:
        def forecast = gateway.fetchForecast(Instant.parse('2026-08-13T00:00:00Z'), Instant.parse('2026-08-15T00:00:00Z'))

        then:
        forecast.provider == 'open_meteo'
        forecast.intervals
        server.verify(getRequestedFor(urlPathEqualTo('/v1/forecast'))
            .withQueryParam('latitude', equalTo('40.7128'))
            .withQueryParam('longitude', equalTo('-74.006'))
            .withQueryParam('timezone', equalTo('America/New_York'))
            .withQueryParam('hourly', containing('precipitation_probability'))
            .withQueryParam('daily', containing('sunrise')))
    }

    def "Open-Meteo HTTP failure is fail-closed and does not mutate other boundaries"() {
        given:
        server.stubFor(get(urlPathEqualTo('/v1/forecast')).willReturn(aResponse().withStatus(503)))
        def gateway = new OpenMeteoWeatherGateway(1d, 2d, ZoneId.of('UTC'), 2,
            "http://localhost:${server.port()}/v1/forecast")

        when:
        gateway.fetchForecast(null, null)

        then:
        def e = thrown(OpenMeteoWeatherGateway.WeatherGatewayException)
        e.classification == 'HTTP_STATUS'
        server.verify(0, postRequestedFor(anyUrl()))
        server.verify(0, putRequestedFor(anyUrl()))
        server.verify(0, deleteRequestedFor(anyUrl()))
    }

    def "Slack webhook and chat API boundaries assert payload auth success and rate failure"() {
        given:
        server.stubFor(post(urlEqualTo('/slack/webhook')).willReturn(aResponse().withStatus(200).withBody('ok')))
        server.stubFor(post(urlEqualTo('/slack/chat')).willReturn(okJson(fixture('slack-chat-success.json'))))
        server.stubFor(post(urlEqualTo('/slack/rate')).willReturn(aResponse().withStatus(429).withHeader('Retry-After', '9')))
        def proxy = { String path ->
            return { SlackMessagingGateway.HttpCall call ->
                def b = HttpRequest.newBuilder(URI.create("http://localhost:${server.port()}${path}"))
                    .header('Content-Type', 'application/json')
                call.headers.each { k, v -> if (!k.equalsIgnoreCase('Content-Type')) b.header(k, v) }
                def response = HttpClient.newHttpClient().send(
                    b.POST(HttpRequest.BodyPublishers.ofString(call.body)).build(),
                    HttpResponse.BodyHandlers.ofString())
                new SlackMessagingGateway.HttpResult(response.statusCode(), response.body(), response.headers().map())
            }
        }
        def webhook = new SlackMessagingGateway(mode: 'webhook', webhookUrlOverride: 'https://hooks.slack.com/services/T/B/x',
            destination: '#planner', transport: proxy('/slack/webhook'), clock: { now })
        def chat = new SlackMessagingGateway(mode: 'chat_api', botTokenOverride: 'xoxb-wiremock-secret',
            destination: 'CPLANNER', transport: proxy('/slack/chat'), clock: { now })
        def rate = new SlackMessagingGateway(mode: 'webhook', webhookUrlOverride: 'https://hooks.slack.com/services/T/B/rate',
            destination: '#planner', transport: proxy('/slack/rate'), clock: { now })

        when:
        def webhookReceipt = webhook.send(message('#planner'))
        def chatReceipt = chat.send(message('CPLANNER'))
        def rateReceipt = rate.send(message('#planner'))

        then:
        webhookReceipt.status == 'DELIVERED'
        chatReceipt.status == 'DELIVERED'
        chatReceipt.providerMessageId == '1723640400.123'
        rateReceipt.status == 'FAILED'
        rateReceipt.errorClassification == 'RATE_LIMIT'
        rateReceipt.metadata.retryAfterSeconds == 9L
        server.verify(postRequestedFor(urlEqualTo('/slack/webhook'))
            .withoutHeader('Authorization')
            .withRequestBody(matchingJsonPath('$.text'))
            .withRequestBody(matchingJsonPath('$.blocks')))
        server.verify(postRequestedFor(urlEqualTo('/slack/chat'))
            .withHeader('Authorization', equalTo('Bearer xoxb-wiremock-secret'))
            .withRequestBody(matchingJsonPath('$.channel', equalTo('CPLANNER')))
            .withRequestBody(matchingJsonPath('$.mrkdwn', equalTo('true'))))
        !webhookReceipt.toMap().toString().contains('xoxb-wiremock-secret')
        !chatReceipt.toMap().toString().contains('xoxb-wiremock-secret')
    }

    private Message message(String destination) {
        Message.builder().kind('daily_summary').subject('Daily plan').body('One task')
            .destination(destination).planId('p1').planVersion(1).planHash('a' * 64)
            .idempotencyKey("msg-${destination}").createdAt(now).build()
    }
}
