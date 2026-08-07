package todoistcaldavsync.planner.messaging

import todoistcaldavsync.planner.domain.Message
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.UnscheduledTask

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders human-facing messages from a stored Plan only (no live scheduler state).
 * Human times: 12-hour AM/PM with messaging timezone. Machine metadata: ISO-8601.
 * Horizon drives summary range/aggregation; riskDeadlineDays filters risk alerts.
 */
class MessageRenderer {
    static final String KIND_DAILY = 'daily_summary'
    static final String KIND_WEEKLY = 'weekly_summary'
    static final String KIND_MEDIUM = 'medium_horizon_summary'
    static final String KIND_RISK = 'capacity_risk_alert'
    static final String KIND_PROPOSAL = 'proposal'

    private static final DateTimeFormatter HUMAN_TIME =
        DateTimeFormatter.ofPattern('h:mm a').withLocale(Locale.US)
    private static final DateTimeFormatter HUMAN_DATE =
        DateTimeFormatter.ofPattern('EEE MMM d').withLocale(Locale.US)
    private static final DateTimeFormatter HUMAN_DATETIME =
        DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a').withLocale(Locale.US)

    private final ZoneId zone
    private final String destination
    private final int riskDeadlineDays

    MessageRenderer(ZoneId zone, String destination = '#planner', int riskDeadlineDays = 5) {
        this.zone = zone ?: ZoneId.of('UTC')
        this.destination = destination ?: '#planner'
        this.riskDeadlineDays = riskDeadlineDays >= 0 ? riskDeadlineDays : 5
    }

    ZoneId getZone() { zone }

    int getRiskDeadlineDays() { riskDeadlineDays }

    Message renderDailySummary(Plan plan, Instant now, Duration horizon = Duration.ofDays(1),
                               DeliveryContext delivery = null) {
        requireNow(now)
        renderSummary(plan, KIND_DAILY, 'daily', horizon ?: Duration.ofDays(1), now, delivery)
    }

    Message renderWeeklySummary(Plan plan, Instant now, Duration horizon = Duration.ofDays(7),
                                DeliveryContext delivery = null) {
        requireNow(now)
        renderSummary(plan, KIND_WEEKLY, 'weekly', horizon ?: Duration.ofDays(7), now, delivery)
    }

    Message renderMediumHorizonSummary(Plan plan, Instant now, Duration horizon = Duration.ofDays(14),
                                       DeliveryContext delivery = null) {
        requireNow(now)
        renderSummary(plan, KIND_MEDIUM, 'medium-horizon', horizon ?: Duration.ofDays(14), now, delivery)
    }

    Message renderCapacityRiskAlert(Plan plan, UnscheduledTask risk, Instant now,
                                    DeliveryContext delivery = null) {
        requireNow(now)
        if (plan == null) {
            throw new IllegalArgumentException('plan is required')
        }
        if (risk == null) {
            throw new IllegalArgumentException('risk task is required')
        }
        Proposal proposal = Proposal.fromPlan(plan)
        String hash = proposal.planHash
        Task task = risk.task
        StringBuilder sb = new StringBuilder()
        sb << "Capacity-risk alert\n"
        sb << "Task: ${task.content} (`${task.id}`)\n"
        if (task.deadline != null) {
            sb << "Deadline: ${fmtHuman(task.deadline)} (${task.deadline})\n"
        } else {
            sb << "Deadline: _(none)_\n"
        }
        long mins = task.effectiveDuration != null ? task.effectiveDuration.toMinutes() :
            (task.nativeDuration != null ? task.nativeDuration.toMinutes() : 0L)
        sb << "Estimated duration: ${formatDurationMinutes(mins)}\n"
        sb << "Reason code: ${risk.code ?: 'capacity_risk'}\n"
        sb << "Reason: ${risk.reason}\n"
        List<String> alternatives = extractAlternatives(risk)
        sb << "Alternatives:\n"
        if (!alternatives) {
            sb << "- _(none)_\n"
        } else {
            alternatives.each { sb << "- ${it}\n" }
        }
        sb << "\n"
        appendProposalFooter(sb, plan, proposal, hash, now)
        String contentKey = contentIdentityKey(plan, KIND_RISK, destination, task.id)
        String deliveryKey = deliveryIdempotencyKey(plan, KIND_RISK, destination, null,
            delivery, task.id)
        return Message.builder()
            .kind(KIND_RISK)
            .subject("Capacity risk: ${task.content}")
            .body(sb.toString())
            .destination(destination)
            .planId(plan.id)
            .planVersion(plan.version)
            .planHash(hash)
            .proposalId(proposal.id)
            .idempotencyKey(deliveryKey)
            .createdAt(now)
            .metadata([
                taskId          : task.id,
                reasonCode      : risk.code,
                alternativeCount: alternatives.size(),
                zone            : zone.id,
                riskDeadlineDays: riskDeadlineDays,
                contentKey      : contentKey,
                scheduleId      : delivery?.scheduleId ?: ScheduleOccurrence.MANUAL_SCHEDULE_ID,
                occurrenceKey   : delivery?.occurrenceKey ?: ScheduleOccurrence.MANUAL_OCCURRENCE
            ])
            .build()
    }

