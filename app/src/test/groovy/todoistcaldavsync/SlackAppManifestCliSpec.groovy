package todoistcaldavsync

import groovy.yaml.YamlSlurper
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

class SlackAppManifestCliSpec extends Specification {
    @Unroll
    def "manifest selects least-privilege #type channel access"() {
        given:
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run((['--operation', 'slack-app-manifest'] + flags) as String[], out, err)
        Map manifest = new YamlSlurper().parseText(out.toString()) as Map

        then:
        code == 0
        err.toString().empty
        manifest.display_information.name == 'SmartPlanner'
        manifest.features.bot_user.display_name == 'smartplanner'
        manifest.features.agent_view.agent_description
        !manifest.features.containsKey('assistant_view')
        manifest.features.slash_commands[0].command == '/smartplanner'
        manifest.oauth_config.scopes.bot.toSet() ==
            (['app_mentions:read', 'assistant:write', 'chat:write', 'commands'] + history).toSet()
        manifest.settings.event_subscriptions.bot_events.toSet() == (['app_mention'] + events).toSet()
        manifest.settings.socket_mode_enabled == true
        manifest.settings.interactivity.is_enabled == true
        !out.toString().contains('request_url')
        !out.toString().contains('redirect_urls')

        where:
        type      | flags                              | history                              | events
        'default' | []                                 | ['channels:history']                 | ['message.channels']
        'public'  | ['--slack-channel-types', 'public']  | ['channels:history']                 | ['message.channels']
        'private' | ['--slack-channel-types', 'private'] | ['groups:history']                   | ['message.groups']
        'both'    | ['--slack-channel-types', 'both']    | ['channels:history', 'groups:history'] | ['message.channels', 'message.groups']
    }

    def "installed launcher generates private QA manifest without config credentials or state"() {
        given:
        File launcher = new File('build/install/todoist-caldav-sync/bin/todoist-caldav-sync').absoluteFile
        File dir = File.createTempDir('slack-manifest-', '')
        File home = new File(dir, 'home'); assert home.mkdir()
        File cwd = new File(dir, 'empty'); assert cwd.mkdir()
        String name = 'Planner: QA "team" #1'
        def builder = new ProcessBuilder([launcher.path, '--operation', 'slack-app-manifest',
            '--slack-app-name', name, '--slack-channel-types', 'private', '--slack-command', '/planner-qa'])
        builder.directory(cwd)
        builder.environment().clear()
        builder.environment().putAll([HOME: home.path, PATH: '/usr/local/bin:/usr/bin:/bin'])

        when:
        Process process = builder.start()
        boolean exited = process.waitFor(30, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        String out = process.inputStream.getText('UTF-8')
        String err = process.errorStream.getText('UTF-8')
        Map manifest = new YamlSlurper().parseText(out) as Map

        then:
        exited
        process.exitValue() == 0
        err.empty
        manifest.display_information.name == name
        manifest.features.bot_user.display_name == 'planner_qa_team_1'
        manifest.features.slash_commands[0].command == '/planner-qa'
        manifest.oauth_config.scopes.bot.contains('groups:history')
        !manifest.oauth_config.scopes.bot.contains('channels:history')
        manifest.settings.event_subscriptions.bot_events == ['app_mention', 'message.groups']
        home.listFiles().length == 0
        cwd.listFiles().length == 0

        cleanup:
        dir?.deleteDir()
    }

    @Unroll
    def "invalid manifest arguments fail closed: #flags"() {
        given:
        def out = new StringBuilder()
        def err = new StringBuilder()

        expect:
        TodoistCalDavSync.run((['--operation', 'slack-app-manifest'] + flags) as String[], out, err) == 2
        out.toString().empty
        err.toString().contains(message)

        where:
        flags                                     | message
        ['--slack-channel-types', 'dm']            | '--slack-channel-types'
        ['--slack-app-name', '   ']                | '--slack-app-name'
        ['--slack-app-name', 'x' * 36]             | '--slack-app-name'
        ['--slack-app-name', 'bad\nname']          | '--slack-app-name'
        ['--slack-command', '/Bad']                | '--slack-command'
        ['--slack-command', '/a' + 'x' * 31]        | '--slack-command'
        ['--remote']                              | 'Only --slack-app-name'
        ['-f', '/does/not/exist']                  | 'Only --slack-app-name'
        ['--confirm-dedicated-qa-account']         | 'Only --slack-app-name'
        ['unexpected']                            | 'Only --slack-app-name'
    }

    def "manifest flags cannot silently start the default sync operation"() {
        given:
        def out = new StringBuilder()
        def err = new StringBuilder()

        expect:
        TodoistCalDavSync.run(['--slack-channel-types', 'private'] as String[], out, err) == 2
        out.toString().empty
        err.toString().contains('require --operation slack-app-manifest')
    }
}
