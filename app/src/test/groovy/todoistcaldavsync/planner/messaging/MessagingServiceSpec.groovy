package todoistcaldavsync.planner.messaging

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.InMemoryCalendarGateway
import todoistcaldavsync.planner.adapters.InMemoryMessagingGateway
import todoistcaldavsync.planner.adapters.InMemoryTodoistGateway
import todoistcaldavsync.planner.apply.PlanApplier
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.ApplicationReceipt
import todoistcaldavsync.planner.domain.ApplyItemStatus
import todoistcaldavsync.planner.domain.DecisionRecord
import todoistcaldavsync.planner.domain.DeliveryReceipt
import todoistcaldavsync.planner.domain.Message
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.UnscheduledTask
import todoistcaldavsync.planner.feedback.FeedbackParser
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.state.DecisionStore
import todoistcaldavsync.planner.state.DeliveryLedger
import todoistcaldavsync.planner.state.PlanStore
import todoistcaldavsync.planner.state.PlanStoreException

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

class MessagingServiceSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')
    def dirs = []

    def cleanup() {
        dirs.each {
            try {
                Files.walk(it).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            } catch (Exception ignored) {
            }
        }
    }

    private Path temp() {
        def d = Files.createTempDirectory('msg-svc-')
        dirs << d
        d
    }

    private PlannerConfig baseConfig(Map messaging = [enabled: false]) {
        PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : messaging
        ])
    }

    private Plan samplePlan(String mode = 'approval_required') {
        def t = Task.builder().id('t1').content('Deep work').priority(1)
            .effectiveDuration(Duration.ofMinutes(60)).durationSource('t').build()
        def tFrozen = Task.builder().id('t-frozen').content('Frozen task').priority(1)
            .effectiveDuration(Duration.ofMinutes(60)).durationSource('t').build()
        def riskTask = Task.builder().id('t-risk').content('Paint the Deck').priority(1)
            .deadline(Instant.parse('2026-08-12T21:00:00Z'))
            .effectiveDuration(Duration.ofMinutes(120)).durationSource('t').build()
        Instant start = LocalDateTime.of(2026, 8, 7, 9, 0).atZone(zone).toInstant()
        def block = ScheduledBlock.builder().id('b1').start(start).end(start + Duration.ofHours(1))
            .taskIds(['t1']).title('Deep work').reason('fit').build()
        def frozen = ScheduledBlock.builder().id('b2')
            .start(start + Duration.ofHours(2)).end(start + Duration.ofHours(3))
            .taskIds(['t-frozen']).title('Frozen block').reason('kept').frozen(true).build()
        def unsched = new UnscheduledTask(riskTask, 'no weather-safe slot', 'weather_infeasible',
            [alternatives: [[title: 'Indoor prep']]])
        def safeCh = PlanChange.builder().id('c1').type('add').taskId('t1')
            .newStart(start).reason('new').build()
        def apprCh = PlanChange.builder().id('c2').type('move').taskId('t-frozen')
            .newStart(start + Duration.ofHours(2)).reason('protected')
            .metadata([approvalRequired: true]).build()
        Plan.builder().id('plan-msg').version(1)
            .createdAt(Instant.parse('2026-08-07T08:00:00Z'))
            .mode(mode).tasks([t, tFrozen, riskTask])
            .scheduledBlocks([block, frozen])
            .unscheduled([unsched])
            .changes([safeCh, apprCh])
            .build()
    }

    private MessagingService service(PlannerConfig cfg, PlanStore plans,
                                     InMemoryMessagingGateway gw = null,
                                     PlanApplier applier = null,
                                     Instant now = Instant.parse('2026-08-07T10:05:00Z'),
                                     DeliveryLedger ledger = null) {
        def root = temp()
        def led = ledger ?: new DeliveryLedger(root.resolve('ledger'))
        def decisions = new DecisionStore(root.resolve('decisions'))
        def parser = new FeedbackParser(decisions, { true }, { now })
        new MessagingService(cfg, plans, gw, led, decisions, parser, applier, { now })
    }

    def "disabled messaging is no-op and does not call gateway"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def svc = service(baseConfig(enabled: false), plans, gw)

        when:
        def receipts = svc.deliverDue(plan.id)
        def one = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)

        then:
        receipts.every { it.status == 'SKIPPED_DISABLED' }
        one.size() == 1
        one[0].status == 'SKIPPED_DISABLED'
        gw.callCount == 0
    }

    def "schedule due/not-due and DST-safe local window"() {
        given:
        def schedDaily = new PlannerConfig.MessageSchedule('d', 'daily_summary', '06:00',
            Duration.ofDays(1), Duration.ofMinutes(30))
        def schedWeekly = new PlannerConfig.MessageSchedule('w', 'weekly_summary', 'mon 09:00',
            Duration.ofDays(7), Duration.ofMinutes(30))

        Instant fri0605 = ZonedDateTime.of(2026, 8, 7, 6, 5, 0, 0, zone).toInstant()
        Instant fri0700 = ZonedDateTime.of(2026, 8, 7, 7, 0, 0, 0, zone).toInstant()
        Instant mon0905 = ZonedDateTime.of(2026, 8, 10, 9, 5, 0, 0, zone).toInstant()
        Instant dstMorning = ZonedDateTime.of(2026, 3, 8, 6, 10, 0, 0, zone).toInstant()

        expect:
        MessagingService.isScheduleDue(schedDaily, fri0605, zone)
        !MessagingService.isScheduleDue(schedDaily, fri0700, zone)
        !MessagingService.isScheduleDue(schedWeekly, fri0605, zone)
        MessagingService.isScheduleDue(schedWeekly, mon0905, zone)
        MessagingService.isScheduleDue(schedDaily, dstMorning, zone)
    }

    def "DST spring missing hour uses first valid after gap; fall repeated hour due once deterministically"() {
        given:
        def sched = new PlannerConfig.MessageSchedule('d', 'daily_summary', '02:30',
            Duration.ofDays(1), Duration.ofMinutes(30))
        // 2026-03-08 spring forward: 02:00 → 03:00; 02:30 does not exist → effective 03:00
        Instant springAtEffective = ZonedDateTime.of(2026, 3, 8, 3, 10, 0, 0, zone).toInstant()
        Instant springOutside = ZonedDateTime.of(2026, 3, 8, 3, 40, 0, 0, zone).toInstant()
        // 2026-11-01 fall back: 01:00-02:00 repeats. Local 01:15 is within [01:00 window) if schedule is 01:00
        def sched1 = new PlannerConfig.MessageSchedule('f', 'daily_summary', '01:00',
            Duration.ofDays(1), Duration.ofMinutes(30))
        Instant fallFirst = ZonedDateTime.of(2026, 11, 1, 1, 10, 0, 0, zone).withEarlierOffsetAtOverlap().toInstant()
        Instant fallSecond = ZonedDateTime.of(2026, 11, 1, 1, 10, 0, 0, zone).withLaterOffsetAtOverlap().toInstant()

        expect:
        // spring: effective target is 03:00; 03:10 is within [03:00, 03:30)
        MessagingService.isScheduleDue(sched, springAtEffective, zone)
        !MessagingService.isScheduleDue(sched, springOutside, zone)
        ScheduleOccurrence.occurrenceKey(sched, springAtEffective, zone).contains('2026-03-08')
        ScheduleOccurrence.occurrenceKey(sched, springAtEffective, zone) ==
            ScheduleOccurrence.occurrenceKey(sched, ZonedDateTime.of(2026, 3, 8, 3, 20, 0, 0, zone).toInstant(), zone)
        // fall: both overlap instants map to local 01:10 → due (same local wall); same occurrence key
        MessagingService.isScheduleDue(sched1, fallFirst, zone)
        MessagingService.isScheduleDue(sched1, fallSecond, zone)
        ScheduleOccurrence.occurrenceKey(sched1, fallFirst, zone) ==
            ScheduleOccurrence.occurrenceKey(sched1, fallSecond, zone)
        // Outside window after 01:30 local
        !MessagingService.isScheduleDue(sched1,
            ZonedDateTime.of(2026, 11, 1, 1, 45, 0, 0, zone).withLaterOffsetAtOverlap().toInstant(), zone)
    }

    def "successful delivery and retry idempotency via ledger"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway({ Instant.parse('2026-08-07T10:05:00Z') })
        def cfg = baseConfig(
            enabled: true,
            provider: 'slack',
            destination: '#planner',
            webhook_url_env: 'SLACK_WEBHOOK_URL',
            enabled_kinds: ['daily_summary'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '06:00']]
        )
        def svc = service(cfg, plans, gw, null, Instant.parse('2026-08-07T14:00:00Z'))

        when:
        def r1 = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)
        def r2 = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)

        then:
        r1.size() == 1
        r1[0].status == 'DELIVERED'
        r2.size() == 1
        r2[0].status == 'SKIPPED_DUPLICATE'
        gw.callCount == 1
        gw.sent[0].body.contains('Deep work')
        gw.sent[0].body.contains('9:00 AM')
    }

    def "provider failure does not mark delivered in ledger"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        gw.failOnCall(1, 'TRANSPORT', 'boom')
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#p',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary']
        )
        def svc = service(cfg, plans, gw)

        when:
        def r1 = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)
        gw.clearFailure()
        gw.setDeliver(true)
        def r2 = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)

        then:
        r1.size() == 1
        r1[0].status == 'FAILED'
        r2.size() == 1
        r2[0].status == 'DELIVERED'
        gw.callCount == 2
    }

    def "pre-send ledger failure does not call provider"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def boom = new AtomicInteger(0)
        def ledger = new DeliveryLedger(temp(), {
            if (boom.getAndIncrement() == 0) {
                throw new RuntimeException('pre-send ledger boom')
            }
        })
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#p',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary']
        )
        def decisions = new DecisionStore(temp())
        def now = Instant.parse('2026-08-07T14:00:00Z')
        def svc = new MessagingService(cfg, plans, gw, ledger, decisions,
            new FeedbackParser(decisions, { true }, { now }), null, { now })

        when:
        svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)

        then:
        def e = thrown(MessagingService.LedgerPersistException)
        e.message.toLowerCase().contains('pre-send') || e.message.toLowerCase().contains('provider not called')
        gw.callCount == 0
    }

    def "provider success then final ledger failure yields UNKNOWN and retry does not resend"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        // Allow PENDING write (receipt + index = 2 moves), fail on DELIVERED receipt write (3rd move)
        def moves = new AtomicInteger(0)
        def ledger = new DeliveryLedger(temp(), {
            int n = moves.incrementAndGet()
            // PENDING: receipt write (1) + index (2). DELIVERED receipt write is 3 → fail
            if (n == 3) {
                throw new RuntimeException('final ledger boom')
            }
        })
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#p',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary']
        )
        def decisions = new DecisionStore(temp())
        def now = Instant.parse('2026-08-07T14:00:00Z')
        def svc = new MessagingService(cfg, plans, gw, ledger, decisions,
            new FeedbackParser(decisions, { true }, { now }), null, { now })

        when:
        svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)

        then:
        def e = thrown(MessagingService.LedgerPersistException)
        e.providerReceipt != null
        e.providerReceipt.status == 'UNKNOWN' || e.providerReceipt.status == 'NEEDS_RECONCILIATION'
        gw.callCount == 1

        when: 'retry must not blindly resend'
        def retry = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)

        then:
        retry.size() == 1
        retry[0].status == 'UNKNOWN' || retry[0].status == 'NEEDS_RECONCILIATION'
        gw.callCount == 1
        !ledger.wasDelivered(gw.sent[0].idempotencyKey)
    }

    def "successful retry after FAILED"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        gw.failOnCall(1, 'TRANSPORT', 'temp')
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#p',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary']
        )
        def svc = service(cfg, plans, gw)

        when:
        def r1 = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)
        gw.clearFailure()
        def r2 = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)

        then:
        r1.size() == 1
        r1[0].status == 'FAILED'
        r2.size() == 1
        r2[0].status == 'DELIVERED'
        gw.callCount == 2
    }

    def "capacity risk alerts delivered with required fields"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            enabled_kinds: ['capacity_risk_alert'],
            capacity_risk_alerts: true,
            risk_deadline_days: 14
        )
        def svc = service(cfg, plans, gw)

        when:
        def r = svc.deliverKind(plan.id, MessageRenderer.KIND_RISK)

        then:
        r.size() == 1
        r[0].status == 'DELIVERED'
        r[0].kind == MessageRenderer.KIND_RISK
        gw.sent[0].kind == MessageRenderer.KIND_RISK
        gw.sent[0].body.contains('Paint the Deck')
        gw.sent[0].body.contains('t-risk')
        gw.sent[0].body.contains('Deadline:')
        gw.sent[0].body.contains('Alternatives:')
        gw.sent[0].body.contains('Indoor prep')
    }

    def "capacityRiskAlerts alone does not send without due schedule intent"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        // Risk enabled but schedule is at 06:00; now is 14:00 — not due
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            capacity_risk_alerts: true,
            enabled_kinds: ['daily_summary', 'capacity_risk_alert'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M']]
        )
        def svc = service(cfg, plans, gw, null,
            ZonedDateTime.of(2026, 8, 7, 14, 0, 0, 0, zone).toInstant())

        when:
        def receipts = svc.deliverDue(plan.id)
        def due = svc.dueIntents()

        then:
        due.isEmpty()
        receipts.every { it.kind != MessageRenderer.KIND_RISK || it.status == 'SKIPPED_DISABLED' }
        gw.callCount == 0
    }

    def "risk alerts send when daily intent due and capacityRiskAlerts enabled"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            capacity_risk_alerts: true,
            risk_deadline_days: 14,
            enabled_kinds: ['daily_summary', 'capacity_risk_alert'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M', horizon: 'P1D']]
        )
        def now = ZonedDateTime.of(2026, 8, 7, 6, 10, 0, 0, zone).toInstant()
        def svc = service(cfg, plans, gw, null, now)

        when:
        def due = svc.dueIntents()
        def receipts = svc.deliverDue(plan.id)

        then:
        due.any { it.kind == MessageRenderer.KIND_DAILY }
        due.any { it.kind == MessageRenderer.KIND_RISK }
        receipts.any { it.status == 'DELIVERED' && it.kind == MessageRenderer.KIND_RISK }
        gw.sent.any { it.kind == MessageRenderer.KIND_RISK && it.body.contains('Paint the Deck') }
    }

    def "alias schedule kind daily normalizes to canonical daily and triggers documented risk path"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            capacity_risk_alerts: true,
            risk_deadline_days: 14,
            enabled_kinds: ['daily', 'risk'],
            schedules: [[name: 'd', kind: 'Daily', schedule: '06:00', window: 'PT30M', horizon: 'P1D']]
        )
        def now = ZonedDateTime.of(2026, 8, 7, 6, 10, 0, 0, zone).toInstant()
        def svc = service(cfg, plans, gw, null, now)

        when:
        def due = svc.dueIntents()
        def receipts = svc.deliverDue(plan.id)

        then:
        cfg.messaging.schedules[0].kind == MessageRenderer.KIND_DAILY
        due.any { it.kind == MessageRenderer.KIND_DAILY }
        due.any { it.kind == MessageRenderer.KIND_RISK }
        !due.any { it.kind == 'Daily' || it.kind == 'daily' || it.kind == 'risk' }
        receipts.any { it.status == 'DELIVERED' && it.kind == MessageRenderer.KIND_DAILY }
        receipts.any { it.status == 'DELIVERED' && it.kind == MessageRenderer.KIND_RISK }
        gw.sent.every { it.kind in [MessageRenderer.KIND_DAILY, MessageRenderer.KIND_RISK] }
    }

    def "alias schedule kind risk yields canonical capacity_risk_alert with no render unknown"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            risk_deadline_days: 14,
            enabled_kinds: ['risk'],
            schedules: [[name: 'r', kind: 'RISK', schedule: '07:00', window: 'PT30M', horizon: 'P5D']]
        )
        def now = ZonedDateTime.of(2026, 8, 7, 7, 5, 0, 0, zone).toInstant()
        def svc = service(cfg, plans, gw, null, now)

        when:
        def due = svc.dueIntents()
        def receipts = svc.deliverDue(plan.id)

        then:
        cfg.messaging.schedules[0].kind == MessageRenderer.KIND_RISK
        due.size() == 1
        due[0].kind == MessageRenderer.KIND_RISK
        receipts.every { it.kind == MessageRenderer.KIND_RISK }
        !receipts.isEmpty()
        gw.sent.every { it.kind == MessageRenderer.KIND_RISK }
    }

    def "deliverDue with enabled_kinds daily only: daily send, no risk send or ledger"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def root = temp()
        def ledger = new DeliveryLedger(root.resolve('ledger'))
        // exclusive allowlist omits risk; default capacity_risk_alerts boolean must not re-enable it
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            risk_deadline_days: 14,
            enabled_kinds: ['daily_summary'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M', horizon: 'P1D']]
        )
        def now = ZonedDateTime.of(2026, 8, 7, 6, 10, 0, 0, zone).toInstant()
        def decisions = new DecisionStore(root.resolve('decisions'))
        def svc = new MessagingService(cfg, plans, gw, ledger, decisions,
            new FeedbackParser(decisions, { true }, { now }), null, { now })

        when:
        def due = svc.dueIntents()
        def receipts = svc.deliverDue(plan.id)

        then:
        cfg.messaging.isKindEnabled(MessageRenderer.KIND_DAILY)
        !cfg.messaging.isKindEnabled(MessageRenderer.KIND_RISK)
        due.any { it.kind == MessageRenderer.KIND_DAILY }
        !due.any { it.kind == MessageRenderer.KIND_RISK }
        receipts.size() == 1
        receipts[0].status == 'DELIVERED'
        receipts[0].kind == MessageRenderer.KIND_DAILY
        gw.callCount == 1
        gw.sent.every { it.kind == MessageRenderer.KIND_DAILY }
        !gw.sent.any { it.kind == MessageRenderer.KIND_RISK }
        !receipts.any { it.kind == MessageRenderer.KIND_RISK }
        // Ledger may retain PENDING+DELIVERED history for the daily key; no risk receipts.
        ledger.listReceiptIds().every { id ->
            def r = ledger.loadReceipt(id)
            r == null || r.kind != MessageRenderer.KIND_RISK
        }
        ledger.listReceiptIds().any { id ->
            ledger.loadReceipt(id)?.kind == MessageRenderer.KIND_DAILY
        }
    }

    def "deliverDue with enabled_kinds risk only sends risk"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            risk_deadline_days: 14,
            enabled_kinds: ['capacity_risk_alert'],
            schedules: [
                [name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M'],
                [name: 'r', kind: 'capacity_risk_alert', schedule: '06:00', window: 'PT30M']
            ]
        )
        def now = ZonedDateTime.of(2026, 8, 7, 6, 10, 0, 0, zone).toInstant()
        def svc = service(cfg, plans, gw, null, now)

        when:
        def due = svc.dueIntents()
        def receipts = svc.deliverDue(plan.id)

        then:
        !due.any { it.kind == MessageRenderer.KIND_DAILY }
        due.any { it.kind == MessageRenderer.KIND_RISK }
        receipts.every { it.kind == MessageRenderer.KIND_RISK }
        receipts.any { it.status == 'DELIVERED' && it.kind == MessageRenderer.KIND_RISK }
        gw.sent.every { it.kind == MessageRenderer.KIND_RISK }
        !gw.sent.any { it.kind == MessageRenderer.KIND_DAILY }
    }

    def "explicit risk schedule due/not-due and repeat idempotency"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            capacity_risk_alerts: true,
            risk_deadline_days: 14,
            enabled_kinds: ['capacity_risk_alert'],
            schedules: [[name: 'r', kind: 'capacity_risk_alert', schedule: '07:00', window: 'PT30M']]
        )
        def dueNow = ZonedDateTime.of(2026, 8, 7, 7, 5, 0, 0, zone).toInstant()
        def notDue = ZonedDateTime.of(2026, 8, 7, 8, 0, 0, 0, zone).toInstant()

        when:
        def svcDue = service(cfg, plans, gw, null, dueNow)
        def r1 = svcDue.deliverDue(plan.id)
        def r2 = svcDue.deliverDue(plan.id)
        def svcNot = service(cfg, plans, new InMemoryMessagingGateway(), null, notDue)
        def rNot = svcNot.deliverDue(plan.id)

        then:
        r1.any { it.status == 'DELIVERED' }
        r2.any { it.status == 'SKIPPED_DUPLICATE' }
        gw.callCount == 1
        rNot.every { it.status != 'DELIVERED' }
        svcNot.dueIntents().isEmpty()
    }

    def "feedback approve then explicit apply; reject zero writes"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def plan = samplePlan('approval_required')
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway(plan.tasks.collect { t ->
            [id: t.id, content: t.content, priority: t.priority]
        })
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def applier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        def gw = new InMemoryMessagingGateway()
        def svc = service(cfg, plans, gw, applier)
        def hash = PlanHash.compute(plan)
        def prop = Proposal.fromPlan(plan)

        when: 'parse approve — no writes yet'
        def fb = svc.handleFeedback(plan.id,
            "approve ${prop.id} ${hash.substring(0, 12)}",
            new FeedbackParser.FeedbackContext(actorId: 'jorsten', correlationId: 'a1'))
        def calAfterParse = cal.upserts.size()
        def todoAfterParse = todo.dueUpdates.size()

        and: 'explicit apply'
        def applyResult = svc.applyDecision(plan.id, fb.decision)
        def receipt = applyResult.receipt

        and: 'reject path'
        def planReject = Plan.builder().id('plan-rej').version(1).createdAt(plan.createdAt)
            .mode('approval_required').tasks(plan.tasks).scheduledBlocks(plan.scheduledBlocks)
            .unscheduled(plan.unscheduled).changes(plan.changes).build()
        plans.save(planReject)
        def hashR = PlanHash.compute(planReject)
        def propR = Proposal.fromPlan(planReject)
        def rej = svc.handleFeedback(planReject.id,
            "reject ${propR.id} ${hashR.substring(0, 12)} nope",
            new FeedbackParser.FeedbackContext(actorId: 'jorsten', correlationId: 'r1'))
        def appliedReject = svc.applyDecision(planReject.id, rej.decision)
        def calAfterReject = cal.upserts.size()
        def todoAfterReject = todo.dueUpdates.size()

        then:
        fb.accepted
        fb.approval != null
        calAfterParse == 0
        todoAfterParse == 0
        applyResult.isApplied()
        receipt != null
        receipt.approvalId == fb.decision.id
        rej.accepted
        appliedReject.isNoop()
        appliedReject.receipt == null
        calAfterReject == cal.upserts.size()
        todoAfterReject == todo.dueUpdates.size()
    }

    def "applyDecision ACCEPTED approve once; replay conflict malformed wrong-hash never apply"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def plan = samplePlan('approval_required')
        plans.save(plan)
        def applyCalls = new AtomicInteger(0)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway(plan.tasks.collect { t ->
            [id: t.id, content: t.content, priority: t.priority]
        })
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def realApplier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        PlanApplier spyApplier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') }) {
            @Override
            ApplicationReceipt apply(Plan p, todoistcaldavsync.planner.domain.Approval a) {
                applyCalls.incrementAndGet()
                return realApplier.apply(p, a)
            }
        }
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), spyApplier)
        def hash = PlanHash.compute(plan)
        def prop = Proposal.fromPlan(plan)
        def now = Instant.parse('2026-08-07T12:00:00Z')

        when: 'exact ACCEPTED APPROVE applies once'
        def fb = svc.handleFeedback(plan.id,
            "approve ${prop.id} ${hash.substring(0, 12)}",
            new FeedbackParser.FeedbackContext(actorId: 'jorsten', correlationId: 'once'))
        def r1 = svc.applyDecision(plan.id, fb.decision)

        and: 'IDEMPOTENT_REPLAY does not apply'
        def replay = DecisionRecord.builder()
            .id('dec-replay').proposalId(prop.id).planId(plan.id).planVersion(plan.version)
            .planHash(hash).action('APPROVE').status('IDEMPOTENT_REPLAY')
            .actorId('jorsten').correlationId('once').decidedAt(now)
            .previousDecisionId(fb.decision.id).build()
        def rReplay = svc.applyDecision(plan.id, replay)

        and: 'CONFLICT throws, no apply'
        def conflict = DecisionRecord.builder()
            .id('dec-c').proposalId(prop.id).planId(plan.id).planVersion(plan.version)
            .planHash(hash).action('APPROVE').status('REJECTED_REPLAY_CONFLICT')
            .actorId('jorsten').correlationId('c').decidedAt(now).build()
        Exception conflictEx = null
        try {
            svc.applyDecision(plan.id, conflict)
        } catch (Exception e) {
            conflictEx = e
        }

        and: 'malformed APPROVE ACCEPTED missing hash refused at builder (cannot bypass)'
        Exception malEx = null
        try {
            DecisionRecord.builder()
                .id('dec-m').proposalId(prop.id).planId(plan.id).planVersion(plan.version)
                .planHash(null).action('APPROVE').status('ACCEPTED')
                .actorId('jorsten').correlationId('m').decidedAt(now).build()
        } catch (Exception e) {
            malEx = e
        }
        // Also reject forged planHash='none' at apply boundary if somehow constructed as REJECTED path
        Exception noneEx = null
        try {
            def forgedNone = DecisionRecord.builder()
                .id('dec-none').proposalId(prop.id).planId(plan.id).planVersion(plan.version)
                .planHash('none').action('APPROVE').status('ACCEPTED')
                .actorId('jorsten').correlationId('none').decidedAt(now).build()
            svc.applyDecision(plan.id, forgedNone)
        } catch (Exception e) {
            noneEx = e
        }

        and: 'wrong hash throws, no apply'
        def wrongHash = DecisionRecord.builder()
            .id('dec-w').proposalId(prop.id).planId(plan.id).planVersion(plan.version)
            .planHash('deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef')
            .action('APPROVE').status('ACCEPTED')
            .actorId('jorsten').correlationId('w').decidedAt(now).build()
        Exception whEx = null
        try {
            svc.applyDecision(plan.id, wrongHash)
        } catch (Exception e) {
            whEx = e
        }

        then:
        r1.isApplied()
        r1.receipt != null
        applyCalls.get() == 1
        rReplay.isReplayed()
        rReplay.receipt == null
        conflictEx instanceof IllegalArgumentException
        malEx instanceof IllegalArgumentException
        noneEx instanceof IllegalArgumentException
        whEx instanceof IllegalArgumentException
        applyCalls.get() == 1
    }

    /**
     * Four distinct apply-safe blocks for exact protection coverage:
     * SAFE ordinary, FROZEN, MANUAL (override, not frozen), APPROVAL (ordinary + PlanChange.approvalRequired).
     */
    private Plan applySafeFourBlockPlan(String mode = 'apply_safe_changes') {
        Instant start = LocalDateTime.of(2026, 8, 7, 9, 0).atZone(zone).toInstant()
        def tSafe = Task.builder().id('t-safe').content('Safe task').priority(1)
            .effectiveDuration(Duration.ofMinutes(60)).durationSource('t').build()
        def tFrozen = Task.builder().id('t-frozen').content('Frozen task').priority(1)
            .effectiveDuration(Duration.ofMinutes(60)).durationSource('t').build()
        def tManual = Task.builder().id('t-manual').content('Manual task').priority(1)
            .effectiveDuration(Duration.ofMinutes(60)).durationSource('t').build()
        def tApproval = Task.builder().id('t-approval').content('Approval task').priority(1)
            .effectiveDuration(Duration.ofMinutes(60)).durationSource('t').build()
        def bSafe = ScheduledBlock.builder().id('b-safe')
            .start(start).end(start + Duration.ofHours(1))
            .taskIds(['t-safe']).title('Safe block').reason('fit').build()
        def bFrozen = ScheduledBlock.builder().id('b-frozen')
            .start(start + Duration.ofHours(1)).end(start + Duration.ofHours(2))
            .taskIds(['t-frozen']).title('Frozen block').reason('kept').frozen(true).build()
        def bManual = ScheduledBlock.builder().id('b-manual')
            .start(start + Duration.ofHours(2)).end(start + Duration.ofHours(3))
            .taskIds(['t-manual']).title('Manual block').reason('kept')
            .manualOverride(true).build()
        def bApproval = ScheduledBlock.builder().id('b-approval')
            .start(start + Duration.ofHours(3)).end(start + Duration.ofHours(4))
            .taskIds(['t-approval']).title('Approval block').reason('near-term').build()
        def chSafe = PlanChange.builder().id('c-safe').type('add').taskId('t-safe')
            .newStart(start).reason('new').build()
        def chApproval = PlanChange.builder().id('c-approval').type('move').taskId('t-approval')
            .newStart(start + Duration.ofHours(3)).reason('within_horizon')
            .metadata([approvalRequired: true]).build()
        Plan.builder().id('plan-apply-safe').version(1)
            .createdAt(Instant.parse('2026-08-07T08:00:00Z'))
            .mode(mode)
            .tasks([tSafe, tFrozen, tManual, tApproval])
            .scheduledBlocks([bSafe, bFrozen, bManual, bApproval])
            .changes([chSafe, chApproval])
            .build()
    }

    def "apply-safe ACCEPTED applies safe only; protected frozen manual approvalRequired skipped exactly"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def plan = applySafeFourBlockPlan('apply_safe_changes')
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway(plan.tasks.collect { t ->
            [id: t.id, content: t.content, priority: t.priority]
        })
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'apply_safe_changes',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def applyCalls = new AtomicInteger(0)
        def safeCalls = new AtomicInteger(0)
        def realApplier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        PlanApplier spyApplier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') }) {
            @Override
            ApplicationReceipt apply(Plan p, todoistcaldavsync.planner.domain.Approval a) {
                applyCalls.incrementAndGet()
                return realApplier.apply(p, a)
            }
            @Override
            ApplicationReceipt applySafeChanges(Plan p) {
                safeCalls.incrementAndGet()
                return realApplier.applySafeChanges(p)
            }
        }
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), spyApplier)
        def hash = PlanHash.compute(plan)
        def prop = Proposal.fromPlan(plan)
        def now = Instant.parse('2026-08-07T12:00:00Z')
        String safeUid = todoistcaldavsync.planner.domain.ManagedEventIds.uidForBlock('b-safe')
        String frozenUid = todoistcaldavsync.planner.domain.ManagedEventIds.uidForBlock('b-frozen')
        String manualUid = todoistcaldavsync.planner.domain.ManagedEventIds.uidForBlock('b-manual')
        String approvalUid = todoistcaldavsync.planner.domain.ManagedEventIds.uidForBlock('b-approval')

        when: 'exact ACCEPTED APPLY_SAFE DecisionRecord bound to plan id/version/hash'
        def decision = DecisionRecord.builder()
            .id('dec-apply-safe-exact').proposalId(prop.id)
            .planId(plan.id).planVersion(plan.version).planHash(hash)
            .action('APPLY_SAFE').status('ACCEPTED')
            .actorId('jorsten').correlationId('safe-exact').decidedAt(now).build()
        def applyRes = svc.applyDecision(plan.id, decision)
        def receipt = applyRes.receipt

        and: 'IDEMPOTENT_REPLAY apply-safe must not call applier again'
        def replay = DecisionRecord.builder()
            .id('dec-safe-replay').proposalId(prop.id).planId(plan.id).planVersion(plan.version)
            .planHash(hash).action('APPLY_SAFE').status('IDEMPOTENT_REPLAY')
            .actorId('jorsten').correlationId('safe-exact').decidedAt(now)
            .previousDecisionId(decision.id).build()
        def rReplay = svc.applyDecision(plan.id, replay)

        and: 'CONFLICT apply-safe throws, zero additional applier calls'
        def conflict = DecisionRecord.builder()
            .id('dec-safe-conflict').proposalId(prop.id).planId(plan.id).planVersion(plan.version)
            .planHash(hash).action('APPLY_SAFE').status('REJECTED_REPLAY_CONFLICT')
            .actorId('jorsten').correlationId('safe-c').decidedAt(now).build()
        Exception conflictEx = null
        try {
            svc.applyDecision(plan.id, conflict)
        } catch (Exception e) {
            conflictEx = e
        }

        then:
        decision.action == 'APPLY_SAFE'
        decision.status == 'ACCEPTED'
        decision.planId == plan.id
        decision.planVersion == plan.version
        decision.planHash == hash
        applyRes.isApplied()
        receipt != null
        receipt.mode == 'apply_safe_changes'
        safeCalls.get() == 1
        applyCalls.get() == 0
        rReplay.isReplayed()
        rReplay.receipt == null
        conflictEx instanceof IllegalArgumentException
        safeCalls.get() == 1
        applyCalls.get() == 0

        // Exact SAFE writes only: one calendar event + one Todoist due for t-safe
        cal.upserts.size() == 1
        cal.upserts[0].uid == safeUid
        cal.upserts[0].title == 'Safe block'
        todo.dueUpdates.size() == 1
        todo.dueUpdates[0].taskId == 't-safe'
        !cal.upserts.any { it.uid == frozenUid || it.uid == manualUid || it.uid == approvalUid }
        !todo.dueUpdates.any { it.taskId in ['t-frozen', 't-manual', 't-approval'] as Set }

        // SAFE applied
        def safeItem = receipt.items.find { it.taskId == 't-safe' }
        safeItem != null
        safeItem.calendarStatus == ApplyItemStatus.APPLIED
        safeItem.todoistStatus == ApplyItemStatus.APPLIED

        // FROZEN: SKIPPED_PROTECTED, zero writes
        def frozenItem = receipt.items.find { it.taskId == 't-frozen' }
        frozenItem != null
        frozenItem.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED
        frozenItem.todoistStatus == ApplyItemStatus.SKIPPED_PROTECTED
        frozenItem.metadata.frozen == true

        // MANUAL: SKIPPED_PROTECTED (manualOverride, not frozen), zero writes
        def manualItem = receipt.items.find { it.taskId == 't-manual' }
        manualItem != null
        manualItem.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED
        manualItem.todoistStatus == ApplyItemStatus.SKIPPED_PROTECTED
        manualItem.metadata.manualOverride == true
        manualItem.metadata.frozen == false

        // APPROVAL: ordinary block + PlanChange.approvalRequired → SKIPPED_UNAPPROVED, zero writes
        def approvalItem = receipt.items.find { it.taskId == 't-approval' }
        approvalItem != null
        approvalItem.calendarStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        approvalItem.todoistStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        approvalItem.metadata.approvalRequired == true

        receipt.items.size() == 4
    }

    def "APPLY_SAFE on stored approval_required plan writes only safe; receipt mode apply_safe_changes; APPLIED"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def plan = applySafeFourBlockPlan('approval_required')
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway(plan.tasks.collect { t ->
            [id: t.id, content: t.content, priority: t.priority]
        })
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def safeCalls = new AtomicInteger(0)
        def applyCalls = new AtomicInteger(0)
        def realApplier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        PlanApplier spyApplier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') }) {
            @Override
            ApplicationReceipt apply(Plan p, todoistcaldavsync.planner.domain.Approval a) {
                applyCalls.incrementAndGet()
                return realApplier.apply(p, a)
            }
            @Override
            ApplicationReceipt applySafeChanges(Plan p) {
                safeCalls.incrementAndGet()
                return realApplier.applySafeChanges(p)
            }
        }
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), spyApplier)
        def hash = PlanHash.compute(plan)
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def decision = DecisionRecord.builder()
            .id('dec-safe-ar').proposalId(Proposal.fromPlan(plan).id)
            .planId(plan.id).planVersion(plan.version).planHash(hash)
            .action('APPLY_SAFE').status('ACCEPTED')
            .actorId('jorsten').correlationId('safe-ar').decidedAt(now).build()

        when:
        def applyRes = svc.applyDecision(plan.id, decision)

        then:
        plan.mode == 'approval_required'
        applyRes.isApplied()
        applyRes.status == MessagingService.ApplyDecisionResult.Status.APPLIED
        applyRes.receipt != null
        applyRes.receipt.mode == 'apply_safe_changes'
        applyRes.receipt.metadata.effectiveMode == 'apply_safe_changes'
        safeCalls.get() == 1
        applyCalls.get() == 0
        cal.upserts.size() == 1
        todo.dueUpdates.size() == 1
        todo.dueUpdates[0].taskId == 't-safe'
        applyRes.receipt.items.find { it.taskId == 't-safe' }.calendarStatus == ApplyItemStatus.APPLIED
        applyRes.receipt.items.find { it.taskId == 't-frozen' }.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED
        applyRes.receipt.items.find { it.taskId == 't-manual' }.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED
        applyRes.receipt.items.find { it.taskId == 't-approval' }.calendarStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        // plan identity/hash unchanged (not mutated)
        PlanHash.compute(plans.load(plan.id)) == hash
        plans.load(plan.id).mode == 'approval_required'
    }

    def "APPLY_SAFE protected-only plan is structured NOOP with receipt retained; zero gateway writes"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        Instant start = java.time.LocalDateTime.of(2026, 8, 7, 9, 0).atZone(zone).toInstant()
        def tFrozen = Task.builder().id('t-frozen').content('Frozen').priority(1)
            .effectiveDuration(Duration.ofMinutes(60)).durationSource('t').build()
        def bFrozen = ScheduledBlock.builder().id('b-frozen')
            .start(start).end(start + Duration.ofHours(1))
            .taskIds(['t-frozen']).title('Frozen').reason('kept').frozen(true).build()
        def plan = Plan.builder().id('plan-prot-only').version(1)
            .createdAt(Instant.parse('2026-08-07T08:00:00Z'))
            .mode('approval_required')
            .tasks([tFrozen]).scheduledBlocks([bFrozen]).build()
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway([[id: 't-frozen', content: 'Frozen', priority: 1]])
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def applier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), applier)
        def hash = PlanHash.compute(plan)
        def decision = DecisionRecord.builder()
            .id('dec-prot').proposalId(Proposal.fromPlan(plan).id)
            .planId(plan.id).planVersion(1).planHash(hash)
            .action('APPLY_SAFE').status('ACCEPTED')
            .actorId('jorsten').correlationId('prot-only').decidedAt(Instant.parse('2026-08-07T12:00:00Z')).build()

        when:
        def applyRes = svc.applyDecision(plan.id, decision)

        then:
        applyRes.isNoop()
        applyRes.status == MessagingService.ApplyDecisionResult.Status.NOOP
        !applyRes.isApplied()
        applyRes.receipt != null
        applyRes.receipt.mode == 'apply_safe_changes'
        applyRes.reason == 'protected-only: zero writes'
        cal.upserts.isEmpty()
        todo.dueUpdates.isEmpty()
        applyRes.receipt.items.every { it.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED }
    }

    def "generic apply(plan,null) on approval_required still refuses; APPLY_SAFE path distinct"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def plan = applySafeFourBlockPlan('approval_required')
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway(plan.tasks.collect { t ->
            [id: t.id, content: t.content, priority: t.priority]
        })
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def applier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })

        when:
        def generic = applier.apply(plan, null)

        then:
        generic.overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        generic.mode == 'approval_required'
        cal.upserts.isEmpty()
        todo.dueUpdates.isEmpty()

        when:
        def safe = applier.applySafeChanges(plan)

        then:
        safe.mode == 'apply_safe_changes'
        safe.wroteAnything()
        cal.upserts.size() == 1
        todo.dueUpdates.size() == 1
    }

    def "ApplyDecisionResult classifies empty plan NOOP and retains receipt"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def plan = Plan.builder().id('plan-empty').version(1)
            .createdAt(Instant.parse('2026-08-07T08:00:00Z'))
            .mode('approval_required').tasks([]).scheduledBlocks([]).build()
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway([])
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def applier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), applier)
        def hash = PlanHash.compute(plan)
        def decision = DecisionRecord.builder()
            .id('dec-empty').proposalId(Proposal.fromPlan(plan).id)
            .planId(plan.id).planVersion(1).planHash(hash)
            .action('APPLY_SAFE').status('ACCEPTED')
            .actorId('jorsten').correlationId('empty').decidedAt(Instant.parse('2026-08-07T12:00:00Z')).build()

        when:
        def res = svc.applyDecision(plan.id, decision)

        then:
        res.isNoop()
        res.receipt != null
        res.receipt.overallStatus == ApplyItemStatus.SKIPPED_NO_CHANGES
        res.reason == 'empty plan: no scheduled blocks'
        cal.upserts.isEmpty()
    }

    def "applyDecision APPLY_SAFE fully_automated plan or config maps REJECTED never APPLIED zero writes"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def start = Instant.parse('2026-08-10T14:00:00Z')
        def block = ScheduledBlock.builder()
            .id('b1').start(start).end(start + java.time.Duration.ofMinutes(30))
            .taskIds(['t1']).title('Safe').reason('t').build()
        def plan = Plan.builder().id('plan-fa').version(1)
            .createdAt(Instant.parse('2026-08-07T08:00:00Z'))
            .mode(planMode).tasks([]).scheduledBlocks([block]).build()
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway([[id: 't1', content: 'T', priority: 2]])
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : configMode,
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def applier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), applier)
        def hash = PlanHash.compute(plan)
        def decision = DecisionRecord.builder()
            .id("dec-fa-${planMode}-${configMode}").proposalId(Proposal.fromPlan(plan).id)
            .planId(plan.id).planVersion(1).planHash(hash)
            .action('APPLY_SAFE').status('ACCEPTED')
            .actorId('jorsten').correlationId("fa-${planMode}-${configMode}")
            .decidedAt(Instant.parse('2026-08-07T12:00:00Z')).build()

        when:
        def res = svc.applyDecision(plan.id, decision)

        then:
        res.isRejected()
        !res.isApplied()
        res.status == MessagingService.ApplyDecisionResult.Status.REJECTED
        res.receipt != null
        res.receipt.overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        res.receipt.mode == 'apply_safe_changes'
        res.receipt.metadata.refused == true
        !res.receipt.wroteAnything()
        cal.upserts.isEmpty()
        todo.dueUpdates.isEmpty()

        where:
        planMode            | configMode
        'fully_automated'   | 'approval_required'
        'approval_required' | 'fully_automated'
    }

    def "applyDecision ACCEPTED APPROVE applies protected frozen manual and approvalRequired with valid approval"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def plan = applySafeFourBlockPlan('approval_required')
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway(plan.tasks.collect { t ->
            [id: t.id, content: t.content, priority: t.priority]
        })
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def applyCalls = new AtomicInteger(0)
        def realApplier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        PlanApplier spyApplier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') }) {
            @Override
            ApplicationReceipt apply(Plan p, todoistcaldavsync.planner.domain.Approval a) {
                applyCalls.incrementAndGet()
                return realApplier.apply(p, a)
            }
        }
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), spyApplier)
        def hash = PlanHash.compute(plan)
        def prop = Proposal.fromPlan(plan)

        when:
        def fb = svc.handleFeedback(plan.id,
            "approve ${prop.id} ${hash.substring(0, 12)}",
            new FeedbackParser.FeedbackContext(actorId: 'jorsten', correlationId: 'appr-all'))
        def applyRes = svc.applyDecision(plan.id, fb.decision)
        def receipt = applyRes.receipt

        then:
        fb.decision.action == 'APPROVE'
        fb.decision.status == 'ACCEPTED'
        applyRes.isApplied()
        receipt != null
        receipt.mode == 'approval_required'
        applyCalls.get() == 1
        cal.upserts.size() == 4
        todo.dueUpdates.size() == 4
        todo.dueUpdates*.taskId as Set == ['t-safe', 't-frozen', 't-manual', 't-approval'] as Set
        receipt.items.every {
            it.calendarStatus == ApplyItemStatus.APPLIED && it.todoistStatus == ApplyItemStatus.APPLIED
        }
        receipt.items.size() == 4
    }

    def "due intents respect enabled kinds and schedules"() {
        given:
        def cfg = baseConfig(
            enabled: true,
            provider: 'slack',
            destination: '#p',
            webhook_url_env: 'W',
            schedules: [
                [name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M'],
                [name: 'w', kind: 'weekly_summary', schedule: 'mon 09:00', window: 'PT30M']
            ]
        )
        def plans = new PlanStore(temp())
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), null,
            ZonedDateTime.of(2026, 8, 7, 6, 10, 0, 0, zone).toInstant())

        when:
        def due = svc.dueIntents()

        then:
        due.any { it.kind == 'daily_summary' }
        !due.any { it.kind == 'weekly_summary' }
    }

    def "messaging timezone overrides planner timezone for due and render"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        // Planner UTC; messaging America/New_York — 06:00 ET due window
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'preview',
            timezone       : 'UTC',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [
                enabled        : true,
                provider       : 'in_memory',
                destination    : '#p',
                webhook_url_env: 'X',
                timezone       : 'America/New_York',
                enabled_kinds  : ['daily_summary'],
                schedules      : [[name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M']]
            ]
        ])
        // 10:10 UTC = 06:10 ET in August
        def now = Instant.parse('2026-08-07T10:10:00Z')
        def svc = service(cfg, plans, gw, null, now)

        when:
        def due = svc.dueIntents()
        def msgs = svc.renderKind(plan, MessageRenderer.KIND_DAILY, now)

        then:
        due.any { it.kind == 'daily_summary' }
        due[0].zone.id == 'America/New_York'
        msgs.size() == 1
        msgs[0].body.contains('America/New_York')
        msgs[0].body.contains('9:00 AM')
        msgs[0].metadata.zone == 'America/New_York'
    }

    def "configured schedule horizon drives summary range not hardcoded defaults"() {
        given:
        def plans = new PlanStore(temp())
        // blocks on day 0, 2, 10
        Instant d0 = LocalDateTime.of(2026, 8, 7, 9, 0).atZone(zone).toInstant()
        Instant d2 = LocalDateTime.of(2026, 8, 9, 9, 0).atZone(zone).toInstant()
        Instant d10 = LocalDateTime.of(2026, 8, 17, 9, 0).atZone(zone).toInstant()
        def blocks = [
            ScheduledBlock.builder().id('b0').start(d0).end(d0 + Duration.ofHours(1))
                .taskIds(['t0']).title('Day0').reason('x').build(),
            ScheduledBlock.builder().id('b2').start(d2).end(d2 + Duration.ofHours(1))
                .taskIds(['t2']).title('Day2').reason('x').build(),
            ScheduledBlock.builder().id('b10').start(d10).end(d10 + Duration.ofHours(1))
                .taskIds(['t10']).title('Day10').reason('x').build()
        ]
        def plan = Plan.builder().id('plan-h').version(1)
            .createdAt(Instant.parse('2026-08-07T08:00:00Z'))
            .mode('preview').scheduledBlocks(blocks).build()
        plans.save(plan)
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
            enabled_kinds: ['weekly_summary'],
            schedules: [[name: 'w', kind: 'weekly_summary', schedule: 'mon 09:00', horizon: 'P3D']]
        )
        def now = Instant.parse('2026-08-07T14:00:00Z')
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), null, now)

        when:
        def msgs = svc.renderKind(plan, MessageRenderer.KIND_WEEKLY, now, Duration.ofDays(3))

        then:
        msgs.size() == 1
        def msg = msgs[0]
        msg.body.contains('Day0')
        msg.body.contains('Day2')
        !msg.body.contains('Day10')
        msg.metadata.horizon == 'PT72H' || msg.metadata.horizonDays == 3L || msg.body.contains('P3') ||
            msg.body.contains('2026-08-10')
        msg.body.contains('Horizon:')
    }

    def "ledger corruption on index surfaces PlanStoreException"() {
        given:
        def root = temp()
        def ledger = new DeliveryLedger(root)
        Files.createDirectories(root)
        Files.writeString(root.resolve('delivery-index.json'), '{not-json')

        when:
        ledger.wasDelivered('k')

        then:
        thrown(PlanStoreException)
    }

    private Plan planWithoutQualifyingRisks() {
        def t = Task.builder().id('t1').content('Deep work').priority(1)
            .effectiveDuration(Duration.ofMinutes(60)).durationSource('t').build()
        // Unscheduled but not a capacity risk (no risk/deadline/capacity code/reason)
        def plain = Task.builder().id('t-plain').content('Optional chore').priority(4)
            .effectiveDuration(Duration.ofMinutes(15)).durationSource('t').build()
        Instant start = LocalDateTime.of(2026, 8, 7, 9, 0).atZone(zone).toInstant()
        def block = ScheduledBlock.builder().id('b1').start(start).end(start + Duration.ofHours(1))
            .taskIds(['t1']).title('Deep work').reason('fit').build()
        def unsched = new UnscheduledTask(plain, 'deferred by user preference', 'user_deferred', [:])
        Plan.builder().id('plan-no-risk').version(1)
            .createdAt(Instant.parse('2026-08-07T08:00:00Z'))
            .mode('preview').tasks([t, plain])
            .scheduledBlocks([block])
            .unscheduled([unsched])
            .build()
    }

    def "explicit risk due with zero qualifying risks: no send, no ledger, no daily_summary"() {
        given:
        def plans = new PlanStore(temp())
        def plan = planWithoutQualifyingRisks()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def root = temp()
        def ledger = new DeliveryLedger(root.resolve('ledger'))
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            capacity_risk_alerts: true,
            risk_deadline_days: 14,
            enabled_kinds: ['capacity_risk_alert'],
            schedules: [[name: 'r', kind: 'capacity_risk_alert', schedule: '07:00', window: 'PT30M']]
        )
        def dueNow = ZonedDateTime.of(2026, 8, 7, 7, 5, 0, 0, zone).toInstant()
        def decisions = new DecisionStore(root.resolve('decisions'))
        def parser = new FeedbackParser(decisions, { true }, { dueNow })
        def svc = new MessagingService(cfg, plans, gw, ledger, decisions, parser, null, { dueNow })

        when:
        def due = svc.dueIntents()
        def rendered = svc.renderKind(plan, MessageRenderer.KIND_RISK, dueNow)
        def receipts = svc.deliverDue(plan.id)
        def kindReceipts = svc.deliverKind(plan.id, MessageRenderer.KIND_RISK)

        then:
        due.any { it.kind == MessageRenderer.KIND_RISK }
        rendered.isEmpty()
        receipts.isEmpty()
        kindReceipts.isEmpty()
        gw.callCount == 0
        gw.sent.isEmpty()
        ledger.listReceiptIds().isEmpty()
        // Must not fall back to daily_summary kind or body
        !gw.sent.any { it.kind == MessageRenderer.KIND_DAILY }
        !gw.sent.any { it.body?.contains("Today's feasible plan") }
    }

    def "risk due with qualifying risk sends exact capacity_risk_alert kind"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            capacity_risk_alerts: true,
            risk_deadline_days: 14,
            enabled_kinds: ['capacity_risk_alert'],
            schedules: [[name: 'r', kind: 'capacity_risk_alert', schedule: '07:00', window: 'PT30M']]
        )
        def dueNow = ZonedDateTime.of(2026, 8, 7, 7, 5, 0, 0, zone).toInstant()
        def svc = service(cfg, plans, gw, null, dueNow)

        when:
        def rendered = svc.renderKind(plan, MessageRenderer.KIND_RISK, dueNow)
        def receipts = svc.deliverDue(plan.id)

        then:
        rendered.size() == 1
        rendered[0].kind == MessageRenderer.KIND_RISK
        !rendered[0].body.contains("Today's feasible plan")
        receipts.size() == 1
        receipts[0].status == 'DELIVERED'
        receipts[0].kind == MessageRenderer.KIND_RISK
        gw.callCount == 1
        gw.sent[0].kind == MessageRenderer.KIND_RISK
        gw.sent[0].body.contains('Paint the Deck')
    }

    def "daily intent still renders daily summary independently of empty risk"() {
        given:
        def plans = new PlanStore(temp())
        def plan = planWithoutQualifyingRisks()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def cfg = baseConfig(
            enabled: true, provider: 'slack', destination: '#alerts',
            webhook_url_env: 'W',
            capacity_risk_alerts: true,
            risk_deadline_days: 14,
            enabled_kinds: ['daily_summary', 'capacity_risk_alert'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M', horizon: 'P1D']]
        )
        def now = ZonedDateTime.of(2026, 8, 7, 6, 10, 0, 0, zone).toInstant()
        def svc = service(cfg, plans, gw, null, now)

        when:
        def due = svc.dueIntents()
        def dailyMsgs = svc.renderKind(plan, MessageRenderer.KIND_DAILY, now)
        def riskMsgs = svc.renderKind(plan, MessageRenderer.KIND_RISK, now)
        def receipts = svc.deliverDue(plan.id)

        then:
        due.any { it.kind == MessageRenderer.KIND_DAILY }
        due.any { it.kind == MessageRenderer.KIND_RISK }
        dailyMsgs.size() == 1
        dailyMsgs[0].kind == MessageRenderer.KIND_DAILY
        dailyMsgs[0].body.contains("Today's feasible plan")
        riskMsgs.isEmpty()
        // Only daily summary sent; empty risk produces no receipt/send
        receipts.size() == 1
        receipts[0].kind == MessageRenderer.KIND_DAILY
        receipts[0].status == 'DELIVERED'
        gw.callCount == 1
        gw.sent[0].kind == MessageRenderer.KIND_DAILY
        !gw.sent.any { it.kind == MessageRenderer.KIND_RISK }
    }

    def "renderKind returns list for all kinds"() {
        given:
        def plans = new PlanStore(temp())
        def withRisk = samplePlan()
        def noRisk = planWithoutQualifyingRisks()
        plans.save(withRisk)
        plans.save(noRisk)
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
            enabled_kinds: ['daily_summary', 'weekly_summary', 'medium_horizon_summary',
                            'capacity_risk_alert', 'proposal'],
            capacity_risk_alerts: true, risk_deadline_days: 14
        )
        def now = Instant.parse('2026-08-07T14:00:00Z')
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), null, now)

        expect:
        def daily = svc.renderKind(withRisk, MessageRenderer.KIND_DAILY, now)
        daily.size() == 1 && daily[0].kind == MessageRenderer.KIND_DAILY

        def weekly = svc.renderKind(withRisk, MessageRenderer.KIND_WEEKLY, now, Duration.ofDays(7))
        weekly.size() == 1 && weekly[0].kind == MessageRenderer.KIND_WEEKLY

        def medium = svc.renderKind(withRisk, MessageRenderer.KIND_MEDIUM, now, Duration.ofDays(14))
        medium.size() == 1 && medium[0].kind == MessageRenderer.KIND_MEDIUM

        def proposal = svc.renderKind(withRisk, MessageRenderer.KIND_PROPOSAL, now)
        proposal.size() == 1 && proposal[0].kind == MessageRenderer.KIND_PROPOSAL

        def risks = svc.renderKind(withRisk, MessageRenderer.KIND_RISK, now)
        risks.size() >= 1 && risks.every { it.kind == MessageRenderer.KIND_RISK }

        def emptyRisk = svc.renderKind(noRisk, MessageRenderer.KIND_RISK, now)
        emptyRisk.isEmpty()
    }

    def "concurrent same-key two service instances yield exactly one provider send"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def dir = temp()
        def ledgerDir = dir.resolve('ledger')
        def calls = new AtomicInteger(0)
        def countingGw = new todoistcaldavsync.planner.adapters.MessagingGateway() {
            @Override
            DeliveryReceipt send(Message message) {
                int n = calls.incrementAndGet()
                Instant t = Instant.parse('2026-08-07T14:00:00Z')
                return DeliveryReceipt.builder()
                    .id("gw-${n}").idempotencyKey(message.idempotencyKey)
                    .kind(message.kind).destination(message.destination)
                    .planId(message.planId).planVersion(message.planVersion).planHash(message.planHash)
                    .status('DELIVERED').providerMessageId("ts-${n}")
                    .attemptedAt(t).completedAt(t).build()
            }
        }
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#p',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary']
        )
        def now = Instant.parse('2026-08-07T14:00:00Z')
        def barrier = new java.util.concurrent.CyclicBarrier(2)
        def pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        def results = java.util.Collections.synchronizedList([])

        when:
        def futures = (0..<2).collect { i ->
            pool.submit {
                def ledger = new DeliveryLedger(ledgerDir)
                def decisions = new DecisionStore(dir.resolve("dec-${i}"))
                def parser = new FeedbackParser(decisions, { true }, { now })
                def svc = new MessagingService(cfg, plans, countingGw, ledger, decisions, parser, null, { now })
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                results << svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)
            }
        }
        futures.each { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
        pool.shutdown()

        then:
        // Exactly one provider call — loser never calls gateway
        calls.get() == 1
        results.size() == 2
        def flat = results.collectMany { it }
        flat.count { it.status == 'DELIVERED' } == 1
        flat.any {
            it.status == 'SKIPPED_DUPLICATE' || it.status == 'NEEDS_RECONCILIATION' ||
                it.metadata?.idempotentNoop == true
        }
        flat.findAll { it.status == 'DELIVERED' }.every { !it.metadata?.idempotentNoop }
        new DeliveryLedger(ledgerDir).wasDelivered(flat.find { it.status == 'DELIVERED' }.idempotencyKey)
    }

    def "concurrent distinct keys both send"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def ledgerDir = temp()
        def calls = new AtomicInteger(0)
        def gw = new todoistcaldavsync.planner.adapters.MessagingGateway() {
            @Override
            DeliveryReceipt send(Message message) {
                int n = calls.incrementAndGet()
                Instant t = Instant.parse('2026-08-07T14:00:00Z')
                return DeliveryReceipt.builder()
                    .id("g-${n}").idempotencyKey(message.idempotencyKey)
                    .kind(message.kind).destination(message.destination)
                    .status('DELIVERED').providerMessageId("ts-${n}")
                    .attemptedAt(t).completedAt(t).build()
            }
        }
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#p',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary', 'weekly_summary']
        )
        def now = Instant.parse('2026-08-07T14:00:00Z')
        def ledger = new DeliveryLedger(ledgerDir)
        def decisions = new DecisionStore(temp())
        def svc = new MessagingService(cfg, plans, gw, ledger, decisions,
            new FeedbackParser(decisions, { true }, { now }), null, { now })

        when:
        def d = svc.deliverKind(plan.id, MessageRenderer.KIND_DAILY)
        def w = svc.deliverKind(plan.id, MessageRenderer.KIND_WEEKLY)

        then:
        calls.get() == 2
        d[0].status == 'DELIVERED'
        w[0].status == 'DELIVERED'
        d[0].idempotencyKey != w[0].idempotencyKey
    }

    // --- Recurring-delivery occurrence idempotency ---

    private MessagingService serviceAt(PlannerConfig cfg, PlanStore plans, InMemoryMessagingGateway gw,
                                       DeliveryLedger ledger, Instant now) {
        def decisions = new DecisionStore(temp())
        new MessagingService(cfg, plans, gw, ledger, decisions,
            new FeedbackParser(decisions, { true }, { now }), null, { now })
    }

    def "daily unchanged plan: Monday send, same-window retry skip, Tuesday new occurrence sends"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def ledger = new DeliveryLedger(temp())
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#planner',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M', horizon: 'P1D']]
        )
        // Monday 2026-08-10 06:05 ET and 06:20 ET (same window); Tuesday 06:05 ET
        Instant monA = ZonedDateTime.of(2026, 8, 10, 6, 5, 0, 0, zone).toInstant()
        Instant monB = ZonedDateTime.of(2026, 8, 10, 6, 20, 0, 0, zone).toInstant()
        Instant tue = ZonedDateTime.of(2026, 8, 11, 6, 5, 0, 0, zone).toInstant()

        when:
        def r1 = serviceAt(cfg, plans, gw, ledger, monA).deliverDue(plan.id)
        def r2 = serviceAt(cfg, plans, gw, ledger, monB).deliverDue(plan.id)
        def r3 = serviceAt(cfg, plans, gw, ledger, tue).deliverDue(plan.id)

        then:
        r1.size() == 1
        r1[0].status == 'DELIVERED'
        r2.size() == 1
        r2[0].status == 'SKIPPED_DUPLICATE'
        r3.size() == 1
        r3[0].status == 'DELIVERED'
        gw.callCount == 2
        r1[0].idempotencyKey != r3[0].idempotencyKey
        gw.sent[0].metadata.occurrenceKey != gw.sent[1].metadata.occurrenceKey
        gw.sent[0].metadata.contentKey == gw.sent[1].metadata.contentKey
    }

    def "weekly and medium next configured occurrence sends with new key"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def ledger = new DeliveryLedger(temp())
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#planner',
            webhook_url_env: 'X',
            enabled_kinds: ['weekly_summary', 'medium_horizon_summary'],
            schedules: [
                [name: 'w', kind: 'weekly_summary', schedule: 'mon 09:00', window: 'PT30M', horizon: 'P7D'],
                [name: 'm', kind: 'medium_horizon_summary', schedule: 'mon 09:30', window: 'PT30M', horizon: 'P14D']
            ]
        )
        Instant mon1w = ZonedDateTime.of(2026, 8, 10, 9, 5, 0, 0, zone).toInstant()
        Instant mon1m = ZonedDateTime.of(2026, 8, 10, 9, 35, 0, 0, zone).toInstant()
        Instant mon2w = ZonedDateTime.of(2026, 8, 17, 9, 5, 0, 0, zone).toInstant()
        Instant mon2m = ZonedDateTime.of(2026, 8, 17, 9, 35, 0, 0, zone).toInstant()

        when:
        def w1 = serviceAt(cfg, plans, gw, ledger, mon1w).deliverDue(plan.id)
        def w1r = serviceAt(cfg, plans, gw, ledger, mon1w.plusSeconds(600)).deliverDue(plan.id)
        def m1 = serviceAt(cfg, plans, gw, ledger, mon1m).deliverDue(plan.id)
        def w2 = serviceAt(cfg, plans, gw, ledger, mon2w).deliverDue(plan.id)
        def m2 = serviceAt(cfg, plans, gw, ledger, mon2m).deliverDue(plan.id)

        then:
        w1.find { it.kind == MessageRenderer.KIND_WEEKLY }.status == 'DELIVERED'
        w1r.find { it.kind == MessageRenderer.KIND_WEEKLY }.status == 'SKIPPED_DUPLICATE'
        m1.find { it.kind == MessageRenderer.KIND_MEDIUM }.status == 'DELIVERED'
        w2.find { it.kind == MessageRenderer.KIND_WEEKLY }.status == 'DELIVERED'
        m2.find { it.kind == MessageRenderer.KIND_MEDIUM }.status == 'DELIVERED'
        w1.find { it.kind == MessageRenderer.KIND_WEEKLY }.idempotencyKey !=
            w2.find { it.kind == MessageRenderer.KIND_WEEKLY }.idempotencyKey
        m1.find { it.kind == MessageRenderer.KIND_MEDIUM }.idempotencyKey !=
            m2.find { it.kind == MessageRenderer.KIND_MEDIUM }.idempotencyKey
        gw.callCount == 4
    }

    def "risk alert same task same day skip; next day due sends again if still risk"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def ledger = new DeliveryLedger(temp())
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#alerts',
            webhook_url_env: 'X', risk_deadline_days: 14,
            enabled_kinds: ['capacity_risk_alert'],
            schedules: [[name: 'r', kind: 'capacity_risk_alert', schedule: '07:00', window: 'PT30M']]
        )
        Instant day1a = ZonedDateTime.of(2026, 8, 7, 7, 5, 0, 0, zone).toInstant()
        Instant day1b = ZonedDateTime.of(2026, 8, 7, 7, 20, 0, 0, zone).toInstant()
        Instant day2 = ZonedDateTime.of(2026, 8, 8, 7, 5, 0, 0, zone).toInstant()

        when:
        def r1 = serviceAt(cfg, plans, gw, ledger, day1a).deliverDue(plan.id)
        def r2 = serviceAt(cfg, plans, gw, ledger, day1b).deliverDue(plan.id)
        def r3 = serviceAt(cfg, plans, gw, ledger, day2).deliverDue(plan.id)

        then:
        r1.any { it.status == 'DELIVERED' && it.kind == MessageRenderer.KIND_RISK }
        r2.any { it.status == 'SKIPPED_DUPLICATE' && it.kind == MessageRenderer.KIND_RISK }
        r3.any { it.status == 'DELIVERED' && it.kind == MessageRenderer.KIND_RISK }
        gw.callCount == 2
        r1.find { it.status == 'DELIVERED' }.idempotencyKey !=
            r3.find { it.status == 'DELIVERED' }.idempotencyKey
    }

    def "two distinct same-kind schedules same date do not collide"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def ledger = new DeliveryLedger(temp())
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#planner',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary'],
            schedules: [
                [name: 'morning', kind: 'daily_summary', schedule: '06:00', window: 'PT30M', horizon: 'P1D'],
                [name: 'evening', kind: 'daily_summary', schedule: '18:00', window: 'PT30M', horizon: 'P1D']
            ]
        )
        Instant morning = ZonedDateTime.of(2026, 8, 10, 6, 5, 0, 0, zone).toInstant()
        Instant evening = ZonedDateTime.of(2026, 8, 10, 18, 5, 0, 0, zone).toInstant()

        when:
        def rM = serviceAt(cfg, plans, gw, ledger, morning).deliverDue(plan.id)
        def rE = serviceAt(cfg, plans, gw, ledger, evening).deliverDue(plan.id)

        then:
        rM[0].status == 'DELIVERED'
        rE[0].status == 'DELIVERED'
        rM[0].idempotencyKey != rE[0].idempotencyKey
        gw.callCount == 2
        def sids = gw.sent.collect { it.metadata.scheduleId } as Set
        sids.size() == 2
        // schedule identity independent of list order
        def s1 = new PlannerConfig.MessageSchedule('a', 'daily_summary', '06:00', Duration.ofDays(1), Duration.ofMinutes(30))
        def s2 = new PlannerConfig.MessageSchedule('b', 'daily_summary', '18:00', Duration.ofDays(1), Duration.ofMinutes(30))
        ScheduleOccurrence.scheduleIdentity(s1, '#planner') != ScheduleOccurrence.scheduleIdentity(s2, '#planner')
        ScheduleOccurrence.scheduleIdentity(s1, '#planner') ==
            ScheduleOccurrence.scheduleIdentity(
                new PlannerConfig.MessageSchedule('other-name', 'daily_summary', '06:00', Duration.ofDays(1), Duration.ofMinutes(30)),
                '#planner')
    }

    def "DST spring and fall: one occurrence key; next date sends"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def ledger = new DeliveryLedger(temp())
        // Spring: 02:30 nonexistent → effective 03:00
        def cfgSpring = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#planner',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '02:30', window: 'PT30M', horizon: 'P1D']]
        )
        Instant springDue = ZonedDateTime.of(2026, 3, 8, 3, 5, 0, 0, zone).toInstant()
        Instant springRetry = ZonedDateTime.of(2026, 3, 8, 3, 15, 0, 0, zone).toInstant()
        Instant springNext = ZonedDateTime.of(2026, 3, 9, 2, 35, 0, 0, zone).toInstant()

        when:
        def s1 = serviceAt(cfgSpring, plans, gw, ledger, springDue).deliverDue(plan.id)
        def s2 = serviceAt(cfgSpring, plans, gw, ledger, springRetry).deliverDue(plan.id)
        def s3 = serviceAt(cfgSpring, plans, gw, ledger, springNext).deliverDue(plan.id)

        then:
        s1[0].status == 'DELIVERED'
        s2[0].status == 'SKIPPED_DUPLICATE'
        s3[0].status == 'DELIVERED'
        s1[0].idempotencyKey != s3[0].idempotencyKey

        when: 'fall fold: both overlap instants same key; one send'
        def gwF = new InMemoryMessagingGateway()
        def ledgerF = new DeliveryLedger(temp())
        def cfgFall = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#planner',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '01:00', window: 'PT30M', horizon: 'P1D']]
        )
        Instant fallA = ZonedDateTime.of(2026, 11, 1, 1, 10, 0, 0, zone).withEarlierOffsetAtOverlap().toInstant()
        Instant fallB = ZonedDateTime.of(2026, 11, 1, 1, 10, 0, 0, zone).withLaterOffsetAtOverlap().toInstant()
        Instant fallNext = ZonedDateTime.of(2026, 11, 2, 1, 10, 0, 0, zone).toInstant()
        def f1 = serviceAt(cfgFall, plans, gwF, ledgerF, fallA).deliverDue(plan.id)
        def f2 = serviceAt(cfgFall, plans, gwF, ledgerF, fallB).deliverDue(plan.id)
        def f3 = serviceAt(cfgFall, plans, gwF, ledgerF, fallNext).deliverDue(plan.id)

        then:
        f1[0].status == 'DELIVERED'
        f2[0].status == 'SKIPPED_DUPLICATE'
        f3[0].status == 'DELIVERED'
        f1[0].idempotencyKey == f2[0].idempotencyKey
        f1[0].idempotencyKey != f3[0].idempotencyKey
        gwF.callCount == 2
    }

    def "delivery key changes with plan/dest/horizon/schedule; stable inside window"() {
        given:
        def plan = samplePlan()
        def dest = '#planner'
        def sched = new PlannerConfig.MessageSchedule('d', 'daily_summary', '06:00',
            Duration.ofDays(1), Duration.ofMinutes(30))
        Instant monA = ZonedDateTime.of(2026, 8, 10, 6, 5, 0, 0, zone).toInstant()
        Instant monB = ZonedDateTime.of(2026, 8, 10, 6, 25, 0, 0, zone).toInstant()
        def ctxA = new MessageRenderer.DeliveryContext(
            ScheduleOccurrence.scheduleIdentity(sched, dest),
            ScheduleOccurrence.occurrenceKey(sched, monA, zone))
        def ctxB = new MessageRenderer.DeliveryContext(
            ScheduleOccurrence.scheduleIdentity(sched, dest),
            ScheduleOccurrence.occurrenceKey(sched, monB, zone))
        def r = new MessageRenderer(zone, dest)

        when:
        def kA = r.renderDailySummary(plan, monA, Duration.ofDays(1), ctxA).idempotencyKey
        def kB = r.renderDailySummary(plan, monB, Duration.ofDays(1), ctxB).idempotencyKey
        def kHorizon = MessageRenderer.deliveryIdempotencyKey(plan, MessageRenderer.KIND_DAILY, dest,
            Duration.ofDays(2), ctxA, null)
        def kDest = MessageRenderer.deliveryIdempotencyKey(plan, MessageRenderer.KIND_DAILY, '#other',
            Duration.ofDays(1), ctxA, null)
        def otherSched = new PlannerConfig.MessageSchedule('e', 'daily_summary', '18:00',
            Duration.ofDays(1), Duration.ofMinutes(30))
        def ctxOther = new MessageRenderer.DeliveryContext(
            ScheduleOccurrence.scheduleIdentity(otherSched, dest),
            ScheduleOccurrence.occurrenceKey(otherSched,
                ZonedDateTime.of(2026, 8, 10, 18, 5, 0, 0, zone).toInstant(), zone))
        def kSched = MessageRenderer.deliveryIdempotencyKey(plan, MessageRenderer.KIND_DAILY, dest,
            Duration.ofDays(1), ctxOther, null)
        def plan2 = Plan.builder().id(plan.id).version(2).createdAt(plan.createdAt).mode(plan.mode)
            .tasks(plan.tasks).scheduledBlocks(plan.scheduledBlocks).unscheduled(plan.unscheduled)
            .changes(plan.changes).build()
        def kPlan = MessageRenderer.deliveryIdempotencyKey(plan2, MessageRenderer.KIND_DAILY, dest,
            Duration.ofDays(1), ctxA, null)
        def manual1 = r.renderDailySummary(plan, monA).idempotencyKey
        def manual2 = r.renderDailySummary(plan, monB).idempotencyKey

        then:
        kA == kB
        kA != kHorizon
        kA != kDest
        kA != kSched
        kA != kPlan
        // manual/direct: stable across invocation times (no clock in key)
        manual1 == manual2
        manual1 != kA
    }

    def "provider-success ledger-failure no-resend remains within occurrence"() {
        given:
        def plans = new PlanStore(temp())
        def plan = samplePlan()
        plans.save(plan)
        def gw = new InMemoryMessagingGateway()
        def moves = new AtomicInteger(0)
        def ledger = new DeliveryLedger(temp(), {
            if (moves.incrementAndGet() == 3) {
                throw new RuntimeException('final ledger boom')
            }
        })
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#planner',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M', horizon: 'P1D']]
        )
        Instant due = ZonedDateTime.of(2026, 8, 10, 6, 5, 0, 0, zone).toInstant()
        Instant retry = ZonedDateTime.of(2026, 8, 10, 6, 15, 0, 0, zone).toInstant()

        when:
        def svc1 = serviceAt(cfg, plans, gw, ledger, due)
        Exception thrown = null
        try {
            svc1.deliverDue(plan.id)
        } catch (MessagingService.LedgerPersistException e) {
            thrown = e
        }
        def retryR = serviceAt(cfg, plans, gw, ledger, retry).deliverDue(plan.id)

        then:
        thrown instanceof MessagingService.LedgerPersistException
        gw.callCount == 1
        retryR.size() == 1
        retryR[0].status == 'UNKNOWN' || retryR[0].status == 'NEEDS_RECONCILIATION'
        gw.callCount == 1
    }

    def "dueIntents carry schedule identity and occurrence key"() {
        given:
        def cfg = baseConfig(
            enabled: true, provider: 'in_memory', destination: '#planner',
            webhook_url_env: 'X', enabled_kinds: ['daily_summary'],
            schedules: [[name: 'd', kind: 'daily_summary', schedule: '06:00', window: 'PT30M', horizon: 'P1D']]
        )
        Instant due = ZonedDateTime.of(2026, 8, 10, 6, 5, 0, 0, zone).toInstant()
        def svc = service(cfg, new PlanStore(temp()), new InMemoryMessagingGateway(), null, due)

        when:
        def intents = svc.dueIntents(due)

        then:
        intents.size() == 1
        intents[0].scheduleId.startsWith('sid-')
        intents[0].occurrenceKey.contains('2026-08-10')
        intents[0].occurrenceKey.contains('06:00')
        intents[0].deliveryContext().scheduleId == intents[0].scheduleId
    }

    def "applyDecision structured APPLIED NOOP REPLAYED; applyDecisionReceipt wrapper"() {
        given:
        def root = temp()
        def plans = new PlanStore(root.resolve('plans'))
        def plan = samplePlan('approval_required')
        plans.save(plan)
        def cal = new InMemoryCalendarGateway()
        def todo = new InMemoryTodoistGateway(plan.tasks.collect { t ->
            [id: t.id, content: t.content, priority: t.priority]
        })
        def appState = new ApplicationStateStore(root.resolve('app'))
        def cfg = PlannerConfig.fromMap(planner: [
            mode           : 'approval_required',
            timezone       : 'America/New_York',
            output_calendar: 'Todoist Planned',
            availability   : [working_windows: [weekday: ['09:00-17:00']]],
            messaging      : [enabled: true, provider: 'slack', destination: '#p', webhook_url_env: 'W']
        ])
        def applier = new PlanApplier(cfg, cal, cal, todo, todo, appState,
            { Instant.parse('2026-08-07T12:00:00Z') })
        def svc = service(cfg, plans, new InMemoryMessagingGateway(), applier)
        def hash = PlanHash.compute(plan)
        def prop = Proposal.fromPlan(plan)
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def calBefore = cal.upserts.size()

        when: 'APPROVE APPLIED has receipt'
        def fb = svc.handleFeedback(plan.id,
            "approve ${prop.id} ${hash.substring(0, 12)}",
            new FeedbackParser.FeedbackContext(actorId: 'jorsten', correlationId: 'struct-1'))
        def applied = svc.applyDecision(plan.id, fb.decision)
        def calAfterApply = cal.upserts.size()

        and: 'REJECT NOOP zero writes'
        def rejPlan = Plan.builder().id('plan-struct-rej').version(1).createdAt(plan.createdAt)
            .mode('approval_required').tasks(plan.tasks).scheduledBlocks(plan.scheduledBlocks).build()
        plans.save(rejPlan)
        def h2 = PlanHash.compute(rejPlan)
        def pr2 = Proposal.fromPlan(rejPlan)
        def rej = svc.handleFeedback(rejPlan.id,
            "reject ${pr2.id} ${h2.substring(0, 12)} no",
            new FeedbackParser.FeedbackContext(actorId: 'jorsten', correlationId: 'struct-rej'))
        def noop = svc.applyDecision(rejPlan.id, rej.decision)
        def wrapNoop = svc.applyDecisionReceipt(rejPlan.id, rej.decision)
        def calAfterNoop = cal.upserts.size()

        and: 'REPLAYED zero writes; wrapper returns null'
        def replay = DecisionRecord.builder()
            .id('dec-struct-rep').proposalId(prop.id).planId(plan.id).planVersion(plan.version)
            .planHash(hash).action('APPROVE').status('IDEMPOTENT_REPLAY')
            .actorId('jorsten').correlationId('struct-1').decidedAt(now)
            .previousDecisionId(fb.decision.id).build()
        def rep = svc.applyDecision(plan.id, replay)
        def wrapRep = svc.applyDecisionReceipt(plan.id, replay)
        def calAfterRep = cal.upserts.size()

        then:
        applied.status == MessagingService.ApplyDecisionResult.Status.APPLIED
        applied.receipt != null
        applied.action == 'APPROVE'
        applied.decisionId == fb.decision.id
        applied.reason == null
        calAfterApply > calBefore

        noop.isNoop()
        noop.receipt == null
        noop.action == 'REJECT'
        wrapNoop == null
        calAfterNoop == calAfterApply

        rep.isReplayed()
        rep.receipt == null
        rep.reason == 'IDEMPOTENT_REPLAY'
        wrapRep == null
        calAfterRep == calAfterApply
    }
}
