package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.Task

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.regex.Pattern

/** Hard redaction performed before serialization; prompt instructions are not a control. */
final class AiRedactor {
    private AiRedactor() {}

    private static final Set<String> SECRET_KEYS = Collections.unmodifiableSet([
        'clientsecret','accesstoken','refreshtoken','privatekey','apikey','authtoken','bearertoken',
        'webhookurl','signingsecret','password','credential','secret','token','authorization','key'
    ] as Set)
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
        /(?i)(?<![A-Za-z0-9])["']?((?:client|access|refresh|auth|bearer|signing)[ ._-]?(?:secret|token|key)|private[ ._-]?key|api[ ._-]?key|webhook(?:[ ._-]?url)?|authorization|password|credential|secret|token|key)["']?\s*(?::|=|\bis\b|\bwas\b)\s*(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;&}\]]+)/)
    private static final List<Pattern> PATTERNS = [
        Pattern.compile(/(?is)-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----.*?-----END(?: [A-Z0-9]+)* PRIVATE KEY-----/),
        Pattern.compile(/(?i)(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}(?![A-Za-z0-9_-])/),
        Pattern.compile(/(?i)\bauthorization\s*:\s*(?:bearer|basic)\s+[^\s,;]+/),
        Pattern.compile(/(?i)\bbearer\s+[A-Za-z0-9._~+\/-]{8,}=*/),
        Pattern.compile(/(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/),
        Pattern.compile(/(?i)\bhttps?:\/\/[^\s<>'"]+/),
        Pattern.compile(/(?i)\b(?:bearer\s+)?(?:sk|xox[baprs]|gh[pousr])[-_A-Za-z0-9]{8,}\b/),
        Pattern.compile(/(?<![A-Za-z0-9+])\+(?:\d[ .()\/-]?){7,14}\d(?![A-Za-z0-9])/),
        Pattern.compile(/(?<!\w)(?:\+?1[ .-]?)?\(?\d{3}\)?[ .-]\d{3}[ .-]\d{4}(?!\w)/),
        Pattern.compile(/(?<![A-Z0-9])[UWCG][A-Z0-9]{8,}(?![A-Z0-9])/),
        Pattern.compile(/(?<!\w)<@[A-Z0-9]{5,}>(?!\w)/),
        Pattern.compile(/(?i)<(?:@|#|!)[^>]{2,}>/),
        Pattern.compile(/(?i)\b(?:slack[_ -]?(?:user|channel)?[_ -]?id)[\s:=]+[A-Z0-9]{5,}\b/),
        Pattern.compile(/(?i)\b(?:attendee|attendees|organizer)[\s:=]+[^,;\n]+/)
    ]

    static RedactedText redactText(String raw, int maxChars) {
        String text = raw ?: ''
        int count = 0
        def assignmentMatcher = CREDENTIAL_ASSIGNMENT.matcher(text)
        StringBuffer assignmentBuffer = new StringBuffer()
        while (assignmentMatcher.find()) {
            String normalized = assignmentMatcher.group(1).replaceAll(/[^A-Za-z0-9]/, '').toLowerCase(Locale.ROOT)
            if (SECRET_KEYS.contains(normalized)) {
                count++
                assignmentMatcher.appendReplacement(assignmentBuffer, '[REDACTED]')
            } else {
                assignmentMatcher.appendReplacement(assignmentBuffer, java.util.regex.Matcher.quoteReplacement(assignmentMatcher.group()))
            }
        }
        assignmentMatcher.appendTail(assignmentBuffer)
        text = assignmentBuffer.toString()
        PATTERNS.each { Pattern p ->
            def m = p.matcher(text)
            StringBuffer sb = new StringBuffer()
            while (m.find()) {
                count++
                m.appendReplacement(sb, '[REDACTED]')
            }
            m.appendTail(sb)
            text = sb.toString()
        }
        text = text.replaceAll(/[\u0000-\u001F\u007F]/, ' ').replaceAll(/\s+/, ' ').trim()
        boolean truncated = maxChars > 0 && text.length() > maxChars
        if (truncated) text = text.substring(0, maxChars)
        new RedactedText(text, count, truncated)
    }

    static final class RedactedText {
        final String text
        final int redactionCount
        final boolean truncated
        RedactedText(String text, int count, boolean truncated) {
            this.text = text; this.redactionCount = count; this.truncated = truncated
        }
    }
}

/**
 * Builds allowlisted, deterministic context. Descriptions, comments, attendees,
 * URLs, locations, raw metadata and credentials are never inspected or emitted.
 */
final class LlmContextBuilder {
    private final PlannerConfig.AiConfig config

    LlmContextBuilder(PlannerConfig.AiConfig config) {
        if (config == null) throw new IllegalArgumentException('AI config is required')
        this.config = config
    }

    ContextBuildResult build(Plan plan, Collection<CalendarEvent> events = [],
                             String feedbackText = null, Instant referenceTime = null) {
        if (plan == null) throw new IllegalArgumentException('plan is required')
        Instant reference = referenceTime ?: plan.createdAt
        int redactions = 0
        int truncations = 0
        int omitted = 0
        List<Map> taskRows = []
        List<Task> sortedTasks = (plan.tasks ?: []).findAll { it != null }.toSorted { a, b -> a.id <=> b.id }
        List<Task> safeTasks = sortedTasks.findAll { safeIdentifier(it.id) }
        omitted += sortedTasks.size() - safeTasks.size()
        safeTasks.take(config.maxItems).each { Task task ->
            def title = redact(task.content)
            def category = redact(task.projectName ?: '')
            redactions += title.redactionCount + category.redactionCount
            truncations += (title.truncated ? 1 : 0) + (category.truncated ? 1 : 0)
            taskRows << compact([
                id: task.id,
                title: title.text,
                category: category.text,
                durationMinutes: task.effectiveDuration?.toMinutes(),
                deadlineSecondsFromReference: task.deadline == null ? null : Duration.between(reference, task.deadline).seconds,
                priority: task.priority
            ])
        }
        omitted += Math.max(0, safeTasks.size() - taskRows.size())

        List<Map> eventRows = []
        List<CalendarEvent> sortedEvents = (events ?: []).findAll { it != null }.toSorted { a, b -> a.id <=> b.id }
        List<CalendarEvent> safeEvents = sortedEvents.findAll { safeIdentifier(it.id) }
        omitted += sortedEvents.size() - safeEvents.size()
        int eventBudget = Math.max(0, config.maxItems - taskRows.size())
        safeEvents.take(eventBudget).each { CalendarEvent event ->
            def title = redact(event.title)
            def category = redact(event.calendarName)
            redactions += title.redactionCount + category.redactionCount
            truncations += (title.truncated ? 1 : 0) + (category.truncated ? 1 : 0)
            eventRows << compact([
                id: event.id, title: title.text, category: category.text,
                startSecondsFromReference: Duration.between(reference, event.start).seconds,
                endSecondsFromReference: Duration.between(reference, event.end).seconds,
                currentRole: event.role?.configValue, allDay: event.allDay
            ])
        }
        omitted += Math.max(0, safeEvents.size() - eventRows.size())

        Map context = [referenceTime: reference.toString(), tasks: taskRows, events: eventRows]
        if (feedbackText != null) {
            def feedback = redact(feedbackText)
            redactions += feedback.redactionCount
            truncations += feedback.truncated ? 1 : 0
            context.feedback = feedback.text
        }
        // Deterministically omit tail entries until the configured body budget is respected.
        while (jsonBytes(context) > config.maxRequestBytes / 2 && (!eventRows.isEmpty() || !taskRows.isEmpty())) {
            if (!eventRows.isEmpty()) eventRows.remove(eventRows.size() - 1)
            else taskRows.remove(taskRows.size() - 1)
            omitted++
        }
        if (jsonBytes(context) > config.maxRequestBytes / 2) {
            throw new IllegalArgumentException('minimum AI context exceeds configured request budget')
        }
        context.omittedCounts = [items: omitted, truncatedStrings: truncations]
        new ContextBuildResult(context, redactions, omitted, truncations,
            taskRows.collect { it.id } as Set, eventRows.collect { it.id } as Set, jsonBytes(context))
    }

    private AiRedactor.RedactedText redact(String value) {
        config.redactionEnabled
            ? AiRedactor.redactText(value, config.maxStringChars)
            : AiRedactor.redactText(value, config.maxStringChars) // bounds/controls always apply
    }

    private static boolean safeIdentifier(String value) {
        if (!(value ==~ /^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$/)) return false
        def redacted=AiRedactor.redactText(value,256)
        redacted.redactionCount==0 && redacted.text==value
    }

    private static int jsonBytes(Map value) {
        JsonOutput.toJson(value).getBytes(StandardCharsets.UTF_8).length
    }

    private static Map compact(Map values) {
        values.findAll { k, v -> v != null && (!(v instanceof String) || !v.isEmpty()) }
    }
}

final class ContextBuildResult {
    final Map<String, Object> context
    final int redactionCount
    final int omittedCount
    final int truncatedStringCount
    final Set<String> taskIds
    final Set<String> eventIds
    final int contextBytes

    ContextBuildResult(Map context, int redactionCount, int omittedCount, int truncatedStringCount,
                       Set<String> taskIds, Set<String> eventIds, int contextBytes) {
        this.context = AiValues.immutableMap(context)
        this.redactionCount = redactionCount
        this.omittedCount = omittedCount
        this.truncatedStringCount = truncatedStringCount
        this.taskIds = Collections.unmodifiableSet(new LinkedHashSet<>(taskIds ?: []))
        this.eventIds = Collections.unmodifiableSet(new LinkedHashSet<>(eventIds ?: []))
        this.contextBytes = contextBytes
    }
}
