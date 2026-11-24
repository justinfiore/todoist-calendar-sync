package todoistcaldavsync.planner.messaging

import spock.lang.Specification
import todoistcaldavsync.planner.domain.MemberInterval
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.domain.UnscheduledTask
import todoistcaldavsync.planner.scheduling.DeadlinePolicy

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DeadlineRiskInventorySpec extends Specification {
    ZoneId zone = ZoneId.of('America/New_York')
    Instant now = LocalDate.of(2026, 8, 6).atTime(8, 0).atZone(zone).toInstant()
    DeadlinePolicy policy = new DeadlinePolicy('hard', 'firm', 'flexible', 7, 7, 7, 7, zone)
    DeadlinePolicy softWindows = new DeadlinePolicy('soft', null, null, 7, 7, 3, 14, zone)

    private Task task(Map args) {
        Task.builder()
            .id(args.id)
            .content(args.content ?: args.id)
            .projectId(args.projectId ?: 'p')
            .projectName(args.projectName ?: 'Project')
            .labels((args.labels ?: []) as List)
            .priority(args.priority != null ? args.priority as int : 2)
            .deadline(args.deadline as Instant)
            .deadlineDate(args.deadlineDate as LocalDate)
            .effectiveDuration(Duration.ofMinutes(30))
            .durationSource('test')
            .build()
    }

    def "late soft and unscheduled hard tasks are both warned and distinguished"() {
        given:
        Instant deadline = LocalDate.of(2026, 8, 8).atStartOfDay(zone).toInstant()
        Task soft = task(id: 'soft', content: 'Same', projectName: 'Alpha', labels: ['flexible'],
            deadline: deadline, deadlineDate: LocalDate.of(2026, 8, 7), priority: 1)
        Task hard = task(id: 'hard', content: 'Same', projectName: 'Beta', labels: ['firm'],
            deadline: deadline, deadlineDate: LocalDate.of(2026, 8, 7), priority: 4)
        def plan = Plan.builder().id('plan').createdAt(now).mode('preview')
            .tasks([soft, hard])
            .scheduledBlocks([
                ScheduledBlock.builder().id('b').title('soft').start(now).end(deadline.plusSeconds(60))
                    .taskIds(['soft']).memberIntervals([new MemberInterval('soft', now, deadline.plusSeconds(60))])
                    .build()
            ])
            .unscheduled([new UnscheduledTask(hard, 'no slot', 'no_capacity', [:])])
            .slots([TimeSlot.builder().start(now).end(deadline.plus(Duration.ofHours(2))).build()])
            .build()

        when:
        def inventory = DeadlineRiskInventory.derive(plan, policy, now, deadline.plus(Duration.ofHours(2)))

        then:
        inventory.rows*.task*.id.toSet() == ['soft', 'hard'] as Set
        inventory.rows.find { it.task.id == 'soft' }.classification.mode == 'soft'
        inventory.rows.find { it.task.id == 'hard' }.classification.mode == 'hard'
        inventory.rows.find { it.task.id == 'soft' }.reason.contains('after Deadline')
        inventory.rows*.number == [1, 2]
        inventory.rows.every { it.taskUrl() == "https://app.todoist.com/app/task/${it.task.id}" }
    }

    def "unscheduled task past the proposal horizon is unverified, not impossible"() {
        given:
        LocalDate deadlineDate = LocalDate.of(2026, 8, 10)
        Instant deadline = deadlineDate.plusDays(1).atStartOfDay(zone).toInstant()
        Instant horizon = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()
        Task task = task(id: 'later', deadline: deadline, deadlineDate: deadlineDate)
        def plan = Plan.builder().id('plan').createdAt(now).mode('preview').tasks([task])
            .unscheduled([new UnscheduledTask(task, 'outside', 'no_capacity', [:])])
            .slots([TimeSlot.builder().start(now).end(horizon).build()])
            .build()

        when:
        def row = DeadlineRiskInventory.derive(plan, policy, now, horizon).rows[0]

        then:
        row.beyondHorizon
        row.reason.contains('unverified')
        !row.reason.toLowerCase().contains('impossible')
    }

    def "on-time and out-of-window tasks are not warned"() {
        given:
        Instant soon = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()
        Instant far = LocalDate.of(2026, 8, 20).atStartOfDay(zone).toInstant()
        Task onTime = task(id: 'ok', deadline: soon, deadlineDate: LocalDate.of(2026, 8, 6))
        Task outside = task(id: 'far', deadline: far, deadlineDate: LocalDate.of(2026, 8, 19))
        def plan = Plan.builder().id('plan').createdAt(now).mode('preview').tasks([onTime, outside])
            .scheduledBlocks([
                ScheduledBlock.builder().id('b').title('ok').start(now).end(now.plusSeconds(60)).taskIds(['ok'])
                    .memberIntervals([new MemberInterval('ok', now, now.plusSeconds(60))]).build()
            ])
            .unscheduled([new UnscheduledTask(outside, 'later', 'no_capacity', [:])])
            .build()

        expect:
        DeadlineRiskInventory.derive(plan, policy, now, far).rows.isEmpty()
    }

    def "warning windows are independent and overdue soft stays in window"() {
        given:
        Task hard = task(id: 'hard', labels: ['firm'], deadlineDate: LocalDate.of(2026, 8, 12),
            deadline: LocalDate.of(2026, 8, 13).atStartOfDay(zone).toInstant())
        Task overdue = task(id: 'overdue', labels: ['flexible'], deadlineDate: LocalDate.of(2026, 8, 1),
            deadline: LocalDate.of(2026, 8, 2).atStartOfDay(zone).toInstant())
        def plan = Plan.builder().id('plan').createdAt(now).mode('preview').tasks([hard, overdue])
            .unscheduled([
                new UnscheduledTask(hard, 'x', 'no_capacity', [:]),
                new UnscheduledTask(overdue, 'x', 'no_capacity', [:])
            ]).build()
        DeadlinePolicy narrowHard = new DeadlinePolicy('hard', 'firm', 'flexible', 7, 7, 3, 14, zone)

        when:
        def rows = DeadlineRiskInventory.derive(plan, narrowHard, now, null)

        then:
        rows.rows*.task*.id == ['overdue']
        rows.rows[0].reason.contains('overdue')
    }
}
