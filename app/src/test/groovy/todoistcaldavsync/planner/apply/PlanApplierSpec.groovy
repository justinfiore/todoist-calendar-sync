package todoistcaldavsync.planner.apply

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.InMemoryCalendarGateway
import todoistcaldavsync.planner.adapters.InMemoryTodoistGateway
import todoistcaldavsync.planner.adapters.ManagedCalendarWriteGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.ApplyItemStatus
import todoistcaldavsync.planner.domain.Approval
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.EventRole
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.domain.MemberInterval
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.state.PlanStoreException

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 3 apply matrix: managed calendar writes + Todoist due sync with approval gates,
 * idempotency, partial failure recovery, drift preservation, ownership safety.
 * No real remote account writes.
 */
class PlanApplierSpec extends Specification {

    static final String MANAGED_CAL = 'Todoist Planned'
    static final ZoneId ZONE = ZoneId.of('America/New_York')

    Path dir
    InMemoryCalendarGateway calendar
    InMemoryTodoistGateway todoist
    ApplicationStateStore stateStore
    PlannerConfig config
    AtomicReference<Instant> clock

    def setup() {
        dir = Files.createTempDirectory('plan-applier-spec')
        calendar = new InMemoryCalendarGateway(MANAGED_CAL, true)
        todoist = new InMemoryTodoistGateway([
            [id: 't1', content: 'Task One', priority: 2,
             deadline: [date: '2026-08-20'], due: [date: '2026-08-01T10:00:00Z']],
            [id: 't2', content: 'Task Two', priority: 2,
             deadline: [date: '2026-08-21'], due: [date: '2026-08-01T11:00:00Z']],
            [id: 't3', content: 'Task Three', priority: 1,
             deadline: [date: '2026-08-22']]
        ])
        stateStore = new ApplicationStateStore(dir)
        config = baseConfig('approval_required')
        clock = new AtomicReference<>(Instant.parse('2026-08-07T15:00:00Z'))
    }

    def cleanup() {
        dir?.toFile()?.deleteDir()
    }

    private PlannerConfig baseConfig(String mode) {
        PlannerConfig.fromMap(planner: [
            mode           : mode,
            timezone       : 'America/New_York',
            output_calendar: MANAGED_CAL,
            availability   : [
                working_windows: [weekday: ['09:00-17:00']],
                calendars      : [
                    [calendar: MANAGED_CAL, default_role: 'managed_output']
                ]
            ],
            stability      : [keep_manual_moves: true]
        ])
    }

    private PlanApplier applier(PlannerConfig cfg = config) {
        new PlanApplier(
            cfg,
            new ManagedCalendarWriteGateway(calendar, MANAGED_CAL),
            calendar,
            todoist,
            todoist,
            stateStore,
            { clock.get() }
        )
    }

    private static Task task(String id, String content = id) {
        Task.builder().id(id).content(content).priority(2)
            .effectiveDuration(Duration.ofMinutes(30)).durationSource('test')
            .deadline(Instant.parse('2026-08-20T23:59:59Z'))
            .build()
    }

    private static ScheduledBlock singleBlock(String blockId, String taskId, Instant start,
                                              int minutes = 30, Map meta = [:]) {
        Instant end = start + Duration.ofMinutes(minutes)
        ScheduledBlock.builder()
            .id(blockId)
            .start(start)
            .end(end)
            .taskIds([taskId])
            .title("Block ${blockId}")
            .reason('test')
            .metadata(meta)
            .build()
    }

    private static ScheduledBlock focusBlock(String blockId, List<String> taskIds,
                                             Instant start, List<Integer> mins) {
        Instant cursor = start
        List<MemberInterval> members = []
        taskIds.eachWithIndex { String tid, int i ->
            Instant end = cursor + Duration.ofMinutes(mins[i])
            members << new MemberInterval(tid, cursor, end)
            cursor = end
        }
        ScheduledBlock.builder()
            .id(blockId)
            .start(start)
            .end(cursor)
            .taskIds(taskIds)
            .memberIntervals(members)
            .title('Focus')
            .focusBlock(true)
            .reason('batch')
            .build()
    }

    private Plan planWith(List<ScheduledBlock> blocks, String mode = 'approval_required',
                          List<PlanChange> changes = [], String id = 'plan-1', int version = 1) {
        List<Task> tasks = blocks.collectMany { b -> b.taskIds.collect { task(it) } }
            .unique { it.id }
        Plan.builder()
            .id(id)
            .version(version)
            .createdAt(Instant.parse('2026-08-07T12:00:00Z'))
            .mode(mode)
            .tasks(tasks)
            .scheduledBlocks(blocks)
            .changes(changes)
            .build()
    }

    private Approval approvalFor(Plan plan, String approver = 'jorsten') {
        Approval.builder()
            .id('appr-1')
            .planId(plan.id)
            .planVersion(plan.version)
            .planHash(PlanHash.compute(plan))
            .approvedAt(Instant.parse('2026-08-07T14:00:00Z'))
            .approvedBy(approver)
            .build()
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    def "approved single task writes managed event and sets Todoist due to exact DTSTART"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.success()
        receipt.overallStatus == ApplyItemStatus.APPLIED
        calendar.upserts.size() == 1
        def ev = calendar.upserts[0]
        ev.calendarName == MANAGED_CAL
        ev.start == start
        ev.end == start + Duration.ofMinutes(30)
        ManagedEventIds.isPlannerUid(ev.uid)
        ManagedEventIds.hasOwnershipMarker(ev.description)
        ev.role == EventRole.MANAGED_OUTPUT

        todoist.dueUpdates.size() == 1
        todoist.dueUpdates[0].taskId == 't1'
        todoist.dueUpdates[0].dueDateTimeIso == start.toString()
        !todoist.dueUpdates[0].hasDeadlineField
        todoist.dueUpdates[0].fields.keySet() == ['due'] as Set

        // deadline preserved
        todoist.deadlineOf('t1') == '2026-08-20' || todoist.deadlineOf('t1')?.toString()?.contains('2026-08-20')
        todoist.deadlineUpdates.isEmpty()

        def mapping = stateStore.loadMapping('t1')
        mapping.eventUid == ev.uid
        mapping.slotStart == start
        mapping.fullyApplied()
        mapping.planId == plan.id
        mapping.planHash == PlanHash.compute(plan)
        mapping.approvalId == approval.id
    }

    def "approved multiple tasks and focus block: aggregate calendar + per-task Todoist dues"() {
        given:
        def start = Instant.parse('2026-08-11T13:00:00Z')
        def focus = focusBlock('fb1', ['t1', 't2'], start, [20, 10])
        def single = singleBlock('b2', 't3', Instant.parse('2026-08-11T16:00:00Z'), 30)
        def plan = planWith([focus, single])
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.APPLIED
        calendar.upserts.size() == 2
        def focusEv = calendar.getByUid(ManagedEventIds.uidForBlock('fb1'))
        focusEv.start == start
        focusEv.end == start + Duration.ofMinutes(30)
        focusEv.title == 'Focus'

        todoist.dueUpdates.size() == 3
        todoist.dueUpdates.find { it.taskId == 't1' }.dueDateTimeIso == start.toString()
        todoist.dueUpdates.find { it.taskId == 't2' }.dueDateTimeIso == (start + Duration.ofMinutes(20)).toString()
        todoist.dueUpdates.find { it.taskId == 't3' }.dueDateTimeIso == Instant.parse('2026-08-11T16:00:00Z').toString()

        stateStore.loadMapping('t1').eventUid == ManagedEventIds.uidForBlock('fb1')
        stateStore.loadMapping('t2').eventUid == ManagedEventIds.uidForBlock('fb1')
        stateStore.loadMapping('t3').eventUid == ManagedEventIds.uidForBlock('b2')
        stateStore.loadMapping('t1').eventUid == stateStore.loadMapping('t2').eventUid
    }

    // -------------------------------------------------------------------------
    // Gates
    // -------------------------------------------------------------------------

    def "preview mode never writes calendar or Todoist"() {
        given:
        config = baseConfig('preview')
        def plan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T14:00:00Z'))], 'preview')
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_PREVIEW
        calendar.upserts.isEmpty()
        todoist.dueUpdates.isEmpty()
        stateStore.loadMappings().isEmpty()
    }

