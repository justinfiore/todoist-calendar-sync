package todoistcaldavsync

import groovy.json.JsonSlurper
import spock.lang.Specification

class AiSubscriptionInstalledLauncherSpec extends Specification {
    def "installed launcher exposes auth contract and fails closed in a clean home"() {
        given:
        File launcher = new File('build/install/todoist-caldav-sync/bin/todoist-caldav-sync').absoluteFile
        assert launcher.isFile()
        File dir = File.createTempDir('ai-auth-installed-', '')
        File home = new File(dir, 'home'); assert home.mkdir()
        File config = new File(dir, 'planner.yaml')
        config.text = '''planner:
  mode: preview
  availability:
    working_windows:
      weekday: ["09:00-12:00"]
  ai:
    enabled: false
    provider: none
'''
        File logging = new File(dir, 'log4j.groovy')
        logging.text = 'log4j.rootLogger="OFF"\n'

        when:
        def help = run(launcher, home, ['--help'])
        def device = run(launcher, home, ['-f', config.path, '-l', logging.path,
            '--operation', 'ai-auth-login', '--ai-provider', 'codex', '--json'])
        def browser = run(launcher, home, ['-f', config.path, '-l', logging.path,
            '--operation', 'ai-auth-login', '--ai-provider', 'grok', '--auth-flow', 'browser', '--json'])
        def local = run(launcher, home, ['-f', config.path, '-l', logging.path,
            '--operation', 'ai-auth-status', '--ai-provider', 'codex', '--json'])
        def remote = run(launcher, home, ['-f', config.path, '-l', logging.path,
            '--operation', 'ai-auth-status', '--ai-provider', 'grok', '--remote', '--json'])

        then:
        help.code == 0
        ['ai-auth-login', 'ai-auth-status', 'ai-auth-logout', '--ai-provider', '--auth-flow', '--remote', '--json']
            .every { help.out.contains(it) }
        [device, browser, local, remote].every { it.code == 3 && it.err.empty }
        new JsonSlurper().parseText(device.out).remoteEntitlement == 'not_requested'
        new JsonSlurper().parseText(browser.out).provider == 'grok'
        new JsonSlurper().parseText(local.out).remoteEntitlement == 'not_requested'
        new JsonSlurper().parseText(remote.out).remoteEntitlement == 'not_checked'
        home.listFiles().length == 0

        cleanup:
        dir?.deleteDir()
    }

    private static Map run(File launcher, File home, List<String> args) {
        ProcessBuilder builder = new ProcessBuilder(([launcher.path] + args) as List<String>)
        builder.environment().clear()
        builder.environment().put('HOME', home.path)
        builder.environment().put('PATH', '/usr/local/bin:/usr/bin:/bin')
        Process process = builder.start()
        String out = process.inputStream.getText('UTF-8')
        String err = process.errorStream.getText('UTF-8')
        int code = process.waitFor()
        [code: code, out: out, err: err]
    }
}
