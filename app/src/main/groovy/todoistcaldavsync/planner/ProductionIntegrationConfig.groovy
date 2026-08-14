package todoistcaldavsync.planner

import java.net.URI
import java.nio.file.Path
import java.time.Duration

/** Validates production-only endpoints, credential references, state paths and policies. */
final class ProductionIntegrationConfig {
    final Map todoist
    final List<Map> calendars
    final Map caldav
    final Map weather
    final Map feedback
    final Path plansDir
    final Path applicationsDir
    final Path decisionsDir
    final Path deliveriesDir
    final String previousPlanId

    private ProductionIntegrationConfig(Map root, Path configDir) {
        Map planner = root.planner instanceof Map ? root.planner as Map : [:]
        Map integration = planner.integration instanceof Map ? planner.integration as Map : [:]
        Map todoistRaw = integration.todoist instanceof Map ? integration.todoist as Map : [:]
        String todoistBase = todoistRaw.base_url?.toString()
        String tokenEnv = todoistRaw.token_env?.toString()
        if (!todoistBase || !tokenEnv) {
            throw new IllegalArgumentException('planner.integration.todoist.base_url and token_env are required')
        }
        rejectInlineSecrets(todoistRaw, 'planner.integration.todoist')
        validateHttpsUri(todoistBase, 'planner.integration.todoist.base_url')
        int maxPages = positiveInt(todoistRaw.max_pages, 100, 1000, 'planner.integration.todoist.max_pages')
        long todoistMaxBytes = positiveLong(todoistRaw.max_response_bytes, 1_048_576L,
            'planner.integration.todoist.max_response_bytes')
        this.todoist = Collections.unmodifiableMap([
            baseUrl            : todoistBase,
            tokenEnv           : tokenEnv,
            includeProjectNames: todoistRaw.include_project_names != false,
            timeout            : parseDuration(todoistRaw.timeout, Duration.ofSeconds(10), 'todoist.timeout'),
            maxPages           : maxPages,
            maxResponseBytes   : todoistMaxBytes
        ])

        Map caldav = integration.caldav instanceof Map ? integration.caldav as Map : [:]
        if (!(caldav.calendars instanceof Collection) || caldav.calendars.isEmpty()) {
            throw new IllegalArgumentException('planner.integration.caldav.calendars must be a non-empty list')
        }
        List<Map> parsedCalendars = []
        (caldav.calendars as Collection).eachWithIndex { row, int idx ->
            if (!(row instanceof Map)) throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}] must be a map")
            Map copy = new LinkedHashMap(row as Map)
            String calendarName = copy.name?.toString()?.trim()
            String calendarUrl = copy.url?.toString()
            if (!calendarName || !calendarUrl) {
                throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}] requires name and url")
            }
            validateHttpsUri(calendarUrl, "planner.integration.caldav.calendars[${idx}].url")
            Map auth = copy.auth instanceof Map ? copy.auth as Map : [:]
            rejectInlineSecrets(auth, "planner.integration.caldav.calendars[${idx}].auth")
            String authType = (auth.type ?: auth.scheme ?: 'none').toString().toLowerCase(Locale.ROOT)
            if (authType == 'basic' && (!auth.username?.toString()?.trim() || !auth.password_env?.toString()?.trim())) {
                throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}].auth basic requires username and password_env")
            }
            if (authType in ['bearer', 'oauth2'] && !auth.token_env?.toString()?.trim()) {
                throw new IllegalArgumentException("planner.integration.caldav.calendars[${idx}].auth bearer requires token_env")
            }
            parsedCalendars << copy
        }
        if (parsedCalendars*.name.collect { it.toString() }.toSet().size() != parsedCalendars.size()) {
            throw new IllegalArgumentException('planner.integration.caldav calendar names must be unique')
        }
        String outputCalendar = planner.output_calendar?.toString()
        if (!outputCalendar || !parsedCalendars*.name.collect { it.toString() }.contains(outputCalendar)) {
            throw new IllegalArgumentException('planner.output_calendar must name one configured integration CalDAV calendar')
        }
        this.calendars = Collections.unmodifiableList(parsedCalendars)
        this.caldav = Collections.unmodifiableMap([
            timeout         : parseDuration(caldav.timeout, Duration.ofSeconds(15), 'caldav.timeout'),
            maxResponseBytes: positiveLong(caldav.max_response_bytes, 2_097_152L,
                'planner.integration.caldav.max_response_bytes')
        ])

        Map weatherRaw = integration.weather instanceof Map ? integration.weather as Map : [:]
        this.weather = Collections.unmodifiableMap([
            baseUrl: (weatherRaw.base_url ?: 'https://api.open-meteo.com/v1/forecast').toString(),
            timeout: parseDuration(weatherRaw.timeout, Duration.ofSeconds(10), 'weather.timeout'),
            maxResponseBytes: positiveLong(weatherRaw.max_response_bytes, 1_048_576L,
                'planner.integration.weather.max_response_bytes')
        ])
        validateHttpsUri(this.weather.baseUrl.toString(), 'planner.integration.weather.base_url')
        Map feedbackRaw = integration.feedback instanceof Map ? new LinkedHashMap(integration.feedback as Map) : [:]
        def actors = feedbackRaw.allowed_actors
        if (actors != null) {
            if (!(actors instanceof Collection)) {
                throw new IllegalArgumentException('planner.integration.feedback.allowed_actors must be a list')
            }
            List<String> normalizedActors = (actors as Collection).collect { it?.toString()?.trim() }
            if (normalizedActors.any { !it } || normalizedActors.toSet().size() != normalizedActors.size()) {
                throw new IllegalArgumentException('planner.integration.feedback.allowed_actors must contain unique nonblank actor ids')
            }
            feedbackRaw.allowed_actors = normalizedActors
        }
        this.feedback = Collections.unmodifiableMap(feedbackRaw)

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
        new ProductionIntegrationConfig(root ?: [:], configDir)
    }

    Collection<String> feedbackActors() {
        def raw = feedback.allowed_actors
        raw instanceof Collection ? (raw as Collection).collect { it.toString() } : []
    }

    private static Path requiredPath(def value, Path configDir, String name) {
        if (value == null || !value.toString().trim()) throw new IllegalArgumentException("${name} is required")
        Path path = Path.of(value.toString())
        if (!path.isAbsolute()) path = configDir.resolve(path)
        path.toAbsolutePath().normalize()
    }

    private static Duration parseDuration(def raw, Duration fallback, String name) {
        if (raw == null) return fallback
        try {
            Duration value = Duration.parse(raw.toString())
            if (value.isZero() || value.isNegative()) throw new IllegalArgumentException()
            value
        } catch (Exception e) {
            throw new IllegalArgumentException("planner.integration.${name} must be a positive ISO-8601 duration")
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
            if (k in ['token', 'access_token', 'api_key', 'password', 'secret', 'password_override', 'token_override']) {
                throw new IllegalArgumentException("${path}.${key} must not contain an inline secret; use an *_env reference")
            }
        }
    }
}
