package todoistcaldavsync.planner.messaging

import todoistcaldavsync.planner.adapters.MessagingGateway
import todoistcaldavsync.planner.apply.PlanApplier
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.ApplicationReceipt
import todoistcaldavsync.planner.domain.ApplyItemStatus
import todoistcaldavsync.planner.domain.Approval
import todoistcaldavsync.planner.domain.DecisionRecord
import todoistcaldavsync.planner.domain.DeliveryReceipt
import todoistcaldavsync.planner.domain.Message
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.feedback.FeedbackParser
import todoistcaldavsync.planner.state.DecisionStore
import todoistcaldavsync.planner.state.DeliveryLedger
import todoistcaldavsync.planner.state.PlanStore

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * Delivery orchestration: load stored Plan → render → send → ledger.
 * Does not recompute schedules. Feedback parsing never auto-applies;
 * {@link #applyDecision} is explicit and test-gated.
 */
class MessagingService {
    private final PlannerConfig config
    private final PlanStore planStore
    private final MessagingGateway gateway
    private final DeliveryLedger ledger
    private final DecisionStore decisionStore
    private final FeedbackParser feedbackParser
    private final MessageRenderer renderer
    private final PlanApplier planApplier
    private final Supplier<Instant> clock
    private final AtomicInteger receiptSeq = new AtomicInteger(0)

    MessagingService(PlannerConfig config,
                     PlanStore planStore,
                     MessagingGateway gateway,
                     DeliveryLedger ledger,
                     DecisionStore decisionStore,
                     FeedbackParser feedbackParser,
                     PlanApplier planApplier = null,
                     Supplier<Instant> clock = { Instant.now() }) {
        if (config == null) throw new IllegalArgumentException('config is required')
        if (planStore == null) throw new IllegalArgumentException('planStore is required')
        if (ledger == null) throw new IllegalArgumentException('ledger is required')
        if (decisionStore == null) throw new IllegalArgumentException('decisionStore is required')
        if (feedbackParser == null) throw new IllegalArgumentException('feedbackParser is required')
        this.config = config
        this.planStore = planStore
        this.gateway = gateway
        this.ledger = ledger
        this.decisionStore = decisionStore
        this.feedbackParser = feedbackParser
        this.planApplier = planApplier
        this.clock = clock ?: ({ Instant.now() } as Supplier)
        ZoneId msgZone = messagingZone()
        String dest = config.messaging?.destination ?: '#planner'
        Integer riskDays = config.messaging?.riskDeadlineDays
        this.renderer = new MessageRenderer(msgZone, dest, riskDays != null ? riskDays : 5)
    }

    PlannerConfig.MessagingConfig getMessagingConfig() {
        config.messaging ?: PlannerConfig.MessagingConfig.disabled()
    }

    /**
     * Messaging timezone: messagingConfig.timezone, else planner timezone, else UTC.
     */
    ZoneId messagingZone() {
        messagingConfig.timezone ?: config.timezone ?: ZoneId.of('UTC')
    }

    /**
     * Deterministically list delivery intents due at {@code now}. No daemon.
     * Risk alerts are due only from an explicit capacity_risk_alert schedule,
     * or when a due daily_summary schedule is present and capacityRiskAlerts is enabled
     * (documented daily-intent trigger). capacityRiskAlerts alone does not bypass schedules.
     */
    List<DeliveryIntent> dueIntents(Instant now = clock.get()) {
        PlannerConfig.MessagingConfig mc = messagingConfig
        if (!mc.enabled) {
            return []
        }
        Instant t = now ?: clock.get()
        ZoneId zone = messagingZone()
        String dest = mc.destination ?: '#planner'
        List<DeliveryIntent> due = []
        boolean dailyDue = false
        PlannerConfig.MessageSchedule dailySched = null
        mc.schedules?.each { PlannerConfig.MessageSchedule sched ->
            if (!mc.isKindEnabled(sched.kind)) {
                return
            }
            if (isScheduleDue(sched, t, zone)) {
                due << intentFromSchedule(sched, dest, t, zone)
                if (sched.kind == MessageRenderer.KIND_DAILY) {
                    dailyDue = true
                    dailySched = sched
                }
            }
        }
        // Documented daily-intent trigger for risk: only when capacityRiskAlerts enabled,
        // risk kind enabled, a daily schedule is due, and no explicit risk schedule already due.
        boolean explicitRiskDue = due.any { it.kind == MessageRenderer.KIND_RISK }
        if (mc.capacityRiskAlerts && mc.isKindEnabled(MessageRenderer.KIND_RISK) &&
            dailyDue && !explicitRiskDue) {
            Duration riskHorizon = Duration.ofDays(mc.riskDeadlineDays > 0 ? mc.riskDeadlineDays : 5)
            // Inherit occurrence from the due daily schedule so risk retries share the day window.
            String sid = dailySched != null
                ? ScheduleOccurrence.scheduleIdentity(dailySched, dest) + '-risk'
                : 'daily-risk-trigger'
            String occ = dailySched != null
                ? ScheduleOccurrence.occurrenceKey(dailySched, t, zone)
                : ScheduleOccurrence.MANUAL_OCCURRENCE
            due << new DeliveryIntent(MessageRenderer.KIND_RISK, riskHorizon, 'daily-risk-trigger',
                zone, sid, occ, dest)
        }
        return due
    }

    /**
     * Deliver all due intents for a stored plan id.
     */
    List<DeliveryReceipt> deliverDue(String planId, Instant now = clock.get()) {
        if (!messagingConfig.enabled) {
            return [skippedDisabled('all')]
        }
        Plan plan = planStore.load(planId)
        if (plan == null) {
            throw new IllegalArgumentException("Plan not found: ${planId}")
        }
        List<DeliveryReceipt> out = []
        Set<String> deliveredRiskKeys = new HashSet<>()
        dueIntents(now).each { intent ->
            if (intent.kind == MessageRenderer.KIND_RISK) {
                renderKind(plan, intent.kind, now, intent.horizon, intent.deliveryContext()).each { msg ->
                    if (deliveredRiskKeys.add(msg.idempotencyKey)) {
                        out << sendWithLedger(msg)
                    }
                }
            } else {
                out.addAll(deliverIntent(plan, intent, now))
            }
        }
        return out
    }

    /**
     * Deliver a specific kind for a stored plan (ignores schedule due-window; still respects enabled/ledger).
     * Uses a stable manual delivery key (no clock-derived occurrence) unless an explicit
     * {@link DeliveryIntent} is used via {@link #deliverIntent}.
     * Empty capacity_risk_alert (no qualifying risks) → empty list, no gateway send, no ledger write.
     */
    List<DeliveryReceipt> deliverKind(String planId, String kind, Instant now = clock.get()) {
        if (!messagingConfig.enabled) {
            return [skippedDisabled(kind)]
        }
        if (!messagingConfig.isKindEnabled(kind)) {
            return [skippedDisabled(kind)]
        }
        if (gateway == null) {
            return [skippedDisabled(kind)]
        }
        Plan plan = planStore.load(planId)
        if (plan == null) {
            throw new IllegalArgumentException("Plan not found: ${planId}")
        }
        Duration horizon = defaultHorizonForKind(kind)
        // Manual/direct: stable key (no silent clock nondeterminism).
        List<Message> messages = renderKind(plan, kind, now, horizon, MessageRenderer.DeliveryContext.manual())
        if (messages.isEmpty()) {
            // No message (e.g. capacity_risk_alert with zero qualifying risks): no send, no PENDING.
            return []
        }
        return messages.collect { sendWithLedger(it) }
    }

    List<DeliveryReceipt> deliverIntent(Plan plan, DeliveryIntent intent, Instant now) {
        if (!messagingConfig.enabled || gateway == null) {
            return [skippedDisabled(intent.kind)]
        }
        List<Message> messages = renderKind(plan, intent.kind, now, intent.horizon, intent.deliveryContext())
        if (messages.isEmpty()) {
            return []
        }
        return messages.collect { sendWithLedger(it) }
    }

    /**
     * Render message(s) for a kind. Most kinds yield exactly one message.
     * {@link MessageRenderer#KIND_RISK} yields zero or more capacity_risk_alert messages only —
     * never a daily_summary fallback when there are no qualifying risks.
     * When {@code delivery} is null, renderer uses stable manual schedule/occurrence tokens.
     */
    List<Message> renderKind(Plan plan, String kind, Instant now, Duration horizon = null,
                             MessageRenderer.DeliveryContext delivery = null) {
        Duration h = horizon != null ? horizon : defaultHorizonForKind(kind)
        MessageRenderer.DeliveryContext ctx = delivery ?: MessageRenderer.DeliveryContext.manual()
        switch (kind) {
            case MessageRenderer.KIND_DAILY:
                return [renderer.renderDailySummary(plan, now, h, ctx)]
            case MessageRenderer.KIND_WEEKLY:
                return [renderer.renderWeeklySummary(plan, now, h, ctx)]
            case MessageRenderer.KIND_MEDIUM:
                return [renderer.renderMediumHorizonSummary(plan, now, h, ctx)]
            case MessageRenderer.KIND_PROPOSAL:
                return [renderer.renderProposal(plan, now, ctx)]
            case MessageRenderer.KIND_RISK:
                // Empty list when no qualifying risks — callers must not send or ledger.
                return renderer.renderCapacityRiskAlerts(plan, now, ctx)
            default:
                throw new IllegalArgumentException("Unknown message kind: ${kind}")
        }
    }

    private Duration defaultHorizonForKind(String kind) {
        PlannerConfig.MessagingConfig mc = messagingConfig
        def match = mc.schedules?.find { it.kind == kind && it.horizon != null }
        if (match?.horizon != null) {
            return match.horizon
        }
        if (kind == MessageRenderer.KIND_WEEKLY) return Duration.ofDays(7)
        if (kind == MessageRenderer.KIND_MEDIUM) return Duration.ofDays(14)
        if (kind == MessageRenderer.KIND_RISK) {
            return Duration.ofDays(mc.riskDeadlineDays > 0 ? mc.riskDeadlineDays : 5)
        }
        return Duration.ofDays(1)
    }

    /**
     * Send through gateway and persist ledger. Idempotent on delivered key.
     * Atomic pre-send claim via {@link DeliveryLedger#tryClaimPending} under store lock —
     * only the winning claim calls the provider. Loser returns explicit idempotent/no-op
     * receipt without false DELIVERED. Provider success → atomic DELIVERED. Final ledger
     * failure after success → UNKNOWN/NEEDS_RECONCILIATION (no blind resend). Provider
     * failure → FAILED (retry may re-claim). Pre-send claim/ledger failure → provider not called.
     */
    DeliveryReceipt sendWithLedger(Message message) {
        if (message == null) {
            throw new IllegalArgumentException('message is required')
        }
        if (!messagingConfig.enabled || gateway == null) {
            return skippedDisabled(message.kind)
        }
        String key = message.idempotencyKey
        Instant now = clock.get()
        DeliveryReceipt pending = DeliveryReceipt.builder()
            .id(nextReceiptId('pend'))
            .idempotencyKey(key)
            .kind(message.kind)
            .destination(message.destination)
            .planId(message.planId)
            .planVersion(message.planVersion)
            .planHash(message.planHash)
            .proposalId(message.proposalId)
            .status('PENDING')
            .attemptedAt(now)
            .metadata([phase: 'pre-send'])
            .build()

        DeliveryLedger.ClaimResult claim
        try {
            claim = ledger.tryClaimPending(key, pending)
        } catch (Exception e) {
            // Pre-send ledger failure: provider must not be called
            throw new LedgerPersistException(
                "Pre-send ledger claim failed for ${key}; provider not called: ${e.message}",
                pending, e)
        }
        if (!claim.claimed) {
            return claimRefusedResult(claim, message, key)
        }

        DeliveryReceipt providerReceipt
        try {
            providerReceipt = gateway.send(message)
        } catch (Exception e) {
            DeliveryReceipt failed = DeliveryReceipt.builder()
                .id(nextReceiptId('fail'))
                .idempotencyKey(key)
                .kind(message.kind)
                .destination(message.destination)
                .planId(message.planId)
                .planVersion(message.planVersion)
                .planHash(message.planHash)
                .proposalId(message.proposalId)
                .status('FAILED')
                .attemptedAt(now)
                .completedAt(clock.get())
                .errorClassification('TRANSPORT')
                .errorMessage(e.message ?: 'provider threw')
                .metadata([priorPendingId: pending.id])
                .build()
            try {
                ledger.transition(key, ['PENDING', 'ATTEMPT'] as Set, failed)
            } catch (Exception ignored) {
                // best-effort; still return failed
            }
            return failed
        }

        if (providerReceipt == null) {
            providerReceipt = DeliveryReceipt.builder()
                .id(nextReceiptId('null'))
                .idempotencyKey(key)
                .kind(message.kind)
                .destination(message.destination)
                .planId(message.planId)
                .planVersion(message.planVersion)
                .planHash(message.planHash)
                .proposalId(message.proposalId)
                .status('FAILED')
                .attemptedAt(now)
                .completedAt(clock.get())
                .errorClassification('TRANSPORT')
                .errorMessage('gateway returned null')
                .build()
        }

        // Ensure final receipt uses same idempotency key
        DeliveryReceipt finalReceipt = providerReceipt
        if (providerReceipt.idempotencyKey != key) {
            finalReceipt = DeliveryReceipt.builder()
                .id(providerReceipt.id ?: nextReceiptId('fix'))
                .idempotencyKey(key)
                .kind(providerReceipt.kind ?: message.kind)
                .destination(providerReceipt.destination ?: message.destination)
                .planId(providerReceipt.planId ?: message.planId)
                .planVersion(providerReceipt.planVersion ?: message.planVersion)
                .planHash(providerReceipt.planHash ?: message.planHash)
                .proposalId(providerReceipt.proposalId ?: message.proposalId)
                .status(providerReceipt.status)
                .providerMessageId(providerReceipt.providerMessageId)
                .threadId(providerReceipt.threadId)
                .channelId(providerReceipt.channelId)
                .attemptedAt(providerReceipt.attemptedAt ?: now)
                .completedAt(providerReceipt.completedAt ?: clock.get())
                .errorClassification(providerReceipt.errorClassification)
                .errorMessage(providerReceipt.errorMessage)
                .metadata(providerReceipt.metadata instanceof Map
                    ? new LinkedHashMap(providerReceipt.metadata as Map) : [:])
                .build()
        }

        try {
            ledger.transition(key, ['PENDING', 'ATTEMPT'] as Set, finalReceipt)
        } catch (Exception e) {
            if (finalReceipt.status == 'DELIVERED') {
                // Provider succeeded; ledger final write failed → UNKNOWN, do not claim delivered in return path
                DeliveryReceipt unknown = DeliveryReceipt.builder()
                    .id(nextReceiptId('unk'))
                    .idempotencyKey(key)
                    .kind(message.kind)
                    .destination(message.destination)
                    .planId(message.planId)
                    .planVersion(message.planVersion)
                    .planHash(message.planHash)
                    .proposalId(message.proposalId)
                    .status('UNKNOWN')
                    .providerMessageId(finalReceipt.providerMessageId)
                    .threadId(finalReceipt.threadId)
                    .channelId(finalReceipt.channelId)
                    .attemptedAt(now)
                    .completedAt(clock.get())
                    .errorClassification('LEDGER')
                    .errorMessage("Provider delivered but ledger persist failed: ${e.message}")
                    .metadata([
                        priorPendingId   : pending.id,
                        providerReceiptId: finalReceipt.id,
                        needsReconciliation: true
                    ])
                    .build()
                // Best-effort mark UNKNOWN so retry won't resend (PENDING still blocks if UNKNOWN write fails)
                try {
                    ledger.transition(key, ['PENDING', 'ATTEMPT'] as Set, unknown)
                } catch (Exception ignored) {
                    // If even UNKNOWN cannot be written, still throw with provider receipt
                }
                throw new LedgerPersistException(
                    "Provider delivered but ledger persist failed for ${key}: ${e.message}",
                    unknown, e)
            }
            throw e
        }
        return finalReceipt
    }

    /**
     * Explicit no-op / idempotent result when atomic claim loses. Never returns false DELIVERED.
     */
    private DeliveryReceipt claimRefusedResult(DeliveryLedger.ClaimResult claim,
                                               Message message, String key) {
        DeliveryReceipt existing = claim.existing
        if (existing != null && existing.status == 'DELIVERED') {
            return duplicateOf(existing, message)
        }
        String priorStatus = existing?.status ?: 'ABSENT'
        String outStatus
        if (priorStatus == 'PENDING' || priorStatus == 'ATTEMPT') {
            outStatus = 'NEEDS_RECONCILIATION'
        } else if (priorStatus == 'UNKNOWN' || priorStatus == 'NEEDS_RECONCILIATION') {
            outStatus = priorStatus
        } else {
            outStatus = 'SKIPPED_DUPLICATE'
        }
        return DeliveryReceipt.builder()
            .id(nextReceiptId('noop'))
            .idempotencyKey(key)
            .kind(message.kind)
            .destination(message.destination)
            .planId(message.planId)
            .planVersion(message.planVersion)
            .planHash(message.planHash)
            .proposalId(message.proposalId)
            .status(outStatus)
            .providerMessageId(existing?.providerMessageId)
            .threadId(existing?.threadId)
            .channelId(existing?.channelId)
            .attemptedAt(clock.get())
            .completedAt(clock.get())
            .errorClassification(outStatus == 'SKIPPED_DUPLICATE' ? null : 'RECONCILIATION')
            .errorMessage(outStatus == 'SKIPPED_DUPLICATE'
                ? "Idempotent no-op; claim refused (${claim.reason})"
                : "Prior delivery state ${priorStatus}; refusing blind resend (${claim.reason})")
            .metadata([
                originalReceiptId: existing?.id,
                priorStatus      : priorStatus,
                claimReason      : claim.reason,
                idempotentNoop   : true
            ])
            .build()
    }

    /**
     * Parse structured feedback against stored plan. Never calls PlanApplier.
     */
    FeedbackParser.FeedbackResult handleFeedback(String planId, String rawText,
                                                 FeedbackParser.FeedbackContext baseCtx) {
        Plan plan = planId != null ? planStore.load(planId) : baseCtx?.plan
        Map opts = [
            actorId             : baseCtx?.actorId,
            correlationId       : baseCtx?.correlationId,
            destination         : baseCtx?.destination ?: messagingConfig.destination,
            threadId            : baseCtx?.threadId,
            messageId           : baseCtx?.messageId,
            reason              : baseCtx?.reason,
            plan                : plan,
            expectedPlanVersion : baseCtx?.expectedPlanVersion
        ]
        return feedbackParser.parseAndRecord(rawText, new FeedbackParser.FeedbackContext(opts))
    }

    /**
     * Explicit apply after validated decision. Returns structured {@link ApplyDecisionResult}
     * for every path (APPLIED / NOOP / REJECTED / REPLAYED). Prefer this over the legacy
     * {@link #applyDecisionReceipt} wrapper.
     *
     * <p>APPROVE → PlanApplier only when status exactly ACCEPTED and toApproval() non-null
     * with exact plan id/version/hash match. Never synthesizes Approval.
     * APPLY_SAFE → {@link PlanApplier#applySafeChanges} only for exact ACCEPTED + matching identity
     * (never generic apply(plan,null) based on stored plan.mode).
     * IDEMPOTENT_REPLAY → REPLAYED (zero writes). CONFLICT / REJECTED → throws or REJECTED.
     * REJECT/STATUS/HELP/REQUEST_CHANGES → NOOP zero writes.
     * Result status is truthful from receipt item write outcomes (APPLIED/PARTIAL/NOOP/ERROR).
     */
    ApplyDecisionResult applyDecision(String planId, DecisionRecord decision) {
        if (planApplier == null) {
            throw new IllegalStateException('PlanApplier not configured; apply is explicit and optional')
        }
        if (decision == null) {
            throw new IllegalArgumentException('decision is required')
        }

        // Replays never apply twice / never mutate
        if (decision.isIdempotentReplay() || decision.isReplayed()) {
            return ApplyDecisionResult.replayed(decision, 'IDEMPOTENT_REPLAY')
        }
        if (decision.isReplayConflict() ||
            (decision.status != null && decision.status.startsWith('REJECTED'))) {
            throw new IllegalArgumentException(
                "decision not applicable for apply: status=${decision.status}")
        }
        // isAccepted() is ACCEPTED-only (replay never authorizes)
        if (!decision.isAccepted()) {
            throw new IllegalArgumentException(
                "decision must be exactly ACCEPTED for apply, got status=${decision.status}")
        }

        Plan plan = planStore.load(planId)
        if (plan == null) {
            throw new IllegalArgumentException("Plan not found: ${planId}")
        }
        String hash = PlanHash.compute(plan)

        // Exact plan identity
        if (decision.planId && decision.planId != plan.id) {
            throw new IllegalArgumentException(
                "decision planId ${decision.planId} does not match stored plan ${plan.id}")
        }
        if (decision.planVersion > 0 && decision.planVersion != plan.version) {
            throw new IllegalArgumentException(
                "decision planVersion ${decision.planVersion} does not match stored plan ${plan.version}")
        }
        if (decision.planHash && decision.planHash != 'none' && decision.planHash != hash) {
            throw new IllegalArgumentException('decision planHash does not match stored plan')
        }

        if (decision.action == 'REJECT' || decision.action == 'STATUS' ||
            decision.action == 'HELP' || decision.action == 'REQUEST_CHANGES') {
            return ApplyDecisionResult.noop(decision, "non-apply action ${decision.action}")
        }

        if (decision.action == 'APPROVE') {
            if (!decision.hasExactPlanIdentity()) {
                throw new IllegalArgumentException(
                    'APPROVE requires exact plan id/version/hash on decision (not forged none)')
            }
            Approval approval = decision.toApproval()
            if (approval == null) {
                throw new IllegalArgumentException(
                    'APPROVE decision cannot produce Approval (toApproval null); refusing to synthesize')
            }
            // Exact identity on approval object
            if (approval.planId != plan.id || approval.planVersion != plan.version ||
                approval.planHash != hash) {
                throw new IllegalArgumentException(
                    'Approval plan identity/hash does not exactly match stored plan')
            }
            ApplicationReceipt receipt = planApplier.apply(plan, approval)
            return ApplyDecisionResult.fromReceipt(receipt, decision)
        }

        if (decision.action == 'APPLY_SAFE') {
            // Exact ACCEPTED already checked; require plan identity/hash present and matching
            if (!decision.hasExactPlanIdentity()) {
                throw new IllegalArgumentException(
                    'APPLY_SAFE requires exact plan id/version/hash on decision')
            }
            if (decision.planId != plan.id || decision.planVersion != plan.version ||
                decision.planHash != hash) {
                throw new IllegalArgumentException(
                    'APPLY_SAFE decision plan identity/hash does not exactly match stored plan')
            }
            // Explicit safe entry — never generic apply(plan,null) on stored plan.mode
            ApplicationReceipt receipt = planApplier.applySafeChanges(plan)
            return ApplyDecisionResult.fromReceipt(receipt, decision)
        }

        throw new IllegalArgumentException("Unsupported apply action: ${decision.action}")
    }

    /**
     * Backward-compatible wrapper: returns {@link ApplicationReceipt} only when APPLIED
     * or PARTIAL (external writes occurred), otherwise null (NOOP/REPLAYED/ERROR/REJECTED).
     * Conflict/rejected still throw. Prefer {@link #applyDecision} for structured outcomes.
     */
    ApplicationReceipt applyDecisionReceipt(String planId, DecisionRecord decision) {
        ApplyDecisionResult result = applyDecision(planId, decision)
        if (result == null) {
            return null
        }
        if (result.isApplied() || result.isPartial()) {
            return result.receipt
        }
        return null
    }

    /**
     * Schedule due check. Supports:
     * - {@code HH:mm} daily at local time
     * - {@code dow HH:mm} e.g. {@code mon 09:00}
     * - cron-like {@code m H * * *} (minute hour dom mon dow) — dom/mon must be *
     * - cron-like {@code m H * * dow} weekly
     * Window: due if local time is within [scheduled, scheduled+window) and same day match.
     * DST spring gap: nonexistent configured local time shifts to first valid time after gap
     * (one reachable occurrence). DST fold: local wall second-of-day match only — same local
     * date/time shares one occurrence key (no double-send across overlap instants).
     */
    static boolean isScheduleDue(PlannerConfig.MessageSchedule sched, Instant now, ZoneId zone) {
        return ScheduleOccurrence.isScheduleDue(sched, now, zone)
    }

    private DeliveryIntent intentFromSchedule(PlannerConfig.MessageSchedule sched, String destination,
                                              Instant now, ZoneId zone) {
        String sid = ScheduleOccurrence.scheduleIdentity(sched, destination)
        String occ = ScheduleOccurrence.occurrenceKey(sched, now, zone)
        new DeliveryIntent(sched.kind, sched.horizon, sched.name, zone, sid, occ, destination)
    }

    private DeliveryReceipt skippedDisabled(String kind) {
        Instant now = clock.get()
        DeliveryReceipt.builder()
            .id(nextReceiptId('skip'))
            .idempotencyKey("skip-${kind}")
            .kind(kind ?: 'unknown')
            .destination(messagingConfig.destination ?: 'none')
            .status('SKIPPED_DISABLED')
            .attemptedAt(now)
            .completedAt(now)
            .build()
    }

    private DeliveryReceipt duplicateOf(DeliveryReceipt existing, Message message) {
        DeliveryReceipt.builder()
            .id(existing.id + '-dup')
            .idempotencyKey(message.idempotencyKey)
            .kind(message.kind)
            .destination(message.destination)
            .planId(message.planId)
            .planVersion(message.planVersion)
            .planHash(message.planHash)
            .proposalId(message.proposalId)
            .status('SKIPPED_DUPLICATE')
            .providerMessageId(existing.providerMessageId)
            .threadId(existing.threadId)
            .channelId(existing.channelId)
            .attemptedAt(clock.get())
            .completedAt(clock.get())
            .metadata([originalReceiptId: existing.id])
            .build()
    }

    private String nextReceiptId(String prefix) {
        "dlv-${prefix}-${clock.get().toEpochMilli()}-${receiptSeq.incrementAndGet()}"
    }

    static final class DeliveryIntent {
        final String kind
        final Duration horizon
        final String name
        final ZoneId zone
        /** Stable schedule identity (hash of canonical schedule fields). */
        final String scheduleId
        /** Occurrence key for this due window (local scheduled date/time + cadence). */
        final String occurrenceKey
        final String destination

        DeliveryIntent(String kind, Duration horizon, String name, ZoneId zone) {
            this(kind, horizon, name, zone, ScheduleOccurrence.MANUAL_SCHEDULE_ID,
                ScheduleOccurrence.MANUAL_OCCURRENCE, null)
        }

        DeliveryIntent(String kind, Duration horizon, String name, ZoneId zone,
                       String scheduleId, String occurrenceKey, String destination) {
            this.kind = kind
            this.horizon = horizon
            this.name = name
            this.zone = zone
            this.scheduleId = scheduleId ?: ScheduleOccurrence.MANUAL_SCHEDULE_ID
            this.occurrenceKey = occurrenceKey ?: ScheduleOccurrence.MANUAL_OCCURRENCE
            this.destination = destination
        }

        MessageRenderer.DeliveryContext deliveryContext() {
            new MessageRenderer.DeliveryContext(scheduleId, occurrenceKey, null)
        }
    }

    /**
     * Immutable structured result of {@link #applyDecision}.
     * Status is truthful from receipt write outcomes:
     * APPLIED only if at least one successful external write;
     * PARTIAL if mixed applied + failed/withheld;
     * NOOP if zero writes and every item skipped/no changes;
     * ERROR if gate/errors with no successful writes;
     * REJECTED/REPLAYED for decision-level outcomes.
     */
    static final class ApplyDecisionResult {
        enum Status { APPLIED, PARTIAL, NOOP, REJECTED, REPLAYED, ERROR }

        final Status status
        final String action
        final String decisionId
        final ApplicationReceipt receipt
        final String reason
        final DecisionRecord decision

        private ApplyDecisionResult(Status status, ApplicationReceipt receipt,
                                    String reason, DecisionRecord decision) {
            this.status = status
            this.receipt = receipt
            this.reason = reason
            this.decision = decision
            this.action = decision?.action
            this.decisionId = decision?.id
        }

        /**
         * Classify apply outcome from receipt item statuses. Receipt retained for audit
         * on NOOP/ERROR/PARTIAL as well as APPLIED.
         * <p>Intentional protected/unapproved skips under APPLY_SAFE do not make PARTIAL —
         * PARTIAL is only mixed successful writes + actual failures. APPLIED when at least
         * one external write succeeded and no item failed.
         */
        static ApplyDecisionResult fromReceipt(ApplicationReceipt receipt, DecisionRecord decision) {
            if (receipt == null) {
                return new ApplyDecisionResult(Status.ERROR, null, 'null receipt', decision)
            }
            boolean anyWrite = receipt.wroteAnything()
            List items = receipt.items ?: []
            // Actual external failures only — not intentional SKIPPED_PROTECTED/UNAPPROVED
            boolean anyFail = items.any {
                it.calendarStatus == ApplyItemStatus.FAILED ||
                    it.todoistStatus == ApplyItemStatus.FAILED ||
                    it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID ||
                    it.calendarStatus == ApplyItemStatus.PARTIAL ||
                    it.todoistStatus == ApplyItemStatus.PARTIAL
            }
            ApplyItemStatus overall = receipt.overallStatus

            if (anyWrite && anyFail) {
                return new ApplyDecisionResult(Status.PARTIAL, receipt,
                    overall?.wire ?: 'partial', decision)
            }
            if (anyWrite) {
                // Successful writes (+ optional intentional protected skips) → APPLIED
                return new ApplyDecisionResult(Status.APPLIED, receipt, null, decision)
            }
            // Zero external writes
            if (overall == ApplyItemStatus.FAILED || overall == ApplyItemStatus.ERROR_EXTERNAL_UID) {
                return new ApplyDecisionResult(Status.ERROR, receipt,
                    receipt.errors ? receipt.errors.join('; ') : (overall?.wire ?: 'error'), decision)
            }
            if (items.isEmpty() && overall == ApplyItemStatus.SKIPPED_UNAPPROVED) {
                // Gate refused entire apply (e.g. missing approval) — receipt retained
                return new ApplyDecisionResult(Status.REJECTED, receipt,
                    receipt.errors ? receipt.errors.join('; ') : 'gate refused', decision)
            }
            if (anyFail && items.isEmpty()) {
                return new ApplyDecisionResult(Status.ERROR, receipt,
                    receipt.errors ? receipt.errors.join('; ') : 'error', decision)
            }
            if (anyFail) {
                return new ApplyDecisionResult(Status.ERROR, receipt,
                    receipt.errors ? receipt.errors.join('; ') : (overall?.wire ?: 'error'), decision)
            }
            // All skipped / empty / protected-only / no-changes → structured NOOP (receipt kept)
            String reason = overall?.wire ?: 'noop'
            if (receipt.metadata?.noChanges) {
                reason = 'empty plan: no scheduled blocks'
            } else if (items && items.every {
                it.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED ||
                    it.calendarStatus == ApplyItemStatus.SKIPPED_UNAPPROVED ||
                    it.calendarStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE
            }) {
                reason = 'protected-only: zero writes'
            } else if (overall == ApplyItemStatus.SKIPPED_PREVIEW) {
                reason = 'preview mode: zero writes'
            } else if (overall == ApplyItemStatus.SKIPPED_IDEMPOTENT) {
                reason = 'skipped_idempotent'
            }
            return new ApplyDecisionResult(Status.NOOP, receipt, reason, decision)
        }

        static ApplyDecisionResult applied(ApplicationReceipt receipt, DecisionRecord decision) {
            fromReceipt(receipt, decision)
        }

        static ApplyDecisionResult noop(DecisionRecord decision, String reason) {
            new ApplyDecisionResult(Status.NOOP, null, reason, decision)
        }

        static ApplyDecisionResult noop(ApplicationReceipt receipt, DecisionRecord decision, String reason) {
            new ApplyDecisionResult(Status.NOOP, receipt, reason, decision)
        }

        static ApplyDecisionResult replayed(DecisionRecord decision, String reason) {
            new ApplyDecisionResult(Status.REPLAYED, null, reason, decision)
        }

        static ApplyDecisionResult rejected(DecisionRecord decision, String reason) {
            new ApplyDecisionResult(Status.REJECTED, null, reason, decision)
        }

        static ApplyDecisionResult rejected(ApplicationReceipt receipt, DecisionRecord decision, String reason) {
            new ApplyDecisionResult(Status.REJECTED, receipt, reason, decision)
        }

        static ApplyDecisionResult error(ApplicationReceipt receipt, DecisionRecord decision, String reason) {
            new ApplyDecisionResult(Status.ERROR, receipt, reason, decision)
        }

        static ApplyDecisionResult partial(ApplicationReceipt receipt, DecisionRecord decision) {
            new ApplyDecisionResult(Status.PARTIAL, receipt, 'partial', decision)
        }

        boolean isApplied() { status == Status.APPLIED }
        boolean isPartial() { status == Status.PARTIAL }
        boolean isNoop() { status == Status.NOOP }
        boolean isReplayed() { status == Status.REPLAYED }
        boolean isRejected() { status == Status.REJECTED }
        boolean isError() { status == Status.ERROR }
    }

    static class LedgerPersistException extends RuntimeException {
        final DeliveryReceipt providerReceipt

        LedgerPersistException(String message, DeliveryReceipt providerReceipt, Throwable cause) {
            super(message, cause)
            this.providerReceipt = providerReceipt
        }
    }
}
