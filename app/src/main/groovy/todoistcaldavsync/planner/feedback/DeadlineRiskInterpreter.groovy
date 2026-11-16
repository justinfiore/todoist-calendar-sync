package todoistcaldavsync.planner.feedback

import todoistcaldavsync.planner.messaging.DeadlineRiskInventory
import todoistcaldavsync.planner.scheduling.DeadlinePolicy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Turns a numbered proposal-thread reply into a complete action set or one clarification.
 * This is the application boundary the configured LLM fills. It has no write gateway.
 * A missing or malformed model result is a clarification, never a guessed write.
 */
final class DeadlineRiskInterpreter {
    static final String SCHEMA = 'deadline_risk_mitigation'

    private final Gateway gateway

    DeadlineRiskInterpreter(Gateway gateway) {
        this.gateway = gateway
    }

    Result interpret(Request request) {
        if (request == null || !request.authorized) {
            return Result.clarification('That reply is not authorized for this proposal thread.')
        }
        if (!request.inventory?.rows) {
            return Result.clarification('This proposal has no numbered Deadline warnings to change.')
        }
        Map response
        try {
            response = gateway.complete(request)
        } catch (RuntimeException ignored) {
            return Result.clarification('I could not interpret that reply. Ask again with the row number and the Deadline or priority you want.')
        }
        if (response == null) {
            return Result.clarification('I could not interpret that reply. Ask again with the row number and the Deadline or priority you want.')
        }
        if (response.clarification) {
            return Result.clarification(response.clarification.toString())
        }
        List actions = response.actions instanceof List ? response.actions as List : null
        if (actions == null) {
            return Result.clarification('The interpretation did not include a complete action list. No task was changed.')
        }
        List<DeadlineRiskFeedback.Action> parsed = []
        List<String> problems = []
        actions.each { Map raw ->
            Integer number = raw.number instanceof Number ? (raw.number as Number).intValue() : null
            DeadlineRiskInventory.Row row = number == null ? null : request.inventory.byNumber(number)
            if (row == null) {
                problems << "row ${raw.number} is not in this proposal"
                return
            }
            LocalDate date = raw.deadlineDate ? parseDate(raw.deadlineDate.toString(), problems, number) : null
            Integer priority = raw.priority ? parsePriority(raw.priority.toString(), problems, number) : null
            if (date == null && priority == null) problems << "row ${number} has no Deadline or priority"
            parsed << new DeadlineRiskFeedback.Action(number, request.proposalId, date, priority)
        }
        if (problems || parsed.isEmpty()) {
            return Result.clarification(problems ? problems.join('; ') : 'No complete instruction was found.')
        }
        Result.actions(parsed)
    }

    /**
     * Prompt context for the configured model. Dates are resolved by the model only when the
     * supplied zone, reply time, and current Deadline make one civil date unambiguous.
     */
    static Map context(Request request) {
        [
            schema: SCHEMA,
            proposalId: request.proposalId,
            threadTs: request.threadTs,
            zone: request.zone?.id,
            replyTime: request.replyTime?.toString(),
            reply: request.reply,
            rows: request.inventory.rows.collect { row ->
                [
                    number: row.number, taskId: row.task.id, content: row.task.content,
                    project: row.task.projectName, deadline: row.deadlineDate?.toString(),
                    mode: row.classification.modeLabel(), priority: row.displayPriority()
                ]
            },
            allowedPriorities: ['P1', 'P2', 'P3', 'P4']
        ]
    }

    private static LocalDate parseDate(String raw, List<String> problems, int number) {
        try {
            return LocalDate.parse(raw)
        } catch (DateTimeParseException ignored) {
            problems << "row ${number} date is not an exact local date"
            return null
        }
    }

    private static Integer parsePriority(String raw, List<String> problems, int number) {
        try {
            return DeadlinePolicy.apiPriority(raw)
        } catch (IllegalArgumentException ignored) {
            problems << "row ${number} priority is not P1–P4"
            return null
        }
    }

