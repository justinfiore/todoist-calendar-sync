package todoistcaldavsync.planner.feedback

import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.messaging.DeadlineRiskInventory
import todoistcaldavsync.planner.scheduling.DeadlinePolicy

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Restores proposal-bound warning numbers from conversation state without resorting them. */
final class StoredDeadlineRiskInventory {
    static DeadlineRiskInventory restore(List rows, ZoneId zone) {
        DeadlinePolicy policy = new DeadlinePolicy('hard', null, null, 7, 7, 7, 7, zone)
        List<DeadlineRiskInventory.Row> restored = (rows ?: []).collect { Map raw ->
            Task task = Task.builder()
                .id(raw.taskId?.toString())
                .content(raw.content?.toString() ?: raw.taskId?.toString())
                .projectId(raw.projectId?.toString())
                .projectName(raw.projectName?.toString())
                .priority(raw.apiPriority instanceof Number ? raw.apiPriority as int : 1)
                .deadlineDate(raw.deadlineDate ? LocalDate.parse(raw.deadlineDate.toString()) : null)
                .effectiveDuration(Duration.ofMinutes(30))
                .durationSource('stored')
                .build()
            boolean hard = raw.mode?.toString() == 'hard'
            def classification = new DeadlinePolicy.Classification(true, hard, hard ? 'hard' : 'soft', raw.conflict?.toString())
            new DeadlineRiskInventory.Row(raw.number as int, task, classification,
                task.deadlineDate, null, raw.reason?.toString(), raw.beyondHorizon == true,
                raw.late == true, raw.unscheduled == true)
        }
        new DeadlineRiskInventory(restored, rows ? rows[0]?.policyIdentity?.toString() : null)
    }
}
