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

        if (moved) {
            sb << '## Moved\n\n'
            moved.each { PlanChange c ->
                sb << "- ${c.taskId}: ${fmtHuman(c.previousStart, z)} → ${fmtHuman(c.newStart, z)}\n"
                sb << "  Reason: ${c.reason}\n"
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
            }
            sb << '\n'
        }

        if (plan.explanations) {
            sb << '## Explanations\n\n'
            plan.explanations.each { ex ->
                sb << "- `[${ex.code}]` ${ex.message}\n"
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