    /**
     * Capacity-risk alerts for unscheduled risk tasks whose deadlines fall within
     * the configured risk window relative to {@code now} in the messaging timezone.
     * Overdue (deadline before now) included; beyond window excluded.
     * Tasks without deadline are excluded (no severe capacity-unscheduled policy).
     */
    List<Message> renderCapacityRiskAlerts(Plan plan, Instant now,
                                           DeliveryContext delivery = null) {
        requireNow(now)
        if (plan == null) {
            return []
        }
        return plan.unscheduled.findAll { isRisk(it) && isWithinRiskWindow(it, now) }
            .collect { renderCapacityRiskAlert(plan, it, now, delivery) }
    }

    /**
     * Deadline within [local today - infinity overdue, local today + riskDeadlineDays] inclusive end day.
     * No deadline → excluded.
     */
    boolean isWithinRiskWindow(UnscheduledTask u, Instant now) {
        if (u?.task?.deadline == null) {
            return false
        }
        Instant deadline = u.task.deadline
        // Overdue always included
        if (!deadline.isAfter(now)) {
            return true
        }
        LocalDate today = now.atZone(zone).toLocalDate()
        LocalDate deadlineDay = deadline.atZone(zone).toLocalDate()
        LocalDate windowEnd = today.plusDays(riskDeadlineDays)
        // inclusive of windowEnd day
        return !deadlineDay.isAfter(windowEnd)
    }

    Message renderProposal(Plan plan, Instant now, DeliveryContext delivery = null) {
        requireNow(now)
        renderSummary(plan, KIND_PROPOSAL, 'proposal', null, now, delivery)
    }

