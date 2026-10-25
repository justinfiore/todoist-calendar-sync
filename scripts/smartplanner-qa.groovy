import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.yaml.YamlBuilder
import groovy.yaml.YamlSlurper
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import todoistcaldavsync.planner.ProductionIntegrationConfig
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.ai.SubscriptionCredentialStore
import todoistcaldavsync.planner.ai.XaiDeviceOAuthAdapter
import todoistcaldavsync.planner.oauth.GoogleOAuthClientMaterialLoader
import todoistcaldavsync.planner.oauth.GoogleOAuthScopes
import todoistcaldavsync.planner.oauth.PrivateFileGoogleOAuthTokenStore

// QA support only: config parsing and protected local files, never provider construction.
final class OrbQa {
    final Path root
    final Path run
    final String id

    OrbQa(Path root, String id) {
        require(id ==~ /[A-Za-z0-9][A-Za-z0-9_-]{0,79}/, 'invalid_run_id')
        this.root = root.toAbsolutePath().normalize()
        this.id = id
        this.run = this.root.resolve('.qa/runs').resolve(id)
        noSymlinks(run)
    }

    static void require(boolean valid, String code) {
        if (!valid) throw new IllegalStateException(code)
    }

    static void noSymlinks(Path path) {
        Path current = path.toAbsolutePath().root
        path.toAbsolutePath().each { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current), 'symlink_refused')
        }
    }

    void privateDirectory(Path path) {
        noSymlinks(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rwx------')))
        }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), 'private_directory_required')
        require(Files.getOwner(path) == Files.getOwner(root), 'private_owner_required')
        require(Files.getPosixFilePermissions(path) == PosixFilePermissions.fromString('rwx------'), 'private_permissions_required')
    }

    void privateFile(Path path) {
        noSymlinks(path)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1_048_576, 'private_file_required')
        require(Files.getOwner(path) == Files.getOwner(root), 'private_owner_required')
        require(Files.getPosixFilePermissions(path) == PosixFilePermissions.fromString('rw-------'), 'private_permissions_required')
        require((Files.getAttribute(path, 'unix:nlink') as Number).intValue() == 1, 'hardlink_refused')
    }

    void writeNew(Path path, String value) {
        privateDirectory(path.parent)
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS), 'existing_file_refused')
        Files.writeString(path, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString('rw-------'))
    }

    Path authRoot(Map config) {
        String value = config.planner.ai.subscription.auth_root?.toString()
        require(value != null && Path.of(value).isAbsolute() && !value.contains('REQUIRED_'), 'external_auth_root_required')
        Path auth = Path.of(value).normalize()
        require(!auth.startsWith(root) && !root.startsWith(auth), 'external_auth_root_required')
        require(!auth.any { it.toString() in ['.codex', '.grok', '.hermes'] }, 'vendor_home_refused')
        noSymlinks(auth)
        if (Files.exists(auth)) privateDirectory(auth)
        auth
    }

    void init(Map options) {
        require(!Files.exists(run, LinkOption.NOFOLLOW_LINKS), 'existing_run_refused')
        Map config = new YamlSlurper().parse(root.resolve('.qa.example/planner-orb-qa.yaml').toFile()) as Map
        config.planner.ai.subscription.auth_root = options['--auth-root']
        authRoot(config)
        config.planner.integration.calendar.google_calendar_api.account_email = options['--account'] ?:
            System.getenv('GOOGLE_USERNAME') ?: 'REQUIRED_ACCOUNT@example.test'
        if (options['--slack-channel']) config.planner.messaging.destination = options['--slack-channel']
        if (options['--slack-user']) config.planner.integration.feedback.allowed_actors = [options['--slack-user']]
        if (options['--model']) config.planner.ai.model = options['--model']
        // Validate the complete template contract even when run-specific placeholders remain.
        PlannerConfig.fromMap(config)
        ProductionIntegrationConfig.fromMap(config, run)
        privateDirectory(root.resolve('.qa'))
        privateDirectory(root.resolve('.qa/runs'))
        privateDirectory(run)
        ['raw', 'sanitized', 'before', 'after', 'receipts', 'state/plans', 'state/applications',
         'state/decisions', 'state/deliveries', 'secrets', 'tokens/normal-event-only',
         'tokens/qa-calendar-management'].each { privateDirectory(run.resolve(it)) }
        def yaml = new YamlBuilder()
        yaml.call(config)
        writeNew(run.resolve('planner.yaml'), yaml.toString())
        writeNew(run.resolve('auth-log4j.groovy'), 'log4j.rootLogger = "OFF"\n')
        writeNew(run.resolve('cases.csv'), Files.readString(root.resolve('.qa.example/orb-qa-cases.csv')))
        Instant start = Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        String revision = 'unknown'
        Process git = new ProcessBuilder('git', '-C', root.toString(), 'rev-parse', 'HEAD').redirectError(ProcessBuilder.Redirect.DISCARD).start()
        String candidate = git.inputStream.text.trim()
        if (git.waitFor() == 0 && candidate ==~ /[0-9a-f]{40}/) revision = candidate
        writeNew(run.resolve('campaign.json'), JsonOutput.prettyPrint(JsonOutput.toJson([
            runId: id, revision: revision, createdAt: Instant.now().toString(),
            rangeStart: start.toString(), rangeEnd: start.plusSeconds(3 * 86400L).toString(),
            initialConfigSha256: hash(run.resolve('planner.yaml')),
            excluded: ['caldav_live_qa', 'openai_subscription_auth'], status: 'not_run'
        ])) + '\n')
        println JsonOutput.toJson([command: 'init', state: 'initialized', runId: id, liveReady: false])
    }

    Map config() {
        privateDirectory(root.resolve('.qa'))
        privateDirectory(root.resolve('.qa/runs'))
        require(Files.isDirectory(run), 'run_missing')
        privateDirectory(run)
        privateFile(run.resolve('planner.yaml'))
        Map value = new YamlSlurper().parse(run.resolve('planner.yaml').toFile()) as Map
        require(value.planner instanceof Map, 'invalid_configuration')
        value
    }

    void validateCredentials(Map config, boolean includeXai) {
        def google = config.planner.integration.calendar.google_calendar_api
        def material = new GoogleOAuthClientMaterialLoader().load(run.resolve('secrets/google-oauth-client.json'))
        require(material.tokenEndpoint.toString() == 'https://oauth2.googleapis.com/token', 'google_endpoint_refused')
        [[name: 'normal-event-only', scopes: GoogleOAuthScopes.EVENTS],
         [name: 'qa-calendar-management', scopes: GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT]].each { profile ->
            privateDirectory(run.resolve("tokens/${profile.name}"))
            privateFile(run.resolve("tokens/${profile.name}/credential.json"))
            def credential = new PrivateFileGoogleOAuthTokenStore(run.resolve("tokens/${profile.name}")).load().orElseThrow()
            require(google.account_email.toString().equalsIgnoreCase(credential.accountEmail) &&
                credential.scopes == profile.scopes, 'google_account_or_scope_mismatch')
        }
        if (includeXai) {
            def store = new SubscriptionCredentialStore(authRoot(config), 'grok')
            // Check file presence first so a preflight never creates an auth store.
            privateFile(authRoot(config).resolve('providers/grok/credential.json'))
            new XaiDeviceOAuthAdapter().validate(store.load().orElseThrow())
        }
    }

    void preflight() {
        Map raw = config()
        require(!JsonOutput.toJson(raw).contains('REQUIRED_'), 'unresolved_inputs')
        def planner = PlannerConfig.fromMap(raw)
        def integration = ProductionIntegrationConfig.fromMap(raw, run)
        require(raw.planner.mode == 'preview', 'baseline_mode_required')
        [raw.planner.ai, raw.planner.messaging, raw.planner.weather, raw.planner.daemon,
         raw.planner.tasks.recurrence, raw.planner.calendarWriteback].each {
            require(it.enabled == false, 'baseline_disabled_controls_required')
        }
        require(integration.calendarProvider.provider == 'google_calendar_api' &&
            planner.ai.provider == 'grok_build_subscription', 'qa_provider_required')
        require(integration.todoist.baseUrl == 'https://api.todoist.com/api/v1' &&
            integration.weather.baseUrl == 'https://api.open-meteo.com/v1/forecast', 'qa_endpoint_required')
        require(planner.ai.redactionEnabled && planner.ai.requireConfirmation, 'ai_safety_required')
        require(raw.planner.ai.subscription.grok.allowed_hosts == ['api.x.ai'], 'qa_endpoint_required')
        require(integration.feedback.rules.every { it.action in ['help', 'status', 'replan', 'acknowledge', 'reject'] }, 'write_rule_refused')
        require(!integration.feedback.allowedActors.empty &&
            integration.feedback.allowedActors.every { it ==~ /U[A-Z0-9]+/ } &&
            raw.planner.messaging.destination ==~ /[CG][A-Z0-9]+/, 'slack_identity_required')
        def google = integration.calendarProvider.googleCalendarApi
        require(google.oauthClientSecretFile == run.resolve('secrets/google-oauth-client.json') &&
            google.tokenStoreDir == run.resolve('tokens/normal-event-only') &&
            google.qaTokenStoreDir == run.resolve('tokens/qa-calendar-management'), 'credential_path_refused')
        List<Path> states = [integration.plansDir, integration.applicationsDir, integration.decisionsDir, integration.deliveriesDir]
        require(states.every { it.startsWith(run.resolve('state')) }, 'state_path_refused')
        states.each { path ->
            require(states.every { other -> other == path || (!other.startsWith(path) && !path.startsWith(other)) }, 'state_path_overlap')
            privateDirectory(path)
        }
        privateFile(run.resolve('secrets/google-oauth-client.json'))
        validateCredentials(raw, true)
        List missing = [integration.todoist.tokenEnv, raw.planner.messaging.bot_token_env, raw.planner.messaging.app_token_env]
            .findAll { !System.getenv(it.toString()) }
        require(missing.empty, 'credential_environment_missing')
        privateFile(run.resolve('campaign.json'))
        Map campaign = new JsonSlurper().parse(run.resolve('campaign.json').toFile()) as Map
        Instant start = Instant.parse(campaign.rangeStart.toString()), end = Instant.parse(campaign.rangeEnd.toString())
        require(start.isBefore(end) && end.isAfter(Instant.now()) &&
            !end.isAfter(Instant.now().plusSeconds(7 * 86400L)), 'forecast_range_invalid')
        println JsonOutput.toJson([command: 'preflight', state: 'ready_offline', runId: id,
            configSha256: hash(run.resolve('planner.yaml')), remoteIdentityVerified: false,
            inferenceVerified: false, mutationAuthorized: false])
    }

    void restoreAuth() {
        Map raw = config()
        Path auth = authRoot(raw)
        Map<String, Path> targets = [
            SMARTPLANNER_GOOGLE_OAUTH_CLIENT_JSON_B64: run.resolve('secrets/google-oauth-client.json'),
            SMARTPLANNER_GOOGLE_NORMAL_TOKEN_JSON_B64: run.resolve('tokens/normal-event-only/credential.json'),
            SMARTPLANNER_GOOGLE_QA_TOKEN_JSON_B64: run.resolve('tokens/qa-calendar-management/credential.json'),
            SMARTPLANNER_XAI_SUBSCRIPTION_CREDENTIAL_JSON_B64: auth.resolve('providers/grok/credential.json')
        ]
        Map<Path, String> decoded = [:]
        targets.each { name, path ->
            noSymlinks(path)
            require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS), 'existing_credential_refused')
            String encoded = System.getenv(name)
            require(encoded != null && encoded.length() <= 90000, 'credential_snapshot_missing')
            byte[] bytes = Base64.decoder.decode(encoded)
            require(bytes.length <= 65536, 'credential_snapshot_too_large')
            String json = new String(bytes, 'UTF-8')
            require(new JsonSlurper().parseText(json) instanceof Map, 'credential_snapshot_invalid')
            decoded[path] = json
        }
        // Validate all parents before writing any file; never overwrite a rotating local grant.
        decoded.keySet().each { privateDirectory(it.parent) }
        decoded.each { path, json -> writeNew(path, json) }
        validateCredentials(raw, true)
        println JsonOutput.toJson([command: 'restore-auth', state: 'restored_local', runId: id,
            remoteIdentityVerified: false, refreshOwner: 'this_orb_only'])
    }

    static String hash(Path file) {
        MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(file)).encodeHex().toString()
    }
}

try {
    OrbQa.require(args.length >= 3, 'invalid_arguments')
    def qa = new OrbQa(Path.of(args[0]), args[2])
    switch (args[1]) {
        case 'init':
            OrbQa.require((args.length - 3) % 2 == 0, 'invalid_arguments')
            Map options = [:]
            for (int i = 3; i < args.length; i += 2) {
                OrbQa.require(args[i] in ['--auth-root', '--account', '--slack-channel', '--slack-user', '--model'] &&
                    !options.containsKey(args[i]), 'invalid_arguments')
                options[args[i]] = args[i + 1]
            }
            qa.init(options)
            break
        case 'preflight':
            OrbQa.require(args.length == 3, 'invalid_arguments')
            qa.preflight()
            break
        case 'restore-auth':
            OrbQa.require(args.length == 3, 'invalid_arguments')
            qa.restoreAuth()
            break
        default: throw new IllegalStateException('invalid_arguments')
    }
} catch (Exception error) {
    // Never print config/parser exceptions, secret values, identities or credential paths.
    String code = error instanceof IllegalStateException && error.message ==~ /[a-z_]+/ ? error.message : 'invalid_configuration'
    println JsonOutput.toJson([state: 'blocked', error: code])
    System.exit(2)
}
