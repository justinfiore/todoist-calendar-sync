package todoistcaldavsync.planner

import todoistcaldavsync.planner.oauth.GoogleOAuthException
import todoistcaldavsync.planner.oauth.GoogleOAuthStoreIsolation

import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Validates production-only endpoints, credentials, daemon schedules, feedback and state. */
final class ProductionIntegrationConfig {
    static final Set<String> FEEDBACK_ACTIONS = [
        'acknowledge', 'approve', 'reject', 'replan', 'apply_safe', 'status', 'help'
    ] as Set

    final Map todoist
    final List<Map> calendars
    final Map caldav
    final CalendarProviderConfig calendarProvider
    final Map weather
    final Map feedback
    final Map daemon
    final Map slack
    final Path plansDir
    final Path applicationsDir
    final Path decisionsDir
    final Path deliveriesDir
    final String previousPlanId

    private ProductionIntegrationConfig(Map root, Path configDir, boolean googleBootstrapOnly, boolean qaBootstrap) {
        Map planner = root.planner instanceof Map ? root.planner as Map : [:]
        Map integration = planner.integration instanceof Map ? planner.integration as Map : [:]
        if (googleBootstrapOnly) {
            Map calendarRaw = integration.calendar instanceof Map ? integration.calendar as Map : [:]
            String provider = calendarRaw.provider?.toString()?.trim()
            if (provider != CalendarProviderConfig.GOOGLE_CALENDAR_API) {
                throw new IllegalArgumentException('planner.integration.calendar.provider must be exactly google_calendar_api for OAuth bootstrap')
            }
            Set<String> calendarKeys = calendarRaw.keySet().collect { it.toString() } as Set
            if (calendarKeys.any { !(it in ['provider', 'google_calendar_api']) } || integration.containsKey('caldav')) {
                throw new IllegalArgumentException('mixed or unsupported calendar provider fields are not allowed for Google OAuth bootstrap')
            }
            Map googleRaw = calendarRaw.google_calendar_api instanceof Map ? calendarRaw.google_calendar_api as Map : [:]
            def google = parseGoogleCalendarApi(googleRaw, configDir, null, true, qaBootstrap)
            this.todoist = Collections.emptyMap()
            this.calendars = google.calendars
            this.caldav = null
            this.calendarProvider = new CalendarProviderConfig(provider, null, google)
            this.weather = Collections.emptyMap()
            this.feedback = Collections.emptyMap()
            this.daemon = Collections.emptyMap()
            this.slack = Collections.emptyMap()
            this.plansDir = null
            this.applicationsDir = null
            this.decisionsDir = null
            this.deliveriesDir = null
            this.previousPlanId = null
            return
        }
        Map todoistRaw = integration.todoist instanceof Map ? integration.todoist as Map : [:]
        String todoistBase = todoistRaw.base_url?.toString()
        String tokenEnv = todoistRaw.token_env?.toString()
        if (!todoistBase || !tokenEnv) {
            throw new IllegalArgumentException('planner.integration.todoist.base_url and token_env are required')
        }
        rejectInlineSecrets(todoistRaw, 'planner.integration.todoist')
        validateHttpsUri(todoistBase, 'planner.integration.todoist.base_url')
        this.todoist = Collections.unmodifiableMap([
            baseUrl            : todoistBase,
            tokenEnv           : tokenEnv,
            includeProjectNames: todoistRaw.include_project_names != false,
            timeout            : parseDuration(todoistRaw.timeout, Duration.ofSeconds(10), 'planner.integration.todoist.timeout'),
            maxPages           : positiveInt(todoistRaw.max_pages, 100, 1000, 'planner.integration.todoist.max_pages'),
            maxResponseBytes   : positiveLong(todoistRaw.max_response_bytes, 1_048_576L,
                'planner.integration.todoist.max_response_bytes')
        ])

        Map calendarRaw = integration.calendar instanceof Map ? integration.calendar as Map : [:]
        String provider = calendarRaw.provider?.toString()?.trim()
        if (!(provider in [CalendarProviderConfig.CALDAV, CalendarProviderConfig.GOOGLE_CALENDAR_API])) {
            throw new IllegalArgumentException('planner.integration.calendar.provider must be exactly caldav or google_calendar_api')
        }
        Set<String> calendarKeys = calendarRaw.keySet().collect { it.toString() } as Set
        if (calendarKeys.any { !(it in ['provider', 'google_calendar_api']) }) {
            throw new IllegalArgumentException('planner.integration.calendar contains mixed or unsupported provider fields')
        }
        String outputCalendar = planner.output_calendar?.toString()
        if (provider == CalendarProviderConfig.CALDAV) {
            if (calendarRaw.containsKey('google_calendar_api')) {
                throw new IllegalArgumentException('mixed calendar provider sections are not allowed when provider is caldav')
            }
            Map parsed = parseCalDav(integration.caldav, outputCalendar)
            Map providerCalDav = Collections.unmodifiableMap([
                calendars: parsed.calendars,
                timeout: parsed.settings.timeout,
                maxResponseBytes: parsed.settings.maxResponseBytes
            ])
            this.calendarProvider = new CalendarProviderConfig(provider, providerCalDav, null)
            this.calendars = this.calendarProvider.caldav.calendars as List<Map>
            this.caldav = Collections.unmodifiableMap([
                timeout: this.calendarProvider.caldav.timeout,
                maxResponseBytes: this.calendarProvider.caldav.maxResponseBytes
            ])
        } else {
            if (integration.containsKey('caldav')) {
                throw new IllegalArgumentException('mixed CalDAV and Google calendar provider sections are not allowed')
            }
            Map googleRaw = calendarRaw.google_calendar_api instanceof Map ? calendarRaw.google_calendar_api as Map : [:]
            def google = parseGoogleCalendarApi(googleRaw, configDir, outputCalendar, googleBootstrapOnly, false)
            this.calendars = google.calendars
            this.caldav = null
            this.calendarProvider = new CalendarProviderConfig(provider, null, google)
        }

        Map weatherRaw = integration.weather instanceof Map ? integration.weather as Map : [:]
        this.weather = Collections.unmodifiableMap([
            baseUrl: (weatherRaw.base_url ?: 'https://api.open-meteo.com/v1/forecast').toString(),
            timeout: parseDuration(weatherRaw.timeout, Duration.ofSeconds(10), 'planner.integration.weather.timeout'),
            maxResponseBytes: positiveLong(weatherRaw.max_response_bytes, 1_048_576L,
                'planner.integration.weather.max_response_bytes')
        ])
        validateHttpsUri(this.weather.baseUrl.toString(), 'planner.integration.weather.base_url')

        Map feedbackRaw = integration.feedback instanceof Map ? new LinkedHashMap(integration.feedback as Map) : [:]
        this.feedback = Collections.unmodifiableMap(parseFeedback(feedbackRaw))
        this.daemon = Collections.unmodifiableMap(parseDaemon(planner.daemon instanceof Map ? planner.daemon as Map : [:]))
        this.slack = Collections.unmodifiableMap(parseSlack(planner.messaging instanceof Map ? planner.messaging as Map : [:], daemon.enabled == true))

        Map state = integration.state instanceof Map ? integration.state as Map : [:]
        this.plansDir = requiredPath(state.plans_dir, configDir, 'planner.integration.state.plans_dir')
        this.applicationsDir = requiredPath(state.applications_dir, configDir, 'planner.integration.state.applications_dir')
        this.decisionsDir = requiredPath(state.decisions_dir, configDir, 'planner.integration.state.decisions_dir')
        this.deliveriesDir = requiredPath(state.deliveries_dir, configDir, 'planner.integration.state.deliveries_dir')
        if ([plansDir, applicationsDir, decisionsDir, deliveriesDir].toSet().size() != 4) {
            throw new IllegalArgumentException('planner.integration.state paths must be separate')
        }
        this.previousPlanId = integration.previous_plan_id?.toString()
    }

