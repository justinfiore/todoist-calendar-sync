package todoistcaldavsync.planner.messaging

import groovy.yaml.YamlBuilder
import groovy.yaml.YamlSlurper

/** Offline installation material only; never constructs a Slack client. */
final class SlackAppManifest {
    static String render(String appName = 'SmartPlanner', String channelTypes = 'public',
                         String command = '/smartplanner') {
        appName = appName.trim()
        if (!appName || appName.length() > 35 || appName.any { Character.isISOControl(it as char) }) {
            throw new IllegalArgumentException('--slack-app-name must be 1..35 characters without control characters')
        }
        if (!(channelTypes in ['public', 'private', 'both'])) {
            throw new IllegalArgumentException('--slack-channel-types must be public, private, or both')
        }
        if (!(command ==~ /\/[a-z0-9_-]{1,31}/)) {
            throw new IllegalArgumentException('--slack-command must be a lowercase Slack command such as /smartplanner')
        }
        Map manifest
        SlackAppManifest.getResourceAsStream('/smartplanner-slack-app-manifest.example.yaml').withCloseable { stream ->
            manifest = new YamlSlurper().parse(stream) as Map
        }
        manifest.display_information.name = appName
        // Slack's bot-name alphabet is narrower than its app-name alphabet.
        manifest.features.bot_user.display_name = appName.toLowerCase(Locale.ROOT)
            .replaceAll(/[^a-z0-9_.-]+/, '_').replaceAll(/^_+|_+$/, '') ?: 'smartplanner'
        manifest.features.slash_commands[0].command = command
        if (channelTypes == 'private') {
            manifest.oauth_config.scopes.bot.remove('channels:history')
            manifest.settings.event_subscriptions.bot_events.remove('message.channels')
        }
        if (channelTypes in ['private', 'both']) {
            manifest.oauth_config.scopes.bot.add('groups:history')
            manifest.settings.event_subscriptions.bot_events.add('message.groups')
        }
        def builder = new YamlBuilder()
        builder.call(manifest)
        builder.toString()
    }
}
