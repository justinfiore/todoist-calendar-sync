package todoistcaldavsync.planner.config

import todoistcaldavsync.planner.domain.EventRole
import todoistcaldavsync.planner.domain.Task

import groovy.yaml.YamlSlurper

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern

/**
 * Validated immutable planner configuration.
 */
final class PlannerConfig {
    final String mode
    final ZoneId timezone
    final String outputCalendar
    final List<WorkingWindow> workingWindows
    final List<CalendarDefault> calendarDefaults
    final List<EventRule> eventRules
    final EventRole unknownCalendarFallback
    final String manualLabel
    final List<String> schedulingEligibleLabels
    final int defaultDurationMinutes
    final Map<String, Integer> durationLabels
    final Task.DurationResolver durationResolver
    final StabilityConfig stability
    final BatchingConfig batching
    final List<TaskContext> taskContexts
    final WeatherConfig weather

    private static final Set<String> VALID_MODES = ['preview', 'approval_required', 'apply_safe_changes', 'fully_automated'] as Set

    private PlannerConfig(Builder b) {
        this.mode = b.mode
        this.timezone = b.timezone
        this.outputCalendar = b.outputCalendar
        this.workingWindows = Collections.unmodifiableList(new ArrayList<>(b.workingWindows))
        this.calendarDefaults = Collections.unmodifiableList(new ArrayList<>(b.calendarDefaults))
        this.eventRules = Collections.unmodifiableList(new ArrayList<>(b.eventRules))
        this.unknownCalendarFallback = b.unknownCalendarFallback
        this.manualLabel = b.manualLabel
        this.schedulingEligibleLabels = Collections.unmodifiableList(new ArrayList<>(b.schedulingEligibleLabels))
        this.defaultDurationMinutes = b.defaultDurationMinutes
        this.durationLabels = Collections.unmodifiableMap(new LinkedHashMap<>(b.durationLabels))
        this.durationResolver = new Task.DurationResolver(b.defaultDurationMinutes, b.durationLabels)
        this.stability = b.stability ?: StabilityConfig.defaults()
        this.batching = b.batching ?: BatchingConfig.defaults()
        this.taskContexts = Collections.unmodifiableList(new ArrayList<>(b.taskContexts ?: []))
        this.weather = b.weather ?: WeatherConfig.disabled()
    }

    static Builder builder() {
        new Builder()
    }

