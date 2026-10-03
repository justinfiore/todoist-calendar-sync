package todoistcaldavsync

import groovy.json.JsonSlurper
import spock.lang.Specification

class AiSubscriptionCliSpec extends Specification {
    private static File config(File dir) {
        File file = new File(dir, 'planner.yaml')
        file.text = '''planner:
  mode: preview
  availability:
    working_windows:
      weekday: ["09:00-12:00"]
  ai:
    enabled: false
    provider: none
'''
        file
    }

    private static File logging(File dir) {
        File file = new File(dir, 'log4j.groovy')
        file.text = 'log4j.rootLogger="OFF"\n'
        file
    }

    def "authentication operations fail closed before planner composition"() {
        given:
        File dir = File.createTempDir('ai-auth-cli-', '')
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', config(dir).path, '-l', logging(dir).path,
            '--operation', operation, '--ai-provider', provider, '--json'] as String[], out, err)

        then:
        code == 3
        err.toString().empty
        def result = new JsonSlurper().parseText(out.toString())
        result.provider == provider
        result.operation == operation
        result.state == 'unsupported'
        result.localCredential == 'not_inspected'
        result.reason.contains('tool-free subscription inference protocol')
        !new File(dir, '.codex').exists()
        !new File(dir, '.grok').exists()

        cleanup:
        dir?.deleteDir()

        where:
        operation        | provider
        'ai-auth-login'  | 'codex'
        'ai-auth-status' | 'grok'
        'ai-auth-logout' | 'codex'
    }

    def "authentication operations reject planner arguments and unsupported providers"() {
        given:
        File dir = File.createTempDir('ai-auth-cli-invalid-', '')
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run((['-f', config(dir).path, '-l', logging(dir).path,
            '--operation', 'ai-auth-status', '--ai-provider', provider] + extra) as String[],
            new StringBuilder(), err)

        then:
        code == 2
        err.toString().contains(expected)

        cleanup:
        dir?.deleteDir()

        where:
        provider          | extra                    | expected
        'openai_compatible' | []                     | '--ai-provider must be one of: codex, grok'
        'codex'             | ['--plan-id', 'plan-1'] | 'Planner and provisioning arguments are refused'
        'grok'              | ['--auth-flow', 'browser'] | '--auth-flow is allowed only with ai-auth-login'
        'codex'             | ['--remote', '--plan-id', 'plan-1'] | 'Planner and provisioning arguments are refused'
    }
}