    private static void requireNow(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException('now Instant is required (no implicit system clock)')
        }
    }

    private Message renderSummary(Plan plan, String kind, String label, Duration horizon, Instant now,
                                  DeliveryContext delivery = null) {
        requireNow(now)
        if (plan == null) {
            throw new IllegalArgumentException('plan is required')
        }
        Proposal proposal = Proposal.fromPlan(plan)
        String hash = proposal.planHash
        LocalDate today = now.atZone(zone).toLocalDate()
        LocalDate horizonEnd = horizon != null ? today.plusDays(Math.max(0L, horizon.toDays())) : null

        List<ScheduledBlock> blocks = plan.scheduledBlocks
        if (horizonEnd != null) {
            blocks = blocks.findAll { b ->
                LocalDate d = b.start.atZone(zone).toLocalDate()
                !d.isBefore(today) && d.isBefore(horizonEnd)
            }
        }

        StringBuilder sb = new StringBuilder()
        if (kind == KIND_DAILY) {
            sb << "Today's feasible plan\n"
        } else if (kind == KIND_WEEKLY) {
            sb << "Weekly plan summary\n"
        } else if (kind == KIND_MEDIUM) {
            sb << "Medium-horizon plan summary\n"
        } else {
            sb << "Plan proposal\n"
        }

        long capacityMinutes = sumBlockMinutes(blocks)
        sb << "Available focus capacity: ${formatDurationMinutes(capacityMinutes)}\n"
        if (horizon != null) {
            sb << "Horizon: ${today} → ${horizonEnd} (${zone.id})\n"
        }
        sb << "Timezone: ${zone.id}\n"
        sb << "\n"

        sb << "Scheduled:\n"
        if (!blocks) {
            sb << "- _(none)_\n"
        } else {
            blocks.toSorted { a, b -> a.start <=> b.start }.each { ScheduledBlock b ->
                sb << "- ${fmtRange(b.start, b.end)} — ${b.title}"
                if (b.frozen) sb << ' [frozen]'
                if (b.manualOverride) sb << ' [manual]'
                if (b.focusBlock) sb << ' [focus]'
                sb << '\n'
            }
        }
        sb << '\n'

        // Unscheduled within view
        List<UnscheduledTask> unsched = plan.unscheduled ?: []
        sb << "Unscheduled: ${unsched.size()}\n"
        unsched.each { UnscheduledTask u ->
            sb << "- ${u.task.content} (`${u.task.id}`): ${u.reason}"
            if (u.code) sb << " [${u.code}]"
            sb << '\n'
        }
        sb << '\n'

        // Changes with safe vs approval-required distinction
        appendChangesSection(sb, plan)

        // Capacity / weather notes from metrics + explanations
        appendNotes(sb, plan)

        // Aggregation for weekly/medium
        if (kind == KIND_WEEKLY || kind == KIND_MEDIUM) {
            appendAggregation(sb, plan, today, horizonEnd)
        }

        appendProposalFooter(sb, plan, proposal, hash, now)

        String subject
        if (kind == KIND_DAILY) {
            subject = "Daily plan — ${today.format(DateTimeFormatter.ofPattern('EEE MMM d').withLocale(Locale.US))}"
        } else if (kind == KIND_WEEKLY) {
            subject = "Weekly plan summary"
        } else if (kind == KIND_MEDIUM) {
            subject = "Medium-horizon plan summary"
        } else {
            subject = "Proposal ${proposal.id}"
        }

        String contentKey = contentIdentityKey(plan, kind, destination, horizonLabel(horizon))
        String deliveryKey = deliveryIdempotencyKey(plan, kind, destination, horizon, delivery, null)
        return Message.builder()
            .kind(kind)
            .subject(subject)
            .body(sb.toString())
            .destination(destination)
            .planId(plan.id)
            .planVersion(plan.version)
            .planHash(hash)
            .proposalId(proposal.id)
            .idempotencyKey(deliveryKey)
            .createdAt(now)
            .metadata([
                horizon      : horizon?.toString(),
                horizonDays  : horizon != null ? horizon.toDays() : null,
                rangeStart   : today?.toString(),
                rangeEnd     : horizonEnd?.toString(),
                zone         : zone.id,
                blockCount   : blocks.size(),
                unscheduled  : unsched.size(),
                generatedAt  : now.toString(),
                planCreatedAt: plan.createdAt?.toString(),
                contentKey   : contentKey,
                scheduleId   : delivery?.scheduleId ?: ScheduleOccurrence.MANUAL_SCHEDULE_ID,
                occurrenceKey: delivery?.occurrenceKey ?: ScheduleOccurrence.MANUAL_OCCURRENCE
            ])
            .build()
    }

    private void appendChangesSection(StringBuilder sb, Plan plan) {
        List<PlanChange> changes = plan.changes ?: []
        List<PlanChange> safe = []
        List<PlanChange> approvalRequired = []
        List<PlanChange> frozenOrManual = []
        List<PlanChange> weatherDriven = []
        changes.each { PlanChange c ->
            boolean ar = c.metadata?.approvalRequired == true || c.metadata?.approvalRequired == 'true'
            boolean weather = c.metadata?.weather != null ||
                (c.reason ?: '').toLowerCase(Locale.ROOT).contains('weather')
            // Infer frozen/manual from matching blocks
            boolean protectedChange = false
            if (c.taskId) {
                def block = plan.scheduledBlocks.find { it.taskIds?.contains(c.taskId) }
                if (block?.frozen || block?.manualOverride) {
                    protectedChange = true
                }
            }
            if (protectedChange) {
                frozenOrManual << c
            } else if (ar) {
                approvalRequired << c
            } else if (weather) {
                weatherDriven << c
            } else {
                safe << c
            }
        }
        sb << "Changes:\n"
        if (!changes) {
            sb << "- _(none)_\n"
        } else {
            sb << "Safe (apply-safe eligible): ${safe.size()}\n"
            safe.each { sb << "  - ${formatChange(it)}\n" }
            sb << "Approval required: ${approvalRequired.size()}\n"
            approvalRequired.each { sb << "  - ${formatChange(it)} [approval-required]\n" }
            sb << "Frozen/manual (protected): ${frozenOrManual.size()}\n"
            frozenOrManual.each { sb << "  - ${formatChange(it)} [protected]\n" }
            sb << "Weather-driven: ${weatherDriven.size()}\n"
            weatherDriven.each { sb << "  - ${formatChange(it)} [weather]\n" }
        }
        sb << '\n'
    }

    private String formatChange(PlanChange c) {
        String t = c.type ?: 'change'
        String task = c.taskId ?: '?'
        String when = c.newStart != null ? fmtHuman(c.newStart) : ''
        String reason = c.reason ?: ''
        "${t} ${task}${when ? ' @ ' + when : ''}${reason ? ' — ' + reason : ''}"
    }

    private void appendNotes(StringBuilder sb, Plan plan) {
        sb << "Notes:\n"
        boolean any = false
        if (plan.metrics) {
            def cap = plan.metrics.availableCapacityMinutes ?: plan.metrics.capacityMinutes
            if (cap != null) {
                sb << "- Capacity metric: ${formatDurationMinutes(cap as long)}\n"
                any = true
            }
            def weatherTs = plan.metrics.forecastRetrievedAt ?: plan.metrics.weatherRetrievedAt
            if (weatherTs != null) {
                sb << "- Forecast retrieved: ${weatherTs}\n"
                any = true
            }
            def issued = plan.metrics.forecastIssuedAt ?: plan.metrics.weatherIssuedAt
            if (issued != null) {
                sb << "- Forecast issued: ${issued}\n"
                any = true
            }
        }
        plan.explanations?.each { ex ->
            if (ex.code?.toString()?.toLowerCase(Locale.ROOT)?.contains('weather') ||
                ex.code?.toString()?.toLowerCase(Locale.ROOT)?.contains('capacity') ||
                ex.code?.toString()?.toLowerCase(Locale.ROOT)?.contains('risk')) {
                sb << "- [${ex.code}] ${ex.message}\n"
                any = true
            }
        }
        if (!any) {
            sb << "- _(none)_\n"
        }
        sb << '\n'
    }

    private void appendAggregation(StringBuilder sb, Plan plan, LocalDate start, LocalDate end) {
        sb << "Demand / capacity by day:\n"
        sb << "Assumptions: working from stored scheduled blocks only; range ${start} → ${end} (${zone.id}).\n"
        Map<LocalDate, Long> byDay = new TreeMap<>()
        plan.scheduledBlocks.each { ScheduledBlock b ->
            LocalDate d = b.start.atZone(zone).toLocalDate()
            if (end != null && (d.isBefore(start) || !d.isBefore(end))) {
                return
            }
            long mins = Duration.between(b.start, b.end).toMinutes()
            byDay[d] = (byDay[d] ?: 0L) + mins
        }
        if (!byDay) {
            sb << "- _(no scheduled demand in range)_\n"
        } else {
            byDay.each { d, mins ->
                sb << "- ${d.format(HUMAN_DATE)}: ${formatDurationMinutes(mins)} scheduled\n"
            }
        }
        // Risk aggregation within risk window
        long riskCount = plan.unscheduled?.count { isRisk(it) && isWithinRiskWindow(it, start.atStartOfDay(zone).toInstant()) } ?: 0L
        sb << "Capacity-risk items: ${riskCount}\n"
        sb << '\n'
    }

    private void appendProposalFooter(StringBuilder sb, Plan plan, Proposal proposal, String hash, Instant now) {
        sb << "---\n"
        sb << "Proposal: ${proposal.id}\n"
        sb << "Plan id: ${plan.id}\n"
        sb << "Plan version: ${plan.version}\n"
        sb << "Plan hash: ${hash}\n"
        sb << "Mode: ${plan.mode}\n"
        sb << "Plan generated: ${plan.createdAt} (ISO) / ${fmtHuman(plan.createdAt)}\n"
        sb << "Message generated: ${now} (ISO) / ${fmtHuman(now)}\n"
        if (plan.metrics?.forecastRetrievedAt) {
            sb << "Forecast timestamp: ${plan.metrics.forecastRetrievedAt}\n"
        }
        sb << "\nStructured actions:\n"
        String hp = hash.length() >= 12 ? hash.substring(0, 12) : hash
        sb << "- approve ${proposal.id} ${hp}\n"
        sb << "- reject ${proposal.id} ${hp} <reason>\n"
        sb << "- apply-safe ${proposal.id} ${hp}\n"
    }

    static boolean isRisk(UnscheduledTask u) {
        if (u == null) {
            return false
        }
        String code = (u.code ?: '').toLowerCase(Locale.ROOT)
        String reason = (u.reason ?: '').toLowerCase(Locale.ROOT)
        return code.contains('risk') || code.contains('deadline') || code.contains('capacity') ||
            code.contains('weather') || code.contains('infeasible') ||
            reason.contains('deadline') || reason.contains('capacity') ||
            reason.contains('no slot') || reason.contains('weather') || reason.contains('infeasible')
    }

    static List<String> extractAlternatives(UnscheduledTask risk) {
        List<String> out = []
        def meta = risk?.metadata ?: [:]
        def alts = meta.alternatives ?: meta.alternativeOptions ?: meta.replacements
        if (alts instanceof Collection) {
            alts.each { a ->
                if (a instanceof Map) {
                    String title = a.title ?: a.content ?: a.taskId ?: a.id
                    String when = a.start ?: a.slot ?: a.window
                    String s = title?.toString() ?: 'option'
                    if (when) {
                        s += " @ ${when}"
                    }
                    if (a.reason) {
                        s += " (${a.reason})"
                    }
                    out << s
                } else if (a != null) {
                    out << a.toString()
                }
            }
        }
        def replacement = meta.replacementTaskId ?: meta.replacement
        if (replacement && out.isEmpty()) {
            out << "replacement: ${replacement}"
        }
        return out
    }

    /**
     * Renderer content identity only (plan/kind/destination/extra). Stable across clock ticks.
     * Not sufficient alone for recurring scheduled delivery — use {@link #deliveryIdempotencyKey}.
     */
    static String contentIdentityKey(Plan plan, String kind, String destination, String extra = null) {
        String hash = PlanHash.compute(plan)
        String raw = "${plan.id}|${plan.version}|${hash}|${kind}|${destination}|${extra ?: ''}"
        return "content-${kind}-${sha256Hex(raw).substring(0, 24)}"
    }

    /**
     * @deprecated Use {@link #contentIdentityKey} for content identity or
     * {@link #deliveryIdempotencyKey} for scheduled delivery. Kept as alias of content identity
     * for backward-compatible call sites that do not pass occurrence context.
     */
    static String idempotencyKey(Plan plan, String kind, String destination, String extra = null) {
        // Historical name: content-only key. Scheduled paths must use deliveryIdempotencyKey.
        String hash = PlanHash.compute(plan)
        String raw = "${plan.id}|${plan.version}|${hash}|${kind}|${destination}|${extra ?: ''}"
        return "msg-${kind}-${sha256Hex(raw).substring(0, 24)}"
    }

    /**
     * Full delivery idempotency key: plan semantic identity + kind + destination + horizon +
     * schedule identity + occurrence key (+ optional risk task/alert id).
     * Manual/direct renders (null delivery) use stable manual schedule/occurrence tokens —
     * no silent clock nondeterminism.
     */
    static String deliveryIdempotencyKey(Plan plan, String kind, String destination, Duration horizon,
                                         DeliveryContext delivery, String alertIdentity = null) {
        String hash = PlanHash.compute(plan)
        String sid = delivery?.scheduleId ?: ScheduleOccurrence.MANUAL_SCHEDULE_ID
        String occ = delivery?.occurrenceKey ?: ScheduleOccurrence.MANUAL_OCCURRENCE
        String dest = destination ?: ''
        String hLabel = horizonLabel(horizon)
        String thread = delivery?.threadId ?: ''
        String alert = alertIdentity ?: ''
        String raw = "${plan.id}|${plan.version}|${hash}|${kind}|${dest}|${thread}|${hLabel}|${sid}|${occ}|${alert}"
        return "msg-${kind}-${sha256Hex(raw).substring(0, 24)}"
    }

    private static String sha256Hex(String raw) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8))
        return dig.collect { String.format('%02x', it & 0xff) }.join()
    }

    /**
     * Explicit delivery/occurrence context. Separates content identity from occurrence idempotency.
     */
    static final class DeliveryContext {
        final String scheduleId
        final String occurrenceKey
        final String threadId

        DeliveryContext(String scheduleId, String occurrenceKey, String threadId = null) {
            this.scheduleId = scheduleId ?: ScheduleOccurrence.MANUAL_SCHEDULE_ID
            this.occurrenceKey = occurrenceKey ?: ScheduleOccurrence.MANUAL_OCCURRENCE
            this.threadId = threadId
        }

        static DeliveryContext manual(String correlation = null) {
            new DeliveryContext(ScheduleOccurrence.MANUAL_SCHEDULE_ID,
                correlation ?: ScheduleOccurrence.MANUAL_OCCURRENCE, null)
        }
    }

    private static String horizonLabel(Duration horizon) {
        if (horizon == null) {
            return 'all'
        }
        return "P${horizon.toDays()}D"
    }

    private static long sumBlockMinutes(List<ScheduledBlock> blocks) {
        long total = 0L
        blocks?.each { total += Duration.between(it.start, it.end).toMinutes() }
        return total
    }

    private static String formatDurationMinutes(long minutes) {
        if (minutes < 0) {
            minutes = 0
        }
        long h = minutes / 60
        long m = minutes % 60
        if (h > 0 && m > 0) {
            return "${h}h ${m}m"
        }
        if (h > 0) {
            return "${h}h"
        }
        return "${m}m"
    }

    private String fmtHuman(Instant instant) {
        if (instant == null) {
            return ''
        }
        return HUMAN_DATETIME.format(instant.atZone(zone))
    }

    private String fmtRange(Instant start, Instant end) {
        if (start == null) {
            return ''
        }
        String a = HUMAN_TIME.format(start.atZone(zone))
        if (end == null) {
            return a
        }
        String b = HUMAN_TIME.format(end.atZone(zone))
        String day = HUMAN_DATE.format(start.atZone(zone))
        return "${day} ${a}–${b}"
    }
}
