package todoistcaldavsync

import spock.lang.Specification

class Phase7MainCliSpec extends Specification {
    def "existing main entry point exposes Phase 7 operations and help needs no config"() {
        given:
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['--help'] as String[], out, err)

        then:
        code == 0
        err.toString().empty
        out.toString().contains('legacy-sync')
        out.toString().contains('preview')
        out.toString().contains('apply-safe')
        out.toString().contains('apply-decision')
        out.toString().contains('ai-suggest')
    }

    def "unknown production operation fails before any network"() {
        given:
        File cfg = File.createTempFile('phase7-main-', '.yaml')
        File log = File.createTempFile('phase7-log-', '.groovy')
        cfg.text = 'planner: {}\n'
        log.text = 'log4j.rootLogger="OFF"\n'
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', cfg.path, '-l', log.path,
            '--operation', 'not-an-operation'] as String[], new StringBuilder(), err)

        then:
        code == 2
        err.toString().contains('Unsupported operation: not-an-operation')
    }
}
