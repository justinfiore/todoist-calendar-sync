package todoistcaldavsync.planner.apply

import todoistcaldavsync.planner.adapters.CalendarReadGateway
import todoistcaldavsync.planner.adapters.CalendarWriteGateway
import todoistcaldavsync.planner.adapters.TodoistReadGateway
import todoistcaldavsync.planner.adapters.TodoistWriteGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.ApplicationReceipt
import todoistcaldavsync.planner.domain.AppliedMapping
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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

/**
 * Applies an approved plan to the managed calendar and Todoist due times.
 * Never mutates deadlines. Never writes in preview. Never mutates external events.
 *
 * Order per item: create/move managed event → cleanup superseded UID → update Todoist due → persist mapping.
 * Partial calendar-success / Todoist-failure is recorded and recoverable on rerun.
 * Idempotent skip requires live read confirmation of owned managed event matching proposed times.
 */
class PlanApplier {
    static final String MODE_PREVIEW = 'preview'
    static final String MODE_APPROVAL_REQUIRED = 'approval_required'
    static final String MODE_APPLY_SAFE = 'apply_safe_changes'
    static final String MODE_FULLY_AUTOMATED = 'fully_automated'

    private static final AtomicLong RECEIPT_SEQ = new AtomicLong()

    private final PlannerConfig config
    private final CalendarWriteGateway calendarWrite
    private final CalendarReadGateway calendarRead
    private final TodoistWriteGateway todoistWrite
    private final TodoistReadGateway todoistRead
    private final ApplicationStateStore stateStore
    private final Supplier<Instant> clock
    private final ZoneId zoneId

    PlanApplier(PlannerConfig config,
                CalendarWriteGateway calendarWrite,
                CalendarReadGateway calendarRead,
                TodoistWriteGateway todoistWrite,
                TodoistReadGateway todoistRead,
                ApplicationStateStore stateStore,
                Supplier<Instant> clock = { Instant.now() }) {
        if (config == null) {
            throw new IllegalArgumentException('config is required')
        }
        if (calendarWrite == null) {
            throw new IllegalArgumentException('calendarWrite is required')
        }
        if (calendarRead == null) {
            throw new IllegalArgumentException('calendarRead is required')
        }
        if (todoistWrite == null) {
            throw new IllegalArgumentException('todoistWrite is required')
        }
        if (todoistRead == null) {
            throw new IllegalArgumentException('todoistRead is required')
        }
        if (stateStore == null) {
            throw new IllegalArgumentException('stateStore is required')
        }
        String outCal = config.outputCalendar
        if (outCal == null || outCal.trim().isEmpty()) {
            throw new IllegalArgumentException('planner.output_calendar is required and must be non-blank')
        }
        this.config = config
        this.calendarWrite = calendarWrite
        this.calendarRead = calendarRead
        this.todoistWrite = todoistWrite
        this.todoistRead = todoistRead
        this.stateStore = stateStore
        this.clock = clock ?: ({ Instant.now() } as Supplier<Instant>)
        this.zoneId = config.timezone ?: ZoneId.of('UTC')
    }

    /**
     * Apply plan under stored plan.mode / config mode gates and optional approval.
     * Phase 3 modes unchanged: preview, approval_required, apply_safe_changes (when stored),
     * fully_automated refused. Does not mutate the plan.
     */
    ApplicationReceipt apply(Plan plan, Approval approval = null) {
        if (plan == null) {
            throw new IllegalArgumentException('plan is required')
        }
        String mode = plan.mode ?: config.mode ?: MODE_PREVIEW
        // Phase 3: preview on plan or config blocks ordinary apply
        boolean forcePreview = (mode == MODE_PREVIEW || config.mode == MODE_PREVIEW)
        return applyWithEffectiveMode(plan, approval, forcePreview ? MODE_PREVIEW : mode)
    }

    /**
     * Explicit user APPLY_SAFE entry: execute with effective gate mode {@link #MODE_APPLY_SAFE}
     * without mutating/rebuilding the stored Plan or weakening semantic hash/approval behavior.
     * Requires no approval; ordinary safe blocks may apply while frozen, manualOverride, and
     * approvalRequired blocks are always skipped. Never escalates to fully_automated.
     * Works when stored Plan.mode is approval_required, preview, or other non-fully-automated
     * proposal mode — effective mode is apply_safe_changes for gates and receipt.mode only.
     *
     * <p>Outer safety gate: if stored {@code plan.mode} OR {@code config.mode} is
     * {@link #MODE_FULLY_AUTOMATED}, zero-write refuse immediately. Does not use the effective
     * mode override to bypass this gate.
     */
    ApplicationReceipt applySafeChanges(Plan plan) {
        if (plan == null) {
            throw new IllegalArgumentException('plan is required')
        }
        Instant started = clock.get()
        String planHash = PlanHash.compute(plan)
        String receiptId = buildReceiptId(plan.id, plan.version, started, planHash)
        String planMode = plan.mode
        String configMode = config.mode
        if (planMode == MODE_FULLY_AUTOMATED || configMode == MODE_FULLY_AUTOMATED) {
            String reason =
                "applySafeChanges refuses fully_automated (plan.mode=${planMode}, config.mode=${configMode})"
            ApplicationReceipt receipt = ApplicationReceipt.builder()
                .id(receiptId)
                .planId(plan.id)
                .planVersion(plan.version)
                .planHash(planHash)
                .mode(MODE_APPLY_SAFE)
                .startedAt(started)
                .finishedAt(clock.get())
                .overallStatus(ApplyItemStatus.SKIPPED_UNAPPROVED)
                .items([])
                .errors([reason])
                .metadata([
                    writeCount     : 0,
                    refused        : true,
                    refusedReason  : 'fully_automated',
                    planMode       : planMode,
                    configMode     : configMode,
                    gate           : 'applySafeChanges_fully_automated',
                    effectiveMode  : MODE_APPLY_SAFE
                ])
                .build()
            stateStore.saveReceipt(receipt)
            return receipt
        }
        return applyWithEffectiveMode(plan, null, MODE_APPLY_SAFE)
    }

    /**
     * Re-run apply for reconciliation (same gates as {@link #apply}). Completes partial Todoist
     * sides without duplicate calendar writes when live owned event already matches proposed block.
     */
    ApplicationReceipt reconcile(Plan plan, Approval approval = null) {
        return apply(plan, approval)
    }

