package todoistcaldavsync.planner.report

import groovy.json.JsonOutput
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats capacity reports as Markdown (12-hour AM/PM) or JSON (ISO-8601).
 */
class CapacityReportFormatter {
    private static final DateTimeFormatter HUMAN_TIME =
        DateTimeFormatter.ofPattern('h:mm a').withLocale(Locale.US)
    private static final DateTimeFormatter HUMAN_DATE =
        DateTimeFormatter.ofPattern('EEE MMM d, yyyy').withLocale(Locale.US)
    private static final DateTimeFormatter HUMAN_DATETIME =
        DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a').withLocale(Locale.US)

    static String toMarkdown(CapacityReportService.CapacityReport report) {
        ZoneId zone = report.timezone
        def sb = new StringBuilder()
        sb << '# Capacity Report\n\n'
        sb << "- Range: ${fmtHuman(report.rangeStart, zone)} → ${fmtHuman(report.rangeEnd, zone)}\n"
        sb << "- Timezone: ${zone.id}\n"
        sb << "- Usable capacity: ${formatDuration(report.usableCapacityMinutes)}\n"
        sb << "- Task demand: ${formatDuration(report.taskDemandMinutes)}\n"
        sb << "- Soft-penalized capacity: ${formatDuration(report.softPenalizedMinutes)}\n"
        sb << "- Candidate tasks: ${report.candidateTasks.size()}\n"
        sb << "- Manual excluded: ${report.manualExcludedTasks.size()}\n"
        sb << "- Deadline risks: ${report.deadlineRisks.size()}\n\n"

        sb << '## Free slots\n\n'
        if (!report.slots) {
            sb << '_No free slots in range._\n\n'
        } else {
            report.slots.each { TimeSlot s ->
                def soft = s.softBlocked ? ' (soft-penalized)' : ''
                sb << "- ${fmtHuman(s.start, zone)} – ${fmtHuman(s.end, zone)} (${s.durationMinutes()}m)${soft}"
                if (s.windowName) {
                    sb << " [${s.windowName}]"
                }
                sb << '\n'
            }
            sb << '\n'
        }

        sb << '## Task demand\n\n'
        if (!report.candidateTasks) {
            sb << '_No planner candidate tasks._\n\n'
        } else {
            report.candidateTasks.each { Task t ->
                sb << "- ${t.content} — ${t.effectiveDuration.toMinutes()}m (${t.durationSource})"
                if (t.deadline) {
                    sb << ", deadline ${fmtHuman(t.deadline, zone)}"
                }
                if (t.dueTime) {
                    sb << ", due/scheduled ${fmtHuman(t.dueTime, zone)}"
                }
                sb << '\n'
            }
            sb << '\n'
        }

        if (report.manualExcludedTasks) {
            sb << '## Manual excluded (@manual)\n\n'
            report.manualExcludedTasks.each { Task t ->
                sb << "- ${t.content} (id=${t.id}) — not a planner candidate\n"
            }
            sb << '\n'
        }

        sb << '## Deadline risks / cannot fit\n\n'
        if (!report.deadlineRisks) {
            sb << '_No deadline risks detected in horizon._\n\n'
        } else {
            report.deadlineRisks.each { r ->
                sb << "- **${r.task.content}** (${r.requiredMinutes}m): ${r.reason}\n"
            }
            sb << '\n'
        }

        sb << '## Event classification (capacity impact)\n\n'
        if (!report.classifiedEvents) {
            sb << '_No events._\n\n'
        } else {
            report.classifiedEvents.each { CalendarEvent ev ->
                sb << "- **${ev.title}** [${ev.calendarName}] ${fmtHuman(ev.start, zone)} – ${fmtHuman(ev.end, zone)}\n"
                sb << "  - Role: ${ev.role?.configValue}\n"
                sb << "  - Rule: ${ev.matchedRuleName}\n"
                sb << "  - Why: ${ev.classificationReason}\n"
                if (ev.bufferBeforeMinutes || ev.bufferAfterMinutes) {
                    sb << "  - Buffers: before ${ev.bufferBeforeMinutes}m / after ${ev.bufferAfterMinutes}m\n"
                }
            }
            sb << '\n'
        }

        sb << '## Explanations\n\n'
        report.explanations.each { ex ->
            sb << "- `[${ex.code}]` ${ex.message}\n"
        }
        return sb.toString()
    }

    static String toJson(CapacityReportService.CapacityReport report) {
        def map = [
            rangeStart             : report.rangeStart.toString(),
            rangeEnd               : report.rangeEnd.toString(),
            timezone               : report.timezone.id,
            usableCapacityMinutes  : report.usableCapacityMinutes,
            taskDemandMinutes      : report.taskDemandMinutes,
            softPenalizedMinutes   : report.softPenalizedMinutes,
            candidateTasks         : report.candidateTasks.collect { taskToMap(it) },
            manualExcludedTasks    : report.manualExcludedTasks.collect { taskToMap(it) },
            slots                  : report.slots.collect { slotToMap(it) },
            deadlineRisks          : report.deadlineRisks.collect {
                [
                    taskId           : it.task.id,
                    content          : it.task.content,
                    requiredMinutes  : it.requiredMinutes,
                    deadline         : it.task.deadline?.toString(),
                    reason           : it.reason
                ]
            },
            classifiedEvents       : report.classifiedEvents.collect { eventToMap(it) },
            explanations           : report.explanations.collect {
                [
                    code       : it.code,
                    message    : it.message,
                    subjectType: it.subjectType,
                    subjectId  : it.subjectId,
                    details    : it.details
                ]
            }
        ]
        return JsonOutput.prettyPrint(JsonOutput.toJson(map))
    }

    private static Map taskToMap(Task t) {
        [
            id               : t.id,
            content          : t.content,
            projectId        : t.projectId,
            projectName      : t.projectName,
            labels           : t.labels,
            priority         : t.priority,
            deadline         : t.deadline?.toString(),
            dueTime          : t.dueTime?.toString(),
            nativeDuration   : t.nativeDuration?.toString(),
            effectiveMinutes : t.effectiveDuration.toMinutes(),
            durationSource   : t.durationSource,
            manual           : t.manual
        ]
    }

    private static Map slotToMap(TimeSlot s) {
        [
            start              : s.start.toString(),
            end                : s.end.toString(),
            durationMinutes    : s.durationMinutes(),
            softBlocked        : s.softBlocked,
            softBlockerEventIds: s.softBlockerEventIds,
            softBlockerReasons : s.softBlockerReasons,
            windowName         : s.windowName
        ]
    }

    private static Map eventToMap(CalendarEvent ev) {
        [
            id                   : ev.id,
            title                : ev.title,
            calendarName         : ev.calendarName,
            start                : ev.start.toString(),
            end                  : ev.end.toString(),
            role                 : ev.role?.configValue,
            matchedRuleName      : ev.matchedRuleName,
            classificationReason : ev.classificationReason,
            bufferBeforeMinutes  : ev.bufferBeforeMinutes,
            bufferAfterMinutes   : ev.bufferAfterMinutes,
            unknownCalendar      : ev.unknownCalendar
        ]
    }

    static String fmtHuman(Instant instant, ZoneId zone) {
        if (instant == null) {
            return ''
        }
        return HUMAN_DATETIME.format(instant.atZone(zone))
    }

    static String formatDuration(long minutes) {
        if (minutes < 60) {
            return "${minutes}m"
        }
        long h = minutes / 60
        long m = minutes % 60
        return m == 0 ? "${h}h" : "${h}h ${m}m"
    }
}
