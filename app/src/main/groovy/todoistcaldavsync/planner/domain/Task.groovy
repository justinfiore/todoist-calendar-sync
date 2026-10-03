package todoistcaldavsync.planner.domain

import todoistcaldavsync.planner.recurrence.LifecycleMarker
import todoistcaldavsync.planner.recurrence.LifecycleMarkerCodec
import todoistcaldavsync.planner.recurrence.MarkerStatus
import todoistcaldavsync.planner.recurrence.OccurrenceIdentity
import todoistcaldavsync.planner.recurrence.TodoistDue

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.Locale

/**
 * Immutable planner task. Todoist deadline is the completion constraint;
 * dueTime is the planner-managed scheduled start (may be null when unscheduled).
 */
final class Task {
    final String id
    final String content
    final String projectId
    final String projectName
    final List<String> labels
    final int priority
    final Instant deadline
    final Instant dueTime
    final Duration nativeDuration
    final Duration effectiveDuration
    final String durationSource
    final boolean manual
    final boolean allDayDue
    final TodoistDue todoistDue
    final LocalDate deadlineDate
    final String description
    final Instant createdAt
    final Instant updatedAt
    final long completedCount
    final LifecycleMarker lifecycleMarker

    private Task(Builder b) {
        this.id = b.id
        this.content = b.content
        this.projectId = b.projectId
        this.projectName = b.projectName
        this.labels = Collections.unmodifiableList(new ArrayList<>(b.labels ?: []))
        this.priority = b.priority
        this.deadline = b.deadline
        this.dueTime = b.dueTime
        this.nativeDuration = b.nativeDuration
        this.effectiveDuration = b.effectiveDuration
        this.durationSource = b.durationSource
        this.manual = b.manual
        this.allDayDue = b.allDayDue
        this.todoistDue = b.todoistDue
        this.deadlineDate = b.deadlineDate
        this.description = b.description
        this.createdAt = b.createdAt
        this.updatedAt = b.updatedAt
        this.completedCount = b.completedCount
        this.lifecycleMarker = b.lifecycleMarker
    }

    static Builder builder() {
        new Builder()
    }

    static Task fromTodoistMap(Map raw, DurationResolver durationResolver, String manualLabel = 'manual',
                               ZoneId timezone = ZoneId.of('UTC')) {
        if (raw == null) {
            throw new IllegalArgumentException('Task raw map must not be null')
        }
        def id = raw.id?.toString()
        if (!id) {
            throw new IllegalArgumentException('Task id is required')
        }
        ZoneId zone = timezone ?: ZoneId.of('UTC')
        def content = (raw.content ?: raw.name ?: '').toString()
        def labels = normalizeLabels(raw)
        def priority = raw.priority != null ? raw.priority as int : 1
        if (priority < 1 || priority > 4) {
            throw new IllegalArgumentException("Task priority must be 1-4, got: ${priority}")
        }

        TodoistDue todoistDue = TodoistDue.from(raw.due, zone) { String value, ZoneId dueZone ->
            parseFlexibleInstant(value, false, dueZone)
        }
        Instant deadline = parseDeadline(raw, zone)
        LocalDate deadlineDate = parseDeadlineDate(raw)
        Instant dueTime = todoistDue?.instant
        boolean allDayDue = todoistDue?.allDay ?: false
        long completedCount = parseCompletedCount(raw)
        String description = raw.description?.toString() ?: ''
        def markerRead = new LifecycleMarkerCodec().read(description)
        LifecycleMarker marker = markerRead.status == MarkerStatus.VALID ? markerRead.marker : null

        Duration nativeDuration = parseNativeDuration(raw)
        def resolved = durationResolver.resolve(nativeDuration, labels)
        boolean manual = labels.any { it.equalsIgnoreCase(manualLabel) }

        return builder()
            .id(id)
            .content(content)
            .projectId(raw.project_id?.toString() ?: raw.projectId?.toString())
            .projectName(raw.project_name?.toString() ?: raw.projectName?.toString())
            .labels(labels)
            .priority(priority)
            .deadline(deadline)
            .dueTime(dueTime)
            .nativeDuration(nativeDuration)
            .effectiveDuration(resolved.duration)
            .durationSource(resolved.source)
            .manual(manual)
            .allDayDue(allDayDue)
            .todoistDue(todoistDue)
            .deadlineDate(deadlineDate)
            .description(description)
            .createdAt(parseTimestamp(raw.created_at ?: raw.createdAt ?: raw.added_at ?: raw.addedAt,
                'created_at/added_at'))
            .updatedAt(parseTimestamp(raw.updated_at ?: raw.updatedAt, 'updated_at'))
            .completedCount(completedCount)
            .lifecycleMarker(marker)
            .build()
    }