    /**
     * Internal apply with caller-chosen effective mode (gates + receipt.mode). Never mutates plan.
     */
    private ApplicationReceipt applyWithEffectiveMode(Plan plan, Approval approval, String effectiveMode) {
        Instant started = clock.get()
        String planHash = PlanHash.compute(plan)
        String receiptId = buildReceiptId(plan.id, plan.version, started, planHash)
        List<AppliedMapping> items = []
        List<Map<String, Object>> drifts = []
        List<String> errors = []
        String mode = effectiveMode ?: MODE_PREVIEW

        // --- Gate: preview never writes ---
        if (mode == MODE_PREVIEW) {
            ApplicationReceipt receipt = ApplicationReceipt.builder()
                .id(receiptId)
                .planId(plan.id)
                .planVersion(plan.version)
                .planHash(planHash)
                .mode(MODE_PREVIEW)
                .approvalId(approval?.id)
                .startedAt(started)
                .finishedAt(clock.get())
                .overallStatus(ApplyItemStatus.SKIPPED_PREVIEW)
                .items([])
                .errors(['preview mode: zero writes'])
                .metadata([writeCount: 0])
                .build()
            stateStore.saveReceipt(receipt)
            return receipt
        }

        // --- Gate: approval binding ---
        ApprovalGateResult gate = evaluateApproval(plan, planHash, approval, mode)
        if (!gate.allowed) {
            ApplicationReceipt receipt = ApplicationReceipt.builder()
                .id(receiptId)
                .planId(plan.id)
                .planVersion(plan.version)
                .planHash(planHash)
                .mode(mode)
                .approvalId(approval?.id)
                .startedAt(started)
                .finishedAt(clock.get())
                .overallStatus(gate.status)
                .items([])
                .errors([gate.reason])
                .metadata([
                    writeCount           : 0,
                    gate                 : gate.reason,
                    approvalValid        : false,
                    explicitApproval     : false,
                    approvalInvalidReason: gate.invalidApprovalReason ?: gate.reason
                ])
                .build()
            stateStore.saveReceipt(receipt)
            return receipt
        }

        // Invalid/stale/tampered approval under apply_safe_changes: never escalate protected
        // items, but still surface the failure in structured audit while safe items may apply.
        if (gate.invalidApprovalReason) {
            errors << gate.invalidApprovalReason
        }

        // In-memory snapshot drives one run; persistence uses per-key putMapping merge only.
        // Never full-replace at end (would clobber concurrent RMW from another process).
        Map<String, AppliedMapping> existingMappings = new LinkedHashMap<>(stateStore.loadMappings())
        // Track UIDs already cleaned up this run (focus blocks share one old UID across members)
        Set<String> cleanedPriorUids = new HashSet<>()

        // Apply one managed event per scheduled block; Todoist due per member task.
        List<ScheduledBlock> blocks = plan.scheduledBlocks ?: []
        if (blocks.isEmpty() && errors.isEmpty()) {
            ApplicationReceipt emptyReceipt = ApplicationReceipt.builder()
                .id(receiptId)
                .planId(plan.id)
                .planVersion(plan.version)
                .planHash(planHash)
                .mode(mode)
                .approvalId(approval?.id)
                .startedAt(started)
                .finishedAt(clock.get())
                .overallStatus(ApplyItemStatus.SKIPPED_NO_CHANGES)
                .items([])
                .errors(['empty plan: no scheduled blocks'])
                .metadata([writeCount: 0, noChanges: true])
                .build()
            stateStore.saveReceipt(emptyReceipt)
            return emptyReceipt
        }
        for (ScheduledBlock block : blocks) {
            try {
                applyBlock(plan, planHash, approval, block, existingMappings, items, drifts, errors, gate, cleanedPriorUids)
            } catch (Exception e) {
                errors << "block ${block.id}: ${e.message}"
            }
        }

        ApplyItemStatus overall = deriveOverall(items, errors, drifts)
        Map<String, Object> meta = [
            writeCount      : items.count {
                it.calendarStatus == ApplyItemStatus.APPLIED || it.todoistStatus == ApplyItemStatus.APPLIED
            },
            blockCount      : blocks.size(),
            approvalBound  : gate.explicitApproval,
            explicitApproval: gate.explicitApproval,
            approvalValid   : gate.invalidApprovalReason == null && (approval == null || gate.explicitApproval || mode == MODE_APPLY_SAFE),
            effectiveMode   : mode
        ]
        if (gate.invalidApprovalReason) {
            meta.approvalValid = false
            meta.approvalInvalidReason = gate.invalidApprovalReason
            meta.protectedWithheld = items.any {
                it.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED ||
                    it.calendarStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
            }
            meta.safeChangesApplied = items.any {
                it.calendarStatus == ApplyItemStatus.APPLIED || it.todoistStatus == ApplyItemStatus.APPLIED
            }
        } else if (mode == MODE_APPLY_SAFE && approval == null) {
            meta.approvalValid = true // no approval presented; safe-only path by design
        }
        ApplicationReceipt receipt = ApplicationReceipt.builder()
            .id(receiptId)
            .planId(plan.id)
            .planVersion(plan.version)
            .planHash(planHash)
            .mode(mode)
            .approvalId(approval?.id)
            .startedAt(started)
            .finishedAt(clock.get())
            .overallStatus(overall)
            .items(items)
            .drifts(drifts)
            .errors(errors)
            .metadata(meta)
            .build()
        stateStore.saveReceipt(receipt)
        return receipt
    }

