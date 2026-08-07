package todoistcaldavsync.planner.config

import todoistcaldavsync.planner.domain.EventRole
import todoistcaldavsync.planner.domain.Task

import groovy.yaml.YamlSlurper

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.Collections
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

        // package-private accessors for invariant validation
        String getMode() { mode }
        ZoneId getTimezone() { timezone }
        String getManualLabel() { manualLabel }
        int getDefaultDurationMinutes() { defaultDurationMinutes }
        Map<String, Integer> getDurationLabels() { durationLabels }
        List<WorkingWindow> getWorkingWindows() { workingWindows }
        EventRole getUnknownCalendarFallback() { unknownCalendarFallback }
        List<EventRule> getEventRules() { eventRules }

        PlannerConfig build() {
            def errors = collectInvariantErrors(this)
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Invalid planner configuration:\n - " + errors.join("\n - "))
            }
            new PlannerConfig(this)
        }
    }
}
