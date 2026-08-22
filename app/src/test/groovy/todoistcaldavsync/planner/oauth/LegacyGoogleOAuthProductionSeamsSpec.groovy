package todoistcaldavsync.planner.oauth

import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import groovy.json.JsonOutput
import spock.lang.Specification

import java.nio.file.Files
import java.time.Instant

class LegacyGoogleOAuthProductionSeamsSpec extends Specification {

    def "bounded explicit JSON source maps only the referenced local test credential"() {
        given:
        def file = Files.createTempFile('legacy-oauth-source-', '.json')
        Files.writeString(file, JsonOutput.toJson([
            access_token: 'local-access', refresh_token: 'local-refresh',
            expires_at: '2030-01-01T00:00:00Z', scopes: GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT,
            account_email: 'owner@example.test'
        ]))

        when:
        def credential = new JsonFileLegacyGoogleOAuthCredentialSource().load(file.toString())

        then:
        credential.accessToken == 'local-access'
        credential.refreshToken == 'local-refresh'
        credential.expiresAt == Instant.parse('2030-01-01T00:00:00Z')
        credential.scopes == GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT
        credential.accountEmail == 'owner@example.test'
    }

    def "Google token-info validator authenticates verified account and exact QA scopes hermetically"() {
        given:
        def verifier = verifier([
            sub: 'authenticated-subject', email: 'owner@example.test', email_verified: true,
            expires_in: 3600, scope: GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT.join(' ')
        ])
        def credential = credential()

        expect:
        verifier.verify(credential, 'owner@example.test', GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT) ==
            new LegacyGoogleOAuthVerification('authenticated-subject', 'owner@example.test',
                GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT)
    }

    def "Google token-info validator rejects unauthenticated identity and scope evidence"() {
        given:
        def verifier = verifier(responseBody)

        when:
        verifier.verify(credential(), 'owner@example.test', GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT)

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == expected

        where:
        responseBody                                                                                                       | expected
        [sub: 's', email: 'owner@example.test', email_verified: false, expires_in: 3600,
         scope: GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT.join(' ')]                                                        | GoogleOAuthErrorClass.ACCOUNT_MISMATCH
        [sub: 's', email: 'wrong@example.test', email_verified: true, expires_in: 3600,
         scope: GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT.join(' ')]                                                        | GoogleOAuthErrorClass.ACCOUNT_MISMATCH
        [sub: 's', email: 'owner@example.test', email_verified: true, expires_in: 3600,
         scope: GoogleOAuthScopes.EVENTS.join(' ')]                                                                        | GoogleOAuthErrorClass.SCOPE_MISMATCH
        [sub: 's', email: 'owner@example.test', email_verified: true, expires_in: 0,
         scope: GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT.join(' ')]                                                        | GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID
    }

    private static GoogleTokenInfoLegacyOAuthCredentialVerifier verifier(Map body) {
        def response = new MockLowLevelHttpResponse().setStatusCode(200).setContentType('application/json')
            .setContent(JsonOutput.toJson(body))
        new GoogleTokenInfoLegacyOAuthCredentialVerifier(
            new MockHttpTransport.Builder().setLowLevelHttpResponse(response).build())
    }

    private static LegacyGoogleOAuthCredential credential() {
        new LegacyGoogleOAuthCredential('local-access', 'local-refresh', Instant.now().plusSeconds(3600),
            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, 'owner@example.test')
    }
}
