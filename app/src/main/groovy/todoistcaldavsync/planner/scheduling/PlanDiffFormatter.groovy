package todoistcaldavsync.planner.scheduling

import groovy.json.JsonOutput
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.UnscheduledTask

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Human-facing plan diff uses 12-hour AM/PM; machine JSON uses ISO-8601.
 */
class PlanDiffFormatter {
    private static final DateTimeFormatter HUMAN_DATETIME =
        DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a').withLocale(Locale.US)
    private static final DateTimeFormatter HUMAN_TIME =
        DateTimeFormatter.ofPattern('h:mm a').withLocale(Locale.US)

    static String toMarkdown(Plan plan, ZoneId zone) {
        ZoneId z = zone ?: ZoneId.of('UTC')
        def sb = new StringBuilder()
        sb << "# Plan ${plan.id}\n\n"
        sb << "- Mode: ${plan.mode}\n"
        sb << "- Version: ${plan.version}\n"
        sb << "- Created: ${fmtHuman(plan.createdAt, z)}\n"
        sb << "- Scheduled blocks: ${plan.scheduledBlocks.size()}\n"
        sb << "- Unscheduled tasks: ${plan.unscheduled.size()}\n\n"

        List<PlanChange> added = plan.changes.findAll { it.type == 'add' || it.type == 'scheduled' }
        List<PlanChange> moved = plan.changes.findAll { it.type == 'move' || it.type == 'moved' }
        List<PlanChange> kept = plan.changes.findAll { it.type == 'keep' || it.type == 'kept' }

        sb << '## Scheduled\n\n'
        if (!plan.scheduledBlocks) {
            sb << '_No scheduled blocks._\n\n'
        } else {
            plan.scheduledBlocks.each { ScheduledBlock b ->
                sb << "- **${b.title}**: ${fmtRange(b.start, b.end, z)}"
                if (b.focusBlock) {
                    sb << " (focus block; tasks: ${b.taskIds.join(', ')})"
                }
                if (b.frozen) {
                    sb << ' [frozen]'
                }
                if (b.manualOverride) {
                    sb << ' [manual]'
                }
                if (b.reason) {
                    sb << "\n  Reason: ${b.reason}"
                }
                sb << '\n'
            }
            sb << '\n'
        }

        // Track weather fingerprints already rendered under changes/unscheduled so
        // explanations do not repeat the same rule/timestamps noisily.
        Set<String> renderedWeatherKeys = new LinkedHashSet<>()

        if (moved) {
            sb << '## Moved\n\n'
            moved.each { PlanChange c ->
                sb << "- ${c.taskId}: ${fmtHuman(c.previousStart, z)} → ${fmtHuman(c.newStart, z)}\n"
                sb << "  Reason: ${c.reason}\n"
                Map weather = extractWeatherMap(c.metadata)
                if (weather) {
                    appendWeatherMeta(sb, weather, z)
                    renderedWeatherKeys << weatherFingerprint(weather, c.taskId)
                }
                appendReplacementMeta(sb, c.metadata)
                if (c.metadata?.approvalRequired == true || c.metadata?.approvalRequired == 'true') {
                    String approvalReason = c.metadata.approvalReason?.toString()
                    if (approvalReason) {
                        sb << "  Approval required: ${humanApprovalReason(approvalReason)}\n"
                    } else {
                        sb << "  Approval required\n"
                    }
                }
            }
            sb << '\n'
        }

        if (kept) {
            sb << '## Kept\n\n'
            kept.each { PlanChange c ->
                sb << "- ${c.taskId}: ${fmtHuman(c.newStart ?: c.previousStart, z)}\n"
                sb << "  Reason: ${c.reason}\n"
            }
            sb << '\n'
        }

        if (added) {
            sb << '## Added\n\n'
            added.each { PlanChange c ->
                sb << "- ${c.taskId}: ${fmtHuman(c.newStart, z)}"
                if (c.newEnd) {
                    sb << " – ${fmtHuman(c.newEnd, z)}"
                }
                sb << "\n  Reason: ${c.reason}\n"
                Map weather = extractWeatherMap(c.metadata)
                if (weather) {
                    appendWeatherMeta(sb, weather, z)
                    renderedWeatherKeys << weatherFingerprint(weather, c.taskId)
                }
                appendReplacementMeta(sb, c.metadata)
            }
            sb << '\n'
        }

        sb << '## Unscheduled\n\n'
        if (!plan.unscheduled) {
            sb << '_None._\n\n'
        } else {
            plan.unscheduled.each { UnscheduledTask u ->
                sb << "- **${u.task.content}**\n"
                sb << "  Reason: ${u.reason}\n"
                if (u.code == 'weather_infeasible') {
                    sb << "  Code: weather_infeasible\n"
                }
                Map weather = extractWeatherMap(u.metadata)
                if (weather) {
                    appendWeatherMeta(sb, weather, z)
                    renderedWeatherKeys << weatherFingerprint(weather, u.task?.id)
                }
            }
            sb << '\n'
        }

        if (plan.explanations) {
            sb << '## Explanations\n\n'
            plan.explanations.each { ex ->
                sb << "- `[${ex.code}]` ${ex.message}\n"
                Map weather = extractWeatherMap(ex.details)
                if (weather) {
                    String key = weatherFingerprint(weather, ex.subjectId)
                    if (!renderedWeatherKeys.contains(key)) {
                        appendWeatherMeta(sb, weather, z, '  ')
                        renderedWeatherKeys << key
                    }
                }
            }
            sb << '\n'
        }
        return sb.toString()
    }

