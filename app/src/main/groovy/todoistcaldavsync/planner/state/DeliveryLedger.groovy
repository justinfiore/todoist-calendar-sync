package todoistcaldavsync.planner.state

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.domain.DeliveryReceipt

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Durable delivery ledger. Atomic + locked like ApplicationStateStore.
 * Idempotency key → latest attempt + immutable first-DELIVERED barrier.
 * Never records DELIVERED without an explicit successful provider outcome passed in.
 *
 * <p>Pre-send lifecycle: PENDING/ATTEMPT before provider call; DELIVERED or FAILED after.
 * UNKNOWN/NEEDS_RECONCILIATION after provider success when final ledger write fails —
 * retry must not blindly resend.
 *
 * <p><b>DELIVERED is a terminal barrier.</b> Once any DELIVERED exists for a key in
 * {@code byIdempotency}, claim/resend is refused forever. Public APIs enforce an explicit
 * legal state machine; raw unrestricted append is package-private.
 *
 * <p>Crash recovery: if a receipt file is durable but the index move failed, orphan
 * receipt files matching the expected name pattern are validated and merged into the
 * index under lock on load/repair.
 */
class DeliveryLedger {
    static final int SCHEMA_VERSION = 1

    /** Terminal or barrier statuses that block a blind resend. */
    static final Set<String> NO_RESEND_STATUSES = [
        'DELIVERED', 'PENDING', 'ATTEMPT', 'UNKNOWN', 'NEEDS_RECONCILIATION'
    ] as Set

    /**
     * Statuses that may be re-claimed for a new provider send (retryable failure only).
     * Absent key is also claimable. PENDING/ATTEMPT/UNKNOWN/NEEDS_RECONCILIATION/DELIVERED refuse.
     */
    static final Set<String> CLAIMABLE_FROM_STATUSES = ['FAILED'] as Set

    /**
     * Legal transitions for {@link #transition} (non-reconcile path).
     * UNKNOWN/NEEDS_RECONCILIATION may only move to each other via transition;
     * resolution to DELIVERED/FAILED requires audited {@link #reconcile}.
     * PENDING/ATTEMPT → DELIVERED/FAILED remain legal (provider completion).
     */
    static final Map<String, Set<String>> LEGAL_TRANSITIONS = [
        'PENDING'             : ['DELIVERED', 'FAILED', 'UNKNOWN', 'NEEDS_RECONCILIATION', 'ATTEMPT'] as Set,
        'ATTEMPT'             : ['DELIVERED', 'FAILED', 'UNKNOWN', 'NEEDS_RECONCILIATION', 'PENDING'] as Set,
        'FAILED'              : ['PENDING', 'ATTEMPT', 'FAILED'] as Set,
        'UNKNOWN'             : ['NEEDS_RECONCILIATION'] as Set, // DELIVERED/FAILED via reconcile only
        'NEEDS_RECONCILIATION': ['UNKNOWN'] as Set, // DELIVERED/FAILED via reconcile only
        'DELIVERED'           : [] as Set, // terminal — no demotion
        'SKIPPED_DISABLED'    : [] as Set,
        'SKIPPED_DUPLICATE'   : [] as Set,
        'SKIPPED_NOT_DUE'     : [] as Set
    ].asImmutable()

    private static final Pattern RECEIPT_FILE_PATTERN =
        Pattern.compile(/^delivery-[A-Za-z0-9._-]+\.json$/)

    private final Path directory
    private final Runnable beforeMoveHook
    private final Runnable afterDataBeforeIndexHook

    private static final ConcurrentHashMap<String, Object> PROCESS_LOCKS = new ConcurrentHashMap<>()
    private static final ThreadLocal<IdentityHashMap<Object, Integer>> LOCK_HOLD_DEPTH =
        ThreadLocal.withInitial { new IdentityHashMap<>() }

    DeliveryLedger(Path directory) {
        this(directory, null, null)
    }

    DeliveryLedger(Path directory, Runnable beforeMoveHook) {
        this(directory, beforeMoveHook, null)
    }

    /**
     * @param afterDataBeforeIndexHook test hook: runs after receipt data is durable,
     *        before index is written (simulates crash between dual-file moves)
     */
    DeliveryLedger(Path directory, Runnable beforeMoveHook, Runnable afterDataBeforeIndexHook) {
        if (directory == null) {
            throw new IllegalArgumentException('directory is required')
        }
        this.directory = directory
        this.beforeMoveHook = beforeMoveHook
        this.afterDataBeforeIndexHook = afterDataBeforeIndexHook
    }

    Path getDirectory() { directory }

    Path indexPath() {
        directory.resolve('delivery-index.json')
    }

    Path receiptPath(String receiptId) {
        directory.resolve("delivery-${ApplicationStateStore.encodeKey(receiptId)}.json")
    }

