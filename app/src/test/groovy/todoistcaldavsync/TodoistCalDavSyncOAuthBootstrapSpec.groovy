package todoistcaldavsync

import spock.lang.Specification
import todoistcaldavsync.planner.oauth.GoogleOAuthBootstrapMode

import java.nio.file.Files
import java.nio.file.Path

class TodoistCalDavSyncOAuthBootstrapSpec extends Specification {

    def "launcher dispatches normal and QA bootstrap without constructing planner or requiring calendars"() {
        given:
        Path dir = Files.createTempDirectory('oauth-launcher-')
        Path config = dir.resolve('planner.yaml')
        Files.writeString(config, """
planner:
  integration:
    calendar:
      provider: google_calendar_api
      google_calendar_api:
        oauth_client_secret_file: secrets/client.json
        token_store_dir: secrets/normal
        qa_token_store_dir: secrets/qa
        account_email: owner@example.test
        ${portLine}
""")
        Path logConfig = dir.resolve('log.groovy')
        Files.writeString(logConfig, 'rootLogger = "OFF"')
        def calls = []
        def factory = { cfg ->
            [bootstrap: { google, mode, terminal ->
                calls << [mode: mode, port: google.oauthCallbackPort, normal: google.tokenStoreDir,
                          qa: google.qaTokenStoreDir, calendars: google.calendars]
                terminal.append('https://consent.example.test/once\ncredential persisted\n')
            }] as Object
        }
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', config.toString(), '-l', logConfig.toString(),
            '--operation', operation] as String[], out, err, factory)

        then:
        code == 0
        calls*.mode == [expectedMode]
        calls[0].port == expectedPort
        calls[0].calendars.empty
        out.toString().contains('https://consent.example.test/once')
        err.empty

        where:
        operation                   | portLine                   | expectedMode                    | expectedPort
        'google-oauth-bootstrap'    | ''                         | GoogleOAuthBootstrapMode.NORMAL | 8787
        'google-oauth-bootstrap-qa' | 'oauth_callback_port: 9191'| GoogleOAuthBootstrapMode.QA     | 9191
    }

    def "non-Google bootstrap refusal occurs before service factory secret resolution or listener"() {
        given:
        Path dir = Files.createTempDirectory('oauth-refusal-')
        Path config = dir.resolve('planner.yaml')
        Files.writeString(config, '''
planner:
  integration:
    calendar:
      provider: caldav
''')
        Path logConfig = dir.resolve('log.groovy')
        Files.writeString(logConfig, 'rootLogger = "OFF"')
        int constructions = 0
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', config.toString(), '-l', logConfig.toString(),
            '--operation', 'google-oauth-bootstrap'] as String[], out, err, { cfg -> constructions++; null })

        then:
        code == 2
        constructions == 0
        out.empty
        err.toString().contains('provider')
    }

    def "launcher dispatches confirmed legacy import to QA and exits without planner provisioning"() {
        given:
        Path dir = Files.createTempDirectory('legacy-oauth-launcher-')
        Path config = dir.resolve('planner.yaml')
        Files.writeString(config, '''
planner:
  integration:
    calendar:
      provider: google_calendar_api
      google_calendar_api:
        oauth_client_secret_file: secrets/client.json
        token_store_dir: secrets/normal
        qa_token_store_dir: secrets/qa
        account_email: owner@example.test
''')
        Path logConfig = dir.resolve('log.groovy')
        Files.writeString(logConfig, 'rootLogger = "OFF"')
        def calls = []
        def operationFactory = { google ->
            calls << [kind: 'factory', google: google]
            [importConfirmedReference: { String inputReference, boolean confirmed ->
                calls << [kind: 'import', reference: inputReference, confirmed: confirmed]
            }] as Object
        }
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', config.toString(), '-l', logConfig.toString(),
            '--operation', 'google-oauth-import-legacy-qa', '--confirm-legacy-qa-import',
            '--input-reference', 'operator-vault:item-42'] as String[], out, err,
            { throw new AssertionError('bootstrap must not be constructed') }, operationFactory)

        then:
        code == 0
        calls*.kind == ['factory', 'import']
        calls[0].google.calendars.empty
        calls[1] == [kind: 'import', reference: 'operator-vault:item-42', confirmed: true]
        out.toString().contains('QA')
        err.empty
    }

    def "legacy import rejects missing operator gate before source operation construction"() {
        given:
        Path dir = Files.createTempDirectory('legacy-oauth-gate-')
        Path config = dir.resolve('planner.yaml')
        Files.writeString(config, '''
planner:
  integration:
    calendar:
      provider: google_calendar_api
      google_calendar_api:
        oauth_client_secret_file: secrets/client.json
        token_store_dir: secrets/normal
        qa_token_store_dir: secrets/qa
        account_email: owner@example.test
''')
        Path logConfig = dir.resolve('log.groovy')
        Files.writeString(logConfig, 'rootLogger = "OFF"')
        int constructions = 0
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run((['-f', config.toString(), '-l', logConfig.toString(),
            '--operation', 'google-oauth-import-legacy-qa'] + extraArgs) as String[], new StringBuilder(), err,
            { throw new AssertionError('bootstrap must not be constructed') }, { google -> constructions++; null })

        then:
        code == 2
        constructions == 0
        err.toString().contains(expectedError)

        where:
        extraArgs                                                        | expectedError
        ['--input-reference', 'operator-vault:item-42']                  | '--confirm-legacy-qa-import'
        ['--confirm-legacy-qa-import']                                   | '--input-reference'
    }
}