    private void applyBlock(Plan plan, String planHash, Approval approval, ScheduledBlock block,
                            Map<String, AppliedMapping> existingMappings,
                            List<AppliedMapping> items,
                            List<Map<String, Object>> drifts,
                            List<String> errors,
                            ApprovalGateResult gate,
                            Set<String> cleanedPriorUids) {
        String eventUid = ManagedEventIds.uidForBlock(block.id)
        Instant now = clock.get()

        // Protect frozen / manualOverride blocks without explicit per-item approval escalation
        if (block.frozen || block.manualOverride) {
            if (!gate.explicitApproval) {
                block.taskIds.each { String taskId ->
                    MemberInterval mi = block.intervalFor(taskId)
                    AppliedMapping skipped = AppliedMapping.builder()
                        .taskId(taskId)
                        .blockId(block.id)
                        .eventUid(eventUid)
                        .slotStart(mi?.start ?: block.start)
                        .slotEnd(mi?.end ?: block.end)
                        .planId(plan.id)
                        .planVersion(plan.version)
                        .planHash(planHash)
                        .approvalId(approval?.id)
                        .approvalTime(approval?.approvedAt)
                        .appliedAt(now)
                        .calendarStatus(ApplyItemStatus.SKIPPED_PROTECTED)
                        .todoistStatus(ApplyItemStatus.SKIPPED_PROTECTED)
                        .metadata([frozen: block.frozen, manualOverride: block.manualOverride])
                        .build()
                    items << skipped
                }
                return
            }
        }

        // Changes tagged approvalRequired need explicit approval even in apply_safe_changes
        boolean changeNeedsApproval = blockRequiresApproval(plan, block)
        if (changeNeedsApproval && !gate.explicitApproval) {
            block.taskIds.each { String taskId ->
                MemberInterval mi = block.intervalFor(taskId)
                items << AppliedMapping.builder()
                    .taskId(taskId)
                    .blockId(block.id)
                    .eventUid(eventUid)
                    .slotStart(mi?.start ?: block.start)
                    .slotEnd(mi?.end ?: block.end)
                    .planId(plan.id)
                    .planVersion(plan.version)
                    .planHash(planHash)
                    .approvalId(approval?.id)
                    .approvalTime(approval?.approvedAt)
                    .appliedAt(now)
                    .calendarStatus(ApplyItemStatus.SKIPPED_UNAPPROVED)
                    .todoistStatus(ApplyItemStatus.SKIPPED_UNAPPROVED)
                    .metadata([approvalRequired: true])
                    .build()
            }
            return
        }

        // Drift / manual override detection against last applied mapping + live state
        DriftCheck drift = detectBlockDrift(block, eventUid, existingMappings)
        if (drift.blocked) {
            drifts.addAll(drift.entries)
            block.taskIds.each { String taskId ->
                MemberInterval mi = block.intervalFor(taskId)
                AppliedMapping prior = existingMappings[taskId]
                items << AppliedMapping.builder()
                    .taskId(taskId)
                    .blockId(block.id)
                    .eventUid(prior?.eventUid ?: eventUid)
                    .slotStart(mi?.start ?: block.start)
                    .slotEnd(mi?.end ?: block.end)
                    .planId(plan.id)
                    .planVersion(plan.version)
                    .planHash(planHash)
                    .approvalId(approval?.id)
                    .approvalTime(approval?.approvedAt)
                    .appliedAt(now)
                    .calendarStatus(ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE)
                    .todoistStatus(ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE)
                    .metadata([drift: true, reasons: drift.entries.findAll { it.taskId == taskId || it.blockId == block.id }])
                    .build()
            }
            return
        }

        // External UID collision: refuse adoption/overwrite when deterministic UID is present
        // without valid ownership (missing marker, wrong calendar, or mismatched metadata).
        CalendarEvent existingAtUid = findEventByUid(eventUid)
        if (existingAtUid != null && !ManagedEventIds.isOwned(existingAtUid, config.outputCalendar)) {
            String collisionReason = externalUidCollisionReason(existingAtUid, eventUid, block)
            errors << collisionReason
            block.taskIds.each { String taskId ->
                MemberInterval mi = block.intervalFor(taskId)
                items << AppliedMapping.builder()
                    .taskId(taskId)
                    .blockId(block.id)
                    .eventUid(eventUid)
                    .slotStart(mi?.start ?: block.start)
                    .slotEnd(mi?.end ?: block.end)
                    .planId(plan.id)
                    .planVersion(plan.version)
                    .planHash(planHash)
                    .approvalId(approval?.id)
                    .approvalTime(approval?.approvedAt)
                    .appliedAt(now)
                    .calendarStatus(ApplyItemStatus.ERROR_EXTERNAL_UID)
                    .todoistStatus(ApplyItemStatus.PENDING)
                    .calendarError(collisionReason)
                    .metadata([
                        externalUidCollision: true,
                        existingCalendar    : existingAtUid.calendarName,
                        existingTitle       : existingAtUid.title,
                        hasOwnershipMarker  : ManagedEventIds.hasOwnershipMarker(existingAtUid.description),
                        isPlannerUid        : ManagedEventIds.isPlannerUid(existingAtUid.uid)
                    ])
                    .build()
            }
            return
        }

        // Live calendar confirmation: owned managed event whose UID/marker/calendar AND start/end
        // exactly match the proposed ScheduledBlock. Prior mapping alone cannot prove current state.
        LiveCalendarMatch liveMatch = assessLiveCalendarMatch(block, eventUid)

        // Full idempotency requires live calendar match AND live Todoist due == each proposed slotStart.
        // prior.todoistApplied + mapping slot alone is insufficient (cleared/changed/malformed due).
        Map<String, Map> liveTasksForIdempotency = loadLiveTasksById()
        Map<String, LiveTodoistDue> liveDueByTask = [:]
        boolean allIdempotent = liveMatch.exactMatch && block.taskIds.every { String taskId ->
            AppliedMapping prior = existingMappings[taskId]
            MemberInterval mi = block.intervalFor(taskId)
            Instant proposedStart = mi?.start ?: block.start
            Instant proposedEnd = mi?.end ?: block.end
            if (prior == null ||
                !prior.fullyApplied() ||
                prior.eventUid != eventUid ||
                prior.slotStart != proposedStart ||
                prior.slotEnd != proposedEnd ||
                prior.planId != plan.id ||
                prior.planHash != planHash) {
                return false
            }
            LiveTodoistDue liveDue = assessLiveTodoistDue(taskId, proposedStart, liveTasksForIdempotency)
            liveDueByTask[taskId] = liveDue
            return liveDue.exactMatch
        }
        if (allIdempotent) {
            block.taskIds.each { String taskId ->
                AppliedMapping prior = existingMappings[taskId]
                AppliedMapping skipped = prior.withStatuses(
                    ApplyItemStatus.SKIPPED_IDEMPOTENT, ApplyItemStatus.SKIPPED_IDEMPOTENT, null, null, now)
                items << skipped
            }
            return
        }

        // --- Calendar first ---
        ApplyItemStatus calStatus
        String calError = null
        boolean calendarOk = false
        boolean calendarAlreadyOk = liveMatch.exactMatch
        String priorUidToCleanup = null
        String priorBlockIdForCleanup = null

        // Collect prior UID for superseded cleanup (when proposed UID differs from mapping)
        AppliedMapping anyPrior = block.taskIds.collect { existingMappings[it] }.find { it != null }
        if (anyPrior != null && anyPrior.eventUid && anyPrior.eventUid != eventUid) {
            priorUidToCleanup = anyPrior.eventUid
            priorBlockIdForCleanup = anyPrior.blockId ?: block.id
        }
        // Also track priorUid stored in metadata from earlier partial cleanup
        if (priorUidToCleanup == null && anyPrior?.metadata?.priorEventUid) {
            priorUidToCleanup = anyPrior.metadata.priorEventUid.toString()
            priorBlockIdForCleanup = (anyPrior.metadata.priorBlockId ?: anyPrior.blockId ?: block.id).toString()
        }

        ApplyItemStatus oldDeleteStatus = null
        String oldDeleteError = null

        if (calendarAlreadyOk) {
            calStatus = ApplyItemStatus.SKIPPED_IDEMPOTENT
            calendarOk = true
        } else {
            // Missing/deleted or owned-but-stale managed event may be (re)created when mapping claims
            // applied. Marker-stripped / unowned same-UID events never reach here (external collision).
            try {
                CalendarEvent managed = buildManagedEvent(plan, block, eventUid)
                calendarWrite.upsertEvent(managed)
                calStatus = ApplyItemStatus.APPLIED
                calendarOk = true
            } catch (Exception e) {
                calStatus = ApplyItemStatus.FAILED
                calError = e.message
                errors << "calendar block ${block.id}: ${e.message}"
            }
        }

        // Superseded managed UID cleanup: after successful new upsert, delete old owned event
        if (calendarOk && priorUidToCleanup && priorUidToCleanup != eventUid) {
            if (cleanedPriorUids.contains(priorUidToCleanup)) {
                oldDeleteStatus = ApplyItemStatus.SKIPPED_IDEMPOTENT
            } else {
                CleanupResult cleanup = cleanupSupersededUid(priorUidToCleanup, priorBlockIdForCleanup, block)
                oldDeleteStatus = cleanup.status
                oldDeleteError = cleanup.error
                if (cleanup.status == ApplyItemStatus.APPLIED || cleanup.status == ApplyItemStatus.SKIPPED_IDEMPOTENT) {
                    cleanedPriorUids << priorUidToCleanup
                } else if (cleanup.status == ApplyItemStatus.FAILED) {
                    errors << "old_uid_cleanup ${priorUidToCleanup}: ${cleanup.error}"
                    // Do not claim full calendar success if required cleanup failed
                    // Keep calendarOk true for new event (exists) but surface partial via oldDelete
                } else if (cleanup.status == ApplyItemStatus.ERROR_EXTERNAL_UID) {
                    errors << "old_uid_cleanup ${priorUidToCleanup}: ${cleanup.error}"
                }
            }
        }

        // --- Todoist per member (only if calendar ok) ---
        for (String taskId : block.taskIds) {
            MemberInterval mi = block.intervalFor(taskId)
            Instant slotStart = mi?.start ?: block.start
            Instant slotEnd = mi?.end ?: block.end
            AppliedMapping prior = existingMappings[taskId]

            ApplyItemStatus tdStatus
            String tdError = null

            Map meta = [
                focusBlock: block.focusBlock,
                blockStart: block.start.toString(),
                blockEnd  : block.end.toString()
            ]
            if (priorUidToCleanup && priorUidToCleanup != eventUid) {
                meta.priorEventUid = priorUidToCleanup
                meta.priorBlockId = priorBlockIdForCleanup
                if (oldDeleteStatus != null) {
                    meta.oldUidDeleteStatus = oldDeleteStatus.wire
                }
                if (oldDeleteError) {
                    meta.oldUidDeleteError = oldDeleteError
                }
            }
            if (liveMatch.recreateReason) {
                meta.calendarRecreateReason = liveMatch.recreateReason
            }

            if (!calendarOk) {
                tdStatus = ApplyItemStatus.PENDING
                AppliedMapping failed = AppliedMapping.builder()
                    .taskId(taskId)
                    .blockId(block.id)
                    .eventUid(eventUid)
                    .slotStart(slotStart)
                    .slotEnd(slotEnd)
                    .planId(plan.id)
                    .planVersion(plan.version)
                    .planHash(planHash)
                    .approvalId(approval?.id)
                    .approvalTime(approval?.approvedAt)
                    .appliedAt(now)
                    .calendarStatus(calStatus)
                    .todoistStatus(tdStatus)
                    .calendarError(calError)
                    .metadata(meta)
                    .build()
                items << failed
                existingMappings[taskId] = failed
                stateStore.putMapping(failed)
                continue
            }

            // If old UID cleanup failed, do not claim full calendar success on mapping
            ApplyItemStatus effectiveCal
            if (oldDeleteStatus == ApplyItemStatus.FAILED || oldDeleteStatus == ApplyItemStatus.ERROR_EXTERNAL_UID) {
                effectiveCal = ApplyItemStatus.PARTIAL
                meta.calendarNewUpsertOk = true
                meta.oldUidCleanupFailed = true
            } else if (calendarAlreadyOk) {
                effectiveCal = ApplyItemStatus.SKIPPED_IDEMPOTENT
            } else {
                effectiveCal = calStatus
            }

            // Live Todoist due must equal proposed slotStart for idempotent skip.
            // Mapping-only claims are insufficient; missing/cleared/changed/malformed handled below.
            LiveTodoistDue liveDue = liveDueByTask[taskId]
            if (liveDue == null) {
                liveDue = assessLiveTodoistDue(taskId, slotStart, loadLiveTasksById())
            }
            if (liveDue.exactMatch &&
                prior != null && prior.todoistApplied() &&
                prior.slotStart == slotStart && prior.eventUid == eventUid &&
                prior.planHash == planHash &&
                oldDeleteStatus != ApplyItemStatus.FAILED) {
                tdStatus = ApplyItemStatus.SKIPPED_IDEMPOTENT
                AppliedMapping done = AppliedMapping.builder()
                    .taskId(taskId)
                    .blockId(block.id)
                    .eventUid(eventUid)
                    .slotStart(slotStart)
                    .slotEnd(slotEnd)
                    .planId(plan.id)
                    .planVersion(plan.version)
                    .planHash(planHash)
                    .approvalId(approval?.id)
                    .approvalTime(approval?.approvedAt)
                    .appliedAt(now)
                    .calendarStatus(effectiveCal)
                    .todoistStatus(tdStatus)
                    .metadata(meta)
                    .build()
                items << done
                existingMappings[taskId] = done
                // No putMapping — untouched mapping identity; avoid unnecessary write races
                continue
            }
            if (liveDue.missingTask) {
                tdStatus = ApplyItemStatus.FAILED
                tdError = "todoist task missing: ${taskId}"
                errors << tdError
                meta.todoistLive = 'missing_task'
                AppliedMapping failed = AppliedMapping.builder()
                    .taskId(taskId)
                    .blockId(block.id)
                    .eventUid(eventUid)
                    .slotStart(slotStart)
                    .slotEnd(slotEnd)
                    .planId(plan.id)
                    .planVersion(plan.version)
                    .planHash(planHash)
                    .approvalId(approval?.id)
                    .approvalTime(approval?.approvedAt)
                    .appliedAt(now)
                    .calendarStatus(effectiveCal)
                    .todoistStatus(tdStatus)
                    .calendarError(calError)
                    .todoistError(tdError)
                    .metadata(meta)
                    .build()
                items << failed
                existingMappings[taskId] = failed
                stateStore.putMapping(failed)
                continue
            }
            if (liveDue.malformedDue) {
                tdStatus = ApplyItemStatus.FAILED
                tdError = "todoist task ${taskId}: malformed due"
                errors << tdError
                meta.todoistLive = 'malformed_due'
                AppliedMapping failed = AppliedMapping.builder()
                    .taskId(taskId)
                    .blockId(block.id)
                    .eventUid(eventUid)
                    .slotStart(slotStart)
                    .slotEnd(slotEnd)
                    .planId(plan.id)
                    .planVersion(plan.version)
                    .planHash(planHash)
                    .approvalId(approval?.id)
                    .approvalTime(approval?.approvedAt)
                    .appliedAt(now)
                    .calendarStatus(effectiveCal)
                    .todoistStatus(tdStatus)
                    .calendarError(calError)
                    .todoistError(tdError)
                    .metadata(meta)
                    .build()
                items << failed
                existingMappings[taskId] = failed
                stateStore.putMapping(failed)
                continue
            }

            try {
                String dueIso = formatDueIso(slotStart)
                // CRITICAL: only update due — never deadline
                todoistWrite.updateTaskDue(taskId, dueIso)
                tdStatus = ApplyItemStatus.APPLIED
            } catch (Exception e) {
                tdStatus = ApplyItemStatus.FAILED
                tdError = e.message
                errors << "todoist task ${taskId}: ${e.message}"
            }

            AppliedMapping mapping = AppliedMapping.builder()
                .taskId(taskId)
                .blockId(block.id)
                .eventUid(eventUid)
                .slotStart(slotStart)
                .slotEnd(slotEnd)
                .planId(plan.id)
                .planVersion(plan.version)
                .planHash(planHash)
                .approvalId(approval?.id)
                .approvalTime(approval?.approvedAt)
                .appliedAt(now)
                .calendarStatus(effectiveCal)
                .todoistStatus(tdStatus)
                .calendarError(calError)
                .todoistError(tdError)
                .metadata(meta)
                .build()

            items << mapping
            existingMappings[taskId] = mapping
            // Persist each touched mapping via atomic locked per-key merge (no full replace)
            stateStore.putMapping(mapping)
        }
    }