    private Path lockPath() {
        directory.resolve('.delivery-ledger.lock')
    }

    /**
     * Find delivered receipt by idempotency key. Only first DELIVERED counts as duplicate barrier.
     */
    DeliveryReceipt findDelivered(String idempotencyKey) {
        if (!idempotencyKey) {
            return null
        }
        withStoreLock {
            Map index = loadIndexUnlocked(true)
            return findDeliveredUnlocked(index, idempotencyKey)
        }
    }

    boolean wasDelivered(String idempotencyKey) {
        findDelivered(idempotencyKey) != null
    }

    /**
     * Latest receipt for key regardless of status (PENDING/FAILED/DELIVERED/UNKNOWN/...).
     */
    DeliveryReceipt findLatest(String idempotencyKey) {
        if (!idempotencyKey) {
            return null
        }
        withStoreLock {
            loadLatestUnlocked(idempotencyKey)
        }
    }

    /**
     * Whether a blind provider resend is forbidden for this key.
     * DELIVERED (any historical, via byIdempotency), in-flight PENDING/ATTEMPT, and
     * UNKNOWN/NEEDS_RECONCILIATION block resend. FAILED allows retry send. Missing key allows first send.
     */
    boolean blocksResend(String idempotencyKey) {
        if (!idempotencyKey) {
            return false
        }
        withStoreLock {
            Map index = loadIndexUnlocked(true)
            if (findDeliveredUnlocked(index, idempotencyKey) != null) {
                return true
            }
            DeliveryReceipt r = loadLatestUnlocked(idempotencyKey)
            if (r == null) {
                return false
            }
            return NO_RESEND_STATUSES.contains(r.status)
        }
    }

    /**
     * Atomic same-key pre-send claim: read-latest + legal transition + persist under one store lock.
     * Succeeds only when key is absent or latest status is retryable FAILED.
     * Refuses forever if any DELIVERED exists for the key in immutable byIdempotency (not only latest).
     * Refuses/no-ops for PENDING, ATTEMPT, UNKNOWN, NEEDS_RECONCILIATION, DELIVERED and any no-resend status.
     * Across instances/processes sharing the directory, exactly one concurrent claim for the same key succeeds.
     *
     * @return claim result; {@code claimed==true} only when pendingReceipt was persisted as latest
     */
    ClaimResult tryClaimPending(String idempotencyKey, DeliveryReceipt pendingReceipt) {
        if (!idempotencyKey) {
            throw new IllegalArgumentException('idempotencyKey is required')
        }
        if (pendingReceipt == null) {
            throw new IllegalArgumentException('pendingReceipt is required')
        }
        if (pendingReceipt.idempotencyKey != idempotencyKey) {
            throw new IllegalArgumentException('pendingReceipt idempotencyKey mismatch')
        }
        if (pendingReceipt.status != 'PENDING' && pendingReceipt.status != 'ATTEMPT') {
            throw new IllegalArgumentException(
                "pendingReceipt status must be PENDING or ATTEMPT, got: ${pendingReceipt.status}")
        }
        withStoreLock {
            Map index = loadIndexUnlocked(true)
            DeliveryReceipt delivered = findDeliveredUnlocked(index, idempotencyKey)
            if (delivered != null) {
                return ClaimResult.refused(delivered, 'ALREADY_DELIVERED')
            }
            DeliveryReceipt current = loadLatestUnlocked(idempotencyKey)
            if (current == null) {
                writeReceiptAndIndexUnlocked(pendingReceipt)
                return ClaimResult.claimed(pendingReceipt, null)
            }
            if (current.status == 'DELIVERED') {
                return ClaimResult.refused(current, 'ALREADY_DELIVERED')
            }
            if (NO_RESEND_STATUSES.contains(current.status) ||
                !CLAIMABLE_FROM_STATUSES.contains(current.status)) {
                return ClaimResult.refused(current, "NOT_CLAIMABLE_${current.status}")
            }
            // FAILED (retryable) → claim new PENDING
            assertLegalTransition(current.status, pendingReceipt.status, idempotencyKey)
            writeReceiptAndIndexUnlocked(pendingReceipt)
            return ClaimResult.claimed(pendingReceipt, current)
        }
    }

