package todoistcaldavsync.planner.qa

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.ai.SubscriptionCredential
import todoistcaldavsync.planner.ai.XaiDeviceOAuthAdapter
import todoistcaldavsync.planner.oauth.GoogleOAuthScopes

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Public helper commands, fake credentials, and a disposable checkout; no provider calls. */
class OrbQaHelperSpec extends Specification {
    Path temporary, checkout, auth, run
    Map<String, String> environment

    def setup() {
        Path source = Path.of('').toAbsolutePath()
        if (!Files.exists(source.resolve('scripts'))) source = source.parent
        temporary = Files.createTempDirectory('orb-qa-helper-')
        checkout = Files.createDirectory(temporary.resolve('checkout'))
        auth = temporary.resolve('external-auth')
        run = checkout.resolve('.qa/runs/case')
        ['scripts', '.qa.example'].each { Files.createDirectory(checkout.resolve(it)) }
        ['scripts/smartplanner-qa.sh', 'scripts/smartplanner-qa.groovy',
         '.qa.example/planner-orb-qa.yaml', '.qa.example/orb-qa-cases.csv'].each {
            Files.copy(source.resolve(it), checkout.resolve(it))
        }
        Path distribution = checkout.resolve('app/build/install/todoist-caldav-sync')
        Files.createDirectories(distribution.parent)
        Files.createSymbolicLink(distribution, source.resolve('app/build/install/todoist-caldav-sync'))
        environment = [TODOIST_ACCESS_TOKEN: 'sentinel-private-todoist', SLACK_BOT_TOKEN: 'sentinel-private-bot',
                       SLACK_APP_TOKEN: 'sentinel-private-app']
    }

