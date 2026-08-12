package todoistcaldavsync.planner.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Deterministic semantic content hash for plan identity binding (approvals, receipts).
 * Covers approval-material fields that influence approved intent: id, version, mode,
 * scheduled blocks (including reason and behavioral flags), member intervals,
 * unscheduled tasks, plan changes (including reason), and explanations.
 * Excludes only volatile {@code createdAt} timestamps and humanDiff formatting.
 */
final class PlanHash {
    private PlanHash() {}

    static String compute(Plan plan) {
        if (plan == null) {
            throw new IllegalArgumentException('plan is required')
        }
        String canonical = canonicalize(plan)
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] dig = md.digest(canonical.getBytes(StandardCharsets.UTF_8))
        return dig.collect { String.format('%02x', it & 0xff) }.join()
    }

    static String canonicalize(Plan plan) {
        def sb = new StringBuilder()
        sb.append('id=').append(plan.id).append('\n')
        sb.append('version=').append(plan.version).append('\n')
        sb.append('mode=').append(plan.mode).append('\n')
        plan.scheduledBlocks.toSorted { a, b -> a.id <=> b.id }.each { ScheduledBlock b ->
            sb.append('block|').append(b.id).append('|')
                .append(b.start).append('|').append(b.end).append('|')
                .append(b.taskIds.join(',')).append('|')
                .append(b.projectId ?: '').append('|').append(b.projectName ?: '').append('|')
                .append(b.focusBlock).append('|').append(b.frozen).append('|')
                .append(b.manualOverride).append('|').append(b.title).append('|')
                .append(b.reason ?: '').append('\n')
            b.memberIntervals.each { mi ->
                sb.append('mi|').append(mi.taskId).append('|')
                    .append(mi.start).append('|').append(mi.end).append('\n')
            }
        }
        plan.unscheduled.toSorted { a, b -> (a.task?.id ?: '') <=> (b.task?.id ?: '') }.each { UnscheduledTask u ->
            sb.append('unscheduled|').append(u.task?.id ?: '').append('|')
                .append(u.code ?: '').append('|').append(u.reason ?: '').append('\n')
        }
        plan.changes.toSorted { a, b -> a.id <=> b.id }.each { PlanChange c ->
            sb.append('change|').append(c.id).append('|').append(c.type).append('|')
                .append(c.taskId ?: '').append('|')
                .append(c.previousStart ?: '').append('|').append(c.newStart ?: '').append('|')
                .append(c.previousEnd ?: '').append('|').append(c.newEnd ?: '').append('|')
                .append(c.reason ?: '').append('|')
                .append(c.metadata?.approvalRequired).append('\n')
        }
        plan.explanations.toSorted { a, b ->
            (a.code <=> b.code) ?: ((a.subjectId ?: '') <=> (b.subjectId ?: '')) ?: (a.message <=> b.message)
        }.each { PlanningExplanation e ->
            sb.append('expl|').append(e.code).append('|').append(e.message).append('|')
                .append(e.subjectType ?: '').append('|').append(e.subjectId ?: '').append('\n')
        }
        return sb.toString()
    }
}