    /**
     * Live Todoist due vs proposed slotStart. Offset-less civil times use planner ZoneId.
     * Missing task / malformed due are structured failures (not false-skip).
     * Cleared due (null) is not exact — caller should update unless drift policy blocked earlier.
     */
    private LiveTodoistDue assessLiveTodoistDue(String taskId, Instant proposedStart,
                                                Map<String, Map> liveTasks) {
        Map live = liveTasks != null ? liveTasks[taskId] : null
        if (live == null) {
            return LiveTodoistDue.missingTask()
        }
        def dueRaw = live.due
        String dueString = null
        if (dueRaw instanceof Map) {
            dueString = (dueRaw.datetime ?: dueRaw.date ?: dueRaw.string)?.toString()
        } else if (live.due_date != null) {
            dueString = live.due_date.toString()
        } else if (dueRaw != null) {
            dueString = dueRaw.toString()
        }
        if (dueString == null || dueString.trim().isEmpty()) {
            return LiveTodoistDue.cleared()
        }
        Instant liveDue = parseDueString(dueString, zoneId)
        if (liveDue == null) {
            // Present but unparseable (and not date-only silence) → malformed
            if (dueString.length() == 10) {
                // date-only: treat as not exact match (needs datetime update), not malformed
                return LiveTodoistDue.mismatch(null)
            }
            return LiveTodoistDue.malformed()
        }
        if (liveDue == proposedStart) {
            return LiveTodoistDue.exact()
        }
        return LiveTodoistDue.mismatch(liveDue)
    }

