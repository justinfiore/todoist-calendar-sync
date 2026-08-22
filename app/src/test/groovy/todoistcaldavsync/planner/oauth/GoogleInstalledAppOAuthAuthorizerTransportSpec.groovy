package todoistcaldavsync.planner.oauth

import com.github.tomakehurst.wiremock.WireMockServer
import com.google.api.client.http.javanet.NetHttpTransport
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class GoogleInstalledAppOAuthAuthorizerTransportSpec extends Specification {
    WireMockServer server
    int callbackPort
    List<URI> consentUris
    InMemoryGoogleOAuthTokenStore store

    def setup() {
        server = new WireMockServer(options().dynamicPort())
        server.start()
        callbackPort = new ServerSocket(0, 1, InetAddress.getByName('127.0.0.1')).withCloseable { it.localPort }
        consentUris = []
        store = new InMemoryGoogleOAuthTokenStore()
    }

    def cleanup() { server?.stop() }

    private GoogleOAuthClientMaterial material() {
        new GoogleOAuthClientMaterial('client-id-value', 'client-secret-value',
            URI.create("http://127.0.0.1:${server.port()}/authorize"),
            URI.create("http://127.0.0.1:${server.port()}/token"))
    }

    private GoogleInstalledAppOAuthAuthorizer authorizer(GoogleOAuthIdentityVerifier verifier) {
        new GoogleInstalledAppOAuthAuthorizer(new NetHttpTransport(),
            { Instant.parse('2026-08-21T12:00:00Z') }, { 'fixed-state' },
            { 'fixed-verifier-value-with-sufficient-entropy-1234567890' }, verifier)
    }

    private GoogleOAuthTokenState authorize(Set<String> scopes, GoogleOAuthIdentityVerifier verifier) {
        authorizer(verifier).authorize(material(), scopes, 'owner@example.test', '127.0.0.1', callbackPort,
            { String raw ->
                URI consent = URI.create(raw)
                consentUris << consent
                Map query = query(consent)
                URI callback = URI.create(query.redirect_uri + '?code=authorization-code-secret&state=' + query.state)
                (callback.toURL().openConnection() as HttpURLConnection).responseCode
            })
    }

    def "authorization code exchange uses redirect state PKCE exact lifecycle scope and verified identity"() {
        given:
        String returnedScopes = (GoogleOAuthScopes.EVENTS + GoogleOAuthScopes.IDENTITY).join(' ')
        server.stubFor(post('/token').willReturn(okJson('''{
          "access_token":"access-secret","refresh_token":"refresh-secret","expires_in":3600,
          "scope":"''' + returnedScopes + '''","id_token":"signed-id-token-secret"
        }''')))
        def verifier = Mock(GoogleOAuthIdentityVerifier)

        when:
        GoogleOAuthTokenState state = authorize(GoogleOAuthScopes.EVENTS, verifier)

        then:
        1 * verifier.verify('signed-id-token-secret', _) >>
            new GoogleOAuthVerifiedIdentity('stable-google-subject', 'owner@example.test')
        state.accountEmail == 'owner@example.test'
        state.accountSubject == 'stable-google-subject'
        state.scopes == GoogleOAuthScopes.EVENTS
        Map consent = query(consentUris[0])
        consent.state == 'fixed-state'
        consent.redirect_uri == "http://127.0.0.1:${callbackPort}/oauth2callback"
        (consent.scope.split(' ') as Set) == GoogleOAuthScopes.EVENTS + GoogleOAuthScopes.IDENTITY
        consent.code_challenge_method == 'S256'
        consent.code_challenge == sha256Url('fixed-verifier-value-with-sufficient-entropy-1234567890')
        server.verify(postRequestedFor(urlEqualTo('/token'))
            .withRequestBody(containing('grant_type=authorization_code'))
            .withRequestBody(containing('code=authorization-code-secret'))
            .withRequestBody(containing('client_id=client-id-value'))
            .withRequestBody(containing('client_secret=client-secret-value'))
            .withRequestBody(containing('redirect_uri=http%3A%2F%2F127.0.0.1'))
            .withRequestBody(containing('code_verifier=fixed-verifier-value')))
    }

    def "QA authorization requests the exact QA lifecycle scopes plus identity evidence scopes"() {
        given:
        String returnedScopes = (GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT + GoogleOAuthScopes.IDENTITY).join(' ')
        server.stubFor(post('/token').willReturn(okJson('''{
          "access_token":"qa-access","refresh_token":"qa-refresh","expires_in":3600,
          "scope":"''' + returnedScopes + '''","id_token":"qa-id-token"
        }''')))
        def verifier = Stub(GoogleOAuthIdentityVerifier)
        verifier.verify(_, _) >> new GoogleOAuthVerifiedIdentity('qa-subject', 'owner@example.test')

        when:
        GoogleOAuthTokenState state = authorize(GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, verifier)

        then:
        (query(consentUris[0]).scope.split(' ') as Set) ==
            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT + GoogleOAuthScopes.IDENTITY
        state.scopes == GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT
    }

    @Unroll("exchange failure is closed, redacted, and unwritten: #caseName")
    def "malformed error oversized and unverified exchange responses never produce persistable state"() {
        given:
        server.stubFor(post('/token').willReturn(aResponse().withStatus(status)
            .withHeader('Content-Type', 'application/json').withBody(body)))
        def verifier = Stub(GoogleOAuthIdentityVerifier)
        verifier.verify(_, _) >> identity
        when:
        store.save(authorize(GoogleOAuthScopes.EVENTS, verifier))

        then:
        def error = thrown(Exception)
        !error.message.contains('provider-access-secret')
        !error.message.contains('signed-id-token-secret')
        !error.message.contains('client-secret-value')
        store.writeCount == 0

        where:
        caseName       | status | body                                                                                                                            | identity
        'malformed'    | 200    | '{"access_token":"provider-access-secret"'                                                                                   | null
        'provider err' | 400    | '{"error":"invalid_grant","access_token":"provider-access-secret"}'                                                    | null
        'oversized'    | 200    | '{"padding":"' + ('x' * 70_000) + '","access_token":"provider-access-secret"}'                                      | null
        'no id token'  | 200    | '{"access_token":"provider-access-secret","refresh_token":"r","expires_in":3600,"scope":"' +
                                     (GoogleOAuthScopes.EVENTS + GoogleOAuthScopes.IDENTITY).join(' ') + '"}'                              | null
        'wrong account'| 200    | '{"access_token":"provider-access-secret","refresh_token":"r","expires_in":3600,"scope":"' +
                                     (GoogleOAuthScopes.EVENTS + GoogleOAuthScopes.IDENTITY).join(' ') + '","id_token":"signed-id-token-secret"}' |
                                     new GoogleOAuthVerifiedIdentity('subject', 'wrong@example.test')
    }

    private static Map<String, String> query(URI uri) {
        uri.rawQuery.split('&').collectEntries { String pair ->
            String[] parts = pair.split('=', 2)
            [(URLDecoder.decode(parts[0], StandardCharsets.UTF_8)):
                URLDecoder.decode(parts.length == 2 ? parts[1] : '', StandardCharsets.UTF_8)]
        }
    }

    private static String sha256Url(String value) {
        Base64.urlEncoder.withoutPadding().encodeToString(
            MessageDigest.getInstance('SHA-256').digest(value.getBytes(StandardCharsets.US_ASCII)))
    }
}
