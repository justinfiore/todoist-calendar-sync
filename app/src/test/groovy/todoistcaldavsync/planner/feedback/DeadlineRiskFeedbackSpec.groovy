package todoistcaldavsync.planner.feedback

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.InMemoryTodoistGateway
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.UnscheduledTask
import todoistcaldavsync.planner.messaging.DeadlineRiskInventory
import todoistcaldavsync.planner.scheduling.DeadlinePolicy

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DeadlineRiskFeedbackSpec extends Specification {
    ZoneId zone = ZoneId.of('America/New_York')
    Instant now = LocalDate.of(2026, 8, 6).atTime(8, 0).atZone(zone).toInstant()
    DeadlinePolicy policy = new DeadlinePolicy('hard', null, null, 7, 7, 7, 7, zone)

    private DeadlineRiskInventory inventory() {
        Task first = Task.builder().id('t1').content('Alpha').projectName('Inbox').priority(1)
            .deadline(LocalDate.of(2026, 8, 8).atStartOfDay(zone).toInstant())
            .deadlineDate(LocalDate.of(2026, 8, 7))
            .effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
        Task second = Task.builder().id('t2').content('Beta').projectName('Work').priority(4)
            .deadline(LocalDate.of(2026, 8, 9).atStartOfDay(zone).toInstant())
            .deadlineDate(LocalDate.of(2026, 8, 8))
            .effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
        def plan = Plan.builder().id('plan-1').createdAt(now).mode('preview').tasks([first, second])
            .unscheduled([
                new UnscheduledTask(first, 'missed', 'no_capacity', [:]),
                new UnscheduledTask(second, 'missed', 'no_capacity', [:])
            ]).build()
        DeadlineRiskInventory.derive(plan, policy, now, null)
    }

    def "fully resolved priority edit writes only priority and refuses a stale deadline"() {
        given:
        def inventory = inventory()
        def gateway = new InMemoryTodoistGateway([
            [id: 't1', priority: 1, deadline: [date: '2026-08-07'], due: [date: '2026-08-06']],
            [id: 't2', priority: 4, deadline: [date: '2026-08-08']]
        ])
        def feedback = new DeadlineRiskFeedback(gateway, zone)
        def live = ['t1': gateway.getTask('t1'), 't2': gateway.getTask('t2')]

        when:
        def ok = feedback.preflight(inventory, [
            new DeadlineRiskFeedback.Action(1, 'prop', null, 3)
        ], live, 'prop', policy.identity())
        def execution = feedback.execute(ok)

        then:
        ok.accepted
        execution.allVerified()
        gateway.priorityUpdates == [[taskId: 't1', priority: 3]]
        gateway.deadlineUpdates.isEmpty()
        gateway.dueUpdates.isEmpty()
        gateway.getTask('t1').due.date == '2026-08-06'

        when:
        gateway.getTask('t2').deadline = [date: '2026-08-12']
        def stale = feedback.preflight(inventory, [
            new DeadlineRiskFeedback.Action(2, 'prop', LocalDate.of(2026, 8, 20), null)
        ], ['t1': gateway.getTask('t1'), 't2': gateway.getTask('t2')], 'prop', policy.identity())

        then:
        !stale.accepted
        stale.problems.any { it.contains('fresh review') }
        gateway.deadlineUpdates.isEmpty()
    }

    def "a redelivered command is not applied twice"() {
        given:
        def inventory = inventory()
        def gateway = new InMemoryTodoistGateway([[id: 't1', priority: 1, deadline: [date: '2026-08-07']]])
        def feedback = new DeadlineRiskFeedback(gateway, zone)
        def action = new DeadlineRiskFeedback.Action(1, 'prop', LocalDate.of(2026, 8, 21), null)
        def preflight = feedback.preflight(inventory, [action], ['t1': gateway.getTask('t1')], 'prop', policy.identity())

        when:
        def first = feedback.execute(preflight)
        def second = feedback.execute(preflight, [first.outcomes[0].commandId] as Set)

        then:
        gateway.deadlineUpdates.size() == 1
        gateway.deadlineUpdates[0].deadlineIso == '2026-08-21'
        second.outcomes[0].detail == 'already applied'
    }

    def "one verified write survives a later rejection without replay or rollback"() {
        given:
        def inventory = inventory()
        def gateway = new InMemoryTodoistGateway([
            [id: 't1', priority: 1, deadline: [date: '2026-08-07']],
            [id: 't2', priority: 4, deadline: [date: '2026-08-08']]
        ])
        gateway.metaClass.updateTaskPriority = { String id, int priority ->
            if (id == 't2') throw new RuntimeException('rejected by provider')
            InMemoryTodoistGateway.declaredMethods.find { it.name == 'updateTaskPriority' }
        }
        def feedback = new DeadlineRiskFeedback(gateway, zone)
        def preflight = feedback.preflight(inventory, [
            new DeadlineRiskFeedback.Action(1, 'prop', null, 3),
            new DeadlineRiskFeedback.Action(2, 'prop', null, 2)
        ], ['t1': gateway.getTask('t1'), 't2': gateway.getTask('t2')], 'prop', policy.identity())

        when:
        gateway.metaClass = null
        def throwing = new DeadlineRiskFeedback(new PriorityFailingGateway(gateway, 't2'), zone)
        def execution = throwing.execute(preflight)

        then:
        execution.outcomes*.state == ['verified', 'rejected']
        gateway.priorityUpdates*.taskId == ['t1']
        gateway.getTask('t1').priority == 3
        gateway.getTask('t2').priority == 4
    }

    private static class PriorityFailingGateway extends InMemoryTodoistGateway {
        private final InMemoryTodoistGateway delegate
        private final String failId
        PriorityFailingGateway(InMemoryTodoistGateway delegate, String failId) {
            super(delegate.fetchTasks())
            this.delegate = delegate
            this.failId = failId
        }
        @Override void updateTaskPriority(String taskId, int apiPriority) {
            if (taskId == failId) throw new RuntimeException('rejected by provider')
            delegate.updateTaskPriority(taskId, apiPriority)
        }
        @Override void updateTaskDeadline(String taskId, String deadlineIso) {
            delegate.updateTaskDeadline(taskId, deadlineIso)
        }
    }
}
