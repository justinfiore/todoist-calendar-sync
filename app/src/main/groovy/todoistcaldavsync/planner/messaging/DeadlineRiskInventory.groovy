package todoistcaldavsync.planner.messaging

import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.UnscheduledTask
import todoistcaldavsync.planner.recurrence.LifecycleSupport
import todoistcaldavsync.planner.scheduling.DeadlinePolicy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Proposal-bound Deadline risk rows. Numbers are assigned once from this inventory
 * and must be stored with the proposal; a later sort must not renumber them.
 *
 * A warning describes the selected plan. It is not proof that no feasible plan exists.
 */
final class DeadlineRiskInventory {
    final List<Row> rows
    final String policyIdentity

    DeadlineRiskInventory(List<Row> rows, String policyIdentity) {
        this.rows = Collections.unmodifiableList(rows)
        this.policyIdentity = policyIdentity
    }

    static DeadlineRiskInventory derive(Plan plan, DeadlinePolicy policy, Instant now, Instant proposalHorizonEnd) {
        if (plan == null || policy == null || now == null) {
            return new DeadlineRiskInventory([], policy?.identity())
        }
        LocalDate today = now.atZone(policy.zone).toLocalDate()
        List<Row> found = []
        (plan.tasks ?: []).each { Task task ->
            DeadlinePolicy.Classification classification = policy.classify(task)
            if (!classification.classified) return
            if (!policy.inWarningWindow(task, now)) return
            Instant finish = finishOf(plan, task.id)
            boolean unscheduled = finish == null
            boolean late = finish != null && task.deadline != null && finish.isAfter(task.deadline)
            if (!unscheduled && !late) return
            boolean beyondHorizon = unscheduled && proposalHorizonEnd != null && task.deadline != null &&
                task.deadline.isAfter(proposalHorizonEnd)
            String reason
            if (beyondHorizon) {
                reason = 'on-time coverage unverified; proposal ends before Deadline'
            } else if (late) {
                reason = 'scheduled to finish after Deadline'
            } else if (task.deadline != null && !task.deadline.isAfter(now)) {
                reason = 'overdue and unscheduled'
            } else {
                reason = 'unscheduled inside the warning window'
            }
            found << new Row(0, task, classification, policy.civilDeadline(task), finish, reason,
                beyondHorizon, late, unscheduled)
        }
        found = found.toSorted { a, b ->
            int c = (a.deadlineDate ?: LocalDate.MAX) <=> (b.deadlineDate ?: LocalDate.MAX)
            if (c != 0) return c
            c = (b.task.priority <=> a.task.priority)
            if (c != 0) return c
            (a.task.id ?: '') <=> (b.task.id ?: '')
        }
        List<Row> numbered = []
        found.eachWithIndex { Row row, int index ->
            numbered << row.numbered(index + 1)
        }
        new DeadlineRiskInventory(numbered, policy.identity())
    }

    Row byNumber(int number) {
        rows.find { it.number == number }
    }

    private static Instant finishOf(Plan plan, String taskId) {
        Instant latest = null
        (plan.scheduledBlocks ?: []).each { ScheduledBlock block ->
            def interval = block.intervalFor(taskId)
            if (interval?.end == null) return
            if (latest == null || interval.end.isAfter(latest)) latest = interval.end
        }
        if (latest != null) return latest
        UnscheduledTask missed = (plan.unscheduled ?: []).find { it.task?.id == taskId }
        return missed == null ? null : null
    }

    static final class Row {
        final int number
        final Task task
        final DeadlinePolicy.Classification classification
        final LocalDate deadlineDate
        final Instant finish
        final String reason
        final boolean beyondHorizon
        final boolean late
        final boolean unscheduled

        Row(int number, Task task, DeadlinePolicy.Classification classification, LocalDate deadlineDate,
            Instant finish, String reason, boolean beyondHorizon, boolean late, boolean unscheduled) {
            this.number = number
            this.task = task
            this.classification = classification
            this.deadlineDate = deadlineDate
            this.finish = finish
            this.reason = reason
            this.beyondHorizon = beyondHorizon
            this.late = late
            this.unscheduled = unscheduled
        }

        Row numbered(int number) {
            new Row(number, task, classification, deadlineDate, finish, reason, beyondHorizon, late, unscheduled)
        }

        String taskUrl() {
            LifecycleSupport.taskLink(task.id)
        }

        String displayPriority() {
            DeadlinePolicy.displayPriority(task.priority)
        }

        Map toMap(ZoneId zone) {
            [
                number: number,
                taskId: task.id,
                content: task.content,
                projectId: task.projectId,
                projectName: task.projectName,
                url: taskUrl(),
                deadlineDate: deadlineDate?.toString(),
                mode: classification.modeLabel(),
                conflict: classification.conflict,
                priority: displayPriority(),
                apiPriority: task.priority,
                reason: reason,
                beyondHorizon: beyondHorizon,
                late: late,
                unscheduled: unscheduled,
                finish: finish?.toString(),
                recurring: task.lifecycleMarker != null
            ]
        }
    }
}