    /**
     * Record initial PENDING for an absent key (state-machine entry). Prefer
     * {@link #tryClaimPending} for pre-send. Refuses if key already has history or DELIVERED.
     */
    DeliveryReceipt recordPending(DeliveryReceipt pending) {
        if (pending == null) {
            throw new IllegalArgumentException('pending receipt is required')
        }
        if (pending.status != 'PENDING' && pending.status != 'ATTEMPT') {
            throw new IllegalTransitionException(
                pending.idempotencyKey, null, pending.status,
                "recordPending requires PENDING or ATTEMPT, got: ${pending.status}")
        }
        withStoreLock {
            String key = pending.idempotencyKey
            Map index = loadIndexUnlocked(true)
            if (key && findDeliveredUnlocked(index, key) != null) {
                throw new IllegalTransitionException(
                    key, 'DELIVERED', pending.status,
                    "Cannot record pending; DELIVERED barrier exists for ${key}")
            }
            DeliveryReceipt current = key ? loadLatestUnlocked(key) : null
            if (current != null) {
                throw new IllegalTransitionException(
                    key, current.status, pending.status,
                    "recordPending requires absent key; latest=${current.status}")
            }
            writeReceiptAndIndexUnlocked(pending)
            return pending
        }
    }

    /**
     * Record FAILED when key is absent (e.g. provider failed before claim) or transition
     * from claimable states via {@link #transition}.
     */
    DeliveryReceipt recordFailed(DeliveryReceipt failed) {
        if (failed == null) {
            throw new IllegalArgumentException('failed receipt is required')
        }
        if (failed.status != 'FAILED') {
            throw new IllegalTransitionException(
                failed.idempotencyKey, null, failed.status,
                "recordFailed requires status FAILED, got: ${failed.status}")
        }
        withStoreLock {
            String key = failed.idempotencyKey
            Map index = loadIndexUnlocked(true)
            if (key && findDeliveredUnlocked(index, key) != null) {
                throw new IllegalTransitionException(
                    key, 'DELIVERED', 'FAILED',
                    "Cannot demote DELIVERED barrier to FAILED for ${key}")
            }
            DeliveryReceipt current = key ? loadLatestUnlocked(key) : null
            if (current == null) {
                writeReceiptAndIndexUnlocked(failed)
                return failed
            }
            assertLegalTransition(current.status, 'FAILED', key)
            writeReceiptAndIndexUnlocked(failed)
            return failed
        }
    }

    /**
     * Record DELIVERED only from an active PENDING/ATTEMPT claim (or audited path via
     * {@link #reconcile}). Cannot do FAILED→DELIVERED or absent→DELIVERED without claim.
     * Never demotes existing barrier. First DELIVERED wins in byIdempotency.
     */
    DeliveryReceipt recordDelivered(DeliveryReceipt delivered) {
        if (delivered == null) {
            throw new IllegalArgumentException('delivered receipt is required')
        }
        if (delivered.status != 'DELIVERED') {
            throw new IllegalTransitionException(
                delivered.idempotencyKey, null, delivered.status,
                "recordDelivered requires status DELIVERED, got: ${delivered.status}")
        }
        withStoreLock {
            String key = delivered.idempotencyKey
            Map index = loadIndexUnlocked(true)
            DeliveryReceipt existingBarrier = key ? findDeliveredUnlocked(index, key) : null
            DeliveryReceipt current = key ? loadLatestUnlocked(key) : null
            if (existingBarrier != null && current != null && current.status == 'DELIVERED') {
                // Already terminal: keep first barrier; allow duplicate DELIVERED append for history
                writeReceiptAndIndexUnlocked(delivered)
                return delivered
            }
            if (current == null) {
                throw new IllegalTransitionException(
                    key, null, 'DELIVERED',
                    "recordDelivered requires active PENDING claim; key absent for ${key}")
            }
            if (current.status == 'FAILED') {
                throw new IllegalTransitionException(
                    key, 'FAILED', 'DELIVERED',
                    "recordDelivered cannot promote FAILED→DELIVERED without PENDING claim for ${key}")
            }
            if (current.status != 'PENDING' && current.status != 'ATTEMPT' &&
                current.status != 'DELIVERED') {
                // UNKNOWN/NEEDS_RECONCILIATION must use reconcile()
                throw new IllegalTransitionException(
                    key, current.status, 'DELIVERED',
                    "recordDelivered requires PENDING/ATTEMPT claim (or reconcile); latest=${current.status}")
            }
            if (current.status != 'DELIVERED') {
                assertLegalTransition(current.status, 'DELIVERED', key)
            }
            writeReceiptAndIndexUnlocked(delivered)
            return delivered
        }
    }

    /**
     * Audited reconciliation: mark UNKNOWN/NEEDS_RECONCILIATION → DELIVERED or FAILED
     * after operator investigation. Only path that may resolve those states to
     * DELIVERED/FAILED. Cannot demote DELIVERED. Stamps actor/reason/time/audit metadata.
     *
     * @param auditReason required non-blank reason for the reconciliation
     * @param actor optional operator/actor identity (stored in metadata.reconcileActor)
     */
    DeliveryReceipt reconcile(String idempotencyKey, DeliveryReceipt next, String auditReason) {
        return reconcile(idempotencyKey, next, auditReason, null)
    }

