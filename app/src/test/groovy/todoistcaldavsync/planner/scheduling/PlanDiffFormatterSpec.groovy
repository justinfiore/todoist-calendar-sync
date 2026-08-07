package todoistcaldavsync.planner.scheduling

import spock.lang.Specification
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.ScheduledBlock

import java.time.Instant
import java.time.ZoneId

class PlanDiffFormatterSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')

    def "Moved section renders approval-required status and reason from metadata"() {
        given:
        def prev = Instant.parse('2026-08-06T20:00:00Z')
        def neu = Instant.parse('2026-08-06T13:00:00Z')
        def plan = Plan.builder()
            .id('diff-approval')
            .createdAt(Instant.parse('2026-08-06T12:00:00Z'))
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('b1')
                    .start(neu)
                    .end(neu.plusSeconds(1800))
                    .taskIds(['t-move'])
                    .title('Moved task')
                    .reason('score')
                    .build()
            ])
            .changes([
                PlanChange.builder()
                    .id('chg-1')
                    .type('move')
                    .taskId('t-move')
                    .previousStart(prev)
                    .previousEnd(prev.plusSeconds(1800))
                    .newStart(neu)
                    .newEnd(neu.plusSeconds(1800))
                    .reason('Best feasible slot')
                    .metadata([
                        approvalRequired: true,
                        approvalReason  : 'move_within_require_approval_horizon'
                    ])
                    .build()
            ])
            .build()

        when:
        def md = PlanDiffFormatter.toMarkdown(plan, zone)

        then:
        md.contains('## Moved')
        md.contains('t-move')
        md.contains('Approval required:')
        md.toLowerCase().contains('require-approval horizon') ||
            md.toLowerCase().contains('require approval')
    }
}