    OccurrenceIdentity occurrenceIdentity() {
        new OccurrenceIdentity(id, completedCount)
    }

    private static List<String> normalizeLabels(Map raw) {
        def source = raw.label_names ?: raw.labels ?: []
        if (!(source instanceof Collection)) {
            throw new IllegalArgumentException('Task labels must be a collection')
        }
        return source.collect { it?.toString() ?: '' }.findAll { it }
    }

    private static Instant parseDeadline(Map raw, ZoneId plannerZone) {
        def dl = raw.deadline
        if (dl == null) {
            return null
        }
        if (dl instanceof Instant) {
            return dl
        }
        if (dl instanceof Map) {
            def dateStr = dl.date?.toString()
            if (!dateStr) {
                return null
            }
            ZoneId zone = resolveFieldTimezone(dl, plannerZone, 'deadline')
            return parseFlexibleInstant(dateStr, true, zone)
        }
        return parseFlexibleInstant(dl.toString(), true, plannerZone)
    }

    private static LocalDate parseDeadlineDate(Map raw) {
        def value = raw.deadline instanceof Map ? raw.deadline.date : raw.deadline
        if (value == null || value.toString().trim().isEmpty()) return null
        String text = value.toString().trim()
        if (text ==~ /\d{4}-\d{2}-\d{2}/) return LocalDate.parse(text)
        // Compatibility with older fixtures/providers that exposed a datetime-shaped deadline.
        if (text.size() >= 10 && text.substring(0, 10) ==~ /\d{4}-\d{2}-\d{2}/) {
            return LocalDate.parse(text.substring(0, 10))
        }
        throw new IllegalArgumentException("Invalid Todoist Deadline: ${text}")
    }

    private static long parseCompletedCount(Map raw) {
        def value = raw.containsKey('completed_count') ? raw.completed_count : raw.completedCount
        if (value == null) return 0L
        try {
            long count = value as long
            if (count < 0) throw new IllegalArgumentException('completed_count must be non-negative')
            count
        } catch (IllegalArgumentException e) { throw e }
        catch (Exception e) { throw new IllegalArgumentException("Invalid completed_count: ${value}", e) }
    }

    private static Instant parseTimestamp(Object value, String field) {
        if (value == null || value.toString().trim().isEmpty()) return null
        try { Instant.parse(value.toString()) }
        catch (Exception e) { throw new IllegalArgumentException("Invalid Todoist ${field}: ${value}", e) }
    }

    private static Instant parseDueTime(Map raw, ZoneId plannerZone) {
        def due = raw.due
        if (due == null) {
            return null
        }
        if (due instanceof Instant) {
            return due
        }
        if (due instanceof Map) {
            def dateStr = due.date?.toString()
            if (!dateStr) {
                return null
            }
            ZoneId zone = resolveFieldTimezone(due, plannerZone, 'due')
            return parseFlexibleInstant(dateStr, false, zone)
        }
        return parseFlexibleInstant(due.toString(), false, plannerZone)
    }

    /**
     * Nested Todoist timezone applies only to zone-less local datetimes (contains T, no offset/Z).
     * Date-only values always use the planner timezone so local start/exclusive-next-midnight
     * semantics stay tied to the planner calendar day.
     */
    private static ZoneId resolveFieldTimezone(Map field, ZoneId plannerZone, String fieldName) {
        def tzRaw = field.timezone ?: field.time_zone ?: field.timeZone
        if (tzRaw == null || tzRaw.toString().trim().isEmpty()) {
            return plannerZone
        }
        def dateStr = field.date?.toString() ?: ''
        // Date-only: keep planner zone (all-day local day semantics)
        if (!dateStr.contains('T')) {
            return plannerZone
        }
        // Explicit offset/Z in the date string: nested timezone is ignored (instant preserved)
        if (hasExplicitOffsetOrZ(dateStr)) {
            return plannerZone
        }
        return parseZoneId(tzRaw.toString(), fieldName)
    }