    DeliveryReceipt reconcile(String idempotencyKey, DeliveryReceipt next, String auditReason,
                              String actor) {
        if (!idempotencyKey) {
            throw new IllegalArgumentException('idempotencyKey is required')
        }
        if (next == null) {
            throw new IllegalArgumentException('next receipt is required')
        }
        if (next.idempotencyKey != idempotencyKey) {
            throw new IllegalArgumentException('next receipt idempotencyKey mismatch')
        }
        if (auditReason == null || auditReason.trim().isEmpty()) {
            throw new IllegalArgumentException('auditReason is required for reconcile')
        }
        if (!(next.status in ['DELIVERED', 'FAILED', 'NEEDS_RECONCILIATION', 'UNKNOWN'] as Set)) {
            throw new IllegalTransitionException(
                idempotencyKey, null, next.status,
                "reconcile target must be DELIVERED, FAILED, UNKNOWN, or NEEDS_RECONCILIATION")
        }
        withStoreLock {
            Map index = loadIndexUnlocked(true)
            DeliveryReceipt barrier = findDeliveredUnlocked(index, idempotencyKey)
            if (barrier != null && next.status != 'DELIVERED') {
                throw new IllegalTransitionException(
                    idempotencyKey, 'DELIVERED', next.status,
                    "reconcile cannot demote DELIVERED barrier for ${idempotencyKey}")
            }
            DeliveryReceipt current = loadLatestUnlocked(idempotencyKey)
            if (current == null) {
                throw new IllegalTransitionException(
                    idempotencyKey, null, next.status,
                    "reconcile requires existing receipt for ${idempotencyKey}")
            }
            if (current.status == 'DELIVERED' && next.status != 'DELIVERED') {
                throw new IllegalTransitionException(
                    idempotencyKey, 'DELIVERED', next.status,
                    "DELIVERED is terminal; cannot reconcile to ${next.status}")
            }
            if (current.status != 'DELIVERED') {
                // Reconcile is the only path for UNKNOWN/NEEDS_RECONCILIATION → DELIVERED/FAILED
                boolean auditedOk = current.status in
                    ['UNKNOWN', 'NEEDS_RECONCILIATION', 'PENDING', 'ATTEMPT'] as Set &&
                    next.status in ['DELIVERED', 'FAILED', 'UNKNOWN', 'NEEDS_RECONCILIATION'] as Set
                if (!auditedOk) {
                    throw new IllegalTransitionException(
                        idempotencyKey, current.status, next.status,
                        "Illegal reconcile ${current.status} → ${next.status}")
                }
            }
            Instant reconcileAt = next.completedAt ?: next.attemptedAt
            Map meta = next.metadata instanceof Map
                ? new LinkedHashMap(next.metadata as Map) : new LinkedHashMap()
            meta.reconciled = true
            meta.reconcileReason = auditReason.trim()
            meta.reconcileFrom = current.status
            meta.reconcileAt = reconcileAt != null ? reconcileAt.toString() : Instant.now().toString()
            if (actor != null && !actor.trim().isEmpty()) {
                meta.reconcileActor = actor.trim()
            }
            meta.audit = [
                action : 'reconcile',
                from   : current.status,
                to     : next.status,
                reason : auditReason.trim(),
                actor  : actor?.trim(),
                at     : meta.reconcileAt
            ]
            DeliveryReceipt audited = DeliveryReceipt.builder()
                .id(next.id)
                .idempotencyKey(next.idempotencyKey)
                .kind(next.kind)
                .destination(next.destination)
                .planId(next.planId)
                .planVersion(next.planVersion)
                .planHash(next.planHash)
                .proposalId(next.proposalId)
                .status(next.status)
                .providerMessageId(next.providerMessageId)
                .threadId(next.threadId)
                .channelId(next.channelId)
                .attemptedAt(next.attemptedAt)
                .completedAt(next.completedAt)
                .errorClassification(next.errorClassification)
                .errorMessage(next.errorMessage)
                .metadata(meta)
                .build()
            writeReceiptAndIndexUnlocked(audited)
            return audited
        }
    }

