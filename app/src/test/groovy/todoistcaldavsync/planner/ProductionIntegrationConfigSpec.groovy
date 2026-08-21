package todoistcaldavsync.planner

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.InMemoryCalendarGateway

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ProductionIntegrationConfigSpec extends Specification {
    Path configDir = Path.of('/tmp/planner-config').toAbsolutePath()

    private Map validRoot() {
        [planner: [output_calendar: 'Planned', integration: [
            todoist: [base_url: 'https://api.todoist.com/api/v1', token_env: 'TODOIST_TOKEN',
                       timeout: 'PT10S', max_pages: 100, max_response_bytes: 1048576],
            calendar: [provider: 'caldav'],
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

    private Map validGoogleRoot() {
        Map root = validRoot()
        root.planner.integration.remove('caldav')
        root.planner.integration.calendar = [provider: 'google_calendar_api', google_calendar_api: [
            oauth_client_secret_file: 'secrets/google-oauth-client.json',
            token_store_dir: 'secrets/google-oauth-tokens',
            account_email: 'smartplanner-qa@example.test',
            oauth_callback_port: 8787,
            calendars: [
                [name: 'Planned', id: 'planned@example.test', role: 'managed_output'],
                [name: 'Busy', id: 'busy@example.test', role: 'hard_blocker']
            ]
        ]]
        root
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
        config.calendarProvider.provider == 'caldav'
        config.calendarProvider.caldav.calendars.is(config.calendars)
        config.calendarProvider.caldav.timeout == config.caldav.timeout
        config.calendarProvider.caldav.maxResponseBytes == 2097152L
        config.calendarProvider.googleCalendarApi == null
        config.calendars*.name == ['Planned', 'Busy']
        config.calendarProvider.caldav.calendars[0].url == 'https://cal.example.test/planned/'
        config.calendarProvider.caldav.calendars[0].auth == [
            type: 'basic', username: 'operator', password_env: 'CALDAV_PASSWORD'
        ]
        config.calendarProvider.caldav.calendars[1].auth == [type: 'bearer', token_env: 'CALDAV_TOKEN']
    }

    def "calendar provider is explicit and restricted to supported values"() {
        given:
        Map root = validRoot()
        if (provider == null) root.planner.integration.remove('calendar')
        else root.planner.integration.calendar.provider = provider

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('planner.integration.calendar.provider')

        where:
        provider << [null, '', 'google', 'CALDAV_API', 'CALDAV']
    }

    def "selected provider rejects mixed provider sections and fields"() {
        given:
        Map root = base.call()
        mutate.call(root)

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.toLowerCase().contains('mixed')

        where:
        [base, mutate] << [
            [{ validRoot() }, { it.planner.integration.calendar.google_calendar_api = [account_email: 'x@example.test'] }],
            [{ validRoot() }, { it.planner.integration.caldav.oauth_client_secret_file = 'secrets/client.json' }],
            [{ validGoogleRoot() }, { it.planner.integration.caldav = [calendars: []] }],
            [{ validGoogleRoot() }, { it.planner.integration.calendar.google_calendar_api.url = 'https://cal.example.test/' }],
            [{ validGoogleRoot() }, { it.planner.integration.calendar.google_calendar_api.password_env = 'CALDAV_PASSWORD' }]
        ]
    }

    def "Google provider validates required secret references account and callback port"() {
        given:
        Map root = validGoogleRoot()
        mutate.call(root.planner.integration.calendar.google_calendar_api)

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains(fragment)

        where:
        [mutate, fragment] << [
            [{ it.remove('oauth_client_secret_file') }, 'oauth_client_secret_file'],
            [{ it.remove('token_store_dir') }, 'token_store_dir'],
            [{ it.remove('account_email') }, 'account_email'],
            [{ it.account_email = 'not-an-email' }, 'account_email'],
            [{ it.oauth_callback_port = 0 }, 'oauth_callback_port'],
            [{ it.oauth_client_secret_file = '../outside-client.json' }, 'oauth_client_secret_file'],
            [{ it.token_store_dir = '/tmp/outside-google-token-store' }, 'token_store_dir']
        ]
    }

    def "Google calendar mappings require unique names IDs and exactly one matching managed output"() {
        given:
        Map root = validGoogleRoot()
        mutate.call(root)

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.toLowerCase().contains(fragment)

        where:
        [mutate, fragment] << [
            [{ it.planner.integration.calendar.google_calendar_api.calendars[1].name = 'Planned' }, 'unique'],
            [{ it.planner.integration.calendar.google_calendar_api.calendars[1].id = 'planned@example.test' }, 'unique'],
            [{ it.planner.integration.calendar.google_calendar_api.calendars[0].remove('id') }, 'name, id, and role'],
            [{ it.planner.integration.calendar.google_calendar_api.calendars[0].role = 'hard_blocker' }, 'managed_output'],
            [{ it.planner.integration.calendar.google_calendar_api.calendars[1].role = 'managed_output' }, 'managed_output'],
            [{ it.planner.output_calendar = 'Busy' }, 'output_calendar']
        ]
    }

    def "Google provider model is normalized and immutable"() {
        when:
        def config = ProductionIntegrationConfig.fromMap(validGoogleRoot(), configDir)

        then:
        config.calendarProvider.provider == 'google_calendar_api'
        config.calendarProvider.caldav == null
        config.calendarProvider.googleCalendarApi.oauthClientSecretFile == configDir.resolve('secrets/google-oauth-client.json')
        config.calendarProvider.googleCalendarApi.tokenStoreDir == configDir.resolve('secrets/google-oauth-tokens')
        config.calendarProvider.googleCalendarApi.accountEmail == 'smartplanner-qa@example.test'
        config.calendarProvider.googleCalendarApi.oauthCallbackPort == 8787
        config.calendarProvider.googleCalendarApi.calendars*.role == ['managed_output', 'hard_blocker']

        when:
        config.calendarProvider.googleCalendarApi.calendars << [name: 'Other']

        then:
        thrown(UnsupportedOperationException)
    }

    def "bootstrap-only Google validation requires only the OAuth bootstrap subset"() {
        given:
        Map root = [planner: [
            output_calendar: '',
            daemon: [enabled: true, planning_runs: 'invalid'],
            messaging: [enabled: true],
            integration: [
                todoist: [base_url: 'http://invalid', token: 'inline-secret'],
                calendar: [provider: 'google_calendar_api', google_calendar_api: [
                    oauth_client_secret_file: 'secrets/google-oauth-client.json',
                    token_store_dir: 'secrets/future/token-store',
                    account_email: 'smartplanner-qa@example.test',
                    oauth_callback_port: 8787,
                    calendars: [[name: '', id: '', role: 'invalid']]
                ]],
                state: [plans_dir: '', applications_dir: ''],
                weather: [base_url: 'http://invalid'],
                feedback: [allowed_actors: 'invalid']
            ]
        ]]

        when:
        def bootstrap = ProductionIntegrationConfig.fromMapForGoogleOAuthBootstrap(root, configDir)

        then:
        bootstrap.calendarProvider.googleCalendarApi.calendars.empty
        bootstrap.calendarProvider.googleCalendarApi.tokenStoreDir == configDir.resolve('secrets/future/token-store')

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('todoist')
    }

    def "Google file references reject an existing symlink escape but allow a future contained suffix"() {
        given:
        Path actualConfigDir = Files.createTempDirectory('google-reference-config-')
        Path outside = Files.createTempDirectory('google-reference-outside-')
        Files.createSymbolicLink(actualConfigDir.resolve('escaped'), outside)
        Map root = validGoogleRoot()
        root.planner.integration.calendar.google_calendar_api.oauth_client_secret_file = 'secrets/future-client.json'
        root.planner.integration.calendar.google_calendar_api.token_store_dir = 'escaped/future-token-store'

        when:
        ProductionIntegrationConfig.fromMapForGoogleOAuthBootstrap(root, actualConfigDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('token_store_dir')
        error.message.contains('configuration directory')
    }

    def "bootstrap-only Google validation still rejects invalid OAuth bootstrap fields"() {
        given:
        Map root = [planner: [integration: [calendar: [
            provider: 'google_calendar_api',
            google_calendar_api: [
                oauth_client_secret_file: 'secrets/client.json',
                token_store_dir: 'secrets/tokens',
                account_email: 'owner@example.test',
                oauth_callback_port: 8787
            ]
        ]]]]
        mutate(root)

        when:
        ProductionIntegrationConfig.fromMapForGoogleOAuthBootstrap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains(fragment)

        where:
        [mutate, fragment] << [
            [{ it.planner.integration.calendar.provider = 'caldav' }, 'provider'],
            [{ it.planner.integration.calendar.google_calendar_api.remove('oauth_client_secret_file') }, 'oauth_client_secret_file'],
            [{ it.planner.integration.calendar.google_calendar_api.remove('token_store_dir') }, 'token_store_dir'],
            [{ it.planner.integration.calendar.google_calendar_api.account_email = 'invalid' }, 'account_email'],
            [{ it.planner.integration.calendar.google_calendar_api.oauth_callback_port = 0 }, 'oauth_callback_port']
        ]
    }

    def "inline Google secrets are rejected without reflecting values"() {
        given:
        Map root = validGoogleRoot()
        root.planner.integration.calendar.google_calendar_api[secretField] = 'super-secret'

        when:
        ProductionIntegrationConfig.fromMap(root, configDir)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('inline secret')
        !error.message.contains('super-secret')

        where:
        secretField << ['client_secret', 'access_token', 'refresh_token', 'password', 'token']
    }

    def "invalid provider configuration constructs zero calendar providers"() {
        given:
        Path state = Files.createTempDirectory('provider-routing-')
        File configFile = state.resolve('planner.yaml').toFile()
        Files.writeString(configFile.toPath(), """
planner:
  mode: preview
  timezone: UTC
  output_calendar: Planned
  availability:
    working_windows:
      weekday: ['09:00-17:00']
  integration:
    todoist:
      base_url: https://api.todoist.com/api/v1
      token_env: TODOIST_TOKEN
    calendar:
      provider: unknown
    state:
      plans_dir: plans
      applications_dir: applications
      decisions_dir: decisions
      deliveries_dir: deliveries
""")
        int caldavConstructions = 0
        int googleConstructions = 0

        when:
        new ProductionPlannerOrchestrator(configFile, { Instant.now() },
            { options -> caldavConstructions++; null },
            { options -> googleConstructions++; null })

        then:
        thrown(IllegalArgumentException)
        caldavConstructions == 0
        googleConstructions == 0
    }

    def "multiple invalid configuration classes construct zero calendar providers"() {
        given:
        Path state = Files.createTempDirectory('invalid-provider-routing-')
        File configFile = state.resolve('planner.yaml').toFile()
        Files.writeString(configFile.toPath(), """
planner:
  mode: preview
  timezone: UTC
  output_calendar: Planned
  availability:
    working_windows:
      weekday: ['09:00-17:00']
  integration:
    todoist:
      base_url: https://api.todoist.com/api/v1
      token_env: TODOIST_TOKEN
${providerYaml}
    state:
      plans_dir: plans
      applications_dir: applications
      decisions_dir: decisions
      deliveries_dir: deliveries
""")
        int caldavConstructions = 0
        int googleConstructions = 0

        when:
        new ProductionPlannerOrchestrator(configFile, { Instant.now() },
            { options -> caldavConstructions++; null },
            { options -> googleConstructions++; null })

        then:
        thrown(IllegalArgumentException)
        caldavConstructions == 0
        googleConstructions == 0

        where:
        invalidClass               | providerYaml
        'mixed providers'          | '''    calendar:\n      provider: google_calendar_api\n      google_calendar_api:\n        oauth_client_secret_file: secrets/client.json\n        token_store_dir: secrets/tokens\n        account_email: owner@example.test\n        calendars:\n          - name: Planned\n            id: planned@example.test\n            role: managed_output\n    caldav:\n      calendars: []'''
        'missing Google reference' | '''    calendar:\n      provider: google_calendar_api\n      google_calendar_api:\n        token_store_dir: secrets/tokens\n        account_email: owner@example.test\n        calendars:\n          - name: Planned\n            id: planned@example.test\n            role: managed_output'''
        'duplicate ID and name'    | '''    calendar:\n      provider: google_calendar_api\n      google_calendar_api:\n        oauth_client_secret_file: secrets/client.json\n        token_store_dir: secrets/tokens\n        account_email: owner@example.test\n        calendars:\n          - name: Planned\n            id: same@example.test\n            role: managed_output\n          - name: Planned\n            id: same@example.test\n            role: hard_blocker'''
        'managed-output mismatch'  | '''    calendar:\n      provider: google_calendar_api\n      google_calendar_api:\n        oauth_client_secret_file: secrets/client.json\n        token_store_dir: secrets/tokens\n        account_email: owner@example.test\n        calendars:\n          - name: Different\n            id: planned@example.test\n            role: managed_output'''
    }

    def "CalendarProviderConfig defensively deep-freezes CalDAV configuration"() {
        given:
        Map source = [
            calendars: [[name: 'Planned', url: 'https://cal.example.test/planned/',
                         auth: [type: 'basic', username: 'operator', password_env: 'CALDAV_PASSWORD']]],
            timeout: java.time.Duration.ofSeconds(19),
            maxResponseBytes: 3456L
        ]

        when:
        def provider = new CalendarProviderConfig(CalendarProviderConfig.CALDAV, source, null)
        source.calendars[0].auth.username = 'mutated'

        then:
        provider.caldav.calendars[0].auth.username == 'operator'

        when:
        provider.caldav.calendars[0].auth.username = 'blocked'

        then:
        thrown(UnsupportedOperationException)

        when:
        provider.caldav.calendars << [name: 'Other']

        then:
        thrown(UnsupportedOperationException)
    }

    def "CalDAV compatibility preserves URL auth timeout response limit and gateway options"() {
        given:
        Path state = Files.createTempDirectory('caldav-compatibility-')
        File configFile = state.resolve('planner.yaml').toFile()
        Files.writeString(configFile.toPath(), """
planner:
  mode: preview
  timezone: America/New_York
  output_calendar: Planned
  availability:
    working_windows:
      weekday: ['09:00-17:00']
  integration:
    todoist:
      base_url: https://api.todoist.com/api/v1
      token_env: TODOIST_TOKEN
    calendar:
      provider: caldav
    caldav:
      timeout: PT19S
      max_response_bytes: 3456
      calendars:
        - name: Planned
          url: https://calendar.example.test/planned/
          auth:
            type: basic
            username: operator
            password_env: CALDAV_PASSWORD
    state:
      plans_dir: plans
      applications_dir: applications
      decisions_dir: decisions
      deliveries_dir: deliveries
""")
        Map capturedOptions
        def gateway = new InMemoryCalendarGateway('Planned', true, [])

        when:
        new ProductionPlannerOrchestrator(configFile, { Instant.now() },
            { Map options -> capturedOptions = options; gateway },
            { Map options -> throw new AssertionError('Google factory must not be called') })

        then:
        capturedOptions.managedCalendarName == 'Planned'
        capturedOptions.timezone.toString() == 'America/New_York'
        capturedOptions.timeout == java.time.Duration.ofSeconds(19)
        capturedOptions.maxResponseBytes == 3456L
        capturedOptions.calendars[0].url == 'https://calendar.example.test/planned/'
        capturedOptions.calendars[0].auth == [
            type: 'basic', username: 'operator', password_env: 'CALDAV_PASSWORD'
        ]
        capturedOptions.keySet() == [
            'calendars', 'managedCalendarName', 'timezone', 'timeout', 'maxResponseBytes'
        ] as Set
    }

    def "production composition constructs only the explicitly selected calendar provider"() {
        given:
        Path state = Files.createTempDirectory('selected-provider-')
        File configFile = state.resolve('planner.yaml').toFile()
        Files.writeString(configFile.toPath(), """
planner:
  mode: preview
  timezone: UTC
  output_calendar: Planned
  availability:
    working_windows:
      weekday: ['09:00-17:00']
  integration:
    todoist:
      base_url: https://api.todoist.com/api/v1
      token_env: TODOIST_TOKEN
${providerYaml}
    state:
      plans_dir: plans
      applications_dir: applications
      decisions_dir: decisions
      deliveries_dir: deliveries
""")
        int caldavConstructions = 0
        int googleConstructions = 0
        def gateway = new InMemoryCalendarGateway('Planned', true, [])

        when:
        new ProductionPlannerOrchestrator(configFile, { Instant.now() },
            { options -> caldavConstructions++; gateway },
            { options -> googleConstructions++; gateway })

        then:
        caldavConstructions == expectedCalDav
        googleConstructions == expectedGoogle

        where:
        providerYaml << [
            '''    calendar:\n      provider: caldav\n    caldav:\n      calendars:\n        - name: Planned\n          url: https://calendar.example.test/planned\n          auth:\n            type: none''',
            '''    calendar:\n      provider: google_calendar_api\n      google_calendar_api:\n        oauth_client_secret_file: secrets/google-oauth-client.json\n        token_store_dir: secrets/google-oauth-tokens\n        account_email: smartplanner-qa@example.test\n        calendars:\n          - name: Planned\n            id: planned@example.test\n            role: managed_output'''
        ]
        expectedCalDav << [1, 0]
        expectedGoogle << [0, 1]
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