    /**
     * Live read: owned managed event at UID with exact start/end match to proposed block.
     * Mapping alone is never sufficient for calendarAlreadyOk.
     * <p>
     * Callers must already refuse unowned/marker-stripped same-UID events as external
     * collisions — this method never implies recreation for marker-stripped events.
     * Only a genuinely missing UID (or owned event with non-matching times after drift
     * checks) may proceed to upsert/recreate.
     */
    private LiveCalendarMatch assessLiveCalendarMatch(ScheduledBlock block, String eventUid) {
        CalendarEvent live = findEventByUid(eventUid)
        if (live == null) {
            return new LiveCalendarMatch(false, 'missing_live_event')
        }
        // Unowned / marker-stripped / wrong-calendar must be handled as external UID collision
        // before this method; never treat as a recreate candidate.
        if (!ManagedEventIds.isOwned(live, config.outputCalendar)) {
            return new LiveCalendarMatch(false, 'not_owned_external')
        }
        if (live.start != block.start || live.end != block.end) {
            return new LiveCalendarMatch(false, 'stale_times')
        }
        // Optional: block-id metadata consistency
        String liveBlock = ManagedEventIds.extractBlockId(live.description)
        if (liveBlock != null && liveBlock != block.id) {
            return new LiveCalendarMatch(false, 'block_id_mismatch')
        }
        return new LiveCalendarMatch(true, null)
    }

