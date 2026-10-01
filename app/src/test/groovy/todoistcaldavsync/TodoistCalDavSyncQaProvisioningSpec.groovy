package todoistcaldavsync

import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class TodoistCalDavSyncQaProvisioningSpec extends Specification {

    def "launcher exposes explicit confirmed QA list and provision operations"() {
        given:
        Path dir = Files.createTempDirectory('qa-provision-launcher-')
        Path config = googleBootstrapConfig(dir)
        Path logConfig = logConfig(dir)
        def calls = []
        def factory = { google, qaRoot, stateFile ->
            calls << [kind: 'factory', google: google, qaRoot: qaRoot, stateFile: stateFile]
            [list: { calls << [kind: 'list']; [[id: 'one', name: 'SmartPlanner QA Output']] },
             provision: { specs -> calls << [kind: 'provision', specs: specs]; [[alias: 'output', id: 'one']] }] as Object
        }
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run((['-f', config, '-l', logConfig,
            '--operation', operation, '--confirm-dedicated-qa-account'] + extra) as String[], out, err,
            { throw new AssertionError('OAuth bootstrap must not run') },
            { throw new AssertionError('legacy import must not run') }, factory)

        then:
        code == 0
        calls*.kind == ['factory', expectedCall]
        calls[0].google.qaTokenStoreDir != null
        calls[0].qaRoot == dir.resolve('.qa')
        calls[0].stateFile == dir.resolve('.qa/state/calendar-ids.json')
        err.empty

        where:
        operation                       | extra                                                                 | expectedCall
        'google-qa-calendars-list'      | []                                                                    | 'list'
        'google-qa-calendars-provision' | ['--qa-calendar', 'output|managed_output|SmartPlanner QA Output']      | 'provision'
    }

    def "QA operations refuse absent dedicated-account confirmation before service construction"() {
        given:
        Path dir = Files.createTempDirectory('qa-provision-refusal-')
        int constructions = 0
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', googleBootstrapConfig(dir).toString(),
            '-l', logConfig(dir).toString(), '--operation', operation] as String[],
            new StringBuilder(), err, { null }, { null }, { a, b, c -> constructions++; null })

        then:
        code == 2
        constructions == 0
        err.toString().contains('--confirm-dedicated-qa-account')

        where:
        operation << ['google-qa-calendars-list', 'google-qa-calendars-provision']
    }

    def "normal planner operations cannot reach QA provisioning factory"() {
        given:
        Path dir = Files.createTempDirectory('qa-normal-refusal-')
        Path invalidPlannerConfig = dir.resolve('planner.yaml')
        Files.writeString(invalidPlannerConfig, 'planner: {}\n')
        int constructions = 0

        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', invalidPlannerConfig.toString(), '-l', logConfig(dir).toString(),
            '--operation', operation, '--confirm-dedicated-qa-account',
            '--qa-calendar', 'output|managed_output|SmartPlanner QA Output'] as String[], new StringBuilder(), err,
            { null }, { null }, { a, b, c -> constructions++; null })

        then:
        code == 2
        constructions == 0
        err.toString().contains('QA calendar provisioning options are refused')

        where:
        operation << ['capacity', 'preview', 'apply', 'apply-safe', 'deliver', 'feedback', 'planner-daemon']
    }

    private static Path googleBootstrapConfig(Path dir) {
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
        config
    }

    private static Path logConfig(Path dir) {
        Path path = dir.resolve('log.groovy')
        Files.writeString(path, 'rootLogger = "OFF"')
        path
    }
}