    static PlannerConfig load(File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Planner config file not found: ${file}")
        }
        def slurped = new YamlSlurper().parse(file)
        return fromMap(slurped instanceof Map ? slurped : [:])
    }

    static PlannerConfig fromMap(Map root) {
        if (root == null) {
            throw new IllegalArgumentException('Planner config root must not be null')
        }
        def planner = root.planner instanceof Map ? root.planner : root
        if (!(planner instanceof Map)) {
            throw new IllegalArgumentException('Planner config must contain a planner section or be the planner map')
        }
        return validateAndBuild(planner as Map)
    }

    private static PlannerConfig validateAndBuild(Map p) {
        def errors = []

        def mode = (p.mode ?: 'preview').toString()
        if (!(mode in VALID_MODES)) {
            errors << "planner.mode must be one of ${VALID_MODES}, got: ${mode}"
        }

        ZoneId timezone
        try {
            timezone = ZoneId.of((p.timezone ?: 'UTC').toString())
        } catch (Exception e) {
            errors << "planner.timezone is invalid: ${p.timezone}"
            timezone = ZoneId.of('UTC')
        }

        def outputCalendar = p.output_calendar?.toString() ?: p.outputCalendar?.toString()

        def availability = p.availability instanceof Map ? p.availability as Map : [:]
        def workingWindows = parseWorkingWindows(availability.working_windows ?: availability.workingWindows, errors)
        def calendarDefaults = parseCalendarDefaults(availability.calendars, errors)
        def eventRules = parseEventRules(availability.event_rules ?: availability.eventRules, errors)

        def fallbackRaw = availability.unknown_calendar_fallback ?: availability.unknownCalendarFallback ?: 'informational'
        EventRole unknownFallback = null
        try {
            unknownFallback = EventRole.fromConfig(fallbackRaw.toString())
        } catch (IllegalArgumentException e) {
            errors << e.message
            unknownFallback = EventRole.INFORMATIONAL
        }

        def tasks = p.tasks instanceof Map ? p.tasks as Map : [:]
        def manualLabel = (tasks.manual_label ?: tasks.manualLabel ?: 'manual').toString()
        if (!manualLabel) {
            errors << 'planner.tasks.manual_label must not be empty'
        }

        def eligible = tasks.scheduling_eligible_labels ?: tasks.schedulingEligibleLabels ?: []
        List<String> eligibleLabels = []
        if (eligible instanceof Collection) {
            eligibleLabels = eligible.collect { it.toString() }
        } else if (eligible != null) {
            errors << 'planner.tasks.scheduling_eligible_labels must be a list'
        }

        int defaultDurationMinutes = 30
        def ddm = tasks.default_duration_minutes ?: tasks.defaultDurationMinutes
        if (ddm != null) {
            try {
                defaultDurationMinutes = ddm as int
                if (defaultDurationMinutes <= 0) {
                    errors << 'planner.tasks.default_duration_minutes must be positive'
                }
            } catch (Exception e) {
                errors << "planner.tasks.default_duration_minutes is invalid: ${ddm}"
            }
        }

        Map<String, Integer> durationLabels = [:]
        def dl = tasks.duration_labels ?: tasks.durationLabels
        if (dl instanceof Map) {
            dl.each { k, v ->
                try {
                    def mins = v as int
                    if (mins <= 0) {
                        errors << "planner.tasks.duration_labels.${k} must be positive"
                    } else {
                        durationLabels[k.toString()] = mins
                    }
                } catch (Exception e) {
                    errors << "planner.tasks.duration_labels.${k} is invalid: ${v}"
                }
            }
        } else if (dl != null) {
            errors << 'planner.tasks.duration_labels must be a map'
        }

        if (workingWindows.isEmpty()) {
            errors << 'planner.availability.working_windows must define at least one window'
        }

        StabilityConfig stability = parseStability(p.stability instanceof Map ? p.stability as Map : [:], errors)
        BatchingConfig batching = parseBatching(p.batching instanceof Map ? p.batching as Map : [:], errors)
        List<TaskContext> taskContexts = parseTaskContexts(tasks.contexts, errors)
        WeatherConfig weather = parseWeather(p.weather instanceof Map ? p.weather as Map : null, timezone, errors)

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid planner configuration:\n - " + errors.join("\n - "))
        }

        return new Builder()
            .mode(mode)
            .timezone(timezone)
            .outputCalendar(outputCalendar)
            .workingWindows(workingWindows)
            .calendarDefaults(calendarDefaults)
            .eventRules(eventRules)
            .unknownCalendarFallback(unknownFallback)
            .manualLabel(manualLabel)
            .schedulingEligibleLabels(eligibleLabels)
            .defaultDurationMinutes(defaultDurationMinutes)
            .durationLabels(durationLabels)
            .stability(stability)
            .batching(batching)
            .taskContexts(taskContexts)
            .weather(weather)
            .build()
    }

    /**
     * Invariant checks shared by YAML load path and {@link Builder#build()}.
     */
    static List<String> collectInvariantErrors(Builder b) {
        List<String> errors = []
        if (!(b.mode in VALID_MODES)) {
            errors << "planner.mode must be one of ${VALID_MODES}, got: ${b.mode}"
        }
        if (b.timezone == null) {
            errors << 'planner.timezone is required'
        }
        if (!b.manualLabel) {
            errors << 'planner.tasks.manual_label must not be empty'
        }
        if (b.defaultDurationMinutes <= 0) {
            errors << 'planner.tasks.default_duration_minutes must be positive'
        }
        if (b.durationLabels) {
            b.durationLabels.each { k, v ->
                if (v == null || (v as int) <= 0) {
                    errors << "planner.tasks.duration_labels.${k} must be positive"
                }
            }
        }
        if (!b.workingWindows) {
            errors << 'planner.availability.working_windows must define at least one window'
        } else {
            b.workingWindows.eachWithIndex { WorkingWindow ww, int idx ->
                if (ww == null) {
                    errors << "workingWindows[${idx}] must not be null"
                } else if (ww.start == null || ww.end == null || !ww.end.isAfter(ww.start)) {
                    errors << "workingWindows[${idx}] end must be after start on the same local day (overnight unsupported)"
                }
            }
        }
        if (b.unknownCalendarFallback == null) {
            errors << 'planner.availability.unknown_calendar_fallback is required'
        }
        if (b.stability == null) {
            errors << 'planner.stability is required'
        } else {
            if (b.stability.freezeWithin == null || b.stability.freezeWithin.isNegative()) {
                errors << 'planner.stability.freeze_within must be a non-negative duration'
            }
            if (b.stability.minimumBufferBetweenBlocksMinutes < 0) {
                errors << 'planner.stability.minimum_buffer_between_blocks_minutes must be non-negative'
            }
            if (b.stability.churnPenalty < 0) {
                errors << 'planner.stability.churn_penalty must be non-negative'
            }
        }
        if (b.batching == null) {
            errors << 'planner.batching is required'
        } else {
            if (b.batching.projectBatchBonus < 0) {
                errors << 'planner.batching.project_batch_bonus must be non-negative'
            }
            if (b.batching.maxFocusBlockMinutes <= 0) {
                errors << 'planner.batching.max_focus_block_minutes must be positive'
            }
            if (b.batching.minimumFocusBlockMinutes <= 0) {
                errors << 'planner.batching.minimum_focus_block_minutes must be positive'
            }
            if (b.batching.minimumFocusBlockMinutes > b.batching.maxFocusBlockMinutes) {
                errors << 'planner.batching.minimum_focus_block_minutes must be <= max_focus_block_minutes'
            }
            if (b.batching.contextSwitchPenalty < 0) {
                errors << 'planner.batching.context_switch_penalty must be non-negative'
            }
        }
        if (b.eventRules) {
            b.eventRules.eachWithIndex { EventRule rule, int idx ->
                if (rule == null) {
                    errors << "eventRules[${idx}] must not be null"
                    return
                }
                if (!rule.name) {
                    errors << "eventRules[${idx}].name is required"
                }
                if (rule.role == null) {
                    errors << "eventRules[${idx}].role is required"
                }
                if (rule.calendarRegex == null && rule.titleRegex == null && rule.textRegex == null) {
                    errors << "eventRules[${idx}] ('${rule.name}') must specify at least one of calendar_regex, title_regex, or text_regex"
                }
                if (rule.bufferBeforeMinutes < 0 || rule.bufferAfterMinutes < 0) {
                    errors << "eventRules[${idx}] buffers must be non-negative"
                }
                if ((rule.bufferBeforeMinutes > 0 || rule.bufferAfterMinutes > 0) &&
                    rule.role != EventRole.HARD_BLOCKER && rule.role != EventRole.SOFT_BLOCKER) {
                    errors << "eventRules[${idx}] ('${rule.name}'): buffers are only allowed for hard_blocker and soft_blocker roles (got ${rule.role?.configValue})"
                }
            }
        }
        if (b.weather == null) {
            errors << 'planner.weather is required'
        } else {
            errors.addAll(collectWeatherErrors(b.weather))
        }
        return errors
    }

    static List<String> collectWeatherErrors(WeatherConfig w) {
        List<String> errors = []
        if (w == null) {
            errors << 'planner.weather is required'
            return errors
        }
        // Coordinates: when present (enabled or disabled), must be finite and in range.
        // When enabled, both are required.
        if (w.enabled && (w.latitude == null || w.longitude == null)) {
            errors << 'planner.weather.latitude and longitude are required when weather is enabled'
        }
        if (w.latitude != null) {
            if (!Double.isFinite(w.latitude)) {
                errors << "planner.weather.latitude must be finite, got: ${w.latitude}"
            } else if (w.latitude < -90d || w.latitude > 90d) {
                errors << "planner.weather.latitude out of range: ${w.latitude}"
            }
        }
        if (w.longitude != null) {
            if (!Double.isFinite(w.longitude)) {
                errors << "planner.weather.longitude must be finite, got: ${w.longitude}"
            } else if (w.longitude < -180d || w.longitude > 180d) {
                errors << "planner.weather.longitude out of range: ${w.longitude}"
            }
        }
        if (w.provider) {
            String p = w.provider.toLowerCase(Locale.ROOT)
            if (!(p in ['open_meteo', 'open-meteo', 'fixture', 'none', 'disabled'] as Set)) {
                errors << "planner.weather.provider unsupported: ${w.provider}"
            }
        }
        if (w.maxAge != null && w.maxAge.isNegative()) {
            errors << 'planner.weather.max_age must be non-negative'
        }
        if (w.forecastHorizonDays != null && (w.forecastHorizonDays < 1 || w.forecastHorizonDays > 16)) {
            errors << 'planner.weather.forecast_horizon_days must be 1..16'
        }
        if (w.suitabilityBonus < 0) {
            errors << 'planner.weather.suitability_bonus must be non-negative'
        }
        String fb = (w.fallback ?: 'fail_closed').toLowerCase(Locale.ROOT)
        if (!(fb in ['fail_closed', 'fail_open', 'closed', 'open', 'allow'] as Set)) {
            errors << "planner.weather.fallback must be fail_closed or fail_open, got: ${w.fallback}"
        }
        // task_rules validated even when disabled so misconfiguration fails loudly
        w.taskRules?.eachWithIndex { WeatherTaskRule rule, int idx ->
            if (rule == null) {
                errors << "planner.weather.task_rules[${idx}] must not be null"
                return
            }
            if (!rule.matchLabels) {
                errors << "planner.weather.task_rules[${idx}].match_labels must not be empty"
            }
            if (rule.precipitationProbabilityMax != null &&
                (rule.precipitationProbabilityMax < 0d || rule.precipitationProbabilityMax > 100d ||
                    !Double.isFinite(rule.precipitationProbabilityMax))) {
                errors << "planner.weather.task_rules[${idx}].require.precipitation_probability_max must be 0..100"
            }
            if (rule.precipitationMmMax != null &&
                (rule.precipitationMmMax < 0d || !Double.isFinite(rule.precipitationMmMax))) {
                errors << "planner.weather.task_rules[${idx}].require.precipitation_mm_max must be non-negative"
            }
            if (rule.windSpeedKphMax != null &&
                (rule.windSpeedKphMax < 0d || !Double.isFinite(rule.windSpeedKphMax))) {
                errors << "planner.weather.task_rules[${idx}].require.wind_speed_kph_max must be non-negative"
            }
            if (rule.temperatureMinC != null && !Double.isFinite(rule.temperatureMinC)) {
                errors << "planner.weather.task_rules[${idx}].require.temperature_min_c must be finite"
            }
            if (rule.temperatureMaxC != null && !Double.isFinite(rule.temperatureMaxC)) {
                errors << "planner.weather.task_rules[${idx}].require.temperature_max_c must be finite"
            }
            if (rule.temperatureMinC != null && rule.temperatureMaxC != null &&
                rule.temperatureMinC > rule.temperatureMaxC) {
                errors << "planner.weather.task_rules[${idx}].require temperature_min_c must be <= temperature_max_c"
            }
            if (rule.preferredForecastConfidenceMin != null &&
                (rule.preferredForecastConfidenceMin < 0d || rule.preferredForecastConfidenceMin > 1d ||
                    !Double.isFinite(rule.preferredForecastConfidenceMin))) {
                errors << "planner.weather.task_rules[${idx}].preferred.forecast_confidence_min must be 0..1"
            }
            boolean anyConstraint =
                rule.precipitationProbabilityMax != null ||
                    rule.precipitationMmMax != null ||
                    rule.windSpeedKphMax != null ||
                    rule.temperatureMinC != null ||
                    rule.temperatureMaxC != null ||
                    rule.requireDaylight != null ||
                    rule.preferredDaylight != null ||
                    rule.preferredForecastConfidenceMin != null
            if (!anyConstraint) {
                errors << "planner.weather.task_rules[${idx}] must define at least one require/preferred constraint"
            }
        }
        return errors
    }

    private static List<WorkingWindow> parseWorkingWindows(def raw, List errors) {
        List<WorkingWindow> result = []
        if (raw == null) {
            return result
        }
        if (!(raw instanceof Map)) {
            errors << 'planner.availability.working_windows must be a map of day-group to time ranges'
            return result
        }
        raw.each { dayGroup, ranges ->
            def days = expandDayGroup(dayGroup.toString(), errors)
            if (!(ranges instanceof Collection)) {
                errors << "planner.availability.working_windows.${dayGroup} must be a list of HH:mm-HH:mm ranges"
                return
            }
            ranges.each { range ->
                def rangeStr = range?.toString()
                def parsed = parseTimeRange(rangeStr)
                if (!parsed) {
                    if (isOvernightRange(rangeStr)) {
                        errors << "Invalid working window range '${range}' under ${dayGroup}: overnight working windows are intentionally unsupported in Phase 1 (end must be after start on the same local day)"
                    } else {
                        errors << "Invalid working window range '${range}' under ${dayGroup}"
                    }
                } else {
                    days.each { DayOfWeek dow ->
                        result << new WorkingWindow(dow, parsed[0], parsed[1], dayGroup.toString())
                    }
                }
            }
        }
        return result
    }

    private static List<DayOfWeek> expandDayGroup(String group, List errors) {
        def g = group.toLowerCase()
        if (g == 'weekday' || g == 'weekdays') {
            return [DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY]
        }
        if (g == 'weekend' || g == 'weekends') {
            return [DayOfWeek.SATURDAY, DayOfWeek.SUNDAY]
        }
        if (g == 'everyday' || g == 'daily' || g == 'all') {
            return DayOfWeek.values() as List
        }
        try {
            return [DayOfWeek.valueOf(g.toUpperCase())]
        } catch (Exception e) {
            errors << "Unknown working window day group: ${group}"
            return []
        }
    }

    private static boolean isOvernightRange(String range) {
        if (!range) {
            return false
        }
        def m = range.trim() =~ /^(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})$/
        if (!m.matches()) {
            return false
        }
        try {
            def start = LocalTime.parse(m[0][1].length() == 4 ? '0' + m[0][1] : m[0][1])
            def end = LocalTime.parse(m[0][2].length() == 4 ? '0' + m[0][2] : m[0][2])
            return !end.isAfter(start)
        } catch (Exception e) {
            return false
        }
    }

    private static List<LocalTime> parseTimeRange(String range) {
        if (!range) {
            return null
        }
        def m = range.trim() =~ /^(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})$/
        if (!m.matches()) {
            return null
        }
        try {
            def start = LocalTime.parse(m[0][1].length() == 4 ? '0' + m[0][1] : m[0][1])
            def end = LocalTime.parse(m[0][2].length() == 4 ? '0' + m[0][2] : m[0][2])
            if (!end.isAfter(start)) {
                // Overnight windows (e.g. 22:00-06:00) are intentionally unsupported in Phase 1
                return null
            }
            return [start, end]
        } catch (Exception e) {
            return null
        }
    }

    private static List<CalendarDefault> parseCalendarDefaults(def raw, List errors) {
        List<CalendarDefault> result = []
        if (raw == null) {
            return result
        }
        if (!(raw instanceof Collection)) {
            errors << 'planner.availability.calendars must be a list'
            return result
        }
        raw.eachWithIndex { entry, idx ->
            if (!(entry instanceof Map)) {
                errors << "planner.availability.calendars[${idx}] must be a map"
                return
            }
            def name = entry.calendar?.toString() ?: entry.name?.toString()
            if (!name) {
                errors << "planner.availability.calendars[${idx}].calendar is required"
                return
            }
            def roleRaw = entry.default_role ?: entry.defaultRole
            if (!roleRaw) {
                errors << "planner.availability.calendars[${idx}].default_role is required"
                return
            }
            try {
                result << new CalendarDefault(name, EventRole.fromConfig(roleRaw.toString()))
            } catch (IllegalArgumentException e) {
                errors << "planner.availability.calendars[${idx}]: ${e.message}"
            }
        }
        return result
    }

    private static List<EventRule> parseEventRules(def raw, List errors) {
        List<EventRule> result = []
        if (raw == null) {
            return result
        }
        if (!(raw instanceof Collection)) {
            errors << 'planner.availability.event_rules must be a list'
            return result
        }
        raw.eachWithIndex { entry, idx ->
            if (!(entry instanceof Map)) {
                errors << "planner.availability.event_rules[${idx}] must be a map"
                return
            }
            def name = entry.name?.toString()
            if (!name) {
                errors << "planner.availability.event_rules[${idx}].name is required"
                return
            }
            def roleRaw = entry.role?.toString()
            EventRole role = null
            try {
                role = EventRole.fromConfig(roleRaw)
            } catch (Exception e) {
                errors << "planner.availability.event_rules[${idx}].role is invalid: ${roleRaw}"
                return
            }

            Pattern calendarRegex = compileOptional(entry.calendar_regex ?: entry.calendarRegex, "event_rules[${idx}].calendar_regex", errors)
            Pattern titleRegex = compileOptional(entry.title_regex ?: entry.titleRegex, "event_rules[${idx}].title_regex", errors)
            Pattern textRegex = compileOptional(entry.text_regex ?: entry.textRegex ?: entry.description_regex ?: entry.descriptionRegex,
                "event_rules[${idx}].text_regex", errors)

            if (calendarRegex == null && titleRegex == null && textRegex == null) {
                errors << "planner.availability.event_rules[${idx}] ('${name}') must specify at least one of calendar_regex, title_regex, or text_regex; catch-all behavior belongs on calendar defaults/global fallback"
            }

            int bufferBefore = parseNonNegInt(entry.buffer_before_minutes ?: entry.bufferBeforeMinutes, 0, "event_rules[${idx}].buffer_before_minutes", errors)
            int bufferAfter = parseNonNegInt(entry.buffer_after_minutes ?: entry.bufferAfterMinutes, 0, "event_rules[${idx}].buffer_after_minutes", errors)

            // Buffer semantics: only hard_blocker and soft_blocker may define buffers.
            // Informational never consumes capacity — buffers are rejected as ambiguous.
            // Managed output occupies its actual block only — buffers are rejected.
            if ((bufferBefore > 0 || bufferAfter > 0) &&
                role != EventRole.HARD_BLOCKER && role != EventRole.SOFT_BLOCKER) {
                errors << "planner.availability.event_rules[${idx}] ('${name}'): buffers are only allowed for hard_blocker and soft_blocker roles (got ${role.configValue}); informational ignores capacity and managed_output occupies its actual block without buffer expansion"
                bufferBefore = 0
                bufferAfter = 0
            }

            result << new EventRule(name, role, calendarRegex, titleRegex, textRegex, bufferBefore, bufferAfter)
        }
        return result
    }

    private static Pattern compileOptional(def value, String path, List errors) {
        if (value == null || value.toString().trim().isEmpty()) {
            return null
        }
        try {
            return Pattern.compile(value.toString())
        } catch (Exception e) {
            errors << "${path} is not a valid regex: ${value}"
            return null
        }
    }

    private static int parseNonNegInt(def value, int defaultValue, String path, List errors) {
        if (value == null) {
            return defaultValue
        }
        try {
            int v = value as int
            if (v < 0) {
                errors << "${path} must be non-negative"
                return defaultValue
            }
            return v
        } catch (Exception e) {
            errors << "${path} is invalid: ${value}"
            return defaultValue
        }
    }

    CalendarDefault findCalendarDefault(String calendarName) {
        calendarDefaults.find { it.calendarName.equalsIgnoreCase(calendarName) }
    }

    /**
     * Contexts whose match_labels intersect the task labels (case-insensitive).
     */
    List<TaskContext> contextsFor(Task task) {
        if (task == null || !task.labels || !taskContexts) {
            return []
        }
        Set<String> labels = task.labels.collect { it.toLowerCase(Locale.ROOT) } as Set
        return taskContexts.findAll { ctx ->
            ctx.matchLabels.any { labels.contains(it.toLowerCase(Locale.ROOT)) }
        }
    }

    private static StabilityConfig parseStability(Map raw, List errors) {
        Duration freezeWithin = parseDurationValue(
            raw.freeze_within ?: raw.freezeWithin, Duration.ofHours(48), 'planner.stability.freeze_within', errors)
        boolean keepManualMoves = raw.keep_manual_moves != null
            ? Boolean.valueOf(raw.keep_manual_moves.toString())
            : (raw.keepManualMoves != null ? Boolean.valueOf(raw.keepManualMoves.toString()) : true)
        Duration requireApprovalForMoveWithin = parseDurationValue(
            raw.require_approval_for_move_within ?: raw.requireApprovalForMoveWithin,
            Duration.ofDays(7), 'planner.stability.require_approval_for_move_within', errors)
        int minBuffer = parseNonNegInt(
            raw.minimum_buffer_between_blocks_minutes ?: raw.minimumBufferBetweenBlocksMinutes,
            10, 'planner.stability.minimum_buffer_between_blocks_minutes', errors)
        int churnPenalty = parseNonNegInt(
            raw.churn_penalty ?: raw.churnPenalty, 40, 'planner.stability.churn_penalty', errors)
        return new StabilityConfig(freezeWithin, keepManualMoves, requireApprovalForMoveWithin, minBuffer, churnPenalty)
    }

    private static BatchingConfig parseBatching(Map raw, List errors) {
        boolean enabled = raw.enabled != null ? Boolean.valueOf(raw.enabled.toString()) : true
        int bonus = parseNonNegInt(raw.project_batch_bonus ?: raw.projectBatchBonus, 25,
            'planner.batching.project_batch_bonus', errors)
        int maxFocus = parsePositiveInt(raw.max_focus_block_minutes ?: raw.maxFocusBlockMinutes, 90,
            'planner.batching.max_focus_block_minutes', errors)
        int minFocus = parsePositiveInt(raw.minimum_focus_block_minutes ?: raw.minimumFocusBlockMinutes, 30,
            'planner.batching.minimum_focus_block_minutes', errors)
        int switchPenalty = parseNonNegInt(raw.context_switch_penalty ?: raw.contextSwitchPenalty, 15,
            'planner.batching.context_switch_penalty', errors)
        if (minFocus > maxFocus) {
            errors << 'planner.batching.minimum_focus_block_minutes must be <= max_focus_block_minutes'
        }
        return new BatchingConfig(enabled, bonus, maxFocus, minFocus, switchPenalty)
    }

    private static List<TaskContext> parseTaskContexts(def raw, List errors) {
        List<TaskContext> result = []
        if (raw == null) {
            return result
        }
        if (!(raw instanceof Map)) {
            errors << 'planner.tasks.contexts must be a map of context name to settings'
            return result
        }
        raw.each { name, entry ->
            if (!(entry instanceof Map)) {
                errors << "planner.tasks.contexts.${name} must be a map"
                return
            }
            def matchRaw = entry.match_labels ?: entry.matchLabels ?: []
            List<String> matchLabels = []
            if (matchRaw instanceof Collection) {
                matchLabels = matchRaw.collect { it.toString() }.findAll { it }
            } else if (matchRaw != null) {
                errors << "planner.tasks.contexts.${name}.match_labels must be a list"
            }
            def windowsRaw = entry.preferred_windows ?: entry.preferredWindows ?: []
            List<PreferredWindow> windows = []
            if (windowsRaw instanceof Collection) {
                windowsRaw.each { w ->
                    def parsed = PreferredWindow.parse(w?.toString(), errors, "planner.tasks.contexts.${name}.preferred_windows")
                    if (parsed) {
                        windows << parsed
                    }
                }
            } else if (windowsRaw != null) {
                errors << "planner.tasks.contexts.${name}.preferred_windows must be a list"
            }
            int preferredBonus = parseNonNegInt(entry.preferred_bonus ?: entry.preferredBonus, 20,
                "planner.tasks.contexts.${name}.preferred_bonus", errors)
            int avoidPenalty = parseNonNegInt(entry.avoid_penalty ?: entry.avoidPenalty, 25,
                "planner.tasks.contexts.${name}.avoid_penalty", errors)
            result << new TaskContext(name.toString(), matchLabels, windows, preferredBonus, avoidPenalty)
        }
        return result
    }

    private static Duration parseDurationValue(def value, Duration defaultValue, String path, List errors) {
        if (value == null) {
            return defaultValue
        }
        try {
            Duration d = Duration.parse(value.toString().trim())
            if (d.isNegative()) {
                errors << "${path} must be non-negative"
                return defaultValue
            }
            return d
        } catch (Exception e) {
            errors << "${path} is invalid ISO-8601 duration: ${value}"
            return defaultValue
        }
    }

    /**
     * Optional weather section. Absent or {@code enabled: false} preserves Phase 2 schedules
     * for non-weather tasks and does not require a provider at startup.
     *
     * Per-task policy is deterministic: first {@code task_rules} entry whose
     * {@code match_labels} intersects the task labels (case-insensitive) wins.
     */
    private static WeatherConfig parseWeather(Map raw, ZoneId plannerTimezone, List errors) {
        if (raw == null || raw.isEmpty()) {
            return WeatherConfig.disabled()
        }
        boolean enabled = raw.enabled != null ? Boolean.valueOf(raw.enabled.toString()) : false
        // Disabled without any weather keys beyond enabled remains backward-compatible.
        // When task_rules / thresholds / coordinates are provided while disabled, still
        // parse and validate them so misconfiguration fails loudly; scheduler ignores
        // because enabled=false.
        boolean hasExtraKeys = raw.keySet().any { k ->
            String key = k?.toString()?.toLowerCase(Locale.ROOT)
            key && key != 'enabled'
        }
        if (!enabled && !hasExtraKeys) {
            return WeatherConfig.disabled()
        }
        String provider = (raw.provider ?: (enabled ? 'open_meteo' : 'none')).toString()
        Double lat = parseDoubleOpt(raw.latitude ?: raw.lat, 'planner.weather.latitude', errors)
        Double lon = parseDoubleOpt(raw.longitude ?: raw.lon ?: raw.lng, 'planner.weather.longitude', errors)
        ZoneId weatherTz = plannerTimezone
        def tzRaw = raw.timezone ?: raw.time_zone ?: raw.timeZone
        if (tzRaw) {
            try {
                weatherTz = ZoneId.of(tzRaw.toString())
            } catch (Exception e) {
                errors << "planner.weather.timezone is invalid: ${tzRaw}"
            }
        }
        Duration maxAge = parseDurationValue(
            raw.max_age ?: raw.maxAge ?: raw.freshness, Duration.ofHours(6),
            'planner.weather.max_age', errors)
        Integer horizon = null
        def hz = raw.forecast_horizon_days ?: raw.forecastHorizonDays ?: raw.forecast_days
        if (hz != null) {
            try {
                horizon = hz as int
                if (horizon < 1 || horizon > 16) {
                    errors << 'planner.weather.forecast_horizon_days must be 1..16'
                    horizon = 7
                }
            } catch (Exception e) {
                errors << "planner.weather.forecast_horizon_days is invalid: ${hz}"
                horizon = 7
            }
        } else {
            horizon = 7
        }
        String fallback = (raw.fallback ?: raw.missing_data_policy ?: raw.missingDataPolicy ?: 'fail_closed').toString()
        long suitabilityBonus = 35L
        def bonusRaw = raw.suitability_bonus ?: raw.suitabilityBonus ?: raw.weather_suitability_bonus
        if (bonusRaw != null) {
            try {
                suitabilityBonus = bonusRaw as long
                if (suitabilityBonus < 0L) {
                    errors << 'planner.weather.suitability_bonus must be non-negative'
                    suitabilityBonus = 35L
                }
            } catch (Exception e) {
                errors << "planner.weather.suitability_bonus is invalid: ${bonusRaw}"
            }
        }
        List<WeatherTaskRule> rules = parseWeatherTaskRules(
            raw.task_rules ?: raw.taskRules, errors)
        WeatherConfig cfg = new WeatherConfig(enabled, provider, lat, lon, weatherTz,
            maxAge, horizon, fallback, suitabilityBonus, rules)
        errors.addAll(collectWeatherErrors(cfg))
        return cfg
    }

    private static List<WeatherTaskRule> parseWeatherTaskRules(def raw, List errors) {
        List<WeatherTaskRule> result = []
        if (raw == null) {
            return result
        }
        if (!(raw instanceof Collection)) {
            errors << 'planner.weather.task_rules must be a list'
            return result
        }
        raw.eachWithIndex { entry, idx ->
            if (!(entry instanceof Map)) {
                errors << "planner.weather.task_rules[${idx}] must be a map"
                return
            }
            Map m = entry as Map
            String name = (m.name ?: m.id ?: "rule-${idx}").toString()
            String id = (m.id ?: name).toString()
            def matchRaw = m.match_labels ?: m.matchLabels ?: []
            List<String> matchLabels = []
            if (matchRaw instanceof Collection) {
                matchLabels = matchRaw.collect { it.toString() }.findAll { it }
            } else if (matchRaw != null) {
                errors << "planner.weather.task_rules[${idx}].match_labels must be a list"
            }
            Map require = m.require instanceof Map ? m.require as Map : [:]
            Map preferred = m.preferred instanceof Map ? m.preferred as Map : [:]
            Double precipProbMax = parseDoubleOpt(
                require.precipitation_probability_max ?: require.precipitationProbabilityMax,
                "planner.weather.task_rules[${idx}].require.precipitation_probability_max", errors)
            Double precipMmMax = parseDoubleOpt(
                require.precipitation_mm_max ?: require.precipitationMmMax,
                "planner.weather.task_rules[${idx}].require.precipitation_mm_max", errors)
            Double windMax = parseDoubleOpt(
                require.wind_speed_kph_max ?: require.windSpeedKphMax,
                "planner.weather.task_rules[${idx}].require.wind_speed_kph_max", errors)
            Double tempMin = parseDoubleOpt(
                require.temperature_min_c ?: require.temperatureMinC,
                "planner.weather.task_rules[${idx}].require.temperature_min_c", errors)
            Double tempMax = parseDoubleOpt(
                require.temperature_max_c ?: require.temperatureMaxC,
                "planner.weather.task_rules[${idx}].require.temperature_max_c", errors)
            Boolean requireDaylight = parseBooleanOpt(require.daylight)
            Boolean preferredDaylight = parseBooleanOpt(preferred.daylight)
            Double confMin = parseDoubleOpt(
                preferred.forecast_confidence_min ?: preferred.forecastConfidenceMin,
                "planner.weather.task_rules[${idx}].preferred.forecast_confidence_min", errors)
            result << new WeatherTaskRule(id, name, matchLabels, precipProbMax, precipMmMax,
                windMax, tempMin, tempMax, requireDaylight, preferredDaylight, confMin)
        }
        return result
    }

    private static Double parseDoubleOpt(def value, String path, List errors) {
        if (value == null) {
            return null
        }
        try {
            double d = value as double
            if (!Double.isFinite(d)) {
                errors << "${path} must be finite, got: ${value}"
                return null
            }
            return d
        } catch (Exception e) {
            errors << "${path} is invalid: ${value}"
            return null
        }
    }

    private static Boolean parseBooleanOpt(def value) {
        if (value == null) {
            return null
        }
        return Boolean.valueOf(value.toString())
    }

    private static int parsePositiveInt(def value, int defaultValue, String path, List errors) {
        if (value == null) {
            return defaultValue
        }
        try {
            int v = value as int
            if (v <= 0) {
                errors << "${path} must be positive"
                return defaultValue
            }
            return v
        } catch (Exception e) {
            errors << "${path} is invalid: ${value}"
            return defaultValue
        }
    }

    /**
     * Stability / churn controls for the preview scheduler.
     * {@link #requireApprovalForMoveWithin} is preview-only metadata: when a proposed
     * move's previous start falls inside this horizon from planning {@code now}, the
     * corresponding {@code PlanChange} is tagged {@code approvalRequired=true} for a
     * later apply step. The Phase 2 scheduler never performs remote writes.
     */
    static final class StabilityConfig {
        final Duration freezeWithin
        final boolean keepManualMoves
        /** Preview-only: tag moves of prior placements within this horizon as approval-required. */
        final Duration requireApprovalForMoveWithin
        final int minimumBufferBetweenBlocksMinutes
        final int churnPenalty

        StabilityConfig(Duration freezeWithin, boolean keepManualMoves, Duration requireApprovalForMoveWithin,
                        int minimumBufferBetweenBlocksMinutes, int churnPenalty) {
            this.freezeWithin = freezeWithin
            this.keepManualMoves = keepManualMoves
            this.requireApprovalForMoveWithin = requireApprovalForMoveWithin
            this.minimumBufferBetweenBlocksMinutes = minimumBufferBetweenBlocksMinutes
            this.churnPenalty = churnPenalty
        }

        static StabilityConfig defaults() {
            new StabilityConfig(Duration.ofHours(48), true, Duration.ofDays(7), 10, 40)
        }
    }

    static final class BatchingConfig {
        final boolean enabled
        final int projectBatchBonus
        final int maxFocusBlockMinutes
        final int minimumFocusBlockMinutes
        final int contextSwitchPenalty

        BatchingConfig(boolean enabled, int projectBatchBonus, int maxFocusBlockMinutes,
                       int minimumFocusBlockMinutes, int contextSwitchPenalty) {
            this.enabled = enabled
            this.projectBatchBonus = projectBatchBonus
            this.maxFocusBlockMinutes = maxFocusBlockMinutes
            this.minimumFocusBlockMinutes = minimumFocusBlockMinutes
            this.contextSwitchPenalty = contextSwitchPenalty
        }

        static BatchingConfig defaults() {
            new BatchingConfig(true, 25, 90, 30, 15)
        }
    }

    /**
     * Weather-aware planning controls. Disabled by default; when disabled the
     * scheduler behavior matches Phase 2/3 exactly for all tasks.
     */
    static final class WeatherConfig {
        final boolean enabled
        final String provider
        final Double latitude
        final Double longitude
        final ZoneId timezone
        final Duration maxAge
        final Integer forecastHorizonDays
        /** fail_closed | fail_open for stale/missing forecast data. */
        final String fallback
        final long suitabilityBonus
        final List<WeatherTaskRule> taskRules

        WeatherConfig(boolean enabled, String provider, Double latitude, Double longitude,
                      ZoneId timezone, Duration maxAge, Integer forecastHorizonDays,
                      String fallback, long suitabilityBonus, List<WeatherTaskRule> taskRules) {
            this.enabled = enabled
            this.provider = provider
            this.latitude = latitude
            this.longitude = longitude
            this.timezone = timezone
            this.maxAge = maxAge
            this.forecastHorizonDays = forecastHorizonDays
            this.fallback = fallback ?: 'fail_closed'
            this.suitabilityBonus = suitabilityBonus
            this.taskRules = Collections.unmodifiableList(new ArrayList<>(taskRules ?: []))
        }

        static WeatherConfig disabled() {
            new WeatherConfig(false, 'none', null, null, null, Duration.ofHours(6), 7,
                'fail_closed', 35L, [])
        }
    }

    /**
     * First-match weather policy for tasks whose labels intersect {@link #matchLabels}.
     */
    static final class WeatherTaskRule {
        final String id
        final String name
        final List<String> matchLabels
        final Double precipitationProbabilityMax
        final Double precipitationMmMax
        final Double windSpeedKphMax
        final Double temperatureMinC
        final Double temperatureMaxC
        final Boolean requireDaylight
        final Boolean preferredDaylight
        final Double preferredForecastConfidenceMin

        WeatherTaskRule(String id, String name, List<String> matchLabels,
                        Double precipitationProbabilityMax, Double precipitationMmMax,
                        Double windSpeedKphMax, Double temperatureMinC, Double temperatureMaxC,
                        Boolean requireDaylight, Boolean preferredDaylight,
                        Double preferredForecastConfidenceMin) {
            this.id = id
            this.name = name
            this.matchLabels = Collections.unmodifiableList(new ArrayList<>(matchLabels ?: []))
            this.precipitationProbabilityMax = precipitationProbabilityMax
            this.precipitationMmMax = precipitationMmMax
            this.windSpeedKphMax = windSpeedKphMax
            this.temperatureMinC = temperatureMinC
            this.temperatureMaxC = temperatureMaxC
            this.requireDaylight = requireDaylight
            this.preferredDaylight = preferredDaylight
            this.preferredForecastConfidenceMin = preferredForecastConfidenceMin
        }
    }

    static final class TaskContext {
        final String name
        final List<String> matchLabels
        final List<PreferredWindow> preferredWindows
        final int preferredBonus
        final int avoidPenalty

        TaskContext(String name, List<String> matchLabels, List<PreferredWindow> preferredWindows,
                    int preferredBonus, int avoidPenalty) {
            this.name = name
            this.matchLabels = Collections.unmodifiableList(new ArrayList<>(matchLabels ?: []))
            this.preferredWindows = Collections.unmodifiableList(new ArrayList<>(preferredWindows ?: []))
            this.preferredBonus = preferredBonus
            this.avoidPenalty = avoidPenalty
        }
    }

    /**
     * Preferred local window: optional day group + HH:mm-HH:mm.
     * Examples: "weekday 12:00-13:00", "09:00-12:00", "saturday 10:00-14:00"
     */
    static final class PreferredWindow {
        final Set<DayOfWeek> days
        final LocalTime start
        final LocalTime end
        final String raw

        PreferredWindow(Set<DayOfWeek> days, LocalTime start, LocalTime end, String raw) {
            this.days = Collections.unmodifiableSet(new LinkedHashSet<>(days ?: []))
            this.start = start
            this.end = end
            this.raw = raw
        }

        boolean matches(DayOfWeek day, LocalTime time) {
            if (days && !days.contains(day)) {
                return false
            }
            return !time.isBefore(start) && time.isBefore(end)
        }

        /**
         * Whether [startZ, endZ) overlaps this preferred window on the same local calendar day.
         * Scheduling candidates are same-day intervals; multi-day or cross-midnight ranges
         * return false (overnight windows are unsupported). Uses the zone of {@code startZ}
         * so DST transitions on that local date are handled via ZonedDateTime conversion.
         */
        boolean overlapsInstantRange(java.time.ZonedDateTime startZ, java.time.ZonedDateTime endZ) {
            if (startZ == null || endZ == null || !endZ.isAfter(startZ)) {
                return false
            }
            // Same local calendar day only — candidates are same-day placeable fragments.
            if (startZ.toLocalDate() != endZ.toLocalDate()) {
                return false
            }
            def day = startZ.dayOfWeek
            if (days && !days.contains(day)) {
                return false
            }
            LocalTime t0 = startZ.toLocalTime()
            LocalTime t1 = endZ.toLocalTime()
            // Same local date already enforced; zero-length local times cannot overlap a window
            if (!t1.isAfter(t0)) {
                return false
            }
            // overlap of [t0,t1) with [start,end) on same local day
            return t0.isBefore(end) && start.isBefore(t1)
        }

        static PreferredWindow parse(String raw, List errors, String path) {
            if (!raw || raw.trim().isEmpty()) {
                errors << "${path} entry must not be empty"
                return null
            }
            def s = raw.trim()
            String dayPart = null
            String rangePart = s
            def m = s =~ /^(?i)(weekday|weekdays|weekend|weekends|everyday|daily|all|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+(.+)$/
            if (m.matches()) {
                dayPart = m[0][1]
                rangePart = m[0][2].trim()
            }
            def range = parseTimeRange(rangePart)
            if (!range) {
                errors << "${path} invalid window '${raw}'"
                return null
            }
            Set<DayOfWeek> days = new LinkedHashSet<>()
            if (dayPart) {
                days.addAll(expandDayGroup(dayPart, errors))
            }
            return new PreferredWindow(days, range[0], range[1], s)
        }
    }

    static final class WorkingWindow {
        final DayOfWeek dayOfWeek
        final LocalTime start
        final LocalTime end
        final String groupName

        WorkingWindow(DayOfWeek dayOfWeek, LocalTime start, LocalTime end, String groupName) {
            this.dayOfWeek = dayOfWeek
            this.start = start
            this.end = end
            this.groupName = groupName
        }
    }

    static final class CalendarDefault {
        final String calendarName
        final EventRole defaultRole

        CalendarDefault(String calendarName, EventRole defaultRole) {
            this.calendarName = calendarName
            this.defaultRole = defaultRole
        }
    }

    static final class EventRule {
        final String name
        final EventRole role
        final Pattern calendarRegex
        final Pattern titleRegex
        final Pattern textRegex
        final int bufferBeforeMinutes
        final int bufferAfterMinutes

        EventRule(String name, EventRole role, Pattern calendarRegex, Pattern titleRegex, Pattern textRegex,
                  int bufferBeforeMinutes, int bufferAfterMinutes) {
            this.name = name
            this.role = role
            this.calendarRegex = calendarRegex
            this.titleRegex = titleRegex
            this.textRegex = textRegex
            this.bufferBeforeMinutes = bufferBeforeMinutes
            this.bufferAfterMinutes = bufferAfterMinutes
        }

        boolean matches(String calendarName, String title, String description) {
            if (calendarRegex && !calendarRegex.matcher(calendarName ?: '').find()) {
                return false
            }
            if (titleRegex && !titleRegex.matcher(title ?: '').find()) {
                return false
            }
            if (textRegex) {
                def haystack = ((title ?: '') + '\n' + (description ?: ''))
                if (!textRegex.matcher(haystack).find()) {
                    return false
                }
            }
            // At least one criterion should meaningfully constrain; pure role-only rules with only calendar regex are OK
            return true
        }
    }

    static final class Builder {
        private String mode = 'preview'
        private ZoneId timezone = ZoneId.of('UTC')
        private String outputCalendar
        private List<WorkingWindow> workingWindows = []
        private List<CalendarDefault> calendarDefaults = []
        private List<EventRule> eventRules = []
        private EventRole unknownCalendarFallback = EventRole.INFORMATIONAL
        private String manualLabel = 'manual'
        private List<String> schedulingEligibleLabels = []
        private int defaultDurationMinutes = 30
        private Map<String, Integer> durationLabels = [:]
        private StabilityConfig stability = StabilityConfig.defaults()
        private BatchingConfig batching = BatchingConfig.defaults()
        private List<TaskContext> taskContexts = []
        private WeatherConfig weather = WeatherConfig.disabled()

        Builder mode(String v) { this.mode = v; this }
        Builder timezone(ZoneId v) { this.timezone = v; this }
        Builder outputCalendar(String v) { this.outputCalendar = v; this }
        Builder workingWindows(List<WorkingWindow> v) { this.workingWindows = v ?: []; this }
        Builder calendarDefaults(List<CalendarDefault> v) { this.calendarDefaults = v ?: []; this }
        Builder eventRules(List<EventRule> v) { this.eventRules = v ?: []; this }
        Builder unknownCalendarFallback(EventRole v) { this.unknownCalendarFallback = v; this }
        Builder manualLabel(String v) { this.manualLabel = v; this }
        Builder schedulingEligibleLabels(List<String> v) { this.schedulingEligibleLabels = v ?: []; this }
        Builder defaultDurationMinutes(int v) { this.defaultDurationMinutes = v; this }
        Builder durationLabels(Map<String, Integer> v) { this.durationLabels = v ?: [:]; this }
        Builder stability(StabilityConfig v) { this.stability = v; this }
        Builder batching(BatchingConfig v) { this.batching = v; this }
        Builder taskContexts(List<TaskContext> v) { this.taskContexts = v ?: []; this }
        Builder weather(WeatherConfig v) { this.weather = v ?: WeatherConfig.disabled(); this }

        // package-private accessors for invariant validation
        String getMode() { mode }
        ZoneId getTimezone() { timezone }
        String getManualLabel() { manualLabel }
        int getDefaultDurationMinutes() { defaultDurationMinutes }
        Map<String, Integer> getDurationLabels() { durationLabels }
        List<WorkingWindow> getWorkingWindows() { workingWindows }
        EventRole getUnknownCalendarFallback() { unknownCalendarFallback }
        List<EventRule> getEventRules() { eventRules }
        StabilityConfig getStability() { stability }
        BatchingConfig getBatching() { batching }
        List<TaskContext> getTaskContexts() { taskContexts }
        WeatherConfig getWeather() { weather }

        PlannerConfig build() {
            def errors = collectInvariantErrors(this)
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Invalid planner configuration:\n - " + errors.join("\n - "))
            }
            new PlannerConfig(this)
        }
    }
}