    static ProductionIntegrationConfig fromMap(Map root, Path configDir) {
        new ProductionIntegrationConfig(root ?: [:], configDir, false, false)
    }

    static ProductionIntegrationConfig fromMapForGoogleOAuthBootstrap(Map root, Path configDir) {
        new ProductionIntegrationConfig(root ?: [:], configDir, true, false)
    }

    static ProductionIntegrationConfig fromMapForGoogleOAuthBootstrap(Map root, Path configDir, boolean qaBootstrap) {
        new ProductionIntegrationConfig(root ?: [:], configDir, true, qaBootstrap)
    }

    Collection<String> feedbackActors() {
        def raw = feedback.allowedActors
        raw instanceof Collection ? (raw as Collection).collect { it.toString() } : []
    }

    List<Map> planningRuns() { daemon.planningRuns as List<Map> }
    List<Map> feedbackRules() { feedback.rules as List<Map> }

    private static Map parseCalDav(def raw, String outputCalendar) {
        Map caldavRaw = raw instanceof Map ? raw as Map : [:]
        if (caldavRaw.keySet().any { it.toString() in [
            'google_calendar_api', 'oauth_client_secret_file', 'token_store_dir', 'qa_token_store_dir',
            'account_email', 'oauth_callback_port'
        ] }) {
            throw new IllegalArgumentException('planner.integration.caldav contains mixed Google provider fields')
        }
        if (!(caldavRaw.calendars instanceof Collection) || caldavRaw.calendars.isEmpty()) {
            throw new IllegalArgumentException('planner.integration.caldav.calendars must be a non-empty list')
        }
        List<Map> parsedCalendars = []
        (caldavRaw.calendars as Collection).eachWithIndex { row, int idx ->
            if (!(row instanceof Map)) throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}] must be a map")
            Map copy = new LinkedHashMap(row as Map)
            if (copy.containsKey('id') || copy.containsKey('role')) {
                throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}] contains mixed Google provider fields")
            }
            String calendarName = copy.name?.toString()?.trim()
            String calendarUrl = copy.url?.toString()
            if (!calendarName || !calendarUrl) {
                throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}] requires name and url")
            }
            validateHttpsUri(calendarUrl, "planner.integration.caldav.calendars[${idx}].url")
            Map auth = copy.auth instanceof Map ? new LinkedHashMap(copy.auth as Map) : [:]
            rejectInlineSecrets(auth, "planner.integration.caldav.calendars[${idx}].auth")
            String authType = (auth.type ?: auth.scheme ?: 'none').toString().toLowerCase(Locale.ROOT)
            if (authType == 'basic' && (!auth.username?.toString()?.trim() || !auth.password_env?.toString()?.trim())) {
                throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}].auth basic requires username and password_env")
            }
            if (authType in ['bearer', 'oauth2'] && !auth.token_env?.toString()?.trim()) {
                throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}].auth bearer requires token_env")
            }
            copy.name = calendarName
            copy.auth = Collections.unmodifiableMap(auth)
            parsedCalendars << Collections.unmodifiableMap(copy)
        }
        if (parsedCalendars*.name.toSet().size() != parsedCalendars.size()) {
            throw new IllegalArgumentException('planner.integration.caldav calendar names must be unique')
        }
        if (!outputCalendar || !parsedCalendars*.name.contains(outputCalendar)) {
            throw new IllegalArgumentException('planner.output_calendar must name one configured integration CalDAV calendar')
        }
        Map settings = Collections.unmodifiableMap([
            timeout         : parseDuration(caldavRaw.timeout, Duration.ofSeconds(15), 'planner.integration.caldav.timeout'),
            maxResponseBytes: positiveLong(caldavRaw.max_response_bytes, 2_097_152L,
                'planner.integration.caldav.max_response_bytes')
        ])
        [calendars: Collections.unmodifiableList(parsedCalendars), settings: settings]
    }

    private static CalendarProviderConfig.GoogleCalendarApiConfig parseGoogleCalendarApi(
        Map raw, Path configDir, String outputCalendar, boolean bootstrapOnly, boolean qaBootstrap) {
        rejectInlineSecrets(raw, 'planner.integration.calendar.google_calendar_api')
        Set<String> allowed = ['oauth_client_secret_file', 'token_store_dir', 'qa_token_store_dir', 'account_email',
                               'oauth_callback_port', 'calendars'] as Set
        if (raw.keySet().any { !allowed.contains(it.toString()) }) {
            throw new IllegalArgumentException('planner.integration.calendar.google_calendar_api contains mixed or unsupported provider fields')
        }
        Path clientFile = requiredContainedPath(raw.oauth_client_secret_file, configDir,
            'planner.integration.calendar.google_calendar_api.oauth_client_secret_file')
        Path tokenStore = requiredContainedPath(raw.token_store_dir, configDir,
            'planner.integration.calendar.google_calendar_api.token_store_dir')
        Path qaTokenStore = raw.qa_token_store_dir != null ? requiredContainedPath(raw.qa_token_store_dir, configDir,
            'planner.integration.calendar.google_calendar_api.qa_token_store_dir') : null
        if (qaBootstrap && qaTokenStore == null) {
            throw new IllegalArgumentException('planner.integration.calendar.google_calendar_api.qa_token_store_dir is required for QA OAuth bootstrap')
        }
        if (qaTokenStore != null && qaTokenStore == tokenStore) {
            throw new IllegalArgumentException('normal and QA Google OAuth token store directories must be distinct')
        }
        try {
            if (qaTokenStore != null) GoogleOAuthStoreIsolation.requireDistinct(tokenStore, qaTokenStore)
            else GoogleOAuthStoreIsolation.requireIsolated(tokenStore)
        } catch (GoogleOAuthException e) {
            throw new IllegalArgumentException(e.message)
        }
        String accountEmail = raw.account_email?.toString()?.trim()
        if (!accountEmail || !(accountEmail ==~ /[^\s@]+@[^\s@]+\.[^\s@]+/)) {
            throw new IllegalArgumentException('planner.integration.calendar.google_calendar_api.account_email must be a valid email address')
        }
        int callbackPort = positiveInt(raw.oauth_callback_port, 8787, 65535,
            'planner.integration.calendar.google_calendar_api.oauth_callback_port')
        List<Map> calendars = parseGoogleCalendars(raw.calendars, outputCalendar, bootstrapOnly)
        new CalendarProviderConfig.GoogleCalendarApiConfig(
            clientFile, tokenStore, qaTokenStore, accountEmail, callbackPort, calendars)
    }

    private static List<Map> parseGoogleCalendars(def raw, String outputCalendar, boolean bootstrapOnly) {
        if (bootstrapOnly) return Collections.emptyList()
        if (!(raw instanceof Collection) || (raw as Collection).isEmpty()) {
            throw new IllegalArgumentException('planner.integration.calendar.google_calendar_api.calendars must be a non-empty list')
        }
        List<Map> calendars = []
        (raw as Collection).eachWithIndex { row, int idx ->
            if (!(row instanceof Map)) {
                throw new IllegalArgumentException("planner.integration.calendar.google_calendar_api.calendars[${idx}] must be a map")
            }
            Set<String> keys = (row as Map).keySet().collect { it.toString() } as Set
            if (keys.any { !(it in ['name', 'id', 'role']) }) {
                throw new IllegalArgumentException("planner.integration.calendar.google_calendar_api.calendars[${idx}] contains mixed or unsupported fields")
            }
            String name = row.name?.toString()?.trim()
            String id = row.id?.toString()?.trim()
            String role = row.role?.toString()?.trim()?.toLowerCase(Locale.ROOT)
            if (!name || !id || !role) {
                throw new IllegalArgumentException("planner.integration.calendar.google_calendar_api.calendars[${idx}] requires name, id, and role")
            }
            if (!(role in ['hard_blocker', 'soft_blocker', 'informational', 'managed_output'])) {
                throw new IllegalArgumentException("planner.integration.calendar.google_calendar_api.calendars[${idx}].role is unsupported")
            }
            calendars << Collections.unmodifiableMap([name: name, id: id, role: role])
        }
        if (calendars*.name.toSet().size() != calendars.size() || calendars*.id.toSet().size() != calendars.size()) {
            throw new IllegalArgumentException('Google calendar names and IDs must be unique')
        }
        List<Map> managed = calendars.findAll { it.role == 'managed_output' }
        if (managed.size() != 1) {
            throw new IllegalArgumentException('Google calendars require exactly one managed_output mapping')
        }
        if (!outputCalendar || managed[0].name != outputCalendar) {
            throw new IllegalArgumentException('planner.output_calendar must match the Google managed_output calendar name')
        }
        Collections.unmodifiableList(calendars)
    }

    private static Map parseFeedback(Map raw) {
        def actors = raw.allowed_actors ?: raw.allowedActors ?: []
        if (!(actors instanceof Collection)) {
            throw new IllegalArgumentException('planner.integration.feedback.allowed_actors must be a list')
        }
        List<String> normalizedActors = (actors as Collection).collect { it?.toString()?.trim() }
        if (normalizedActors.any { !it } || normalizedActors.toSet().size() != normalizedActors.size()) {
            throw new IllegalArgumentException('planner.integration.feedback.allowed_actors must contain unique nonblank actor ids')
        }
        def rulesRaw = raw.rules ?: []
        if (!(rulesRaw instanceof Collection)) {
            throw new IllegalArgumentException('planner.integration.feedback.rules must be a list')
        }
        if ((rulesRaw as Collection).size() > 50) {
            throw new IllegalArgumentException('planner.integration.feedback.rules supports at most 50 entries')
        }
        Set<String> names = [] as Set
        List<Map> rules = []
        (rulesRaw as Collection).eachWithIndex { row, int idx ->
            if (!(row instanceof Map)) throw new IllegalArgumentException("planner.integration.feedback.rules[${idx}] must be a map")
            String name = row.name?.toString()?.trim()
            String pattern = row.pattern?.toString()
            String action = row.action?.toString()?.trim()?.toLowerCase(Locale.ROOT)?.replace('-', '_')
            if (!name || !names.add(name)) throw new IllegalArgumentException("planner.integration.feedback.rules[${idx}].name must be unique and nonblank")
            if (!pattern || pattern.length() > 1000) throw new IllegalArgumentException("planner.integration.feedback.rules[${idx}].pattern must be 1..1000 characters")
            if (!FEEDBACK_ACTIONS.contains(action)) throw new IllegalArgumentException("planner.integration.feedback.rules[${idx}].action unsupported: ${action}")
            Pattern compiled
            try { compiled = Pattern.compile(pattern) }
            catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("planner.integration.feedback.rules[${idx}].pattern invalid: ${e.description}")
            }
            Map overrides = row.overrides instanceof Map ? new LinkedHashMap(row.overrides as Map) : [:]
            validateOverrideTemplate(overrides, "planner.integration.feedback.rules[${idx}].overrides")
            rules << Collections.unmodifiableMap([name: name, pattern: pattern, compiled: compiled,
                action: action, overrides: Collections.unmodifiableMap(overrides)])
        }
        [allowedActors: Collections.unmodifiableList(normalizedActors), rules: Collections.unmodifiableList(rules)]
    }

    private static void validateOverrideTemplate(Map overrides, String path) {
        Set<String> allowed = ['horizon', 'priority_overrides', 'exclude_task_ids', 'freeze_task_ids', 'criteria'] as Set
        def unknown = overrides.keySet().collect { it.toString() }.findAll { !allowed.contains(it) }
        if (unknown) throw new IllegalArgumentException("${path} contains unsupported keys: ${unknown.join(', ')}")
        if (overrides.horizon != null) {
            Duration horizon = parseDuration(overrides.horizon, null, "${path}.horizon")
            if (horizon < Duration.ofMinutes(5) || horizon > Duration.ofDays(90)) {
                throw new IllegalArgumentException("${path}.horizon must be between PT5M and P90D")
            }
        }
        ['exclude_task_ids', 'freeze_task_ids'].each { key ->
            if (overrides[key] == null) return
            if (!(overrides[key] instanceof Collection)) throw new IllegalArgumentException("${path}.${key} must be a list")
            List<String> ids = (overrides[key] as Collection).collect { it?.toString()?.trim() }
            if (ids.size() > 100 || ids.any { !it } || ids.toSet().size() != ids.size()) {
                throw new IllegalArgumentException("${path}.${key} must contain at most 100 unique nonblank task ids")
            }
            overrides[key] = Collections.unmodifiableList(ids)
        }
        if (overrides.priority_overrides != null) {
            if (!(overrides.priority_overrides instanceof Map) || (overrides.priority_overrides as Map).size() > 100) {
                throw new IllegalArgumentException("${path}.priority_overrides must be a map of at most 100 task ids")
            }
            Map normalized = [:]
            (overrides.priority_overrides as Map).each { id, priority ->
                String taskId = id?.toString()?.trim()
                int value
                try { value = Integer.parseInt(priority?.toString()) }
                catch (Exception ignored) { throw new IllegalArgumentException("${path}.priority_overrides values must be integers 1..4") }
                if (!taskId || value < 1 || value > 4) throw new IllegalArgumentException("${path}.priority_overrides values must use nonblank ids and priorities 1..4")
                normalized[taskId] = value
            }
            overrides.priority_overrides = Collections.unmodifiableMap(normalized)
        }
        if (overrides.criteria != null) {
            String criteria = overrides.criteria.toString().trim()
            if (!criteria || criteria.length() > 1000) throw new IllegalArgumentException("${path}.criteria must be 1..1000 characters")
            overrides.criteria = criteria
        }
    }

    private static Map parseDaemon(Map raw) {
        boolean enabled = raw.enabled == true || raw.enabled?.toString()?.equalsIgnoreCase('true')
        def runsRaw = raw.planning_runs ?: raw.planningRuns ?: []
        if (enabled && (!(runsRaw instanceof Collection) || (runsRaw as Collection).isEmpty())) {
            throw new IllegalArgumentException('planner.daemon.planning_runs must be a non-empty list when daemon is enabled')
        }
        if (!(runsRaw instanceof Collection)) throw new IllegalArgumentException('planner.daemon.planning_runs must be a list')
        Set<String> names = [] as Set
        List<Map> runs = []
        (runsRaw as Collection).eachWithIndex { row, int idx ->
            if (!(row instanceof Map)) throw new IllegalArgumentException("planner.daemon.planning_runs[${idx}] must be a map")
            String name = row.name?.toString()?.trim()
            if (!name || !names.add(name)) throw new IllegalArgumentException("planner.daemon.planning_runs[${idx}].name must be unique and nonblank")
            Duration horizon = parseDuration(row.horizon, null, "planner.daemon.planning_runs[${idx}].horizon")
            Duration interval = parseDuration(row.interval ?: row.periodicity, null, "planner.daemon.planning_runs[${idx}].interval")
            Duration initial = parseDuration(row.initial_delay, Duration.ZERO, "planner.daemon.planning_runs[${idx}].initial_delay", true)
            if (initial.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException("planner.daemon.planning_runs[${idx}].initial_delay must be between PT0S and P30D")
            }
            if (horizon.compareTo(Duration.ofMinutes(5)) < 0 || horizon.compareTo(Duration.ofDays(90)) > 0) {
                throw new IllegalArgumentException("planner.daemon.planning_runs[${idx}].horizon must be between PT5M and P90D")
            }
            if (interval.compareTo(Duration.ofSeconds(10)) < 0 || interval.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException("planner.daemon.planning_runs[${idx}].interval must be between PT10S and P30D")
            }
            runs << Collections.unmodifiableMap([name: name, horizon: horizon, interval: interval,
                initialDelay: initial, runOnStartup: row.run_on_startup != false])
        }
        Map retry = raw.retry instanceof Map ? raw.retry as Map : [:]
        double multiplier
        try { multiplier = retry.multiplier == null ? 2.0d : Double.parseDouble(retry.multiplier.toString()) }
        catch (NumberFormatException ignored) { throw new IllegalArgumentException('planner.daemon.retry.multiplier must be numeric') }
        if (!Double.isFinite(multiplier) || multiplier < 1.0d || multiplier > 10.0d) {
            throw new IllegalArgumentException('planner.daemon.retry.multiplier must be between 1 and 10')
        }
        Duration shutdown = parseDuration(raw.shutdown_timeout, Duration.ofSeconds(20), 'planner.daemon.shutdown_timeout')
        Duration retryInitial = parseDuration(retry.initial_delay, Duration.ofSeconds(5), 'planner.daemon.retry.initial_delay')
        Duration retryMax = parseDuration(retry.max_delay, Duration.ofMinutes(5), 'planner.daemon.retry.max_delay')
        if (shutdown > Duration.ofMinutes(10)) throw new IllegalArgumentException('planner.daemon.shutdown_timeout must be at most PT10M')
        if (retryInitial > Duration.ofHours(1) || retryMax > Duration.ofDays(1) || retryMax < retryInitial) {
            throw new IllegalArgumentException('planner.daemon.retry delays require initial_delay <= PT1H and initial_delay <= max_delay <= P1D')
        }
        [enabled: enabled,
         startupConnectivityCheck: raw.startup_connectivity_check != false,
         shutdownTimeout: shutdown,
         retryInitialDelay: retryInitial,
         retryMaxDelay: retryMax,
         retryMultiplier: multiplier,
         planningRuns: Collections.unmodifiableList(runs)]
    }

    private static Map parseSlack(Map raw, boolean daemonEnabled) {
        boolean enabled = raw.enabled == true || raw.enabled?.toString()?.equalsIgnoreCase('true')
        String provider = (raw.provider ?: 'slack').toString().toLowerCase(Locale.ROOT)
        String mode = (raw.slack_mode ?: raw.slackMode ?: raw.mode ?: 'webhook').toString().toLowerCase(Locale.ROOT)
        String channel = (raw.destination ?: raw.channel ?: raw.channel_id)?.toString()?.trim()
        String botTokenEnv = (raw.bot_token_env ?: raw.botTokenEnv ?: raw.secret_env)?.toString()?.trim()
        String appTokenEnv = (raw.app_token_env ?: raw.appTokenEnv)?.toString()?.trim()
        rejectInlineSecrets(raw, 'planner.messaging')
        if (daemonEnabled) {
            if (!enabled || provider != 'slack' || mode != 'socket_mode') {
                throw new IllegalArgumentException('planner-daemon requires planner.messaging enabled with provider slack and slack_mode socket_mode')
            }
            if (!channel || !botTokenEnv || !appTokenEnv) {
                throw new IllegalArgumentException('planner-daemon Slack requires destination, bot_token_env, and app_token_env')
            }
        }
        def loading = raw.loading_messages ?: ['is planning…', 'is checking capacity…']
        if (!(loading instanceof Collection) || (loading as Collection).size() > 10) {
            throw new IllegalArgumentException('planner.messaging.loading_messages must be a list of at most 10 strings')
        }
        List<String> loadingMessages = (loading as Collection).collect {
            String s = it?.toString()?.trim()
            if (!s || s.length() > 100) throw new IllegalArgumentException('planner.messaging.loading_messages entries must be 1..100 characters')
            s
        }
        String appName = (raw.app_name ?: 'SmartPlanner').toString().trim()
        String command = (raw.command ?: '/smartplanner').toString().trim()
        String workingStatus = (raw.working_status ?: 'is working on your request…').toString().trim()
        if (!appName || appName.length() > 80) throw new IllegalArgumentException('planner.messaging.app_name must be 1..80 characters')
        if (!(command ==~ /\/[a-z0-9_-]{1,31}/)) throw new IllegalArgumentException('planner.messaging.command must be a lowercase Slack command such as /smartplanner')
        if (!workingStatus || workingStatus.length() > 100) throw new IllegalArgumentException('planner.messaging.working_status must be 1..100 characters')
        int maxEventTextChars = positiveInt(raw.max_event_text_chars, 4000, 40_000, 'planner.messaging.max_event_text_chars')
        int eventQueueCapacity = positiveInt(raw.event_queue_capacity, 100, 10_000, 'planner.messaging.event_queue_capacity')
        [enabled: enabled, provider: provider, mode: mode, channel: channel,
         botTokenEnv: botTokenEnv, appTokenEnv: appTokenEnv,
         appName: appName,
         command: command,
         workingStatus: workingStatus,
         maxEventTextChars: maxEventTextChars,
         eventQueueCapacity: eventQueueCapacity,
         loadingMessages: Collections.unmodifiableList(loadingMessages)]
    }

    private static Path requiredPath(def value, Path configDir, String name) {
        if (value == null || !value.toString().trim()) throw new IllegalArgumentException("${name} is required")
        Path path = Path.of(value.toString())
        if (!path.isAbsolute()) path = configDir.resolve(path)
        path.toAbsolutePath().normalize()
    }

    private static Path requiredContainedPath(def value, Path configDir, String name) {
        if (configDir == null) throw new IllegalArgumentException('configuration directory is required')
        Path base = configDir.toAbsolutePath().normalize()
        Path path = requiredPath(value, base, name)
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("${name} must resolve within the configuration directory")
        }
        Path realBase = Files.exists(base, LinkOption.NOFOLLOW_LINKS) ? base.toRealPath() : base
        Path current = base
        for (Path component : base.relativize(path)) {
            current = current.resolve(component)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break
            try {
                if (!current.toRealPath().startsWith(realBase)) {
                    throw new IllegalArgumentException("${name} must resolve within the configuration directory")
                }
            } catch (IOException ignored) {
                throw new IllegalArgumentException("${name} must resolve within the configuration directory")
            }
        }
        path
    }

    private static Duration parseDuration(def raw, Duration fallback, String name, boolean allowZero = false) {
        if (raw == null) {
            if (fallback == null) throw new IllegalArgumentException("${name} is required")
            return fallback
        }
        try {
            Duration value = Duration.parse(raw.toString())
            if (value.isNegative() || (!allowZero && value.isZero())) throw new IllegalArgumentException()
            value
        } catch (Exception ignored) {
            throw new IllegalArgumentException("${name} must be ${allowZero ? 'a non-negative' : 'a positive'} ISO-8601 duration")
        }
    }

    private static int positiveInt(def raw, int fallback, int maximum, String name) {
        int value
        try { value = raw == null ? fallback : Integer.parseInt(raw.toString()) }
        catch (Exception ignored) { throw new IllegalArgumentException("${name} must be a positive integer") }
        if (value < 1 || value > maximum) throw new IllegalArgumentException("${name} must be between 1 and ${maximum}")
        value
    }

    private static long positiveLong(def raw, long fallback, String name) {
        long value
        try { value = raw == null ? fallback : Long.parseLong(raw.toString()) }
        catch (Exception ignored) { throw new IllegalArgumentException("${name} must be a positive integer") }
        if (value < 1) throw new IllegalArgumentException("${name} must be positive")
        value
    }

    private static void validateHttpsUri(String raw, String name) {
        URI uri
        try { uri = URI.create(raw) }
        catch (Exception ignored) { throw new IllegalArgumentException("${name} must be an absolute HTTPS URL") }
        if (!uri.host || !uri.scheme?.equalsIgnoreCase('https') || uri.userInfo || uri.query || uri.fragment) {
            throw new IllegalArgumentException("${name} must be an absolute HTTPS URL without user-info, query, or fragment")
        }
    }

    private static void rejectInlineSecrets(Map map, String path) {
        map.keySet().each { key ->
            String k = key.toString().toLowerCase(Locale.ROOT)
            if (k in ['token', 'access_token', 'refresh_token', 'client_secret', 'api_key', 'password', 'secret', 'password_override',
                      'token_override', 'app_token', 'bot_token', 'webhook_url']) {
                throw new IllegalArgumentException("${path}.${key} must not contain an inline secret; use an environment or file reference")
            }
        }
    }
}