    static String toJson(Plan plan) {
        // Reuse PlanStore shape without depending on file I/O
        return JsonOutput.prettyPrint(JsonOutput.toJson(
            todoistcaldavsync.planner.state.PlanStore.planToMap(plan)
        ))
    }

    static String humanApprovalReason(String code) {
        if (code == null || code.isEmpty()) {
            return 'approval required'
        }
        switch (code) {
            case 'move_within_require_approval_horizon':
                return 'move within require-approval horizon'
            default:
                return code.replace('_', ' ')
        }
    }

    /**
     * Prefer nested weather maps; fall back to flat explanation-detail shape.
     */
    private static Map extractWeatherMap(Map metadata) {
        if (!(metadata instanceof Map) || metadata.isEmpty()) {
            return null
        }
        if (metadata.weather instanceof Map) {
            return metadata.weather as Map
        }
        if (metadata.priorWeather instanceof Map) {
            return metadata.priorWeather as Map
        }
        if (metadata.replacedTaskWeather instanceof Map) {
            return metadata.replacedTaskWeather as Map
        }
        // Flat weather evaluation details (unscheduled explanation / metadata)
        if (metadata.ruleName || metadata.ruleId || metadata.forecastIssuedAt || metadata.result) {
            return metadata
        }
        return null
    }

    private static String weatherFingerprint(Map weather, String subjectId) {
        if (!(weather instanceof Map)) {
            return ''
        }
        return [
            subjectId ?: '',
            weather.ruleId ?: '',
            weather.ruleName ?: '',
            weather.result ?: '',
            weather.forecastIssuedAt?.toString() ?: '',
            weather.forecastRetrievedAt?.toString() ?: '',
            weather.observedField ?: '',
            weather.observedValue?.toString() ?: ''
        ].join('|')
    }

    /**
     * Append forecast timestamp + applied rule for weather-driven changes (12-hour local).
     * Deterministic field order: rule, evaluation, issued, retrieved, provider, location, observed.
     */
    private static void appendWeatherMeta(StringBuilder sb, Map weather, ZoneId zone, String indent = '  ') {
        if (!(weather instanceof Map) || weather.isEmpty()) {
            return
        }
        if (weather.ruleName || weather.ruleId) {
            sb << "${indent}Weather rule: ${weather.ruleName ?: weather.ruleId}\n"
        }
        if (weather.result) {
            sb << "${indent}Weather evaluation: ${weather.result}\n"
        }
        if (weather.forecastIssuedAt) {
            try {
                Instant issued = Instant.parse(weather.forecastIssuedAt.toString())
                sb << "${indent}Forecast issued: ${fmtHuman(issued, zone)}\n"
            } catch (Exception e) {
                sb << "${indent}Forecast issued: ${weather.forecastIssuedAt}\n"
            }
        }
        if (weather.forecastRetrievedAt) {
            try {
                Instant retrieved = Instant.parse(weather.forecastRetrievedAt.toString())
                sb << "${indent}Forecast retrieved: ${fmtHuman(retrieved, zone)}\n"
            } catch (Exception e) {
                sb << "${indent}Forecast retrieved: ${weather.forecastRetrievedAt}\n"
            }
        }
        if (weather.provider) {
            sb << "${indent}Weather provider: ${weather.provider}\n"
        }
        if (weather.latitude != null || weather.longitude != null) {
            String lat = weather.latitude != null ? weather.latitude.toString() : '?'
            String lon = weather.longitude != null ? weather.longitude.toString() : '?'
            sb << "${indent}Weather location: ${lat}, ${lon}\n"
        }
        if (weather.observedField) {
            sb << "${indent}Observed ${weather.observedField}: ${weather.observedValue}"
            if (weather.threshold != null) {
                sb << " (threshold ${weather.threshold})"
            }
            sb << '\n'
        }
    }

    private static void appendReplacementMeta(StringBuilder sb, Map metadata) {
        if (!(metadata instanceof Map)) {
            return
        }
        if (metadata.replacesWeatherInvalidTaskId) {
            sb << "  Indoor replacement for: ${metadata.replacesWeatherInvalidTaskId}\n"
        }
        if (metadata.replacedByIndoorTaskId) {
            sb << "  Replaced by indoor task: ${metadata.replacedByIndoorTaskId}\n"
        }
    }

    static String fmtHuman(Instant instant, ZoneId zone) {
        if (instant == null) {
            return ''
        }
        return HUMAN_DATETIME.format(instant.atZone(zone))
    }

    static String fmtRange(Instant start, Instant end, ZoneId zone) {
        if (start == null || end == null) {
            return ''
        }
        def zs = start.atZone(zone)
        def ze = end.atZone(zone)
        if (zs.toLocalDate() == ze.toLocalDate()) {
            return "${HUMAN_DATETIME.format(zs)}–${HUMAN_TIME.format(ze)}"
        }
        return "${HUMAN_DATETIME.format(zs)} – ${HUMAN_DATETIME.format(ze)}"
    }
}