    private static boolean hasExplicitOffsetOrZ(String value) {
        def v = value?.trim() ?: ''
        if (v.endsWith('Z') || v.endsWith('z')) {
            return true
        }
        if (v =~ /[+-]\d{2}:\d{2}$/) {
            return true
        }
        if (v =~ /[+-]\d{4}$/) {
            return true
        }
        return false
    }

    static ZoneId parseZoneId(String timezoneId, String context = 'timezone') {
        if (timezoneId == null || timezoneId.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid ${context}: timezone id must not be blank")
        }
        try {
            return ZoneId.of(timezoneId.trim())
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid ${context} timezone '${timezoneId}': must be a valid IANA ZoneId (e.g. America/New_York)", e)
        }
    }

    private static boolean detectAllDayDue(Map raw) {
        def due = raw.due
        if (!(due instanceof Map)) {
            return false
        }
        def dateStr = due.date?.toString()
        if (!dateStr) {
            return false
        }
        return !dateStr.contains('T')
    }

    /**
     * Parse Todoist-style date strings.
     * Date-only deadlines become exclusive next local midnight (end of that local date).
     * Date-only due times become local start-of-day (all-day due remains true via detectAllDayDue).
     * Offset/Z values are preserved exactly as instants.
     * Zone-less local datetimes (contains T, no offset/Z) are interpreted in {@code timezone}
     * using Java {@link LocalDateTime#atZone(ZoneId)} rules:
     * <ul>
     *   <li>DST overlap (fall-back): earlier offset is chosen</li>
     *   <li>DST gap (spring-forward): local time is adjusted forward into a valid offset</li>
     * </ul>
     */
    static Instant parseFlexibleInstant(String value, boolean endOfDayIfDateOnly,
                                        ZoneId timezone = ZoneId.of('UTC')) {
        if (value == null || value.trim().isEmpty()) {
            return null
        }
        ZoneId zone = timezone ?: ZoneId.of('UTC')
        def v = value.trim()
        if (v.matches(/^\d{4}-\d{2}-\d{2}$/)) {
            LocalDate date = LocalDate.parse(v)
            if (endOfDayIfDateOnly) {
                // Exclusive end of local date: next local midnight
                return date.plusDays(1).atStartOfDay(zone).toInstant()
            }
            return date.atStartOfDay(zone).toInstant()
        }
        if (v.endsWith('Z')) {
            return Instant.parse(v)
        }
        if (v.endsWith('z')) {
            return Instant.parse(v.substring(0, v.length() - 1) + 'Z')
        }
        // Offset without colon: 2026-08-06T10:00:00-0400
        def m = v =~ /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?)([+-]\d{2})(\d{2})$/
        if (m.matches()) {
            return java.time.OffsetDateTime.parse("${m[0][1]}${m[0][2]}:${m[0][3]}").toInstant()
        }
        // Offset with colon already
        if (v =~ /[+-]\d{2}:\d{2}$/) {
            return java.time.OffsetDateTime.parse(v).toInstant()
        }
        // Zone-less local datetime — interpret in provided zone (planner or nested Todoist tz)
        if (v.contains('T')) {
            LocalDateTime ldt = LocalDateTime.parse(v)
            return ldt.atZone(zone).toInstant()
        }
        throw new IllegalArgumentException("Unparseable datetime: ${value}")
    }

    private static Duration parseNativeDuration(Map raw) {
        def d = raw.duration
        if (d == null) {
            return null
        }
        if (d instanceof Duration) {
            return d
        }
        if (d instanceof Number) {
            return Duration.ofMinutes(d.longValue())
        }
        if (d instanceof Map) {
            def amount = d.amount as long
            def unit = (d.unit ?: 'minute').toString().toLowerCase()
            if (unit.startsWith('minute')) {
                return Duration.ofMinutes(amount)
            }
            if (unit.startsWith('hour')) {
                return Duration.ofHours(amount)
            }
            if (unit.startsWith('day')) {
                return Duration.ofDays(amount)
            }
            throw new IllegalArgumentException("Unknown duration unit: ${unit}")
        }
        throw new IllegalArgumentException("Unsupported duration value: ${d}")
    }

    static final class Builder {
        private String id
        private String content
        private String projectId
        private String projectName
        private List<String> labels = []
        private int priority = 1
        private Instant deadline
        private Instant dueTime
        private Duration nativeDuration
        private Duration effectiveDuration
        private String durationSource
        private boolean manual
        private boolean allDayDue
        private TodoistDue todoistDue
        private LocalDate deadlineDate
        private String description = ''
        private Instant createdAt
        private Instant updatedAt
        private long completedCount
        private LifecycleMarker lifecycleMarker

        Builder id(String v) { this.id = v; this }
        Builder content(String v) { this.content = v; this }
        Builder projectId(String v) { this.projectId = v; this }
        Builder projectName(String v) { this.projectName = v; this }
        Builder labels(List<String> v) { this.labels = v ?: []; this }
        Builder priority(int v) { this.priority = v; this }
        Builder deadline(Instant v) { this.deadline = v; this }
        Builder dueTime(Instant v) { this.dueTime = v; this }
        Builder nativeDuration(Duration v) { this.nativeDuration = v; this }
        Builder effectiveDuration(Duration v) { this.effectiveDuration = v; this }
        Builder durationSource(String v) { this.durationSource = v; this }
        Builder manual(boolean v) { this.manual = v; this }
        Builder allDayDue(boolean v) { this.allDayDue = v; this }
        Builder todoistDue(TodoistDue v) { this.todoistDue = v; this }
        Builder deadlineDate(LocalDate v) { this.deadlineDate = v; this }
        Builder description(String v) { this.description = v ?: ''; this }
        Builder createdAt(Instant v) { this.createdAt = v; this }
        Builder updatedAt(Instant v) { this.updatedAt = v; this }
        Builder completedCount(long v) { this.completedCount = v; this }
        Builder lifecycleMarker(LifecycleMarker v) { this.lifecycleMarker = v; this }

        Task build() {
            if (!id) {
                throw new IllegalArgumentException('Task id is required')
            }
            if (content == null) {
                throw new IllegalArgumentException('Task content is required')
            }
            if (priority < 1 || priority > 4) {
                throw new IllegalArgumentException("Task priority must be 1-4, got: ${priority}")
            }
            if (effectiveDuration == null || effectiveDuration.isZero() || effectiveDuration.isNegative()) {
                throw new IllegalArgumentException('Task effectiveDuration must be positive')
            }
            if (!durationSource) {
                throw new IllegalArgumentException('Task durationSource is required')
            }
            if (completedCount < 0) {
                throw new IllegalArgumentException('Task completedCount must be non-negative')
            }
            return new Task(this)
        }
    }

    /**
     * Resolves effective duration: native > configured duration labels > default.
     * Duration label lookup is case-insensitive.
     */
    static final class DurationResolver {
        final int defaultDurationMinutes
        final Map<String, Integer> durationLabels
        private final Map<String, Integer> durationLabelsByLower

        DurationResolver(int defaultDurationMinutes, Map<String, Integer> durationLabels) {
            if (defaultDurationMinutes <= 0) {
                throw new IllegalArgumentException('defaultDurationMinutes must be positive')
            }
            this.defaultDurationMinutes = defaultDurationMinutes
            this.durationLabels = durationLabels ? Collections.unmodifiableMap(new LinkedHashMap<>(durationLabels)) : Collections.emptyMap()
            Map<String, Integer> lower = new LinkedHashMap<>()
            this.durationLabels.each { k, v -> lower[k.toString().toLowerCase(Locale.ROOT)] = v as int }
            this.durationLabelsByLower = Collections.unmodifiableMap(lower)
        }

        ResolvedDuration resolve(Duration nativeDuration, List<String> labels) {
            if (nativeDuration != null && !nativeDuration.isZero() && !nativeDuration.isNegative()) {
                return new ResolvedDuration(nativeDuration, 'native')
            }
            if (labels) {
                for (String label : labels) {
                    def key = label?.toString()
                    if (!key) {
                        continue
                    }
                    def mins = durationLabelsByLower[key.toLowerCase(Locale.ROOT)]
                    if (mins != null && mins > 0) {
                        return new ResolvedDuration(Duration.ofMinutes(mins), "label:${key}")
                    }
                }
            }
            return new ResolvedDuration(Duration.ofMinutes(defaultDurationMinutes), 'default')
        }
    }

    static final class ResolvedDuration {
        final Duration duration
        final String source

        ResolvedDuration(Duration duration, String source) {
            this.duration = duration
            this.source = source
        }
    }
}