    /**
     * Atomically transition latest receipt for key from expected statuses to a new receipt.
     * Enforces legal state machine. DELIVERED is terminal — cannot demote.
     * Returns the recorded receipt. Throws {@link IllegalTransitionException} on illegal transition.
     */
    DeliveryReceipt transition(String idempotencyKey, Set<String> expectedFrom, DeliveryReceipt next) {
        if (!idempotencyKey) {
            throw new IllegalArgumentException('idempotencyKey is required')
        }
        if (next == null) {
            throw new IllegalArgumentException('next receipt is required')
        }
        if (next.idempotencyKey != idempotencyKey) {
            throw new IllegalArgumentException('next receipt idempotencyKey mismatch')
        }
        withStoreLock {
            Map index = loadIndexUnlocked(true)
            // Immutable delivered barrier: refuse any non-DELIVERED next if barrier exists
            DeliveryReceipt barrier = findDeliveredUnlocked(index, idempotencyKey)
            if (barrier != null && next.status != 'DELIVERED') {
                throw new IllegalTransitionException(
                    idempotencyKey, 'DELIVERED', next.status,
                    "DELIVERED is terminal barrier; cannot transition to ${next.status} for ${idempotencyKey}")
            }
            DeliveryReceipt current = loadLatestUnlocked(idempotencyKey)
            if (current != null && expectedFrom != null && !expectedFrom.isEmpty()) {
                if (!expectedFrom.contains(current.status)) {
                    throw new IllegalTransitionException(
                        idempotencyKey, current.status, next.status,
                        "Delivery transition refused for ${idempotencyKey}: " +
                            "current=${current.status}, expected one of ${expectedFrom}")
                }
            }
            if (current != null) {
                if (current.status == 'DELIVERED' && next.status != 'DELIVERED') {
                    throw new IllegalTransitionException(
                        idempotencyKey, 'DELIVERED', next.status,
                        "DELIVERED is terminal; cannot demote to ${next.status}")
                }
                if (current.status != 'DELIVERED' || next.status != 'DELIVERED') {
                    assertLegalTransition(current.status, next.status, idempotencyKey)
                }
            } else {
                // Absent: only PENDING/ATTEMPT/FAILED entry — not DELIVERED without claim
                if (next.status != 'PENDING' && next.status != 'ATTEMPT' &&
                    next.status != 'FAILED') {
                    throw new IllegalTransitionException(
                        idempotencyKey, null, next.status,
                        "Absent key may only transition to PENDING/ATTEMPT/FAILED")
                }
            }
            writeReceiptAndIndexUnlocked(next)
            return next
        }
    }