    static final class Request {
        final String proposalId
        final String threadTs
        final String actorId
        final boolean authorized
        final String reply
        final Instant replyTime
        final ZoneId zone
        final DeadlineRiskInventory inventory
        Request(Map values) {
            proposalId = values.proposalId
            threadTs = values.threadTs
            actorId = values.actorId
            authorized = values.authorized == true
            reply = values.reply
            replyTime = values.replyTime as Instant
            zone = values.zone as ZoneId
            inventory = values.inventory as DeadlineRiskInventory
        }
    }

    static final class Result {
        final List<DeadlineRiskFeedback.Action> actions
        final String clarification
        private Result(List<DeadlineRiskFeedback.Action> actions, String clarification) {
            this.actions = actions
            this.clarification = clarification
        }
        static Result actions(List<DeadlineRiskFeedback.Action> actions) { new Result(actions, null) }
        static Result clarification(String question) { new Result(null, question) }
        boolean complete() { actions != null && clarification == null }
    }

    interface Gateway {
        Map complete(Request request)
    }

    /**
     * Test double that resolves the three approved instruction shapes without granting
     * writes. Production uses the configured LLM gateway behind {@link Gateway}.
     */
    /**
     * Production gateway: the configured application LLM, validated by {@code LlmSchemaValidator}.
     * A rejected or unavailable result is a clarification, not a write.
     */
    static final class LlmGateway implements Gateway {
        private final Closure suggestions
        LlmGateway(Closure suggestions) { this.suggestions = suggestions }

        Map complete(Request request) {
            def interpreted = suggestions.call(request)
            if (!interpreted?.accepted) return null
            def rows = interpreted.bundle?.suggestions ?: []
            if (interpreted.bundle?.rootOutcome == 'clarification' || rows.any { it.clarification }) {
                return [clarification: rows.find { it.clarification }?.clarification ?: 'Please clarify the outstanding instruction.']
            }
            [actions: rows.collect { [number: it.number, deadlineDate: it.deadlineDate, priority: it.priority] }]
        }
    }

    static final class FixtureGateway implements Gateway {
        Map complete(Request request) {
            List<String> lines = (request.reply ?: '').readLines().collect { it.trim() }.findAll { it }
            List<Map> actions = []
            List<String> unclear = []
            lines.each { String line ->
                def number = (line =~ /(?i)(?:^|\s)(\d+)(?:\s|$)/)
                if (!number.find()) {
                    unclear << line
                    return
                }
                int row = number.group(1) as int
                DeadlineRiskInventory.Row bound = request.inventory.byNumber(row)
                if (bound == null) {
                    unclear << line
                    return
                }
                def priority = (line =~ /(?i)\bP([1-4])\b/)
                def weeks = (line =~ /(?i)push back (\d+) weeks?/)
                def dated = (line =~ /(?i)(?:deadline|date).*?([A-Za-z]{3,9}\s+\d{1,2})/)
                if (priority.find()) {
                    actions << [number: row, priority: "P${priority.group(1)}"]
                } else if (weeks.find() && bound.deadlineDate != null) {
                    actions << [number: row, deadlineDate: bound.deadlineDate.plusWeeks(weeks.group(1) as long).toString()]
                } else if (dated.find()) {
                    LocalDate resolved = resolve(dated.group(1), request)
                    if (resolved == null) unclear << line
                    else actions << [number: row, deadlineDate: resolved.toString()]
                } else {
                    unclear << line
                }
            }
            if (unclear || actions.size() != lines.size()) {
                return [clarification: "I need a clearer instruction for: ${unclear.join(' | ')}"]
            }
            [actions: actions]
        }

        private static LocalDate resolve(String token, Request request) {
            LocalDate today = request.replyTime.atZone(request.zone).toLocalDate()
            def monthDay = token =~ /(?i)(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+(\d{1,2})/
            if (monthDay.find()) {
                int month = DateTimeFormatter.ofPattern('MMM', Locale.US)
                    .parse(monthDay.group(1).substring(0, 3)).get(ChronoField.MONTH_OF_YEAR)
                LocalDate candidate = LocalDate.of(today.year, month, monthDay.group(2) as int)
                return candidate.isBefore(today) ? candidate.plusYears(1) : candidate
            }
            return null
        }
    }
}