    private CleanupResult cleanupSupersededUid(String priorUid, String priorBlockId, ScheduledBlock newBlock) {
        CalendarEvent old = findEventByUid(priorUid)
        if (old == null) {
            // Already gone — idempotent success
            return new CleanupResult(ApplyItemStatus.SKIPPED_IDEMPOTENT, null)
        }
        if (!ManagedEventIds.isOwned(old, config.outputCalendar)) {
            // Externalized/tampered — never delete; surface error
            return new CleanupResult(ApplyItemStatus.ERROR_EXTERNAL_UID,
                "refusing_old_uid_delete unowned_or_external uid=${priorUid}")
        }
        String expectedBlock = priorBlockId ?: ManagedEventIds.extractBlockId(old.description) ?: newBlock.id
        if (!ManagedEventIds.descriptionHasBlockId(old.description, expectedBlock) &&
            ManagedEventIds.extractBlockId(old.description) != null) {
            // Block metadata does not match expected prior mapping — refuse
            return new CleanupResult(ApplyItemStatus.ERROR_EXTERNAL_UID,
                "refusing_old_uid_delete block_mismatch uid=${priorUid} expected=${expectedBlock}")
        }
        try {
            calendarWrite.deleteOwnedEvent(priorUid, expectedBlock)
            return new CleanupResult(ApplyItemStatus.APPLIED, null)
        } catch (Exception e) {
            return new CleanupResult(ApplyItemStatus.FAILED, e.message)
        }
    }

    private CalendarEvent buildManagedEvent(Plan plan, ScheduledBlock block, String eventUid) {
        String calName = config.outputCalendar
        if (!calName) {
            throw new IllegalStateException('planner.output_calendar is required for apply')
        }
        String description = ManagedEventIds.buildDescription(block.id, plan.id, block.reason)
        return CalendarEvent.builder()
            .id(eventUid)
            .uid(eventUid)
            .title(block.title)
            .description(description)
            .calendarName(calName)
            .start(block.start)
            .end(block.end)
            .allDay(false)
            .role(EventRole.MANAGED_OUTPUT)
            .classificationReason('planner_managed_output')
            .build()
    }

    private CalendarEvent findEventByUid(String uid) {
        if (!uid) {
            return null
        }
        // Global across all accessible calendars (collision detection requires this).
        return calendarRead.findEventByUid(uid)
    }

    private String externalUidCollisionReason(CalendarEvent existing, String eventUid, ScheduledBlock block) {
        if (config.outputCalendar && existing.calendarName != config.outputCalendar) {
            return "external_uid_collision uid=${eventUid} wrong_calendar=${existing.calendarName}"
        }
        if (ManagedEventIds.isPlannerUid(existing.uid) && !ManagedEventIds.hasOwnershipMarker(existing.description)) {
            return "external_uid_collision uid=${eventUid} missing_ownership_marker"
        }
        if (!ManagedEventIds.isPlannerUid(existing.uid)) {
            return "external_uid_collision uid=${eventUid} non_planner_uid"
        }
        return "external_uid_collision uid=${eventUid} unowned"
    }

    private DriftCheck detectBlockDrift(ScheduledBlock block, String eventUid,
                                        Map<String, AppliedMapping> existingMappings) {
        List<Map<String, Object>> entries = []
        boolean blocked = false
        if (!config.stability?.keepManualMoves) {
            return new DriftCheck(false, entries)
        }

        // Calendar drift: managed event moved away from last applied block times
        AppliedMapping anyPrior = block.taskIds.collect { existingMappings[it] }.find {
            it != null && it.calendarApplied() && it.eventUid == eventUid
        }
        if (anyPrior != null) {
            CalendarEvent current = findEventByUid(eventUid)
            if (current != null && ManagedEventIds.isOwned(current, config.outputCalendar)) {
                Instant expectedStart = block.start
                Instant expectedEnd = block.end
                Instant lastBlockStart = anyPrior.metadata?.blockStart
                    ? Instant.parse(anyPrior.metadata.blockStart.toString())
                    : (block.taskIds.size() == 1 ? anyPrior.slotStart : null)
                Instant lastBlockEnd = anyPrior.metadata?.blockEnd
                    ? Instant.parse(anyPrior.metadata.blockEnd.toString())
                    : (block.taskIds.size() == 1 ? anyPrior.slotEnd : null)

                if (lastBlockStart != null && lastBlockEnd != null) {
                    boolean eventMatchesLast = current.start == lastBlockStart && current.end == lastBlockEnd
                    boolean eventMatchesProposed = current.start == expectedStart && current.end == expectedEnd
                    // Owned live times that match neither last-applied nor proposed are a manual
                    // calendar override — including same-plan reapply (proposal == last-applied).
                    // Do not use proposalSameAsLast to suppress real owned-event time drift.
                    if (!eventMatchesLast && !eventMatchesProposed) {
                        blocked = true
                        entries << [
                            type         : 'manual_calendar_move',
                            blockId      : block.id,
                            eventUid     : eventUid,
                            lastStart    : lastBlockStart.toString(),
                            currentStart : current.start.toString(),
                            proposedStart: expectedStart.toString()
                        ]
                    }
                } else if (current.start != expectedStart || current.end != expectedEnd) {
                    // Without durable last block span: treat owned live mismatch vs proposal as
                    // manual override whenever a prior calendar apply exists for this UID.
                    if (anyPrior.calendarApplied()) {
                        blocked = true
                        entries << [
                            type         : 'manual_calendar_move',
                            blockId      : block.id,
                            eventUid     : eventUid,
                            currentStart : current.start.toString(),
                            proposedStart: expectedStart.toString()
                        ]
                    }
                }
            }
        }

        // Todoist due drift per task
        Map<String, Map> liveTasks = loadLiveTasksById()
        for (String taskId : block.taskIds) {
            AppliedMapping prior = existingMappings[taskId]
            if (prior == null || !prior.todoistApplied()) {
                continue
            }
            MemberInterval mi = block.intervalFor(taskId)
            Instant proposedStart = mi?.start ?: block.start
            Map live = liveTasks[taskId]
            Instant liveDue = extractDueInstant(live)
            if (liveDue != null && liveDue != prior.slotStart && liveDue != proposedStart) {
                blocked = true
                entries << [
                    type         : 'manual_todoist_due_change',
                    taskId       : taskId,
                    lastApplied  : prior.slotStart.toString(),
                    currentDue   : liveDue.toString(),
                    proposedStart: proposedStart.toString()
                ]
            }
        }

        return new DriftCheck(blocked, entries)
    }