    /**
     * Private raw append — not part of the public API. Groovy {@code private} prevents
     * external and package callers. All state writes must flow through
     * {@link #recordPending}, {@link #recordFailed}, {@link #recordDelivered},
     * {@link #transition}, {@link #tryClaimPending}, or {@link #reconcile}.
     * No {@code allowDelivered} bypass: enforces legal SM and DELIVERED terminal barrier.
     */
    private void record(DeliveryReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException('receipt is required')
        }
        withStoreLock {
            String key = receipt.idempotencyKey
            if (key) {
                Map index = loadIndexUnlocked(true)
                DeliveryReceipt barrier = findDeliveredUnlocked(index, key)
                if (barrier != null && receipt.status != 'DELIVERED') {
                    throw new IllegalTransitionException(
                        key, 'DELIVERED', receipt.status,
                        "DELIVERED is terminal; refuse record(${receipt.status}) for ${key}")
                }
                DeliveryReceipt current = loadLatestUnlocked(key)
                if (current != null && current.status == 'DELIVERED' && receipt.status != 'DELIVERED') {
                    throw new IllegalTransitionException(
                        key, 'DELIVERED', receipt.status,
                        "DELIVERED is terminal; refuse record(${receipt.status}) for ${key}")
                }
                if (current == null) {
                    if (!(receipt.status in ['PENDING', 'ATTEMPT', 'FAILED'] as Set)) {
                        throw new IllegalTransitionException(
                            key, null, receipt.status,
                            "Absent key may only record PENDING/ATTEMPT/FAILED via private record")
                    }
                } else if (current.status != 'DELIVERED' && receipt.status != current.status) {
                    assertLegalTransition(current.status, receipt.status, key)
                }
            }
            writeReceiptAndIndexUnlocked(receipt)
        }
    }

    /**
     * Scan orphan receipt files and merge into index. Safe to call anytime; automatic on load.
     * @return number of orphan receipts merged
     */
    int repairIndex() {
        withStoreLock {
            Map index = loadIndexUnlocked(false)
            return recoverOrphansUnlocked(index, true)
        }
    }

    /**
     * Result of {@link #tryClaimPending}.
     */
    static final class ClaimResult {
        final boolean claimed
        final DeliveryReceipt pending
        final DeliveryReceipt existing
        final String reason

        private ClaimResult(boolean claimed, DeliveryReceipt pending,
                            DeliveryReceipt existing, String reason) {
            this.claimed = claimed
            this.pending = pending
            this.existing = existing
            this.reason = reason
        }

        static ClaimResult claimed(DeliveryReceipt pending, DeliveryReceipt prior) {
            new ClaimResult(true, pending, prior, null)
        }

        static ClaimResult refused(DeliveryReceipt existing, String reason) {
            new ClaimResult(false, null, existing, reason)
        }

        boolean isClaimed() { claimed }
    }

    /**
     * Structured refusal of illegal delivery state transition. No mutation occurred.
     */
    static class IllegalTransitionException extends PlanStoreException {
        final String idempotencyKey
        final String fromStatus
        final String toStatus

        IllegalTransitionException(String idempotencyKey, String fromStatus, String toStatus,
                                   String message) {
            super(message, null, 'transition')
            this.idempotencyKey = idempotencyKey
            this.fromStatus = fromStatus
            this.toStatus = toStatus
        }
    }

    DeliveryReceipt loadReceipt(String receiptId) {
        if (!receiptId) {
            return null
        }
        withStoreLock {
            loadReceiptUnlocked(receiptId)
        }
    }

    List<String> listReceiptIds() {
        withStoreLock {
            Map index = loadIndexUnlocked(true)
            List ids = []
            if (index.entries instanceof Collection) {
                (index.entries as Collection).each { e ->
                    if (e instanceof Map && e.id) {
                        ids << e.id.toString()
                    }
                }
            }
            return ids
        }
    }

    private static void assertLegalTransition(String from, String to, String key) {
        if (from == null) {
            return
        }
        if (from == 'DELIVERED' && to != 'DELIVERED') {
            throw new IllegalTransitionException(key, from, to,
                "DELIVERED is terminal; cannot transition to ${to}")
        }
        Set allowed = LEGAL_TRANSITIONS[from]
        if (allowed == null) {
            throw new IllegalTransitionException(key, from, to,
                "Unknown current status ${from}")
        }
        if (!allowed.contains(to)) {
            throw new IllegalTransitionException(key, from, to,
                "Illegal delivery transition ${from} → ${to} for ${key}")
        }
    }

    private void writeReceiptAndIndexUnlocked(DeliveryReceipt receipt) {
        Path target = receiptPath(receipt.id)
        if (Files.exists(target)) {
            target = allocateUniquePath(receipt.id)
        }
        // Data first — never create index entries before durable data
        atomicWriteJsonUnlocked(target, JsonOutput.prettyPrint(JsonOutput.toJson(receipt.toMap())))
        if (afterDataBeforeIndexHook != null) {
            afterDataBeforeIndexHook.run()
        }
        Map index = loadIndexUnlocked(false)
        mergeReceiptIntoIndex(index, receipt, target.fileName.toString())
        index.schemaVersion = SCHEMA_VERSION
        atomicWriteJsonUnlocked(indexPath(), JsonOutput.prettyPrint(JsonOutput.toJson(index)))
    }

    private void mergeReceiptIntoIndex(Map index, DeliveryReceipt receipt, String fileName) {
        List entries = index.entries instanceof List ? new ArrayList(index.entries as List) : []
        // Avoid duplicate entry for same id+file
        boolean exists = entries.any { e ->
            e instanceof Map && e.id?.toString() == receipt.id && e.file?.toString() == fileName
        }
        if (!exists) {
            entries << [id: receipt.id, file: fileName,
                        idempotencyKey: receipt.idempotencyKey, status: receipt.status]
        }
        index.entries = entries

        if (receipt.idempotencyKey) {
            Map byLatest = index.byLatest instanceof Map
                ? new LinkedHashMap(index.byLatest as Map) : new LinkedHashMap()
            // Once terminal DELIVERED is latest, keep latest as DELIVERED (do not demote pointer)
            def priorLatest = byLatest[receipt.idempotencyKey]
            boolean priorWasDelivered = priorLatest instanceof Map &&
                priorLatest.status?.toString() == 'DELIVERED'
            if (!(priorWasDelivered && receipt.status != 'DELIVERED')) {
                byLatest[receipt.idempotencyKey] = [
                    receiptId: receipt.id,
                    file     : fileName,
                    status   : receipt.status
                ]
            }
            index.byLatest = byLatest
        }

        if (receipt.status == 'DELIVERED' && receipt.idempotencyKey) {
            Map byIdem = index.byIdempotency instanceof Map
                ? new LinkedHashMap(index.byIdempotency as Map) : new LinkedHashMap()
            // First delivered wins for idempotency (do not overwrite with later)
            if (!byIdem.containsKey(receipt.idempotencyKey)) {
                byIdem[receipt.idempotencyKey] = [
                    receiptId: receipt.id,
                    file     : fileName,
                    status   : receipt.status
                ]
                index.byIdempotency = byIdem
            }
        }
    }

    private DeliveryReceipt findDeliveredUnlocked(Map index, String idempotencyKey) {
        def entry = index.byIdempotency instanceof Map ? index.byIdempotency.get(idempotencyKey) : null
        if (entry instanceof Map && entry.receiptId) {
            DeliveryReceipt r = loadReceiptUnlocked(entry.receiptId.toString())
            if (r != null && r.status == 'DELIVERED') {
                return r
            }
        }
        // Fallback: scan entries for any DELIVERED with this key (recovery if index partial)
        if (index.entries instanceof Collection) {
            DeliveryReceipt first = null
            (index.entries as Collection).each { e ->
                if (first == null && e instanceof Map &&
                    e.idempotencyKey?.toString() == idempotencyKey &&
                    e.status?.toString() == 'DELIVERED' && e.id) {
                    DeliveryReceipt r = loadReceiptUnlocked(e.id.toString())
                    if (r != null && r.status == 'DELIVERED') {
                        first = r
                    }
                }
            }
            return first
        }
        return null
    }

    private DeliveryReceipt loadLatestUnlocked(String idempotencyKey) {
        Map index = loadIndexUnlocked(true)
        def entry = null
        if (index.byLatest instanceof Map) {
            entry = index.byLatest.get(idempotencyKey)
        }
        // Fall back to delivered index, then scan entries newest-last
        if (!(entry instanceof Map) && index.byIdempotency instanceof Map) {
            entry = index.byIdempotency.get(idempotencyKey)
        }
        if (entry instanceof Map && entry.receiptId) {
            DeliveryReceipt r = loadReceiptUnlocked(entry.receiptId.toString())
            if (r != null) {
                return r
            }
        }
        DeliveryReceipt last = null
        if (index.entries instanceof Collection) {
            (index.entries as Collection).each { e ->
                if (e instanceof Map && e.idempotencyKey?.toString() == idempotencyKey && e.id) {
                    DeliveryReceipt r = loadReceiptUnlocked(e.id.toString())
                    if (r != null) {
                        last = r
                    }
                }
            }
        }
        return last
    }

    private DeliveryReceipt loadReceiptUnlocked(String receiptId) {
        Map index = loadIndexUnlocked(true)
        if (index.entries instanceof Collection) {
            for (def e : (index.entries as Collection)) {
                if (e instanceof Map && e.id?.toString() == receiptId && e.file) {
                    Path p = StorePaths.resolveContained(directory, e.file.toString(), 'delivery-index')
                    if (Files.exists(p)) {
                        return parseReceipt(new String(Files.readAllBytes(p), StandardCharsets.UTF_8), p.toString())
                    }
                }
            }
        }
        Path primary = receiptPath(receiptId)
        if (Files.exists(primary)) {
            return parseReceipt(new String(Files.readAllBytes(primary), StandardCharsets.UTF_8), primary.toString())
        }
        // Orphan unique path delivery-{encoded}-{n}.json
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(directory, 'delivery-*.json')
            try {
                for (Path p : stream) {
                    String name = p.fileName.toString()
                    if (!RECEIPT_FILE_PATTERN.matcher(name).matches()) {
                        continue
                    }
                    if (!Files.isRegularFile(p)) {
                        continue
                    }
                    try {
                        DeliveryReceipt r = parseReceipt(
                            new String(Files.readAllBytes(p), StandardCharsets.UTF_8), p.toString())
                        if (r != null && r.id == receiptId) {
                            return r
                        }
                    } catch (Exception ignored) {
                        // skip corrupt
                    }
                }
            } finally {
                stream.close()
            }
        } catch (Exception ignored) {
        }
        return null
    }

    private Map loadIndexUnlocked(boolean recoverOrphans) {
        Path p = indexPath()
        Map m
        if (!Files.exists(p)) {
            m = [schemaVersion: SCHEMA_VERSION, entries: [], byIdempotency: [:], byLatest: [:]]
        } else {
            try {
                def root = new JsonSlurper().parseText(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                if (!(root instanceof Map)) {
                    throw new PlanStoreException('Delivery index root must be object', p.toString(), 'parse')
                }
                m = root as Map
                if (m.byIdempotency == null) {
                    m.byIdempotency = [:]
                }
                if (m.byLatest == null) {
                    m.byLatest = [:]
                }
                if (m.entries == null) {
                    m.entries = []
                }
            } catch (PlanStoreException e) {
                throw e
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Malformed delivery index: ${e.message}", p.toString(), 'parse', e)
            }
        }
        if (recoverOrphans) {
            recoverOrphansUnlocked(m, true)
        }
        return m
    }

    /**
     * Scan directory for delivery-*.json files not in index; validate and merge.
     * Never reads outside directory. Ignores corruption safely.
     */
    private int recoverOrphansUnlocked(Map index, boolean persistIfChanged) {
        if (!Files.isDirectory(directory)) {
            return 0
        }
        Set<String> knownFiles = new HashSet<>()
        if (index.entries instanceof Collection) {
            (index.entries as Collection).each { e ->
                if (e instanceof Map && e.file) {
                    knownFiles << e.file.toString()
                }
            }
        }
        int merged = 0
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(directory, 'delivery-*.json')
            try {
                List<Path> orphans = []
                for (Path p : stream) {
                    String name = p.fileName.toString()
                    if (!RECEIPT_FILE_PATTERN.matcher(name).matches()) {
                        continue
                    }
                    if (knownFiles.contains(name)) {
                        continue
                    }
                    if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        continue
                    }
                    if (Files.isSymbolicLink(p)) {
                        continue
                    }
                    orphans << p
                }
                // Deterministic order by file name
                orphans.sort { a, b -> a.fileName.toString() <=> b.fileName.toString() }
                for (Path p : orphans) {
                    try {
                        DeliveryReceipt r = parseReceipt(
                            new String(Files.readAllBytes(p), StandardCharsets.UTF_8), p.toString())
                        if (r == null || !r.id) {
                            continue
                        }
                        mergeReceiptIntoIndex(index, r, p.fileName.toString())
                        knownFiles << p.fileName.toString()
                        merged++
                    } catch (Exception ignored) {
                        // reject corruption safely
                    }
                }
            } finally {
                stream.close()
            }
        } catch (Exception ignored) {
            return merged
        }
        if (merged > 0 && persistIfChanged) {
            index.schemaVersion = SCHEMA_VERSION
            try {
                atomicWriteJsonUnlocked(indexPath(), JsonOutput.prettyPrint(JsonOutput.toJson(index)))
            } catch (Exception ignored) {
                // best-effort persist; in-memory index still has merge for this lock hold
            }
        }
        return merged
    }

    private Path allocateUniquePath(String receiptId) {
        String base = ApplicationStateStore.encodeKey(receiptId)
        int n = 1
        while (true) {
            Path candidate = directory.resolve("delivery-${base}-${n}.json")
            if (!Files.exists(candidate)) {
                return candidate
            }
            n++
            if (n > 10000) {
                throw new PlanStoreException(
                    "Unable to allocate unique delivery path for ${receiptId}",
                    directory.toString(), 'save')
            }
        }
    }

    private static DeliveryReceipt parseReceipt(String text, String path) {
        if (text == null || text.trim().isEmpty()) {
            throw new PlanStoreException('Delivery receipt is empty', path, 'parse')
        }
        try {
            def root = new JsonSlurper().parseText(text)
            if (!(root instanceof Map)) {
                throw new PlanStoreException('Delivery receipt root must be object', path, 'parse')
            }
            return DeliveryReceipt.fromMap(root as Map)
        } catch (PlanStoreException e) {
            throw e
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid delivery receipt: ${e.message}", path, 'parse', e)
        }
    }

    private <T> T withStoreLock(Closure<T> action) {
        try {
            Files.createDirectories(directory)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Failed to create delivery ledger directory: ${directory}",
                directory.toString(), 'save', e)
        }
        Path lp = lockPath()
        String key
        try {
            key = lp.toAbsolutePath().normalize().toString()
        } catch (Exception e) {
            key = lp.toString()
        }
        Object monitor = PROCESS_LOCKS.computeIfAbsent(key, { k -> new Object() })
        IdentityHashMap<Object, Integer> depths = LOCK_HOLD_DEPTH.get()
        Integer held = depths.get(monitor)
        if (held != null && held > 0) {
            throw new IllegalStateException(
                'DeliveryLedger lock must not nest on the same thread')
        }
        synchronized (monitor) {
            depths.put(monitor, 1)
            FileChannel channel = null
            FileLock lock = null
            try {
                channel = FileChannel.open(lp,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                lock = channel.lock()
                return action.call()
            } catch (IllegalStateException | PlanStoreException e) {
                throw e
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Failed under delivery ledger lock: ${e.message}", lp.toString(), 'lock', e)
            } finally {
                depths.remove(monitor)
                if (lock != null) {
                    try { lock.release() } catch (Exception ignored) {}
                }
                if (channel != null) {
                    try { channel.close() } catch (Exception ignored) {}
                }
            }
        }
    }

    private void atomicWriteJsonUnlocked(Path target, String json) {
        Path temp = null
        try {
            String stem = target.fileName.toString().replaceAll(/[^A-Za-z0-9._-]/, '_')
            temp = Files.createTempFile(directory, ".${stem}.", '.tmp')
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try (def ch = Files.newByteChannel(temp, StandardOpenOption.WRITE)) {
                ch.force(true)
            }
            if (beforeMoveHook != null) {
                beforeMoveHook.run()
            }
            try {
                Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            temp = null
        } catch (PlanStoreException e) {
            cleanupTemp(temp)
            throw e
        } catch (Exception e) {
            cleanupTemp(temp)
            throw new PlanStoreException(
                "Failed to save delivery state to ${target}: ${e.message}",
                target.toString(), 'save', e)
        } finally {
            cleanupTemp(temp)
        }
    }

    private static void cleanupTemp(Path temp) {
        if (temp != null) {
            try { Files.deleteIfExists(temp) } catch (Exception ignored) {}
        }
    }
}
