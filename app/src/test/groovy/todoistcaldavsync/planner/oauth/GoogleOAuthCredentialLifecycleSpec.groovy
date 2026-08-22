package todoistcaldavsync.planner.oauth

import com.github.tomakehurst.wiremock.WireMockServer
import com.google.api.client.http.javanet.NetHttpTransport
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Duration
import java.time.Instant

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class GoogleOAuthCredentialLifecycleSpec extends Specification {
    WireMockServer server
    Instant now = Instant.parse('2026-08-21T12:00:00Z')

    def setup() {
        server = new WireMockServer(options().dynamicPort())
        server.start()
    }

    def cleanup() { server.stop() }

    private GoogleOAuthClientMaterial material() {
        new GoogleOAuthClientMaterial('client-id-secret', 'client-secret-value',
            URI.create("http://127.0.0.1:${server.port()}/authorize"),
            URI.create("http://127.0.0.1:${server.port()}/token"))
    }

    private GoogleOAuthCredentialService service(InMemoryGoogleOAuthTokenStore store,
                                                  Set<String> scopes = GoogleOAuthScopes.EVENTS) {
        new GoogleOAuthCredentialService(material(), store, scopes, 'owner@example.test',
            { now }, new NetHttpTransport(), Duration.ofMinutes(2), 1024)
    }

    def "fresh credential is returned without a provider request"() {
        given:
        def store = new InMemoryGoogleOAuthTokenStore()
        store.save(new GoogleOAuthTokenState('fresh-access', 'refresh-value', now.plusSeconds(600),
            GoogleOAuthScopes.EVENTS, 'owner@example.test', 'owner-subject'))

        expect:
        service(store).accessToken() == 'fresh-access'
        server.allServeEvents.empty
        store.writeCount == 1
    }

    def "credential inside refresh window refreshes before use and persists replacement"() {
        given:
        server.stubFor(post(urlEqualTo('/token'))
            .withRequestBody(containing('grant_type=refresh_token'))
            .withRequestBody(containing('refresh_token=refresh-value'))
            .willReturn(okJson('{"access_token":"new-access","expires_in":3600,"token_type":"Bearer","scope":"https://www.googleapis.com/auth/calendar.events"}')))
        def store = new InMemoryGoogleOAuthTokenStore()
        store.save(new GoogleOAuthTokenState('old-access', 'refresh-value', now.plusSeconds(30),
            GoogleOAuthScopes.EVENTS, 'owner@example.test', 'owner-subject'))

        when:
        String access = service(store).accessToken()

        then:
        access == 'new-access'
        store.load().get().refreshToken == 'refresh-value'
        store.load().get().expiresAt == now.plusSeconds(3600)
        store.writeCount == 2
        server.verify(1, postRequestedFor(urlEqualTo('/token')))
    }

    @Unroll("refresh failure is closed and redacted [#expected]")
    def "revoked malformed and oversized refresh responses fail closed and redact secrets"() {
        given:
        server.stubFor(post(urlEqualTo('/token')).willReturn(aResponse().withStatus(status).withHeader('Content-Type', 'application/json').withBody(body)))
        def store = new InMemoryGoogleOAuthTokenStore()
        store.save(new GoogleOAuthTokenState('old-access-secret', 'refresh-value', now.minusSeconds(1),
            GoogleOAuthScopes.EVENTS, 'owner@example.test', 'owner-subject'))

        when:
        service(store).accessToken()

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == expected
        !error.message.contains('provider-access-secret')
        !error.message.contains('refresh-value')
        !error.message.contains('client-secret-value')
        !error.message.contains('Bearer')
        store.writeCount == 1
        server.verify(1, postRequestedFor(urlEqualTo('/token')))

        where:
        status | body                                                                                                                               | expected
        400    | '{"error":"invalid_grant","access_token":"provider-access-secret"}'                                                         | GoogleOAuthErrorClass.CREDENTIAL_REVOKED
        200    | '{"access_token":"provider-access-secret","expires_in":"not-a-number"}'                                                     | GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID
        200    | '{"access_token":"provider-access-secret","expires_in":3600,"scope":"https://www.googleapis.com/auth/calendar"}'           | GoogleOAuthErrorClass.SCOPE_MISMATCH
        200    | '{"padding":"' + ('x' * 2048) + '","access_token":"provider-access-secret"}'                                               | GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID
    }

    def "normal credential lifecycle rejects broad legacy scope before any request"() {
        given:
        def store = new InMemoryGoogleOAuthTokenStore()
        store.save(new GoogleOAuthTokenState('legacy-access', 'legacy-refresh', now.plusSeconds(600),
            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, 'owner@example.test', 'owner-subject'))

        when:
        service(store).accessToken()

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == GoogleOAuthErrorClass.SCOPE_MISMATCH
        server.allServeEvents.empty
        store.writeCount == 1
    }

    def "redactor removes OAuth values and authorization headers"() {
        given:
        String unsafe = 'client_id=cid client_secret=abc authorization_code=xyz code_verifier=pkce id_token=jwt access_token=aaa refresh_token=rrr Authorization: Bearer bearer-value'

        expect:
        GoogleOAuthRedactor.redact(unsafe) == 'client_id=[REDACTED] client_secret=[REDACTED] authorization_code=[REDACTED] code_verifier=[REDACTED] id_token=[REDACTED] access_token=[REDACTED] refresh_token=[REDACTED] Authorization: [REDACTED]'
    }
}