    private Map<String, Map> loadLiveTasksById() {
        Map<String, Map> out = [:]
        todoistRead.fetchTasks().each { Map t ->
            def id = t.id?.toString()
            if (id) {
                out[id] = t
            }
        }
        return out
    }

    /**
     * Parse Todoist due datetime. Offset-less civil datetimes use planner ZoneId (never append Z).
     * Explicit Z / offset preserved.
     */
    Instant extractDueInstant(Map raw) {
        if (raw == null) {
            return null
        }
        def due = raw.due
        String s = null
        if (due instanceof Map) {
            s = (due.datetime ?: due.date ?: due.string)?.toString()
        } else if (raw.due_date != null) {
            s = raw.due_date.toString()
        } else if (due != null) {
            s = due.toString()
        }
        if (!s) {
            return null
        }
        return parseDueString(s, zoneId)
    }

    /**
     * Parse a due datetime string with explicit ZoneId for offset-less values.
     */
    static Instant parseDueString(String s, ZoneId zone) {
        if (!s) {
            return null
        }
        ZoneId z = zone ?: ZoneId.of('UTC')
        try {
            if (s.length() == 10) {
                return null // date-only — not a precise override signal for datetime plans
            }
            // Explicit Z
            if (s.endsWith('Z') || s.endsWith('z')) {
                return Instant.parse(s.toUpperCase().endsWith('Z') ? s : (s.substring(0, s.length() - 1) + 'Z'))
            }
            // Explicit offset (+hh:mm or -hh:mm at end)
            if (s =~ /[Tt].*[+-]\d{2}:\d{2}$/ || s =~ /[Tt].*[+-]\d{4}$/) {
                return OffsetDateTime.parse(s).toInstant()
            }
            // Offset-less civil datetime — interpret in planner zone (never append Z)
            String normalized = s.contains('T') ? s : s.replace(' ', 'T')
            // Strip trailing zone name if present
            LocalDateTime ldt = LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            return ldt.atZone(z).toInstant()
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(s).toInstant()
            } catch (Exception ignored2) {
                try {
                    return ZonedDateTime.parse(s).toInstant()
                } catch (Exception ignored3) {
                    return null
                }
            }
        }
    }

    private boolean blockRequiresApproval(Plan plan, ScheduledBlock block) {
        if (block.metadata?.approvalRequired == true || block.metadata?.approvalRequired == 'true') {
            return true
        }
        // Any change for this block's tasks tagged approvalRequired
        for (PlanChange c : (plan.changes ?: [])) {
            if (c.taskId && block.taskIds.contains(c.taskId)) {
                if (c.metadata?.approvalRequired == true || c.metadata?.approvalRequired == 'true') {
                    return true
                }
            }
            if (c.metadata?.focusBlockId == block.id &&
                (c.metadata?.approvalRequired == true || c.metadata?.approvalRequired == 'true')) {
                return true
            }
        }
        return false
    }

    private ApprovalGateResult evaluateApproval(Plan plan, String planHash, Approval approval, String mode) {
        // fully_automated not implemented beyond Phase 3 — still require approval binding for safety
        // apply_safe_changes: allow without approval for non-protected items; protected checked per-block.
        // Any Approval used to authorize protected items must bind exact plan id/version/hash.
        if (mode == MODE_APPLY_SAFE) {
            if (approval == null) {
                return new ApprovalGateResult(true, false, ApplyItemStatus.APPLIED, 'apply_safe_changes', null)
            }
            String invalid = validateApprovalBinding(plan, planHash, approval)
            if (invalid != null) {
                // Safe unprotected items may still apply; never set explicitApproval on bad binding.
                return new ApprovalGateResult(true, false, ApplyItemStatus.APPLIED, 'apply_safe_changes', invalid)
            }
            return new ApprovalGateResult(true, true, ApplyItemStatus.APPLIED, 'apply_safe_changes_approved', null)
        }
        if (mode == MODE_FULLY_AUTOMATED) {
            // Phase 3: do not implement fully automated trust escalation
            return new ApprovalGateResult(false, false, ApplyItemStatus.SKIPPED_UNAPPROVED,
                'fully_automated not enabled in Phase 3; refusing writes', null)
        }
        // approval_required (default write mode)
        if (approval == null) {
            return new ApprovalGateResult(false, false, ApplyItemStatus.SKIPPED_UNAPPROVED,
                'missing approval record', 'missing approval record')
        }
        String invalid = validateApprovalBinding(plan, planHash, approval)
        if (invalid != null) {
            return new ApprovalGateResult(false, false, ApplyItemStatus.SKIPPED_UNAPPROVED, invalid, invalid)
        }
        return new ApprovalGateResult(true, true, ApplyItemStatus.APPLIED, 'approved', null)
    }

    /**
     * Validate approval binds this exact plan identity. Returns null if valid, else reason string.
     * Approval model has no expiry field; stale is expressed via version/hash mismatch.
     */
    private static String validateApprovalBinding(Plan plan, String planHash, Approval approval) {
        if (approval.planId != plan.id) {
            return "approval planId mismatch: ${approval.planId} != ${plan.id}"
        }
        if (approval.planVersion != plan.version) {
            return "stale approval planVersion: ${approval.planVersion} != ${plan.version}"
        }
        if (approval.planHash != planHash) {
            return 'approval planHash mismatch (stale or tampered proposal)'
        }
        return null
    }

    private static ApplyItemStatus deriveOverall(List<AppliedMapping> items, List<String> errors,
                                                 List<Map<String, Object>> drifts) {
        if (items.isEmpty() && errors) {
            return ApplyItemStatus.FAILED
        }
        if (items.isEmpty()) {
            return ApplyItemStatus.SKIPPED_NO_CHANGES
        }
        boolean anyFail = items.any {
            it.calendarStatus == ApplyItemStatus.FAILED ||
                it.todoistStatus == ApplyItemStatus.FAILED ||
                it.calendarStatus == ApplyItemStatus.ERROR_EXTERNAL_UID ||
                it.calendarStatus == ApplyItemStatus.PARTIAL
        }
        boolean anyPartial = items.any {
            (it.calendarApplied() && it.todoistStatus == ApplyItemStatus.FAILED) ||
                it.calendarStatus == ApplyItemStatus.PARTIAL
        }
        boolean allWithheld = items.every {
            it.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED ||
                it.calendarStatus == ApplyItemStatus.SKIPPED_UNAPPROVED ||
                it.calendarStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE ||
                it.calendarStatus == ApplyItemStatus.SKIPPED_PREVIEW
        }
        boolean anyWithheld = items.any {
            it.calendarStatus == ApplyItemStatus.SKIPPED_PROTECTED ||
                it.calendarStatus == ApplyItemStatus.SKIPPED_UNAPPROVED ||
                it.calendarStatus == ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE
        }
        boolean anyAppliedSide = items.any {
            it.fullyApplied() ||
                it.calendarStatus == ApplyItemStatus.APPLIED ||
                it.todoistStatus == ApplyItemStatus.APPLIED ||
                it.calendarStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
        }
        if (anyPartial) {
            return ApplyItemStatus.PARTIAL
        }
        if (anyFail) {
            // if some applied and some failed without partial pattern
            boolean anyApplied = items.any { it.fullyApplied() || it.calendarStatus == ApplyItemStatus.APPLIED }
            if (anyApplied) {
                return ApplyItemStatus.PARTIAL
            }
            return ApplyItemStatus.FAILED
        }
        // Mixed safe-applied + protected/unapproved withheld (e.g. invalid approval under apply_safe)
        if (anyWithheld && anyAppliedSide && !allWithheld) {
            return ApplyItemStatus.PARTIAL
        }
        if (allWithheld && items.every {
            !it.fullyApplied() && it.calendarStatus != ApplyItemStatus.SKIPPED_IDEMPOTENT
        }) {
            def first = items[0].calendarStatus
            return first
        }
        if (items.every {
            it.calendarStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT &&
                it.todoistStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
        }) {
            return ApplyItemStatus.SKIPPED_IDEMPOTENT
        }
        if (items.every { it.fullyApplied() ||
            (it.calendarStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT &&
                it.todoistStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT) ||
            (it.calendarApplied() && it.todoistApplied())
        }) {
            return ApplyItemStatus.APPLIED
        }
        if (drifts) {
            return ApplyItemStatus.SKIPPED_MANUAL_OVERRIDE
        }
        return ApplyItemStatus.APPLIED
    }

    /**
     * Format due datetime as ISO-8601 instant (Z). Exact DTSTART synchronization.
     */
    static String formatDueIso(Instant start) {
        if (start == null) {
            throw new IllegalArgumentException('start is required')
        }
        return DateTimeFormatter.ISO_INSTANT.format(start)
    }

    /**
     * Collision-free receipt id even with frozen clock / concurrent calls.
     * Preserves deterministic/auditable plan linkage via hash of plan identity + timestamp,
     * plus monotonic sequence component.
     */
    static String buildReceiptId(String planId, int version, Instant at, String planHash) {
        long seq = RECEIPT_SEQ.incrementAndGet()
        String base = "${planId}|${version}|${at}|${planHash}|${seq}"
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        String hex = md.digest(base.getBytes(StandardCharsets.UTF_8))
            .collect { String.format('%02x', it & 0xff) }.join()
        return "ar-${hex.substring(0, 16)}-${Long.toString(seq, 36)}"
    }

    private static final class ApprovalGateResult {
        final boolean allowed
        final boolean explicitApproval
        final ApplyItemStatus status
        final String reason
        /** Non-null when an approval was presented but failed plan id/version/hash binding. */
        final String invalidApprovalReason

        ApprovalGateResult(boolean allowed, boolean explicitApproval, ApplyItemStatus status,
                           String reason, String invalidApprovalReason = null) {
            this.allowed = allowed
            this.explicitApproval = explicitApproval
            this.status = status
            this.reason = reason
            this.invalidApprovalReason = invalidApprovalReason
        }
    }

    private static final class DriftCheck {
        final boolean blocked
        final List<Map<String, Object>> entries

        DriftCheck(boolean blocked, List<Map<String, Object>> entries) {
            this.blocked = blocked
            this.entries = entries ?: []
        }
    }

    private static final class LiveCalendarMatch {
        final boolean exactMatch
        final String recreateReason

        LiveCalendarMatch(boolean exactMatch, String recreateReason) {
            this.exactMatch = exactMatch
            this.recreateReason = recreateReason
        }
    }

    private static final class LiveTodoistDue {
        final boolean exactMatch
        final boolean missingTask
        final boolean malformedDue
        final boolean clearedDue
        final Instant liveDue

        private LiveTodoistDue(boolean exactMatch, boolean missingTask, boolean malformedDue,
                               boolean clearedDue, Instant liveDue) {
            this.exactMatch = exactMatch
            this.missingTask = missingTask
            this.malformedDue = malformedDue
            this.clearedDue = clearedDue
            this.liveDue = liveDue
        }

        static LiveTodoistDue exact() {
            new LiveTodoistDue(true, false, false, false, null)
        }

        static LiveTodoistDue missingTask() {
            new LiveTodoistDue(false, true, false, false, null)
        }

        static LiveTodoistDue malformed() {
            new LiveTodoistDue(false, false, true, false, null)
        }

        static LiveTodoistDue cleared() {
            new LiveTodoistDue(false, false, false, true, null)
        }

        static LiveTodoistDue mismatch(Instant liveDue) {
            new LiveTodoistDue(false, false, false, false, liveDue)
        }
    }

    private static final class CleanupResult {
        final ApplyItemStatus status
        final String error

        CleanupResult(ApplyItemStatus status, String error) {
            this.status = status
            this.error = error
        }
    }
}
