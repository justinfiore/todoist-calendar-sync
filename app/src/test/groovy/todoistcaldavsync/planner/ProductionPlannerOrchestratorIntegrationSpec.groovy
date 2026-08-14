package todoistcaldavsync.planner

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.InMemoryCalendarGateway
import todoistcaldavsync.planner.adapters.InMemoryTodoistGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.state.PlanStore

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ProductionPlannerOrchestratorIntegrationSpec extends Specification {
    Instant start = Instant.parse('2026-08-13T09:00:00Z')
    Instant end = Instant.parse('2026-08-13T17:00:00Z')

    private Map root(String mode, File stateRoot) {
        [planner: [mode: mode, timezone: 'UTC', output_calendar: 'Planned',
            availability: [working_windows: [weekday: ['09:00-17:00']],
                calendars: [[calendar: 'Planned', default_role: 'managed_output']]],
            tasks: [scheduling_eligible_labels: ['schedule'], default_duration_minutes: 30],
            stability: [freeze_within: 'PT2H', require_approval_for_move_within: 'P1D'],
            messaging: [enabled: false], ai: [enabled: false],
            integration: [
                todoist: [base_url: 'https://api.todoist.com/api/v1', token_env: 'TEST_TOKEN'],
                caldav: [calendars: [[name: 'Planned', url: 'https://calendar.example.test/planned', auth: [type: 'none']]]],
                feedback: [allowed_actors: ['alice']],
                state: [plans_dir: new File(stateRoot, 'plans').path,
                    applications_dir: new File(stateRoot, 'applications').path,
                    decisions_dir: new File(stateRoot, 'decisions').path,
                    deliveries_dir: new File(stateRoot, 'deliveries').path]
            ]
        ]]
    }

    private InMemoryTodoistGateway todoist(List<Map> rows = null) {
        new InMemoryTodoistGateway(rows ?: [[id: 't1', content: 'Write report', project_id: 'p1',
            project_name: 'Work', labels: ['schedule'], priority: 4,
            duration: [amount: 30, unit: 'minute']]])
    }

    private ProductionPlannerOrchestrator orchestrator(Map root, InMemoryTodoistGateway td,
                                                       InMemoryCalendarGateway cal, Instant now = start) {
        def config = PlannerConfig.fromMap(root)
        def integration = ProductionIntegrationConfig.fromMap(root, Path.of('.').toAbsolutePath())
        new ProductionPlannerOrchestrator(config, integration, td, td, cal, cal, { now })
    }

    def "live capacity and persisted deterministic preview preserve previous-plan stability with zero writes"() {
        given:
        File stateRoot = Files.createTempDirectory('phase7-preview-').toFile()
        Map cfg = root('preview', stateRoot)
        def td = todoist()
        def cal = new InMemoryCalendarGateway('Planned', true, [])
        def first = orchestrator(cfg, td, cal, start)

        when:
        String capacity = first.capacity(start, end, 'json')
        Plan plan1 = first.preview(start, end)
        def applyReceipt = first.apply(plan1.id)

        then:
        capacity.contains('"taskDemandMinutes"')
        plan1.scheduledBlocks*.taskIds.flatten() == ['t1']
        PlanStore.toJson(first.planStore.load(plan1.id)) == PlanStore.toJson(plan1)
        applyReceipt.overallStatus.wire == 'skipped_preview'
        td.dueUpdates.empty
        cal.upserts.empty

        when: 'a later crawl automatically uses the latest stored plan as stability baseline'
        def second = orchestrator(cfg, td, cal, start.plusSeconds(1800))
        Plan plan2 = second.preview(start, end)

        then:
        plan2.id != plan1.id
        plan2.scheduledBlocks[0].start == plan1.scheduledBlocks[0].start
        plan2.changes.any { it.taskId == 't1' && it.type == 'keep' }
        PlanStore.toJson(second.planStore.load(plan2.id)) == PlanStore.toJson(plan2)
        td.dueUpdates.empty
        cal.upserts.empty
    }

    def "approval_required binds exact plan hash and only explicit accepted decision applies"() {
        given:
        File stateRoot = Files.createTempDirectory('phase7-approval-').toFile()
        Map cfg = root('approval_required', stateRoot)
        def td = todoist()
        def cal = new InMemoryCalendarGateway('Planned', true, [])
        def app = orchestrator(cfg, td, cal)
        Plan plan = app.preview(start, end)
        String hash = PlanHash.compute(plan)
        String proposal = Proposal.fromPlan(plan).id

        expect: 'missing approval is a durable zero-write refusal'
        app.apply(plan.id).overallStatus.wire == 'skipped_unapproved'
        td.dueUpdates.empty
        cal.upserts.empty

        when: 'tampered approval cannot authorize'
        def bad = Approval.builder().id('bad').planId(plan.id).planVersion(plan.version)
            .planHash('0' * 64).approvedAt(start).approvedBy('alice').build()
        def badReceipt = app.apply(plan.id, bad)

        then:
        badReceipt.overallStatus.wire == 'skipped_unapproved'
        td.dueUpdates.empty
        cal.upserts.empty

        when: 'feedback persists an exact accepted decision but does not auto-apply'
        def feedback = app.feedback(plan.id, "approve ${proposal} ${hash}", 'alice', 'corr-approve')

        then:
        feedback.accepted
        feedback.approval.planHash == hash
        td.dueUpdates.empty
        cal.upserts.empty

        when: 'the explicit decision-apply operation revalidates and applies'
        def result = app.applyDecision(plan.id, feedback.decision.id)

        then:
        result.status.name() == 'APPLIED'
        td.dueUpdates*.taskId == ['t1']
        td.deadlineUpdates.empty
        cal.upserts.size() == 1
        ManagedEventIds.isOwned(cal.upserts[0], 'Planned')
    }

    def "apply_safe_changes applies ordinary blocks while withholding approval-required blocks"() {
        given:
        File stateRoot = Files.createTempDirectory('phase7-safe-').toFile()
        Map cfg = root('apply_safe_changes', stateRoot)
        def rows = [
            [id: 'safe', content: 'Safe', labels: ['schedule'], priority: 4],
            [id: 'protected', content: 'Protected', labels: ['schedule'], priority: 3]
        ]
        def td = todoist(rows)
        def cal = new InMemoryCalendarGateway('Planned', true, [])
        def app = orchestrator(cfg, td, cal)
        Task safeTask = Task.fromTodoistMap(rows[0], app.plannerConfig.durationResolver, 'manual')
        Task protectedTask = Task.fromTodoistMap(rows[1], app.plannerConfig.durationResolver, 'manual')
        def safeBlock = ScheduledBlock.builder().id('block-safe').start(start).end(start.plusSeconds(1800))
            .taskIds(['safe']).title('Safe').build()
        def protectedBlock = ScheduledBlock.builder().id('block-protected').start(start.plusSeconds(3600))
            .end(start.plusSeconds(5400)).taskIds(['protected']).title('Protected')
            .metadata([approvalRequired: true]).build()
        Plan plan = Plan.builder().id('safe-plan').version(1).createdAt(start).mode('apply_safe_changes')
            .tasks([safeTask, protectedTask]).scheduledBlocks([safeBlock, protectedBlock]).build()
        app.planStore.save(plan)

        when:
        def receipt = app.applySafe(plan.id)

        then:
        td.dueUpdates*.taskId == ['safe']
        td.deadlineUpdates.empty
        cal.upserts.size() == 1
        receipt.items.find { it.taskId == 'protected' }.calendarStatus.wire == 'skipped_unapproved'
        receipt.metadata.writeCount == 1
    }

    def "fully_automated remains refused with no Todoist or calendar writes"() {
        given:
        File stateRoot = Files.createTempDirectory('phase7-full-').toFile()
        Map cfg = root('fully_automated', stateRoot)
        def td = todoist()
        def cal = new InMemoryCalendarGateway('Planned', true, [])
        def app = orchestrator(cfg, td, cal)
        Plan plan = app.preview(start, end)

        when:
        def receipt = app.apply(plan.id)
        def safeReceipt = app.applySafe(plan.id)

        then:
        receipt.overallStatus.wire == 'skipped_unapproved'
        safeReceipt.metadata.refused == true
        td.dueUpdates.empty
        td.deadlineUpdates.empty
        cal.upserts.empty
        cal.deletes.empty
    }
}