    def cleanup() {
        if (temporary != null) {
            // Files.walk does not follow the installed-distribution or hostile-workspace links.
            Files.walk(temporary).withCloseable { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }

    def "init from outside checkout creates safe isolated not-run workspace without overwriting"() {
        when:
        Map result = initialize()
        Map config = configuration()
        byte[] original = Files.readAllBytes(run.resolve('planner.yaml'))

        then:
        result.exit == 0
        result.json.state == 'initialized'
        result.json.liveReady == false
        config.planner.weather.latitude == 40.7253341
        config.planner.weather.longitude == -80.0984618
        config.planner.ai.enabled == false
        config.planner.mode == 'preview'
        config.planner.integration.calendar.provider == 'google_calendar_api'
        !config.planner.integration.containsKey('caldav')
        Files.getPosixFilePermissions(run) == PosixFilePermissions.fromString('rwx------')
        Files.getPosixFilePermissions(run.resolve('planner.yaml')) == PosixFilePermissions.fromString('rw-------')
        Files.readAllLines(run.resolve('cases.csv')).drop(1).every { it.split(',')[4] == 'not_run' }
        !Files.exists(auth)

        when:
        Map repeat = initialize()
        Map unresolved = command('preflight', 'case')

        then:
        repeat.exit == 2 && repeat.json.error == 'existing_run_refused'
        Files.readAllBytes(run.resolve('planner.yaml')) == original
        unresolved.exit == 2 && unresolved.json.error == 'unresolved_inputs'
    }

    def "init refuses traversal and symlink workspaces without modifying targets"() {
        given:
        Path outside = Files.createDirectory(temporary.resolve('outside'))
        Files.createSymbolicLink(checkout.resolve('.qa'), outside)

        expect:
        command('init', '../outside', '--auth-root', auth.toString()).json.error == 'invalid_run_id'
        initialize().json.error == 'symlink_refused'
        Files.list(outside).withCloseable { it.count() } == 0
    }

    def "restore and preflight validate fake complete records locally and refuse stale snapshot overwrite"() {
        given:
        assert initialize().exit == 0
        resolveInputs()
        snapshots()

        when:
        Map restored = command('restore-auth', 'case')
        Map checked = command('preflight', 'case')
        byte[] original = Files.readAllBytes(auth.resolve('providers/grok/credential.json'))
        Map repeated = command('restore-auth', 'case')

        then:
        restored.exit == 0 && restored.json.state == 'restored_local'
        checked.exit == 0 && checked.json.state == 'ready_offline'
        checked.json.remoteIdentityVerified == false
        checked.json.inferenceVerified == false
        checked.json.mutationAuthorized == false
        repeated.exit == 2 && repeated.json.error == 'existing_credential_refused'
        Files.readAllBytes(auth.resolve('providers/grok/credential.json')) == original
        Files.getPosixFilePermissions(auth.resolve('providers/grok/credential.json')) == PosixFilePermissions.fromString('rw-------')
    }

    def "preflight refuses unsafe enabled controls state escape and absent provider tokens"() {
        given:
        assert initialize().exit == 0
        resolveInputs()
        snapshots()
        assert command('restore-auth', 'case').exit == 0
        Map original = configuration()

        when:
        Map unsafe = configuration()
        unsafe.planner.messaging.enabled = true
        saveConfig(unsafe)
        Map enabled = command('preflight', 'case')
        saveConfig(original)
        unsafe = configuration()
        unsafe.planner.integration.state.plans_dir = temporary.resolve('not-owned-state').toString()
        saveConfig(unsafe)
        Map escaped = command('preflight', 'case')
        saveConfig(original)
        environment.remove('SLACK_APP_TOKEN')
        Map absent = command('preflight', 'case')

        then:
        enabled.exit == 2 && enabled.json.error == 'baseline_disabled_controls_required'
        escaped.exit == 2 && escaped.json.error == 'state_path_refused'
        absent.exit == 2 && absent.json.error == 'credential_environment_missing'
        !Files.exists(temporary.resolve('not-owned-state'))
    }

    def "missing snapshot blocks restoration before any credential is written"() {
        given:
        assert initialize().exit == 0
        resolveInputs()
        snapshots()
        environment.remove('SMARTPLANNER_XAI_SUBSCRIPTION_CREDENTIAL_JSON_B64')

        when:
        Map result = command('restore-auth', 'case')

        then:
        result.exit == 2 && result.json.error == 'credential_snapshot_missing'
        !Files.exists(run.resolve('secrets/google-oauth-client.json'))
        !Files.exists(auth)
    }

    private Map initialize() {
        command('init', 'case', '--auth-root', auth.toString(), '--account', 'qa@example.test',
            '--slack-channel', 'CQA123', '--slack-user', 'UQA123', '--model', 'grok-fixture')
    }

    private Map configuration() { new YamlSlurper().parse(run.resolve('planner.yaml').toFile()) as Map }
    private void saveConfig(Map value) { Files.writeString(run.resolve('planner.yaml'), JsonOutput.toJson(value)) }
    private void resolveInputs() {
        Map config = configuration()
        config.planner.integration.calendar.google_calendar_api.calendars[0].id = 'qa-output'
        config.planner.integration.calendar.google_calendar_api.calendars[1].id = 'qa-blocker'
        saveConfig(config)
    }

    private void snapshots() {
        Map client = [installed: [client_id: 'fixture-client', client_secret: 'sentinel-private-google-client',
            auth_uri: 'https://accounts.google.com/o/oauth2/auth', token_uri: 'https://oauth2.googleapis.com/token']]
        Map token = [access_token: 'sentinel-private-access', refresh_token: 'sentinel-private-refresh',
            expires_at: Instant.now().plusSeconds(3600).toString(), account_email: 'qa@example.test', account_subject: 'fixture-subject']
        Map xai = new SubscriptionCredential(provider: 'grok', adapterRevision: XaiDeviceOAuthAdapter.REVISION,
            issuer: 'https://auth.x.ai', clientId: XaiDeviceOAuthAdapter.CLIENT_ID,
            accessToken: 'sentinel-private-xai', refreshToken: 'sentinel-private-xai-refresh',
            expiresAt: Instant.now().plusSeconds(3600), scopes: XaiDeviceOAuthAdapter.SCOPES).persisted()
        [SMARTPLANNER_GOOGLE_OAUTH_CLIENT_JSON_B64: client,
         SMARTPLANNER_GOOGLE_NORMAL_TOKEN_JSON_B64: token + [scopes: GoogleOAuthScopes.EVENTS],
         SMARTPLANNER_GOOGLE_QA_TOKEN_JSON_B64: token + [scopes: GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT],
         SMARTPLANNER_XAI_SUBSCRIPTION_CREDENTIAL_JSON_B64: xai].each { key, value ->
            environment[key] = JsonOutput.toJson(value).bytes.encodeBase64().toString()
        }
    }

    private Map command(String... arguments) {
        ProcessBuilder builder = new ProcessBuilder(['bash', checkout.resolve('scripts/smartplanner-qa.sh').toString()] + arguments.toList())
        builder.directory(temporary.toFile()).redirectErrorStream(true)
        // Never inherit the operator's credentials; the fixture has no real provider material.
        String javaHome = System.getProperty('java.home')
        builder.environment().clear()
        builder.environment().putAll([PATH: "${javaHome}/bin:/usr/bin:/bin".toString(), HOME: temporary.toString()] + environment)
        Process process = builder.start()
        assert process.waitFor(45, TimeUnit.SECONDS)
        String output = process.inputStream.text.trim()
        assert !output.contains('sentinel-private')
        [exit: process.exitValue(), json: new JsonSlurper().parseText(output)]
    }
}