    def "missing approval causes zero writes"() {
        given:
        def plan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T14:00:00Z'))])

        when:
        def receipt = applier().apply(plan, null)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        calendar.upserts.isEmpty()
        todoist.dueUpdates.isEmpty()
    }

    def "wrong plan id approval is rejected"() {
        given:
        def plan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T14:00:00Z'))])
        def bad = Approval.builder()
            .id('x').planId('other').planVersion(1).planHash(PlanHash.compute(plan))
            .approvedAt(Instant.parse('2026-08-07T14:00:00Z')).approvedBy('u').build()

        when:
        def receipt = applier().apply(plan, bad)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        calendar.upserts.isEmpty()
        todoist.dueUpdates.isEmpty()
    }

    def "stale approval version is rejected"() {
        given:
        def plan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T14:00:00Z'))], 'approval_required', [], 'plan-1', 2)
        def stale = Approval.builder()
            .id('x').planId(plan.id).planVersion(1).planHash(PlanHash.compute(plan))
            .approvedAt(Instant.parse('2026-08-07T14:00:00Z')).approvedBy('u').build()

        when:
        def receipt = applier().apply(plan, stale)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        calendar.upserts.isEmpty()
    }

    def "tampered plan hash approval is rejected"() {
        given:
        def plan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T14:00:00Z'))])
        def bad = Approval.builder()
            .id('x').planId(plan.id).planVersion(1).planHash('0' * 64)
            .approvedAt(Instant.parse('2026-08-07T14:00:00Z')).approvedBy('u').build()

        when:
        def receipt = applier().apply(plan, bad)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        calendar.upserts.isEmpty()
    }

    def "fully_automated mode refuses writes in Phase 3"() {
        given:
        config = baseConfig('fully_automated')
        def plan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T14:00:00Z'))], 'fully_automated')

        when:
        def receipt = applier().apply(plan, null)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        calendar.upserts.isEmpty()
    }

