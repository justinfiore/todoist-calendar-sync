package todoistcaldavsync.planner

import spock.lang.Specification

import java.nio.file.Path

class ProductionIntegrationConfigSpec extends Specification {
    Path configDir = Path.of('/tmp/planner-config').toAbsolutePath()

    private Map validRoot() {
        [planner: [output_calendar: 'Planned', integration: [
            todoist: [base_url: 'https://api.todoist.com/api/v1', token_env: 'TODOIST_TOKEN',
                       timeout: 'PT10S', max_pages: 100, max_response_bytes: 1048576],
            caldav: [timeout: 'PT15S', max_response_bytes: 2097152, calendars: [
                [name: 'Planned', url: 'https://cal.example.test/planned/',
                 auth: [type: 'basic', username: 'operator', password_env: 'CALDAV_PASSWORD']],
                [name: 'Busy', url: 'https://cal.example.test/busy/',
                 auth: [type: 'bearer', token_env: 'CALDAV_TOKEN']]
            ]],
            weather: [base_url: 'https://api.open-meteo.com/v1/forecast', timeout: 'PT5S', max_response_bytes: 10000],
            feedback: [allowed_actors: ['actor-1']],
            state: [plans_dir: 'state/plans', applications_dir: 'state/apps',
                    decisions_dir: 'state/decisions', deliveries_dir: 'state/deliveries']
        ]]]
    }

    def "valid production configuration resolves independent relative state paths"() {
        when:
        def config = ProductionIntegrationConfig.fromMap(validRoot(), configDir)

        then:
        config.plansDir == configDir.resolve('state/plans').normalize()
        config.applicationsDir == configDir.resolve('state/apps').normalize()
        config.decisionsDir == configDir.resolve('state/decisions').normalize()
        config.deliveriesDir == configDir.resolve('state/deliveries').normalize()
        [config.plansDir, config.applicationsDir, config.decisionsDir, config.deliveriesDir].toSet().size() == 4
        config.feedbackActors() == ['actor-1']
    }

    def "invalid endpoints duplicate calendars unmanaged output and nonpositive controls fail closed"() {
        given:
        Map root = validRoot()
        mutate(root)

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.toLowerCase().contains(fragment)
        !error.message.contains('super-secret')

        where:
        [mutate, fragment] << [
            [{ it.planner.integration.todoist.base_url = 'http://api.todoist.test' }, 'https'],
            [{ it.planner.integration.todoist.max_pages = 0 }, 'max_pages'],
            [{ it.planner.integration.todoist.max_response_bytes = 0 }, 'max_response_bytes'],
            [{ it.planner.integration.caldav.calendars[1].name = 'Planned' }, 'unique'],
            [{ it.planner.output_calendar = 'Missing' }, 'output_calendar'],
            [{ it.planner.integration.caldav.calendars[0].url = '/relative' }, 'https'],
            [{ it.planner.integration.caldav.calendars[0].auth.password_env = '' }, 'password_env'],
            [{ it.planner.integration.caldav.timeout = 'PT0S' }, 'positive'],
            [{ it.planner.integration.caldav.max_response_bytes = -1 }, 'max_response_bytes'],
            [{ it.planner.integration.state.deliveries_dir = it.planner.integration.state.plans_dir }, 'state paths']
        ]
    }

    def "inline production secrets are rejected without reflecting their values"() {
        given:
        Map root = validRoot()
        root.planner.integration.todoist.token = 'super-secret'

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('inline secret')
        !error.message.contains('super-secret')
    }

    def "feedback allowlist must contain unique nonblank actor ids"() {
        given:
        Map root = validRoot()
        root.planner.integration.feedback.allowed_actors = actors

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('allowed_actors')

        where:
        actors << [[''], ['same', 'same'], 'actor']
    }
}
