package todoistcaldavsync.planner.oauth

import spock.lang.Specification

import java.time.Instant
import java.nio.file.Path

class LegacyGoogleOAuthCredentialImporterSpec extends Specification {
    Instant expiry = Instant.parse('2030-01-01T00:00:00Z')

    def "confirmed legacy broad credential imports only to QA store after offline validation"() {
        given:
        def normal = new InMemoryGoogleOAuthTokenStore()
        def qa = new InMemoryGoogleOAuthTokenStore()
        def verifier = Mock(LegacyGoogleOAuthCredentialVerifier)
        def importer = new LegacyGoogleOAuthCredentialImporter('qa-owner@example.test', normal, qa, verifier)
        def legacy = new LegacyGoogleOAuthCredential('legacy-access', 'legacy-refresh', expiry,
            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, 'qa-owner@example.test')

        when:
        importer.importConfirmedForQa(legacy, true)

        then:
        1 * verifier.verify(legacy, 'qa-owner@example.test', GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT) >>
            new LegacyGoogleOAuthVerification('verified-subject', 'qa-owner@example.test',
                GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT)
        normal.load().empty
        normal.writeCount == 0
        qa.load().get().refreshToken == 'legacy-refresh'
        qa.load().get().scopes == GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT
        qa.writeCount == 1
    }

    def "legacy validation failure writes neither store and requires no Google Calendar seam"() {
        given:
        def normal = new InMemoryGoogleOAuthTokenStore()
        def qa = new InMemoryGoogleOAuthTokenStore()
        def verifier = Stub(LegacyGoogleOAuthCredentialVerifier) {
            verify(_, _, _) >> verification
        }
        def importer = new LegacyGoogleOAuthCredentialImporter('qa-owner@example.test', normal, qa, verifier)
        def legacy = new LegacyGoogleOAuthCredential('legacy-access-secret', 'legacy-refresh-secret', expiry,
            scopes, account)

        when:
        importer.importConfirmedForQa(legacy, confirmed)

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == expected
        !error.message.contains('legacy-access-secret')
        !error.message.contains('legacy-refresh-secret')
        normal.writeCount == 0
        qa.writeCount == 0

        where:
        confirmed | account                 | scopes                                   | verification                                                              | expected
        false     | 'qa-owner@example.test' | GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT | null                                                                      | GoogleOAuthErrorClass.CLIENT_CONFIGURATION
        true      | 'wrong@example.test'    | GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT | new LegacyGoogleOAuthVerification('s', 'wrong@example.test', GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT) | GoogleOAuthErrorClass.ACCOUNT_MISMATCH
        true      | 'qa-owner@example.test' | GoogleOAuthScopes.EVENTS                  | new LegacyGoogleOAuthVerification('s', 'qa-owner@example.test', GoogleOAuthScopes.EVENTS)            | GoogleOAuthErrorClass.SCOPE_MISMATCH
        true      | 'qa-owner@example.test' | GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT | null                                                                      | GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID
    }

    def "legacy calendar-management credential can never import into normal store"() {
        given:
        def normal = new InMemoryGoogleOAuthTokenStore()
        def qa = new InMemoryGoogleOAuthTokenStore()
        def importer = new LegacyGoogleOAuthCredentialImporter('qa-owner@example.test', normal, qa,
            Stub(LegacyGoogleOAuthCredentialVerifier))
        def legacy = new LegacyGoogleOAuthCredential('access', 'refresh', expiry,
            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, 'qa-owner@example.test')

        when:
        importer.importIntoNormal(legacy)

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == GoogleOAuthErrorClass.SCOPE_MISMATCH
        normal.writeCount == 0
        qa.writeCount == 0
    }

    def "legacy importer rejects the same store instance before verifier or write"() {
        given:
        def shared = new InMemoryGoogleOAuthTokenStore()
        def verifier = Mock(LegacyGoogleOAuthCredentialVerifier)

        when:
        new LegacyGoogleOAuthCredentialImporter('qa-owner@example.test', shared, shared, verifier)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('distinct')
        0 * verifier._
        shared.writeCount == 0
    }

    def "production legacy QA import composition is hermetic and writes only verified QA store"() {
        given:
        Path root = java.nio.file.Files.createTempDirectory('legacy-qa-operation-')
        Path normalPath = root.resolve('normal')
        Path qaPath = root.resolve('qa')
        def verifier = Stub(LegacyGoogleOAuthCredentialVerifier) {
            verify(_, _, _) >> new LegacyGoogleOAuthVerification('subject', 'qa-owner@example.test',
                GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT)
        }
        def operation = LegacyGoogleOAuthQaImportOperation.production(
            'qa-owner@example.test', normalPath, qaPath, verifier)
        def legacy = new LegacyGoogleOAuthCredential('access', 'refresh', expiry,
            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, 'qa-owner@example.test')

        when:
        operation.importConfirmed(legacy, true)

        then:
        !java.nio.file.Files.exists(normalPath.resolve('credential.json'))
        new PrivateFileGoogleOAuthTokenStore(qaPath).load().get().accountSubject == 'subject'
    }

    def "operator operation loads explicit reference then authenticates account and scopes before QA write"() {
        given:
        def source = Mock(LegacyGoogleOAuthCredentialSource)
        def verifier = Mock(LegacyGoogleOAuthCredentialVerifier)
        def qa = new InMemoryGoogleOAuthTokenStore()
        def importer = new LegacyGoogleOAuthCredentialImporter('qa-owner@example.test', qa, verifier)
        def operation = new LegacyGoogleOAuthQaImportOperation(source, importer)
        def legacy = new LegacyGoogleOAuthCredential('access', 'refresh', expiry,
            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, 'qa-owner@example.test')

        when:
        operation.importConfirmedReference('operator-vault:item-42', true)

        then:
        1 * source.load('operator-vault:item-42') >> legacy

        then:
        1 * verifier.verify(legacy, 'qa-owner@example.test', GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT) >>
            new LegacyGoogleOAuthVerification('verified-subject', 'qa-owner@example.test',
                GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT)
        qa.writeCount == 1
        qa.load().get().accountSubject == 'verified-subject'
    }

    def "operator operation rejects missing gate or reference before reading source or validating"() {
        given:
        def source = Mock(LegacyGoogleOAuthCredentialSource)
        def verifier = Mock(LegacyGoogleOAuthCredentialVerifier)
        def qa = new InMemoryGoogleOAuthTokenStore()
        def operation = new LegacyGoogleOAuthQaImportOperation(source,
            new LegacyGoogleOAuthCredentialImporter('qa-owner@example.test', qa, verifier))

        when:
        operation.importConfirmedReference(reference, confirmed)

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == GoogleOAuthErrorClass.CLIENT_CONFIGURATION
        0 * source._
        0 * verifier._
        qa.writeCount == 0

        where:
        reference                | confirmed
        'operator-vault:item-42' | false
        ''                       | true
        null                     | true
    }
}