    def "frozen block without explicit approval is not applied"() {
        given:
        config = baseConfig('apply_safe_changes')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def block = ScheduledBlock.builder()
            .id('bf').start(start).end(start + Duration.ofMinutes(30))
            .taskIds(['t1']).title('Frozen').frozen(true).reason('freeze').build()
        def plan = planWith([block], 'apply_safe_changes')

        when:
        def receipt = applier().apply(plan, null)

        then:
        calendar.upserts.isEmpty()
        todoist.dueUpdates.isEmpty()
        receipt.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED }
    }

    def "approvalRequired change without approval is skipped in apply_safe_changes"() {
        given:
        config = baseConfig('apply_safe_changes')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def changes = [
            PlanChange.builder().id('c1').type('move').taskId('t1').reason('near-term')
                .newStart(start).metadata([approvalRequired: true]).build()
        ]
        def plan = planWith([singleBlock('b1', 't1', start)], 'apply_safe_changes', changes)

        when:
        def receipt = applier().apply(plan, null)

        then:
        calendar.upserts.isEmpty()
        receipt.items[0].calendarStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
    }

    def "apply_safe_changes applies non-protected block without approval"() {
        given:
        config = baseConfig('apply_safe_changes')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)], 'apply_safe_changes')

        when:
        def receipt = applier().apply(plan, null)

        then:
        receipt.overallStatus == ApplyItemStatus.APPLIED
        calendar.upserts.size() == 1
        todoist.dueUpdates.size() == 1
    }

    // -------------------------------------------------------------------------
    // apply_safe_changes approval binding (protected escalation)
    // -------------------------------------------------------------------------

    def "apply_safe_changes wrong plan id approval never escalates protected; surfaces audit; safe still applies"() {
        given:
        config = baseConfig('apply_safe_changes')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def frozen = ScheduledBlock.builder()
            .id('bf').start(start).end(start + Duration.ofMinutes(30))
            .taskIds(['t1']).title('Frozen').frozen(true).reason('freeze').build()
        def safe = singleBlock('bs', 't2', start + Duration.ofHours(1))
        def plan = planWith([frozen, safe], 'apply_safe_changes')
        def bad = Approval.builder()
            .id('bad-id').planId('other-plan').planVersion(plan.version)
            .planHash(PlanHash.compute(plan))
            .approvedAt(Instant.parse('2026-08-07T14:00:00Z')).approvedBy('u').build()

        when:
        def receipt = applier().apply(plan, bad)

        then:
        calendar.upserts.size() == 1
        calendar.upserts[0].uid == ManagedEventIds.uidForBlock('bs')
        todoist.dueUpdates.size() == 1
        todoist.dueUpdates[0].taskId == 't2'
        receipt.items.find { it.taskId == 't1' }.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED
        receipt.items.find { it.taskId == 't2' }.calendarStatus == ApplyItemStatus.APPLIED
        receipt.overallStatus == ApplyItemStatus.PARTIAL
        receipt.errors.any { it.contains('planId mismatch') }
        receipt.metadata.explicitApproval == false
        receipt.metadata.approvalValid == false
        receipt.metadata.approvalInvalidReason.toString().contains('planId mismatch')
        receipt.metadata.protectedWithheld == true
        receipt.metadata.safeChangesApplied == true
    }

    def "apply_safe_changes stale approval version never escalates protected"() {
        given:
        config = baseConfig('apply_safe_changes')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def frozen = ScheduledBlock.builder()
            .id('bf').start(start).end(start + Duration.ofMinutes(30))
            .taskIds(['t1']).title('Frozen').frozen(true).reason('freeze').build()
        def safe = singleBlock('bs', 't2', start + Duration.ofHours(1))
        def plan = planWith([frozen, safe], 'apply_safe_changes', [], 'plan-1', 3)
        def stale = Approval.builder()
            .id('stale').planId(plan.id).planVersion(1)
            .planHash(PlanHash.compute(plan))
            .approvedAt(Instant.parse('2026-08-07T14:00:00Z')).approvedBy('u').build()

        when:
        def receipt = applier().apply(plan, stale)

        then:
        calendar.upserts.size() == 1
        todoist.dueUpdates*.taskId == ['t2']
        receipt.items.find { it.taskId == 't1' }.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED
        receipt.errors.any { it.contains('planVersion') }
        receipt.metadata.explicitApproval == false
        receipt.metadata.approvalValid == false
    }

    def "apply_safe_changes tampered plan hash never escalates protected or approvalRequired"() {
        given:
        config = baseConfig('apply_safe_changes')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def changes = [
            PlanChange.builder().id('c1').type('move').taskId('t1').reason('near-term')
                .newStart(start).metadata([approvalRequired: true]).build()
        ]
        def safe = singleBlock('bs', 't2', start + Duration.ofHours(1))
        def plan = planWith([singleBlock('b1', 't1', start), safe], 'apply_safe_changes', changes)
        def bad = Approval.builder()
            .id('x').planId(plan.id).planVersion(plan.version).planHash('0' * 64)
            .approvedAt(Instant.parse('2026-08-07T14:00:00Z')).approvedBy('u').build()

        when:
        def receipt = applier().apply(plan, bad)

        then:
        calendar.upserts.size() == 1
        calendar.upserts[0].uid == ManagedEventIds.uidForBlock('bs')
        todoist.dueUpdates*.taskId == ['t2']
        receipt.items.find { it.taskId == 't1' }.calendarStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        receipt.items.find { it.taskId == 't2' }.fullyApplied()
        receipt.errors.any { it.contains('planHash mismatch') }
        receipt.metadata.explicitApproval == false
        receipt.metadata.approvalValid == false
        receipt.overallStatus == ApplyItemStatus.PARTIAL
    }

    def "apply_safe_changes valid approval applies protected frozen and approvalRequired items"() {
        given:
        config = baseConfig('apply_safe_changes')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def frozen = ScheduledBlock.builder()
            .id('bf').start(start).end(start + Duration.ofMinutes(30))
            .taskIds(['t1']).title('Frozen').frozen(true).reason('freeze').build()
        def changes = [
            PlanChange.builder().id('c1').type('move').taskId('t2').reason('near-term')
                .newStart(start + Duration.ofHours(1)).metadata([approvalRequired: true]).build()
        ]
        def needsAppr = singleBlock('ba', 't2', start + Duration.ofHours(1))
        def safe = singleBlock('bs', 't3', start + Duration.ofHours(2))
        def plan = planWith([frozen, needsAppr, safe], 'apply_safe_changes', changes)
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.APPLIED
        calendar.upserts.size() == 3
        todoist.dueUpdates.size() == 3
        receipt.metadata.explicitApproval == true
        receipt.metadata.approvalValid == true
        !receipt.errors.any { it.toString().contains('mismatch') }
    }

    def "apply_safe_changes invalid approval with only protected items writes nothing and audits"() {
        given:
        config = baseConfig('apply_safe_changes')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def frozen = ScheduledBlock.builder()
            .id('bf').start(start).end(start + Duration.ofMinutes(30))
            .taskIds(['t1']).title('Frozen').frozen(true).reason('freeze').build()
        def plan = planWith([frozen], 'apply_safe_changes')
        def bad = Approval.builder()
            .id('x').planId(plan.id).planVersion(plan.version).planHash('deadbeef' * 8)
            .approvedAt(Instant.parse('2026-08-07T14:00:00Z')).approvedBy('u').build()

        when:
        def receipt = applier().apply(plan, bad)

        then:
        calendar.upserts.isEmpty()
        todoist.dueUpdates.isEmpty()
        receipt.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED }
        receipt.errors.any { it.contains('planHash mismatch') }
        receipt.metadata.approvalValid == false
        receipt.metadata.explicitApproval == false
        receipt.metadata.writeCount == 0
    }

    // -------------------------------------------------------------------------
    // Idempotency + deterministic UID
    // -------------------------------------------------------------------------

    def "deterministic UID stable across reruns and second apply is idempotent"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        def expectedUid = ManagedEventIds.uidForBlock('b1')

        when:
        def r1 = applier().apply(plan, approval)
        clock.set(Instant.parse('2026-08-07T16:00:00Z'))
        def r2 = applier().apply(plan, approval)

        then:
        r1.overallStatus == ApplyItemStatus.APPLIED
        r2.overallStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
        calendar.upserts.size() == 1
        calendar.upserts[0].uid == expectedUid
        todoist.dueUpdates.size() == 1
        stateStore.loadMapping('t1').eventUid == expectedUid
        ManagedEventIds.uidForBlock('b1') == ManagedEventIds.uidForBlock('b1')
    }

    // -------------------------------------------------------------------------
    // Deadline preservation
    // -------------------------------------------------------------------------

    def "Todoist update request shape never includes deadline field"() {
        given:
        def beforeDeadline = todoist.deadlineOf('t1')
        def start = Instant.parse('2026-08-10T18:30:00Z') // DST-relevant wall in US
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)

        when:
        applier().apply(plan, approval)

        then:
        todoist.dueUpdates.every { !it.hasDeadlineField && !it.fields.containsKey('deadline') }
        todoist.deadlineUpdates.isEmpty()
        todoist.deadlineOf('t1') == beforeDeadline
        todoist.dueUpdates[0].dueDateTimeIso == '2026-08-10T18:30:00Z'
    }

    // -------------------------------------------------------------------------
    // Partial failure + reconciliation
    // -------------------------------------------------------------------------

    def "calendar success and Todoist failure records PARTIAL and reconcile completes without duplicate calendar write"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        todoist.failDueOnCall = 1

        when:
        def r1 = applier().apply(plan, approval)

        then:
        r1.overallStatus == ApplyItemStatus.PARTIAL
        calendar.upserts.size() == 1
        todoist.dueUpdates.size() == 1 // attempted
        def m1 = stateStore.loadMapping('t1')
        m1.calendarApplied()
        m1.todoistStatus == ApplyItemStatus.FAILED
        !r1.success()

        when: // reconcile after fixing Todoist
        todoist.resetCounters()
        todoist.failDueOnCall = null
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T16:00:00Z'))
        def calWritesBefore = calendar.upserts.size()
        def r2 = applier().reconcile(plan, approval)

        then:
        r2.overallStatus == ApplyItemStatus.APPLIED
        calendar.upserts.size() == calWritesBefore // no duplicate calendar write
        todoist.dueUpdates.size() == 1 // completed missing side only
        stateStore.loadMapping('t1').fullyApplied()
        // audit trail: two receipts
        stateStore.listReceiptIds().size() == 2
        stateStore.listReceipts()*.overallStatus.contains(ApplyItemStatus.PARTIAL)
    }

    def "calendar failure before Todoist means no Todoist update"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        calendar.failUpsertOnCall = 1

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.FAILED
        calendar.upserts.isEmpty()
        todoist.dueUpdates.isEmpty()
        def m = stateStore.loadMapping('t1')
        m == null || m.todoistStatus == ApplyItemStatus.PENDING
    }

    def "failed apply is not skipped as already-applied on retry"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        calendar.failUpsertOnCall = 1
        applier().apply(plan, approval)

        when:
        calendar.resetCounters()
        calendar.failUpsertOnCall = null
        clock.set(Instant.parse('2026-08-07T17:00:00Z'))
        def r2 = applier().apply(plan, approval)

        then:
        r2.overallStatus == ApplyItemStatus.APPLIED
        calendar.upserts.size() == 1
        todoist.dueUpdates.size() == 1
        !r2.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT }
    }

    // -------------------------------------------------------------------------
    // Drift / manual override
    // -------------------------------------------------------------------------

    def "manual calendar move is detected and preserved by default"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        def uid = ManagedEventIds.uidForBlock('b1')
        // User moves managed event
        def movedStart = Instant.parse('2026-08-10T16:00:00Z')
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Block b1')
            .description(ManagedEventIds.buildDescription('b1', plan.id))
            .calendarName(MANAGED_CAL)
            .start(movedStart).end(movedStart + Duration.ofMinutes(30))
            .role(EventRole.MANAGED_OUTPUT)
            .build())

        def newPlan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T18:00:00Z'))],
            'approval_required', [], 'plan-2', 1)
        def newApproval = approvalFor(newPlan)

        when:
        def beforeUpserts = calendar.upserts.size()
        def beforeDue = todoist.dueUpdates.size()
        def receipt = applier().apply(newPlan, newApproval)

        then:
        receipt.drifts.any { it.type == 'manual_calendar_move' }
        receipt.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
        calendar.upserts.size() == beforeUpserts
        todoist.dueUpdates.size() == beforeDue
        // event remains at user-moved time
        calendar.getByUid(uid).start == movedStart
    }

    def "manual Todoist due change is detected and preserved"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        // User changes Todoist due away from applied and away from new proposal
        todoist.updateTaskDue('t1', '2026-08-10T19:00:00Z')
        todoist.dueUpdates.clear()

        def newPlan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T18:00:00Z'))],
            'approval_required', [], 'plan-3', 1)
        def newApproval = approvalFor(newPlan)

        when:
        def receipt = applier().apply(newPlan, newApproval)

        then:
        receipt.drifts.any { it.type == 'manual_todoist_due_change' && it.taskId == 't1' }
        receipt.items.every { it.todoistStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
        todoist.dueUpdates.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Ownership / external collision
    // -------------------------------------------------------------------------

    def "unowned and wrong-calendar writes are rejected without mutation"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')

        when:
        calendar.upsertEvent(CalendarEvent.builder()
            .id('ext-1').uid('external-uid@example.com').title('X')
            .description('no marker').calendarName(MANAGED_CAL)
            .start(start).end(start + Duration.ofMinutes(30)).build())
        then:
        thrown(IllegalStateException)
        calendar.getByUid('external-uid@example.com') == null

        when:
        calendar.upsertEvent(CalendarEvent.builder()
            .id('p').uid(ManagedEventIds.uidForBlock('other')).title('X')
            .description(ManagedEventIds.buildDescription('other', 'p'))
            .calendarName('Work')
            .start(start).end(start + Duration.ofMinutes(30)).build())
        then:
        thrown(IllegalStateException)
        calendar.getByUid(ManagedEventIds.uidForBlock('other')) == null
    }

    def "existing external event with non-planner uid is not deleted or adopted"() {
        given:
        def extUid = 'external-collision@example.com'
        calendar.seed(CalendarEvent.builder()
            .id(extUid).uid(extUid).title('External Meeting')
            .description('not planner')
            .calendarName(MANAGED_CAL)
            .start(Instant.parse('2026-08-10T10:00:00Z'))
            .end(Instant.parse('2026-08-10T11:00:00Z'))
            .build())
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.APPLIED
        // external event untouched
        calendar.getByUid(extUid).title == 'External Meeting'
        calendar.getByUid(extUid).start == Instant.parse('2026-08-10T10:00:00Z')
        // planner wrote its own uid only
        calendar.getByUid(ManagedEventIds.uidForBlock('b1')) != null
        calendar.deletes.isEmpty()
    }

    def "deterministic planner UID without ownership marker refuses adoption and preserves external event"() {
        given:
        def blockId = 'b1'
        def uid = ManagedEventIds.uidForBlock(blockId)
        def existingStart = Instant.parse('2026-08-10T10:00:00Z')
        def existingEnd = Instant.parse('2026-08-10T11:00:00Z')
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Pre-existing same UID')
            .description('no planner marker; foreign content')
            .calendarName(MANAGED_CAL)
            .start(existingStart).end(existingEnd)
            .build())
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock(blockId, 't1', start)])
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.FAILED ||
            receipt.items.every { it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID }
        receipt.items.every { it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID }
        receipt.items.every { it.todoistStatus == ApplyItemStatus.PENDING }
        receipt.errors.any { it.contains('external_uid_collision') && it.contains('missing_ownership_marker') }
        calendar.upserts.isEmpty()
        calendar.deletes.isEmpty()
        todoist.dueUpdates.isEmpty()
        calendar.getByUid(uid).title == 'Pre-existing same UID'
        calendar.getByUid(uid).start == existingStart
        calendar.getByUid(uid).end == existingEnd
        calendar.getByUid(uid).description == 'no planner marker; foreign content'
        stateStore.loadMapping('t1') == null || !stateStore.loadMapping('t1').calendarApplied()

        when: // rerun remains refusal, not idempotent adoption
        clock.set(Instant.parse('2026-08-07T16:00:00Z'))
        def r2 = applier().apply(plan, approval)

        then:
        r2.items.every { it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID }
        calendar.upserts.isEmpty()
        calendar.deletes.isEmpty()
        todoist.dueUpdates.isEmpty()
        calendar.getByUid(uid).title == 'Pre-existing same UID'
        calendar.getByUid(uid).start == existingStart
    }

    def "deterministic planner UID on wrong calendar refuses overwrite and preserves event"() {
        given:
        def blockId = 'b-wrong-cal'
        def uid = ManagedEventIds.uidForBlock(blockId)
        def existingStart = Instant.parse('2026-08-10T09:00:00Z')
        def existingEnd = Instant.parse('2026-08-10T09:45:00Z')
        // Same deterministic UID + even has marker, but wrong calendar → not owned
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Work calendar collision')
            .description(ManagedEventIds.buildDescription(blockId, 'foreign-plan'))
            .calendarName('Work')
            .start(existingStart).end(existingEnd)
            .build())
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock(blockId, 't1', start)])
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.items.every { it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID }
        receipt.errors.any { it.contains('external_uid_collision') && it.contains('wrong_calendar') }
        calendar.upserts.isEmpty()
        calendar.deletes.isEmpty()
        todoist.dueUpdates.isEmpty()
        def preserved = calendar.getByUid(uid)
        preserved.calendarName == 'Work'
        preserved.title == 'Work calendar collision'
        preserved.start == existingStart
        preserved.end == existingEnd

        when: // second run still refuses
        clock.set(Instant.parse('2026-08-07T17:00:00Z'))
        def r2 = applier().apply(plan, approval)

        then:
        r2.items.every { it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID }
        calendar.upserts.isEmpty()
        calendar.deletes.isEmpty()
        todoist.dueUpdates.isEmpty()
        calendar.getByUid(uid).calendarName == 'Work'
    }

    def "deterministic UID missing marker on managed calendar: gateway also refuses overwrite"() {
        given:
        def uid = ManagedEventIds.uidForBlock('b-gw')
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Foreign')
            .description('no marker')
            .calendarName(MANAGED_CAL)
            .start(Instant.parse('2026-08-10T10:00:00Z'))
            .end(Instant.parse('2026-08-10T11:00:00Z'))
            .build())

        when:
        calendar.upsertEvent(CalendarEvent.builder()
            .id(uid).uid(uid).title('Planner try')
            .description(ManagedEventIds.buildDescription('b-gw', 'p'))
            .calendarName(MANAGED_CAL)
            .start(Instant.parse('2026-08-10T14:00:00Z'))
            .end(Instant.parse('2026-08-10T14:30:00Z'))
            .build())

        then:
        thrown(IllegalStateException)
        calendar.getByUid(uid).title == 'Foreign'
        calendar.upserts.isEmpty()
        calendar.rejectedWrites.any { it.reason == 'external_uid_collision' }
    }

    def "delete of unowned event is rejected"() {
        given:
        calendar.seed(CalendarEvent.builder()
            .id('e1').uid('someone@else.com').title('External')
            .description('').calendarName(MANAGED_CAL)
            .start(Instant.parse('2026-08-10T10:00:00Z'))
            .end(Instant.parse('2026-08-10T11:00:00Z'))
            .build())

        when:
        calendar.deleteOwnedEvent('someone@else.com', 'any-block')

        then:
        thrown(IllegalStateException)
        calendar.getByUid('someone@else.com') != null
    }

    def "no writes outside managed calendar via ManagedCalendarWriteGateway"() {
        given:
        def gw = new ManagedCalendarWriteGateway(calendar, calendar, MANAGED_CAL)
        def start = Instant.parse('2026-08-10T14:00:00Z')

        when:
        gw.upsertEvent(CalendarEvent.builder()
            .id('x').uid(ManagedEventIds.uidForBlock('b'))
            .title('t').description(ManagedEventIds.buildDescription('b', 'p'))
            .calendarName('Personal')
            .start(start).end(start + Duration.ofMinutes(15)).build())

        then:
        thrown(IllegalStateException)
        calendar.upserts.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Quality fixes: live calendarAlreadyOk / recreate / move / ownership / zone
    // -------------------------------------------------------------------------

    def "stable block id moved T1 to T2 forces calendar upsert and Todoist due=T2"() {
        given:
        def t1 = Instant.parse('2026-08-10T14:00:00Z')
        def t2 = Instant.parse('2026-08-10T18:00:00Z')
        def plan1 = planWith([singleBlock('b-stable', 't1', t1)], 'approval_required', [], 'plan-move', 1)
        def appr1 = approvalFor(plan1)
        applier().apply(plan1, appr1)
        calendar.upserts.clear()
        todoist.dueUpdates.clear()

        def plan2 = planWith([singleBlock('b-stable', 't1', t2)], 'approval_required', [], 'plan-move', 2)
        def appr2 = approvalFor(plan2)
        clock.set(Instant.parse('2026-08-07T16:00:00Z'))

        when:
        def receipt = applier().apply(plan2, appr2)

        then:
        receipt.overallStatus == ApplyItemStatus.APPLIED
        calendar.upserts.size() == 1
        calendar.upserts[0].start == t2
        calendar.upserts[0].end == t2 + Duration.ofMinutes(30)
        calendar.upserts[0].uid == ManagedEventIds.uidForBlock('b-stable')
        todoist.dueUpdates.size() == 1
        todoist.dueUpdates[0].dueDateTimeIso == t2.toString()
        stateStore.loadMapping('t1').slotStart == t2
        stateStore.loadMapping('t1').fullyApplied()
    }

    def "prior PARTIAL cal-ok Todoist-fail at T1 then approved T2 plan moves calendar and completes Todoist"() {
        given:
        def t1 = Instant.parse('2026-08-10T14:00:00Z')
        def t2 = Instant.parse('2026-08-10T19:00:00Z')
        def plan1 = planWith([singleBlock('b-p', 't1', t1)], 'approval_required', [], 'plan-partial-move', 1)
        def appr1 = approvalFor(plan1)
        todoist.failDueOnCall = 1
        def r1 = applier().apply(plan1, appr1)
        assert r1.overallStatus == ApplyItemStatus.PARTIAL
        assert stateStore.loadMapping('t1').calendarApplied()
        assert stateStore.loadMapping('t1').todoistStatus == ApplyItemStatus.FAILED

        todoist.resetCounters()
        todoist.failDueOnCall = null
        todoist.dueUpdates.clear()
        calendar.upserts.clear()
        clock.set(Instant.parse('2026-08-07T17:00:00Z'))

        def plan2 = planWith([singleBlock('b-p', 't1', t2)], 'approval_required', [], 'plan-partial-move', 2)
        def appr2 = approvalFor(plan2)

        when:
        def r2 = applier().apply(plan2, appr2)

        then:
        r2.overallStatus == ApplyItemStatus.APPLIED
        calendar.upserts.size() == 1
        calendar.upserts[0].start == t2
        todoist.dueUpdates.size() == 1
        todoist.dueUpdates[0].dueDateTimeIso == t2.toString()
        stateStore.loadMapping('t1').fullyApplied()
        stateStore.loadMapping('t1').slotStart == t2
    }

    def "mapping claims calendar applied but deleted live event is recreated before Todoist"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-del', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        def uid = ManagedEventIds.uidForBlock('b-del')
        calendar.forceRemove(uid)
        calendar.upserts.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T16:00:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        calendar.upserts.size() == 1
        calendar.getByUid(uid) != null
        calendar.getByUid(uid).start == start
        // Todoist may be SKIPPED_IDEMPOTENT if already applied
        stateStore.loadMapping('t1').calendarApplied()
        receipt.items.every { it.calendarStatus != ApplyItemStatus.SKIPPED_IDEMPOTENT || calendar.upserts.size() == 1 }
    }

    def "owned live end drift vs last-applied and proposal is manual override not forced upsert"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-stale', 't1', start, 30)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        def uid = ManagedEventIds.uidForBlock('b-stale')
        // User-resized end while keeping ownership — differs from last-applied and proposal
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Block b-stale')
            .description(ManagedEventIds.buildDescription('b-stale', plan.id))
            .calendarName(MANAGED_CAL)
            .start(start).end(start + Duration.ofMinutes(90))
            .role(EventRole.MANAGED_OUTPUT)
            .build())
        calendar.upserts.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T16:30:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.drifts.any { it.type == 'manual_calendar_move' }
        receipt.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
        calendar.upserts.isEmpty()
        calendar.getByUid(uid).end == start + Duration.ofMinutes(90)
    }

    def "marker stripped same-UID event refuses as external collision never recreate"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-mark', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        def uid = ManagedEventIds.uidForBlock('b-mark')
        // Remove marker but keep planner UID + times → not owned; external collision only
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Block b-mark')
            .description('marker stripped by user')
            .calendarName(MANAGED_CAL)
            .start(start).end(start + Duration.ofMinutes(30))
            .build())
        calendar.upserts.clear()
        calendar.deletes.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T16:40:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        // Marker-stripped same UID is external collision — never overwrite/recreate
        receipt.items.every { it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID }
        receipt.errors.any { it.contains('external_uid_collision') && it.contains('missing_ownership_marker') }
        calendar.upserts.isEmpty()
        calendar.deletes.isEmpty()
        todoist.dueUpdates.isEmpty()
        calendar.getByUid(uid).description == 'marker stripped by user'
        !receipt.items.any { it.metadata?.calendarRecreateReason == 'marker_removed' }
    }

    def "PlanApplier constructor requires non-null calendar and todoist read gateways"() {
        when:
        new PlanApplier(config, new ManagedCalendarWriteGateway(calendar, MANAGED_CAL),
            null, todoist, todoist, stateStore, { clock.get() })
        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.toLowerCase().contains('calendarread')

        when:
        new PlanApplier(config, new ManagedCalendarWriteGateway(calendar, MANAGED_CAL),
            calendar, todoist, null, stateStore, { clock.get() })
        then:
        def e2 = thrown(IllegalArgumentException)
        e2.message.toLowerCase().contains('todoistread')
    }

    def "ownership write requires both UID and marker; UID-only and marker-only rejected"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def gw = new ManagedCalendarWriteGateway(calendar, calendar, MANAGED_CAL)

        when: // UID only
        gw.upsertEvent(CalendarEvent.builder()
            .id('u').uid(ManagedEventIds.uidForBlock('bx')).title('t')
            .description('no marker here')
            .calendarName(MANAGED_CAL)
            .start(start).end(start + Duration.ofMinutes(15)).build())
        then:
        thrown(IllegalStateException)

        when: // marker only
        gw.upsertEvent(CalendarEvent.builder()
            .id('m').uid('external@x.com').title('t')
            .description(ManagedEventIds.buildDescription('bx', 'p'))
            .calendarName(MANAGED_CAL)
            .start(start).end(start + Duration.ofMinutes(15)).build())
        then:
        thrown(IllegalStateException)

        when: // wrong calendar
        gw.upsertEvent(CalendarEvent.builder()
            .id('w').uid(ManagedEventIds.uidForBlock('bx')).title('t')
            .description(ManagedEventIds.buildDescription('bx', 'p'))
            .calendarName('Work')
            .start(start).end(start + Duration.ofMinutes(15)).build())
        then:
        thrown(IllegalStateException)

        when: // both OK
        gw.upsertEvent(CalendarEvent.builder()
            .id('ok').uid(ManagedEventIds.uidForBlock('bx')).title('t')
            .description(ManagedEventIds.buildDescription('bx', 'p'))
            .calendarName(MANAGED_CAL)
            .start(start).end(start + Duration.ofMinutes(15)).build())
        then:
        calendar.getByUid(ManagedEventIds.uidForBlock('bx')) != null
    }

    def "deleteOwnedEvent rejects mismatched block metadata and wrong calendar"() {
        given:
        def uid = ManagedEventIds.uidForBlock('b-own')
        def start = Instant.parse('2026-08-10T14:00:00Z')
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Owned')
            .description(ManagedEventIds.buildDescription('b-own', 'p'))
            .calendarName(MANAGED_CAL)
            .start(start).end(start + Duration.ofMinutes(30))
            .role(EventRole.MANAGED_OUTPUT).build())
        def gw = new ManagedCalendarWriteGateway(calendar, calendar, MANAGED_CAL)

        when:
        gw.deleteOwnedEvent(uid, 'wrong-block')
        then:
        thrown(IllegalStateException)
        calendar.getByUid(uid) != null

        when:
        gw.deleteOwnedEvent(uid, 'b-own')
        then:
        calendar.getByUid(uid) == null
    }

    def "focus block move cleans old UID once after new aggregate exists"() {
        given:
        def start1 = Instant.parse('2026-08-11T13:00:00Z')
        // Focus with stable-ish first block id; second plan uses different block id → different UID
        def focus1 = focusBlock('fb-old', ['t1', 't2'], start1, [20, 10])
        def plan1 = planWith([focus1], 'approval_required', [], 'plan-fb', 1)
        def appr1 = approvalFor(plan1)
        applier().apply(plan1, appr1)
        def oldUid = ManagedEventIds.uidForBlock('fb-old')
        assert calendar.getByUid(oldUid) != null

        calendar.upserts.clear()
        calendar.deletes.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T18:00:00Z'))

        def start2 = Instant.parse('2026-08-11T15:00:00Z')
        def focus2 = focusBlock('fb-new', ['t1', 't2'], start2, [20, 10])
        def plan2 = planWith([focus2], 'approval_required', [], 'plan-fb', 2)
        def appr2 = approvalFor(plan2)

        when:
        def receipt = applier().apply(plan2, appr2)

        then:
        receipt.overallStatus == ApplyItemStatus.APPLIED
        def newUid = ManagedEventIds.uidForBlock('fb-new')
        calendar.getByUid(newUid) != null
        calendar.getByUid(newUid).start == start2
        calendar.getByUid(oldUid) == null
        calendar.deletes.size() == 1
        calendar.deletes[0] == oldUid
        todoist.dueUpdates.size() == 2
        stateStore.loadMapping('t1').eventUid == newUid
        stateStore.loadMapping('t2').eventUid == newUid
        stateStore.loadMapping('t1').metadata.priorEventUid == oldUid
    }

    def "externalized old event is preserved with error on focus move cleanup"() {
        given:
        def start1 = Instant.parse('2026-08-11T13:00:00Z')
        def focus1 = focusBlock('fb-ext', ['t1', 't2'], start1, [20, 10])
        def plan1 = planWith([focus1], 'approval_required', [], 'plan-fb-ext', 1)
        applier().apply(plan1, approvalFor(plan1))
        def oldUid = ManagedEventIds.uidForBlock('fb-ext')
        // Externalize old event: strip marker
        calendar.seed(CalendarEvent.builder()
            .id(oldUid).uid(oldUid).title('Hijacked')
            .description('no longer planner owned')
            .calendarName(MANAGED_CAL)
            .start(start1).end(start1 + Duration.ofMinutes(30))
            .build())
        calendar.upserts.clear()
        calendar.deletes.clear()
        clock.set(Instant.parse('2026-08-07T18:30:00Z'))

        def focus2 = focusBlock('fb-ext-2', ['t1', 't2'], Instant.parse('2026-08-11T16:00:00Z'), [20, 10])
        def plan2 = planWith([focus2], 'approval_required', [], 'plan-fb-ext', 2)

        when:
        def receipt = applier().apply(plan2, approvalFor(plan2))

        then:
        // New event upserted
        calendar.getByUid(ManagedEventIds.uidForBlock('fb-ext-2')) != null
        // Old external preserved
        calendar.getByUid(oldUid) != null
        calendar.getByUid(oldUid).title == 'Hijacked'
        calendar.deletes.isEmpty()
        receipt.errors.any { it.toString().contains('old_uid_cleanup') || it.toString().contains('refusing_old_uid') }
        receipt.items.every { it.calendarStatus == ApplyItemStatus.PARTIAL || it.metadata?.oldUidCleanupFailed == true || it.metadata?.oldUidDeleteStatus != null }
    }

    def "delete failure on old UID is recoverable without duplicating new event"() {
        given:
        def start1 = Instant.parse('2026-08-11T13:00:00Z')
        def focus1 = focusBlock('fb-df', ['t1', 't2'], start1, [20, 10])
        def plan1 = planWith([focus1], 'approval_required', [], 'plan-fb-df', 1)
        applier().apply(plan1, approvalFor(plan1))
        def oldUid = ManagedEventIds.uidForBlock('fb-df')

        calendar.upserts.clear()
        calendar.deletes.clear()
        todoist.dueUpdates.clear()
        calendar.failDeleteOnCall = 1
        clock.set(Instant.parse('2026-08-07T19:00:00Z'))

        def focus2 = focusBlock('fb-df-2', ['t1', 't2'], Instant.parse('2026-08-11T17:00:00Z'), [20, 10])
        def plan2 = planWith([focus2], 'approval_required', [], 'plan-fb-df', 2)
        def appr2 = approvalFor(plan2)

        when:
        def r1 = applier().apply(plan2, appr2)

        then:
        r1.overallStatus == ApplyItemStatus.PARTIAL || r1.errors.any { it.contains('old_uid_cleanup') }
        def newUid = ManagedEventIds.uidForBlock('fb-df-2')
        calendar.getByUid(newUid) != null
        calendar.getByUid(oldUid) != null // delete failed
        calendar.upserts.size() == 1

        when: // rerun completes cleanup, no duplicate new upsert if live matches
        calendar.resetCounters()
        calendar.failDeleteOnCall = null
        calendar.upserts.clear()
        calendar.deletes.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T19:30:00Z'))
        def r2 = applier().apply(plan2, appr2)

        then:
        calendar.getByUid(oldUid) == null
        calendar.deletes.size() == 1
        // new event already exact-match live → no new upsert
        calendar.upserts.isEmpty()
        stateStore.loadMapping('t1').eventUid == newUid
        r2.overallStatus == ApplyItemStatus.APPLIED || r2.overallStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
    }

    def "frozen clock yields distinct receipt ids and append-only paths"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        // freeze clock
        clock.set(Instant.parse('2026-08-07T15:00:00Z'))

        when:
        def r1 = applier().apply(plan, approval)
        def r2 = applier().apply(plan, approval)

        then:
        r1.id != r2.id
        stateStore.listReceiptIds().size() == 2
        stateStore.loadReceipt(r1.id) != null
        stateStore.loadReceipt(r2.id) != null
        stateStore.receiptPath(r1.id) != null
    }

    def "offset-less Todoist due uses planner ZoneId America/New_York including DST"() {
        given:
        // Civil 10:00 America/New_York on 2026-03-09 (EDT, UTC-4) => 14:00Z
        // and on 2026-01-15 (EST, UTC-5) => 15:00Z
        def winterCivil = '2026-01-15T10:00:00'
        def summerCivil = '2026-03-09T10:00:00'
        expect:
        PlanApplier.parseDueString(winterCivil, ZONE) == Instant.parse('2026-01-15T15:00:00Z')
        PlanApplier.parseDueString(summerCivil, ZONE) == Instant.parse('2026-03-09T14:00:00Z')
        PlanApplier.parseDueString('2026-03-09T14:00:00Z', ZONE) == Instant.parse('2026-03-09T14:00:00Z')
        PlanApplier.parseDueString('2026-03-09T10:00:00-04:00', ZONE) == Instant.parse('2026-03-09T14:00:00Z')
        // Must NOT treat offset-less as Z
        PlanApplier.parseDueString(winterCivil, ZONE) != Instant.parse('2026-01-15T10:00:00Z')
    }

    def "offset-less live due drift detection uses config timezone not Z"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z') // 10:00 AM EDT
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        // User sets due to civil datetime without offset in NY zone away from applied and proposal
        // 16:00 NY EDT = 20:00Z
        todoist.updateTaskDue('t1', '2026-08-10T16:00:00')
        // Patch stored due to offset-less form (gateway stores as given)
        def t = todoist.getTask('t1')
        t.due = [date: '2026-08-10T16:00:00', datetime: '2026-08-10T16:00:00', string: '2026-08-10T16:00:00']
        t.due_date = '2026-08-10T16:00:00'
        todoist.dueUpdates.clear()

        def newPlan = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T18:00:00Z'))],
            'approval_required', [], 'plan-zone', 1)
        def newApproval = approvalFor(newPlan)

        when:
        def receipt = applier().apply(newPlan, newApproval)

        then:
        receipt.drifts.any { it.type == 'manual_todoist_due_change' && it.taskId == 't1' }
        receipt.items.every { it.todoistStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
        todoist.dueUpdates.isEmpty()
    }

    // -------------------------------------------------------------------------
    // State persistence
    // -------------------------------------------------------------------------

    def "corrupt mappings file throws structured error"() {
        given:
        Files.write(stateStore.mappingsPath(), '{not json'.getBytes(StandardCharsets.UTF_8))

        when:
        stateStore.loadMappings()

        then:
        def e = thrown(PlanStoreException)
        e.context == 'parse'
        e.path != null
    }

    def "interrupted mappings write preserves prior snapshot"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        def before = new String(Files.readAllBytes(stateStore.mappingsPath()), StandardCharsets.UTF_8)

        def failingStore = new ApplicationStateStore(dir, {
            throw new IOException('simulated failure before move')
        })

        when:
        failingStore.saveMappings(stateStore.loadMappings())

        then:
        thrown(PlanStoreException)
        new String(Files.readAllBytes(stateStore.mappingsPath()), StandardCharsets.UTF_8) == before
    }

    def "receipt human summary uses 12-hour AM/PM; machine JSON uses ISO"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z') // 10:00 AM Eastern
        def plan = planWith([singleBlock('b1', 't1', start)])
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)
        def human = receipt.toHumanSummary(ZONE)
        def json = groovy.json.JsonOutput.toJson(receipt.toMap())

        then:
        human.contains('AM') || human.contains('PM')
        json.contains('2026-08-10T14:00:00Z') || json.contains(start.toString())
        !json.toLowerCase().contains(' 10:00 am')
    }

    def "DST offset exact DTSTART synchronization"() {
        given:
        // America/New_York DST: 2026-03-08 spring forward
        def start = Instant.parse('2026-03-09T14:00:00Z') // 10:00 AM EDT
        def plan = planWith([singleBlock('b-dst', 't1', start)])
        def approval = approvalFor(plan)

        when:
        applier().apply(plan, approval)

        then:
        calendar.upserts[0].start == start
        todoist.dueUpdates[0].dueDateTimeIso == '2026-03-09T14:00:00Z'
        stateStore.loadMapping('t1').slotStart == start
    }

    def "PlanHash is stable for same plan and changes when blocks move"() {
        given:
        def p1 = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T14:00:00Z'))])
        def p2 = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T14:00:00Z'))])
        def p3 = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T15:00:00Z'))])

        expect:
        PlanHash.compute(p1) == PlanHash.compute(p2)
        PlanHash.compute(p1) != PlanHash.compute(p3)
    }

    // -------------------------------------------------------------------------
    // Round-2 quality: concurrent mapping merge, live Todoist due, empty plan,
    // output calendar required, PlanHash semantic binding
    // -------------------------------------------------------------------------

    def "concurrent PlanApplier instances applying different tasks preserve both mappings"() {
        given:
        def start1 = Instant.parse('2026-08-10T14:00:00Z')
        def start2 = Instant.parse('2026-08-10T15:00:00Z')
        def planA = planWith([singleBlock('ba', 't1', start1)], 'approval_required', [], 'plan-a', 1)
        def planB = planWith([singleBlock('bb', 't2', start2)], 'approval_required', [], 'plan-b', 1)
        def apprA = approvalFor(planA)
        def apprB = approvalFor(planB)
        def storeA = new ApplicationStateStore(dir)
        def storeB = new ApplicationStateStore(dir)
        def applierA = new PlanApplier(config,
            new ManagedCalendarWriteGateway(calendar, MANAGED_CAL), calendar, todoist, todoist, storeA,
            { clock.get() })
        def applierB = new PlanApplier(config,
            new ManagedCalendarWriteGateway(calendar, MANAGED_CAL), calendar, todoist, todoist, storeB,
            { clock.get() })
        def errors = Collections.synchronizedList([])
        def barrier = new java.util.concurrent.CyclicBarrier(2)

        when:
        def tA = Thread.start {
            try {
                barrier.await()
                applierA.apply(planA, apprA)
            } catch (Exception e) {
                errors << e
            }
        }
        def tB = Thread.start {
            try {
                barrier.await()
                applierB.apply(planB, apprB)
            } catch (Exception e) {
                errors << e
            }
        }
        tA.join()
        tB.join()
        def loaded = new ApplicationStateStore(dir).loadMappings()

        then:
        errors.isEmpty()
        loaded['t1']?.taskId == 't1'
        loaded['t2']?.taskId == 't2'
        loaded['t1'].fullyApplied()
        loaded['t2'].fullyApplied()
        loaded['t1'].slotStart == start1
        loaded['t2'].slotStart == start2
        calendar.getByUid(ManagedEventIds.uidForBlock('ba')) != null
        calendar.getByUid(ManagedEventIds.uidForBlock('bb')) != null
    }

    def "prior success then cleared Todoist due restores exact start without calendar duplicate"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-clear', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        // Clear due entirely
        def t = todoist.getTask('t1')
        t.due = null
        t.due_date = null
        t.remove('due')
        todoist.dueUpdates.clear()
        calendar.upserts.clear()
        clock.set(Instant.parse('2026-08-07T16:00:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        calendar.upserts.isEmpty() // live calendar still matches
        todoist.dueUpdates.size() == 1
        todoist.dueUpdates[0].dueDateTimeIso == start.toString()
        receipt.items.every { it.todoistStatus == ApplyItemStatus.APPLIED }
        receipt.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT }
        stateStore.loadMapping('t1').fullyApplied()
        stateStore.loadMapping('t1').slotStart == start
    }

    def "prior success then changed Todoist due is preserved as drift not false-applied"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-chg', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        todoist.updateTaskDue('t1', '2026-08-10T19:00:00Z')
        todoist.dueUpdates.clear()
        calendar.upserts.clear()
        clock.set(Instant.parse('2026-08-07T16:10:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.drifts.any { it.type == 'manual_todoist_due_change' && it.taskId == 't1' }
        receipt.items.every { it.todoistStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
        todoist.dueUpdates.isEmpty()
        calendar.upserts.isEmpty()
        // Must not claim idempotent applied success
        receipt.overallStatus != ApplyItemStatus.APPLIED
        receipt.overallStatus != ApplyItemStatus.SKIPPED_IDEMPOTENT
    }

    def "exact live Todoist due matching slotStart skips Todoist update"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-exact', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        todoist.dueUpdates.clear()
        calendar.upserts.clear()
        clock.set(Instant.parse('2026-08-07T16:20:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
        todoist.dueUpdates.isEmpty()
        calendar.upserts.isEmpty()
    }

    def "offset-less civil live due normalized with planner zone equals slot for idempotent skip"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z') // 10:00 America/New_York EDT
        def plan = planWith([singleBlock('b-civil', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        // Rewrite live due as offset-less civil in planner zone
        def t = todoist.getTask('t1')
        t.due = [date: '2026-08-10T10:00:00', datetime: '2026-08-10T10:00:00', string: '2026-08-10T10:00:00']
        t.due_date = '2026-08-10T10:00:00'
        todoist.dueUpdates.clear()
        calendar.upserts.clear()
        clock.set(Instant.parse('2026-08-07T16:25:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
        todoist.dueUpdates.isEmpty()
        calendar.upserts.isEmpty()
    }

    def "missing Todoist task is structured error not false success"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-miss', 't-missing', start)])
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        calendar.upserts.size() == 1 // calendar may still apply first
        receipt.items.every {
            it.todoistStatus == ApplyItemStatus.FAILED || it.todoistStatus == ApplyItemStatus.ERROR_EXTERNAL_UID
        }
        !receipt.success()
        receipt.errors.any { it.toString().toLowerCase().contains('missing') || it.toString().contains('t-missing') }
        def m = stateStore.loadMapping('t-missing')
        m == null || !m.todoistApplied()
    }

    def "malformed live Todoist due is structured error not false skip"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-mal', 't1', start)])
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        def t = todoist.getTask('t1')
        t.due = [date: 'not-a-datetime', datetime: 'not-a-datetime', string: 'not-a-datetime']
        t.due_date = 'not-a-datetime'
        todoist.dueUpdates.clear()
        calendar.upserts.clear()
        clock.set(Instant.parse('2026-08-07T16:30:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.items.every { it.todoistStatus == ApplyItemStatus.FAILED }
        !receipt.items.any { it.todoistStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT }
        !receipt.success()
        todoist.dueUpdates.isEmpty()
        receipt.errors.any { it.toString().toLowerCase().contains('malformed') || it.toString().contains('due') }
    }

    def "empty plan with no scheduled blocks is safe no-op not APPLIED"() {
        given:
        def plan = planWith([], 'approval_required', [], 'plan-empty', 1)
        def approval = approvalFor(plan)

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.overallStatus == ApplyItemStatus.SKIPPED_NO_CHANGES
        !receipt.success() || receipt.overallStatus != ApplyItemStatus.APPLIED
        receipt.overallStatus != ApplyItemStatus.APPLIED
        calendar.upserts.isEmpty()
        todoist.dueUpdates.isEmpty()
        receipt.metadata.writeCount == 0
        stateStore.loadMappings().isEmpty()
    }

    def "PlanApplier requires non-blank managed output calendar"() {
        when:
        def blankCfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: '   ',
            availability   : [
                working_windows: [weekday: ['09:00-17:00']],
                calendars      : [[calendar: MANAGED_CAL, default_role: 'managed_output']]
            ],
            stability      : [keep_manual_moves: true]
        ])
        new PlanApplier(blankCfg,
            new ManagedCalendarWriteGateway(calendar, MANAGED_CAL), calendar, todoist, todoist, stateStore,
            { clock.get() })

        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.toLowerCase().contains('output')

        when:
        def nullCfg = PlannerConfig.fromMap(planner: [
            mode        : 'approval_required',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [weekday: ['09:00-17:00']],
                calendars      : [[calendar: MANAGED_CAL, default_role: 'managed_output']]
            ],
            stability   : [keep_manual_moves: true]
        ])
        new PlanApplier(nullCfg,
            new ManagedCalendarWriteGateway(calendar, MANAGED_CAL), calendar, todoist, todoist, stateStore,
            { clock.get() })

        then:
        def e2 = thrown(IllegalArgumentException)
        e2.message.toLowerCase().contains('output')
    }

    def "PlanHash ignores createdAt but binds semantic content mutations"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def base = planWith([singleBlock('b1', 't1', start)], 'approval_required', [], 'plan-h', 1)
        def otherCreated = Plan.builder()
            .id(base.id).version(base.version)
            .createdAt(Instant.parse('2099-01-01T00:00:00Z'))
            .mode(base.mode).tasks(base.tasks).scheduledBlocks(base.scheduledBlocks)
            .changes(base.changes).build()
        def moved = planWith([singleBlock('b1', 't1', Instant.parse('2026-08-10T15:00:00Z'))],
            'approval_required', [], 'plan-h', 1)
        def otherVersion = planWith([singleBlock('b1', 't1', start)], 'approval_required', [], 'plan-h', 2)
        def otherTask = planWith([singleBlock('b1', 't2', start)], 'approval_required', [], 'plan-h', 1)
        def withChange = planWith([singleBlock('b1', 't1', start)], 'approval_required', [
            PlanChange.builder().id('c1').type('schedule').taskId('t1').reason('test')
                .newStart(start).newEnd(start + Duration.ofMinutes(30))
                .metadata([approvalRequired: true]).build()
        ], 'plan-h', 1)

        expect:
        PlanHash.compute(base) == PlanHash.compute(otherCreated)
        PlanHash.compute(base) != PlanHash.compute(moved)
        PlanHash.compute(base) != PlanHash.compute(otherVersion)
        PlanHash.compute(base) != PlanHash.compute(otherTask)
        PlanHash.compute(base) != PlanHash.compute(withChange)
    }

    // -------------------------------------------------------------------------
    // Round-3 quality: same-plan manual move, PlanHash reason, global UID lookup
    // -------------------------------------------------------------------------

    def "same approved plan reapplied after user moves owned event is SKIPPED_MANUAL_OVERRIDE with zero writes"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-same', 't1', start)], 'approval_required', [], 'plan-same', 1)
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        def uid = ManagedEventIds.uidForBlock('b-same')
        def movedStart = Instant.parse('2026-08-10T16:30:00Z')
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Block b-same')
            .description(ManagedEventIds.buildDescription('b-same', plan.id))
            .calendarName(MANAGED_CAL)
            .start(movedStart).end(movedStart + Duration.ofMinutes(30))
            .role(EventRole.MANAGED_OUTPUT)
            .build())
        calendar.upserts.clear()
        calendar.deletes.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T17:00:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        receipt.drifts.any { it.type == 'manual_calendar_move' && it.eventUid == uid }
        receipt.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
        receipt.items.every { it.todoistStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
        calendar.upserts.isEmpty()
        calendar.deletes.isEmpty()
        todoist.dueUpdates.isEmpty()
        calendar.getByUid(uid).start == movedStart
        receipt.overallStatus != ApplyItemStatus.APPLIED
        receipt.overallStatus != ApplyItemStatus.SKIPPED_IDEMPOTENT
    }

    def "manual move to time equal another plan proposal still preserves override on same-plan reapply"() {
        given:
        def tA = Instant.parse('2026-08-10T14:00:00Z')
        def tB = Instant.parse('2026-08-10T18:00:00Z')
        // Apply plan A at tA
        def planA = planWith([singleBlock('b-eq', 't1', tA)], 'approval_required', [], 'plan-eq-a', 1)
        applier().apply(planA, approvalFor(planA))
        def uid = ManagedEventIds.uidForBlock('b-eq')
        // User moves owned event to tB (which happens to equal what plan B would propose)
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Block b-eq')
            .description(ManagedEventIds.buildDescription('b-eq', planA.id))
            .calendarName(MANAGED_CAL)
            .start(tB).end(tB + Duration.ofMinutes(30))
            .role(EventRole.MANAGED_OUTPUT)
            .build())
        // Same plan A reapplied (proposal still tA) — must not treat as recreate just because
        // live time equals some other plan's proposal
        calendar.upserts.clear()
        calendar.deletes.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T17:10:00Z'))

        when:
        def receipt = applier().apply(planA, approvalFor(planA))

        then:
        receipt.drifts.any { it.type == 'manual_calendar_move' }
        receipt.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
        calendar.upserts.isEmpty()
        calendar.deletes.isEmpty()
        todoist.dueUpdates.isEmpty()
        calendar.getByUid(uid).start == tB
    }

    def "keepManualMoves false allows calendar overwrite of user-moved owned event"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-km', 't1', start)], 'approval_required', [], 'plan-km', 1)
        def approval = approvalFor(plan)
        def cfgOff = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: MANAGED_CAL,
            availability   : [
                working_windows: [weekday: ['09:00-17:00']],
                calendars      : [[calendar: MANAGED_CAL, default_role: 'managed_output']]
            ],
            stability      : [keep_manual_moves: false]
        ])
        applier(cfgOff).apply(plan, approval)
        def uid = ManagedEventIds.uidForBlock('b-km')
        def movedStart = Instant.parse('2026-08-10T16:00:00Z')
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Block b-km')
            .description(ManagedEventIds.buildDescription('b-km', plan.id))
            .calendarName(MANAGED_CAL)
            .start(movedStart).end(movedStart + Duration.ofMinutes(30))
            .role(EventRole.MANAGED_OUTPUT)
            .build())
        calendar.upserts.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T17:20:00Z'))

        when:
        def receipt = applier(cfgOff).apply(plan, approval)

        then:
        receipt.drifts.isEmpty() || !receipt.drifts.any { it.type == 'manual_calendar_move' }
        calendar.upserts.size() == 1
        calendar.getByUid(uid).start == start
        !receipt.items.any { it.calendarStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE }
    }

    def "genuinely missing owned event is recreated on same-plan reapply"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def plan = planWith([singleBlock('b-miss', 't1', start)], 'approval_required', [], 'plan-miss', 1)
        def approval = approvalFor(plan)
        applier().apply(plan, approval)
        def uid = ManagedEventIds.uidForBlock('b-miss')
        calendar.forceRemove(uid)
        calendar.upserts.clear()
        calendar.deletes.clear()
        todoist.dueUpdates.clear()
        clock.set(Instant.parse('2026-08-07T17:30:00Z'))

        when:
        def receipt = applier().apply(plan, approval)

        then:
        calendar.upserts.size() == 1
        calendar.getByUid(uid) != null
        calendar.getByUid(uid).start == start
        ManagedEventIds.isOwned(calendar.getByUid(uid), MANAGED_CAL)
        receipt.items.every { it.calendarStatus == ApplyItemStatus.APPLIED || it.calendarStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT }
        !receipt.drifts.any { it.type == 'manual_calendar_move' }
    }

    def "PlanHash reason-only change on ScheduledBlock alters hash"() {
        given:
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def end = start + Duration.ofMinutes(30)
        def b1 = ScheduledBlock.builder()
            .id('b1').start(start).end(end).taskIds(['t1']).title('Block b1').reason('alpha').build()
        def b2 = ScheduledBlock.builder()
            .id('b1').start(start).end(end).taskIds(['t1']).title('Block b1').reason('beta').build()
        def p1 = planWith([b1], 'approval_required', [], 'plan-reason', 1)
        def p2 = planWith([b2], 'approval_required', [], 'plan-reason', 1)

        expect:
        PlanHash.compute(p1) != PlanHash.compute(p2)
        PlanHash.canonicalize(p1).contains('alpha')
        PlanHash.canonicalize(p2).contains('beta')
    }

    def "findEventByUid finds wrong-calendar collision globally"() {
        given:
        def blockId = 'b-global-uid'
        def uid = ManagedEventIds.uidForBlock(blockId)
        def existingStart = Instant.parse('2026-08-10T09:00:00Z')
        def existingEnd = Instant.parse('2026-08-10T09:30:00Z')
        // Event only on Work calendar — must still be found by global UID lookup
        calendar.seed(CalendarEvent.builder()
            .id(uid).uid(uid).title('Work collision')
            .description(ManagedEventIds.buildDescription(blockId, 'foreign'))
            .calendarName('Work')
            .start(existingStart).end(existingEnd)
            .build())

        expect:
        calendar.findEventByUid(uid) != null
        calendar.findEventByUid(uid).calendarName == 'Work'
        calendar.findEventByUid(uid).title == 'Work collision'

        when:
        def plan = planWith([singleBlock(blockId, 't1', Instant.parse('2026-08-10T14:00:00Z'))])
        def receipt = applier().apply(plan, approvalFor(plan))

        then:
        receipt.items.every { it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID }
        receipt.errors.any { it.contains('wrong_calendar') }
        calendar.upserts.isEmpty()
        calendar.getByUid(uid).calendarName == 'Work'
        calendar.getByUid(uid).start == existingStart
    }
}
