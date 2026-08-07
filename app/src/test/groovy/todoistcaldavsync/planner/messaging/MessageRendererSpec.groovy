package todoistcaldavsync.planner.messaging

import spock.lang.Specification
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.UnscheduledTask
import todoistcaldavsync.planner.state.PlanStore

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class MessageRendererSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')
    Instant now = Instant.parse('2026-08-07T14:00:00Z') // 10:00 AM EDT

    def cleanupDirs = []

    def cleanup() {
        cleanupDirs.each { Files.walk(it).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private Task task(String id, String content, Instant deadline = null, long mins = 60) {
        Task.builder()
            .id(id)
            .content(content)
            .priority(1)
            .deadline(deadline)
            .effectiveDuration(Duration.ofMinutes(mins))
            .durationSource('test')
            .build()
    }

    private Plan fixturePlan() {
        def t1 = task('t-scouts', 'Scouts focus block', null, 60)
        def t2 = task('t-phone', 'Phone/admin', null, 20)
        def t3 = task('t-deck', 'Paint the Deck',
            LocalDateTime.of(2026, 8, 12, 17, 0).atZone(zone).toInstant(), 120)
        Instant s1 = LocalDateTime.of(2026, 8, 7, 8, 0).atZone(zone).toInstant()
        Instant s2 = LocalDateTime.of(2026, 8, 7, 12, 15).atZone(zone).toInstant()
        Instant s3 = LocalDateTime.of(2026, 8, 7, 20, 0).atZone(zone).toInstant()
        def b1 = ScheduledBlock.builder().id('b1').start(s1).end(s1 + Duration.ofHours(1))
            .taskIds(['t-scouts']).title('Scouts focus block').reason('deadline').build()
        def b2 = ScheduledBlock.builder().id('b2').start(s2).end(s2 + Duration.ofMinutes(20))
            .taskIds(['t-phone']).title('Phone/admin').reason('context').build()
        def b3 = ScheduledBlock.builder().id('b3').start(s3).end(s3 + Duration.ofMinutes(45))
            .taskIds(['t-ai']).title('AI project review').reason('batch').frozen(true).build()
        def unsched = new UnscheduledTask(t3,
            'Paint the Deck has five days remaining, but no weather-safe slot is currently available.',
            'weather_infeasible',
            [alternatives: [
                [title: 'Sand porch rails', start: '2026-08-08T14:00:00Z', reason: 'indoor'],
                [title: 'Order paint supplies', reason: 'prep']
            ]])
        def changeSafe = PlanChange.builder().id('c1').type('add').taskId('t-phone')
            .newStart(s2).reason('scheduled in preferred window').build()
        def changeAppr = PlanChange.builder().id('c2').type('move').taskId('t-scouts')
            .previousStart(s1 - Duration.ofDays(1)).newStart(s1)
            .reason('moved within approval horizon')
            .metadata([approvalRequired: true, approvalReason: 'within_horizon']).build()
        Plan.builder()
            .id('plan-day-1')
            .version(2)
            .createdAt(Instant.parse('2026-08-07T10:00:00Z'))
            .mode('approval_required')
            .tasks([t1, t2, t3])
            .scheduledBlocks([b1, b2, b3])
            .unscheduled([unsched])
            .changes([changeSafe, changeAppr])
            .metrics([availableCapacityMinutes: 135, forecastRetrievedAt: '2026-08-07T09:00:00Z'])
            .build()
    }

    def "daily summary uses 12-hour AM/PM and stored plan fields"() {
        given:
        def plan = fixturePlan()
        def r = new MessageRenderer(zone, '#planner-test')

        when:
        def msg = r.renderDailySummary(plan, now)

        then:
        msg.kind == MessageRenderer.KIND_DAILY
        msg.body.contains("Today's feasible plan")
        msg.body.contains('8:00 AM')
        msg.body.contains('9:00 AM')
        msg.body.contains('12:15 PM')
        msg.body.contains('8:00 PM')
        msg.body.contains('Scouts focus block')
        msg.body.contains('Phone/admin')
        msg.body.contains('AI project review')
        msg.body.contains('[frozen]')
        msg.body.contains('Paint the Deck')
        msg.body.contains('plan-day-1')
        msg.body.contains('Plan version: 2')
        msg.body.contains('approval_required')
        msg.body.contains('Safe (apply-safe eligible)')
        msg.body.contains('Approval required')
        msg.body.contains('[approval-required]')
        msg.body.contains('Frozen/manual')
        msg.planHash == PlanHash.compute(plan)
        msg.proposalId == Proposal.fromPlan(plan).id
        msg.body.contains(msg.proposalId)
        msg.body.contains('approve ')
        msg.body.contains('2026-08-07T10:00:00Z')
        !msg.body.toLowerCase().contains('xoxb-')
        !msg.body.toLowerCase().contains('secret')
        msg.metadata.zone == 'America/New_York'
    }

    def "weekly and medium summaries aggregate demand with assumptions"() {
        given:
        def plan = fixturePlan()
        def r = new MessageRenderer(zone, '#planner-test')

        when:
        def weekly = r.renderWeeklySummary(plan, now)
        def medium = r.renderMediumHorizonSummary(plan, now)

        then:
        weekly.kind == MessageRenderer.KIND_WEEKLY
        weekly.body.contains('Weekly plan summary')
        weekly.body.contains('Demand / capacity by day')
        weekly.body.contains('Assumptions:')
        medium.kind == MessageRenderer.KIND_MEDIUM
        medium.body.contains('Medium-horizon')
        medium.body.contains('Capacity-risk items:')
    }

    def "horizon override changes included blocks and displayed range"() {
        given:
        Instant d0 = LocalDateTime.of(2026, 8, 7, 9, 0).atZone(zone).toInstant()
        Instant d5 = LocalDateTime.of(2026, 8, 12, 9, 0).atZone(zone).toInstant()
        Instant d20 = LocalDateTime.of(2026, 8, 27, 9, 0).atZone(zone).toInstant()
        def plan = Plan.builder().id('p-h').version(1).createdAt(now).mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder().id('b0').start(d0).end(d0 + Duration.ofHours(1))
                    .taskIds(['a']).title('Near').reason('x').build(),
                ScheduledBlock.builder().id('b5').start(d5).end(d5 + Duration.ofHours(1))
                    .taskIds(['b']).title('Mid').reason('x').build(),
                ScheduledBlock.builder().id('b20').start(d20).end(d20 + Duration.ofHours(1))
                    .taskIds(['c']).title('Far').reason('x').build()
            ]).build()
        def r = new MessageRenderer(zone, '#h')

        when:
        def shortH = r.renderWeeklySummary(plan, now, Duration.ofDays(3))
        def longH = r.renderWeeklySummary(plan, now, Duration.ofDays(30))

        then:
        shortH.body.contains('Near')
        !shortH.body.contains('Mid')
        !shortH.body.contains('Far')
        shortH.metadata.horizonDays == 3L || shortH.body.contains('2026-08-10')
        longH.body.contains('Near')
        longH.body.contains('Mid')
        longH.body.contains('Far')
        longH.metadata.blockCount == 3
        shortH.metadata.blockCount == 1
    }

    def "capacity-risk alert includes task deadline reason alternatives"() {
        given:
        def plan = fixturePlan()
        def r = new MessageRenderer(zone, '#alerts', 14)
        def risk = plan.unscheduled[0]

        when:
        def msg = r.renderCapacityRiskAlert(plan, risk, now)

        then:
        msg.kind == MessageRenderer.KIND_RISK
        msg.body.contains('Capacity-risk alert')
        msg.body.contains('t-deck')
        msg.body.contains('Paint the Deck')
        msg.body.contains('Deadline:')
        msg.body.contains('Estimated duration: 2h')
        msg.body.contains('Reason code: weather_infeasible')
        msg.body.contains('no weather-safe slot')
        msg.body.contains('Alternatives:')
        msg.body.contains('Sand porch rails')
        msg.body.contains('Order paint supplies')
        msg.idempotencyKey.contains('capacity_risk')
    }

    def "riskDeadlineDays boundary overdue beyond and no-deadline"() {
        given:
        // now = 2026-08-07 10:00 ET
        def overdue = task('t-over', 'Overdue', Instant.parse('2026-08-01T12:00:00Z'), 30)
        def boundary = task('t-bound', 'Boundary',
            LocalDateTime.of(2026, 8, 12, 23, 59).atZone(zone).toInstant(), 30) // +5 days local
        def justOutside = task('t-out', 'Outside',
            LocalDateTime.of(2026, 8, 13, 0, 1).atZone(zone).toInstant(), 30) // +6 days
        def noDl = task('t-none', 'No deadline', null, 30)
        def risks = [
            new UnscheduledTask(overdue, 'past deadline', 'deadline_risk', [:]),
            new UnscheduledTask(boundary, 'at window end', 'deadline_risk', [:]),
            new UnscheduledTask(justOutside, 'beyond window', 'deadline_risk', [:]),
            new UnscheduledTask(noDl, 'capacity risk no dl', 'capacity_risk', [:])
        ]
        def plan = Plan.builder().id('p-risk').version(1).createdAt(now).mode('preview')
            .unscheduled(risks).build()
        def r = new MessageRenderer(zone, '#a', 5)

        when:
        def msgs = r.renderCapacityRiskAlerts(plan, now)
        def ids = msgs.collect { it.metadata.taskId }

        then:
        ids.contains('t-over')
        ids.contains('t-bound')
        !ids.contains('t-out')
        !ids.contains('t-none')
        r.isWithinRiskWindow(risks[0], now)
        r.isWithinRiskWindow(risks[1], now)
        !r.isWithinRiskWindow(risks[2], now)
        !r.isWithinRiskWindow(risks[3], now)
    }

    def "capacity-risk alert shows explicit none when no alternatives"() {
        given:
        def t = task('t-x', 'Impossible', Instant.parse('2026-08-10T00:00:00Z'), 30)
        def u = new UnscheduledTask(t, 'no slot before deadline', 'deadline_risk', [:])
        def plan = Plan.builder().id('p').version(1).createdAt(now).mode('preview')
            .unscheduled([u]).build()
        def r = new MessageRenderer(zone)

        when:
        def msg = r.renderCapacityRiskAlert(plan, u, now)

        then:
        msg.body.contains('Alternatives:')
        msg.body.contains('_(none)_')
    }

    def "renderer uses messaging zone not UTC for human times when planner would differ"() {
        given:
        // Block at 14:00 UTC = 10:00 AM ET
        Instant start = Instant.parse('2026-08-07T14:00:00Z')
        def plan = Plan.builder().id('p-tz').version(1).createdAt(now).mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder().id('b').start(start).end(start + Duration.ofHours(1))
                    .taskIds(['t']).title('TZ block').reason('x').build()
            ]).build()
        def ny = new MessageRenderer(ZoneId.of('America/New_York'), '#tz')
        def utc = new MessageRenderer(ZoneId.of('UTC'), '#tz')

        when:
        def nyMsg = ny.renderDailySummary(plan, now)
        def utcMsg = utc.renderDailySummary(plan, now)

        then:
        nyMsg.body.contains('10:00 AM')
        utcMsg.body.contains('2:00 PM')
        nyMsg.metadata.zone == 'America/New_York'
        utcMsg.metadata.zone == 'UTC'
    }

    def "persisted plan round-trip yields identical rendered output"() {
        given:
        def dir = Files.createTempDirectory('plan-msg-rt')
        cleanupDirs << dir
        def store = new PlanStore(dir)
        def plan = fixturePlan()
        store.save(plan)
        def reloaded = store.load(plan.id)
        def r = new MessageRenderer(zone, '#rt', 14)

        when:
        def a = r.renderDailySummary(plan, now)
        def b = r.renderDailySummary(reloaded, now)
        def ra = r.renderCapacityRiskAlert(plan, plan.unscheduled[0], now)
        def rb = r.renderCapacityRiskAlert(reloaded, reloaded.unscheduled[0], now)

        then:
        reloaded != null
        PlanHash.compute(plan) == PlanHash.compute(reloaded)
        a.body == b.body
        a.planHash == b.planHash
        a.proposalId == b.proposalId
        a.idempotencyKey == b.idempotencyKey
        ra.body == rb.body
    }

    def "idempotency key stable for same plan/kind/destination"() {
        given:
        def plan = fixturePlan()

        expect:
        MessageRenderer.idempotencyKey(plan, 'daily_summary', '#x', 'P1D') ==
            MessageRenderer.idempotencyKey(plan, 'daily_summary', '#x', 'P1D')
        MessageRenderer.idempotencyKey(plan, 'daily_summary', '#x', 'P1D') !=
            MessageRenderer.idempotencyKey(plan, 'weekly_summary', '#x', 'P7D')
    }

    def "content identity separate from occurrence delivery key"() {
        given:
        def plan = fixturePlan()
        def r = new MessageRenderer(zone, '#x')
        def ctx1 = new MessageRenderer.DeliveryContext('sid-aaa', 'd|2026-08-10|06:00')
        def ctx2 = new MessageRenderer.DeliveryContext('sid-aaa', 'd|2026-08-11|06:00')

        when:
        def m1 = r.renderDailySummary(plan, now, Duration.ofDays(1), ctx1)
        def m2 = r.renderDailySummary(plan, now, Duration.ofDays(1), ctx2)
        def manual = r.renderDailySummary(plan, now)

        then:
        m1.metadata.contentKey == m2.metadata.contentKey
        m1.idempotencyKey != m2.idempotencyKey
        manual.metadata.scheduleId == ScheduleOccurrence.MANUAL_SCHEDULE_ID
        manual.metadata.occurrenceKey == ScheduleOccurrence.MANUAL_OCCURRENCE
        MessageRenderer.deliveryIdempotencyKey(plan, 'daily_summary', '#x', Duration.ofDays(1), ctx1, null) ==
            m1.idempotencyKey
    }

    def "same plan and now yields identical createdAt content and idempotency; null now rejects"() {
        given:
        def plan = fixturePlan()
        def r = new MessageRenderer(zone, '#det')

        when:
        def a = r.renderDailySummary(plan, now)
        def b = r.renderDailySummary(plan, now)
        def w1 = r.renderWeeklySummary(plan, now)
        def w2 = r.renderWeeklySummary(plan, now)

        then:
        a.createdAt == now
        b.createdAt == now
        a.createdAt == b.createdAt
        a.body == b.body
        a.idempotencyKey == b.idempotencyKey
        w1.body == w2.body
        w1.idempotencyKey == w2.idempotencyKey
        w1.createdAt == now

        when:
        r.renderDailySummary(plan, null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('now')

        when:
        r.renderCapacityRiskAlerts(plan, null)

        then:
        thrown(IllegalArgumentException)

        when:
        r.renderProposal(plan, null)

        then:
        thrown(IllegalArgumentException)
    }
}
