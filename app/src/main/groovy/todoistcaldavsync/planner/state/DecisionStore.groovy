package todoistcaldavsync.planner.state

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.domain.DecisionRecord

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Append-only, atomic, locked decision store. Collision-free paths.
 * Never overwrites an existing decision id.
 *
 * <p>Crash recovery: orphan decision-*.json files written before index move are
 * validated and merged into the index under lock on load/repair.
 */
class DecisionStore {
    static final int SCHEMA_VERSION = 1

    private static final Pattern DECISION_FILE_PATTERN =
        Pattern.compile(/^decision-[A-Za-z0-9._-]+\.json$/)

    private final Path directory
    private final Runnable beforeMoveHook
    private final Runnable afterDataBeforeIndexHook

    private static final ConcurrentHashMap<String, Object> PROCESS_LOCKS = new ConcurrentHashMap<>()
    private static final ThreadLocal<IdentityHashMap<Object, Integer>> LOCK_HOLD_DEPTH =
        ThreadLocal.withInitial { new IdentityHashMap<>() }

    DecisionStore(Path directory) {
        this(directory, null, null)
    }

    DecisionStore(Path directory, Runnable beforeMoveHook) {
        this(directory, beforeMoveHook, null)
    }

    /**
     * @param afterDataBeforeIndexHook test hook after decision data durable, before index
     */
    DecisionStore(Path directory, Runnable beforeMoveHook, Runnable afterDataBeforeIndexHook) {
        if (directory == null) {
            throw new IllegalArgumentException('directory is required')
        }
        this.directory = directory
        this.beforeMoveHook = beforeMoveHook
        this.afterDataBeforeIndexHook = afterDataBeforeIndexHook
    }

    Path getDirectory() { directory }

    Path indexPath() {
        directory.resolve('decision-index.json')
    }

    Path decisionPath(String decisionId) {
        directory.resolve("decision-${ApplicationStateStore.encodeKey(decisionId)}.json")
    }

    private Path lockPath() {
        directory.resolve('.decision-store.lock')
    }

    /**
     * Private raw append — not part of the public API. External hosts must use
     * {@link #appendClassified} (feedback decisions) or {@link #appendAudit}
     * (non-authorizing HELP/STATUS/rejected). Bypassing classification is forbidden.
     * Fails if same id already exists (collision-free append-only).
     */
    private void append(DecisionRecord decision) {
        if (decision == null) {
            throw new IllegalArgumentException('decision is required')
        }
        withStoreLock {
            appendUnlocked(decision)
        }
    }

    /**
     * Atomic correlation classification under one JVM+file lock: re-read existing
     * correlation records, classify exact replay vs conflict vs new, and persist the
     * appropriate auditable record before releasing.
     *
     * <p>Same correlation + same action/plan id/version/hash/actor/context identity ⇒
     * exactly one ACCEPTED; concurrent loser gets IDEMPOTENT_REPLAY (never a second ACCEPTED).
     * Same correlation but differing command/action/hash/actor ⇒ conflict; no accepted
     * authorization; rejected conflict record appended when safe.
     *
     * @param candidate pre-validated candidate (syntax/auth/plan/hash already checked by parser).
     *                  For new accepts, status should be ACCEPTED. Reject candidates may carry
     *                  REJECTED_* statuses and are appended as-is when no correlation conflict.
     */
    DecisionAppendOutcome appendClassified(DecisionRecord candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException('candidate is required')
        }
        withStoreLock {
            return appendClassifiedUnlocked(candidate)
        }
    }

    /**
     * Append a non-authorizing audit record (HELP/STATUS accepted, or any REJECTED_*).
     * Enforces correlation uniqueness/state rules via the same classification path as
     * {@link #appendClassified}: exact replay → IDEMPOTENT_REPLAY; conflicting ACCEPTED →
     * REJECTED_REPLAY_CONFLICT; never a second ACCEPTED for the same correlation identity.
     * Refuses ACCEPTED plan-bound authorizing actions (APPROVE/APPLY_SAFE/REJECT/REQUEST_CHANGES)
     * — those must use {@link #appendClassified} only through FeedbackParser.
     */
    DecisionAppendOutcome appendAudit(DecisionRecord decision) {
        if (decision == null) {
            throw new IllegalArgumentException('decision is required')
        }
        if (decision.isAccepted() && decision.isPlanBoundAction()) {
            throw new IllegalArgumentException(
                "appendAudit refuses authorizing ACCEPTED ${decision.action}; use appendClassified")
        }
        withStoreLock {
            return appendClassifiedUnlocked(decision)
        }
    }

    private DecisionAppendOutcome appendClassifiedUnlocked(DecisionRecord candidate) {
        // Independent of builder: refuse authorizing ACCEPTED/IDEMPOTENT_REPLAY without
        // nonblank correlation before any lookup/append so malformed/deserialized objects
        // cannot bypass. No ACCEPTED file is written.
        refuseBlankCorrelationForAcceptedLike(candidate)
        String correlation = candidate.correlationId
        List<DecisionRecord> prior = listForCorrelationUnlocked(correlation)
        DecisionRecord priorExact = findExactAcceptedMatch(prior, candidate)
        if (priorExact != null) {
            // Never reuse candidate.id for replay — always a new durable id under lock
            String replayId = allocateDerivedUniqueIdUnlocked(
                'dec-replay-', semanticIdMaterial(candidate, priorExact, 'replay'))
            DecisionRecord replay = buildReplayRecord(candidate, priorExact, replayId)
            refuseBlankCorrelationForAcceptedLike(replay)
            appendUnlocked(replay)
            return DecisionAppendOutcome.idempotentReplay(replay, priorExact)
        }
        DecisionRecord priorConflict = findConflictingAccepted(prior, candidate)
        if (priorConflict != null) {
            String conflictId = allocateDerivedUniqueIdUnlocked(
                'dec-conflict-', semanticIdMaterial(candidate, priorConflict, 'conflict'))
            DecisionRecord conflict = buildConflictRecord(candidate, priorConflict, conflictId)
            appendUnlocked(conflict)
            return DecisionAppendOutcome.conflict(conflict, priorConflict)
        }
        // New path: preserve proposed id only if unused; else collision-free derived id
        DecisionRecord toPersist = ensureUniquePersistedIdUnlocked(candidate, 'dec-ok-')
        appendUnlocked(toPersist)
        if (toPersist.isAccepted()) {
            return DecisionAppendOutcome.newAccepted(toPersist)
        }
        return DecisionAppendOutcome.newRecord(toPersist)
    }

    /**
     * Defense-in-depth: ACCEPTED and IDEMPOTENT_REPLAY require nonblank correlationId.
     * Throws before index lookup or file write.
     */
    private static void refuseBlankCorrelationForAcceptedLike(DecisionRecord candidate) {
        if (candidate == null) {
            return
        }
        boolean acceptedLike = candidate.isAccepted() || candidate.isIdempotentReplay()
        if (!acceptedLike) {
            return
        }
        String c = candidate.correlationId
        if (c == null || c.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "DecisionStore refuses ${candidate.status} without nonblank correlationId" +
                    " (action=${candidate.action}, id=${candidate.id})")
        }
    }

    private static DecisionRecord findExactAcceptedMatch(List<DecisionRecord> prior,
                                                         DecisionRecord candidate) {
        if (!prior) {
            return null
        }
        return prior.find { DecisionRecord it ->
            it != null && it.isAccepted() && sameCorrelationIdentity(it, candidate)
        }
    }

    private static DecisionRecord findConflictingAccepted(List<DecisionRecord> prior,
                                                          DecisionRecord candidate) {
        if (!prior) {
            return null
        }
        // Conflicting ACCEPTED on same correlation with differing identity fields
        return prior.find { DecisionRecord it ->
            if (it == null || !it.isAccepted()) {
                return false
            }
            // Any accepted with different action/plan/hash/actor/proposal is a conflict
            return !sameCorrelationIdentity(it, candidate)
        }
    }

    /**
     * Exact identity for correlation replay: action + proposal + plan id/version/hash + actor.
     * Context fields (destination/thread/message) are informational; correlation id already binds them.
     */
    private static boolean sameCorrelationIdentity(DecisionRecord a, DecisionRecord b) {
        return Objects.equals(a.action, b.action) &&
            Objects.equals(nz(a.proposalId), nz(b.proposalId)) &&
            Objects.equals(nz(a.planId), nz(b.planId)) &&
            a.planVersion == b.planVersion &&
            Objects.equals(nz(a.planHash), nz(b.planHash)) &&
            Objects.equals(nz(a.actorId), nz(b.actorId))
    }

    private static String nz(String s) {
        s == null ? '' : s
    }

    private static DecisionRecord buildReplayRecord(DecisionRecord candidate, DecisionRecord prior,
                                                    String id) {
        DecisionRecord.Builder b = DecisionRecord.builder()
            .id(id)
            .proposalId(prior.proposalId ?: candidate.proposalId)
            .planId(prior.planId ?: candidate.planId)
            .action(prior.action ?: candidate.action)
            .status('IDEMPOTENT_REPLAY')
            .actorId(candidate.actorId ?: prior.actorId)
            .correlationId(candidate.correlationId ?: prior.correlationId)
            .destination(candidate.destination ?: prior.destination)
            .threadId(candidate.threadId ?: prior.threadId)
            .messageId(candidate.messageId ?: prior.messageId)
            .decidedAt(candidate.decidedAt)
            .reason(candidate.reason ?: 'idempotent replay')
            .previousDecisionId(prior.id)
            .conflictStatus('none')
            .metadata([replayOf: prior.id, replayed: true])
        if (prior.planVersion >= 1) {
            b.planVersion(prior.planVersion)
        } else if (candidate.planVersion >= 1) {
            b.planVersion(candidate.planVersion)
        }
        String hash = prior.planHash ?: candidate.planHash
        if (hash) {
            b.planHash(hash)
        }
        return b.build()
    }

    private static DecisionRecord buildConflictRecord(DecisionRecord candidate,
                                                      DecisionRecord priorConflict,
                                                      String id) {
        String action = candidate.action ?: 'REJECT'
        DecisionRecord.Builder b = DecisionRecord.builder()
            .id(id)
            .proposalId(candidate.proposalId ?: priorConflict.proposalId)
            .planId(candidate.planId ?: priorConflict.planId)
            .action(action)
            .status('REJECTED_REPLAY_CONFLICT')
            .actorId(candidate.actorId)
            .correlationId(candidate.correlationId)
            .destination(candidate.destination)
            .threadId(candidate.threadId)
            .messageId(candidate.messageId)
            .decidedAt(candidate.decidedAt)
            .reason(candidate.reason ?:
                "conflicting prior decision ${priorConflict.action} (${priorConflict.id})")
            .previousDecisionId(priorConflict.id)
            .conflictStatus('conflict')
            .metadata([
                conflictWith: priorConflict.id,
                priorAction : priorConflict.action
            ])
        Integer ver = candidate.planVersion >= 1 ? candidate.planVersion :
            (priorConflict.planVersion >= 1 ? priorConflict.planVersion : null)
        if (ver != null) {
            b.planVersion(ver)
        }
        String hash = candidate.planHash ?: priorConflict.planHash
        if (hash) {
            b.planHash(hash)
        }
        return b.build()
    }

    /**
     * Preserve proposed id only when nonblank and unused; otherwise allocate a derived
     * collision-free id under the store lock. Returned record carries the durable id so
     * Approval binds the actual persisted id.
     */
    private DecisionRecord ensureUniquePersistedIdUnlocked(DecisionRecord candidate, String prefix) {
        String proposed = candidate?.id
        if (proposed != null && !proposed.trim().isEmpty() && !decisionIdExistsUnlocked(proposed.trim())) {
            return candidate.id == proposed.trim() ? candidate : candidate.withId(proposed.trim())
        }
        String id = allocateDerivedUniqueIdUnlocked(prefix, semanticIdMaterial(candidate, null, 'accept'))
        return candidate.withId(id)
    }

    /**
     * Store-atomic unique id helper for accepted collision, replay, and conflict.
     * No System.nanoTime. Prefix + SHA-256 of stable semantic material + collision counter.
     */
    private String allocateDerivedUniqueIdUnlocked(String prefix, String semanticMaterial) {
        String pfx = (prefix == null || prefix.isEmpty()) ? 'dec-' : prefix
        String hex = sha256Hex(semanticMaterial ?: '')
        int counter = 0
        while (counter <= 10000) {
            String id = counter == 0
                ? "${pfx}${hex.substring(0, 24)}"
                : "${pfx}${hex.substring(0, 20)}-${counter}"
            if (!decisionIdExistsUnlocked(id)) {
                return id
            }
            counter++
        }
        throw new PlanStoreException(
            "Unable to allocate unique decision id with prefix ${pfx}",
            directory.toString(), 'save')
    }

    private static String semanticIdMaterial(DecisionRecord candidate, DecisionRecord prior,
                                             String kind) {
        return [
            kind ?: '',
            nz(candidate?.correlationId),
            nz(prior?.id),
            nz(candidate?.id),
            nz(candidate?.action),
            nz(candidate?.actorId),
            nz(candidate?.planId),
            candidate?.planVersion != null ? String.valueOf(candidate.planVersion) : '',
            nz(candidate?.planHash),
            nz(candidate?.status),
            nz(candidate?.proposalId)
        ].join('|')
    }

    private boolean decisionIdExistsUnlocked(String decisionId) {
        if (decisionId == null || decisionId.trim().isEmpty()) {
            return false
        }
        String id = decisionId.trim()
        Map index = loadIndexUnlocked(true)
        if (index.entries instanceof Collection) {
            for (def e : (index.entries as Collection)) {
                if (e instanceof Map && e.id?.toString() == id) {
                    return true
                }
            }
        }
        if (Files.exists(decisionPath(id))) {
            return true
        }
        // Collision-free path suffixes (decision-<enc>-N.json) from legacy path allocation
        String base = ApplicationStateStore.encodeKey(id)
        if (Files.isDirectory(directory)) {
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "decision-${base}*.json")
                try {
                    for (Path path : stream) {
                        String name = path.fileName.toString()
                        if (name == "decision-${base}.json" || name.startsWith("decision-${base}-")) {
                            return true
                        }
                    }
                } finally {
                    stream.close()
                }
            } catch (Exception ignored) {
            }
        }
        return false
    }

    private static String sha256Hex(String raw) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] dig = md.digest((raw ?: '').getBytes(StandardCharsets.UTF_8))
        return dig.collect { String.format('%02x', it & 0xff) }.join()
    }

    private void appendUnlocked(DecisionRecord decision) {
        if (decisionIdExistsUnlocked(decision.id)) {
            throw new PlanStoreException(
                "Decision id already exists (append-only): ${decision.id}",
                decisionPath(decision.id).toString(), 'save')
        }
        Path primary = decisionPath(decision.id)
        if (Files.exists(primary)) {
            // Path occupied by different content — allocate unique path (id already unique)
            primary = allocateUniquePath(decision.id)
        }
        // Data first — never index before durable decision file
        atomicWriteJsonUnlocked(primary, JsonOutput.prettyPrint(JsonOutput.toJson(decision.toMap())))
        if (afterDataBeforeIndexHook != null) {
            afterDataBeforeIndexHook.run()
        }
        Map index = loadIndexUnlocked(false)
        mergeDecisionIntoIndex(index, decision, primary.fileName.toString())
        index.schemaVersion = SCHEMA_VERSION
        atomicWriteJsonUnlocked(indexPath(), JsonOutput.prettyPrint(JsonOutput.toJson(index)))
    }

    private List<DecisionRecord> listForCorrelationUnlocked(String correlationId) {
        if (!correlationId) {
            return []
        }
        Map index = loadIndexUnlocked(true)
        List ids = []
        if (index.byCorrelation instanceof Map && index.byCorrelation[correlationId] instanceof List) {
            ids = new ArrayList(index.byCorrelation[correlationId] as List)
        }
        return ids.collect { loadUnlocked(it.toString()) }.findAll { it != null }
    }

    /**
     * Immutable outcome of {@link #appendClassified}.
     */
    static final class DecisionAppendOutcome {
        enum Kind {
            NEW_ACCEPTED,
            NEW_RECORD,
            IDEMPOTENT_REPLAY,
            CONFLICT
        }

        final Kind kind
        /** Record persisted by this call (accepted, replay, conflict, or rejected). */
        final DecisionRecord persisted
        /** Prior ACCEPTED record when replay or conflict; null for new. */
        final DecisionRecord existing
        final boolean accepted
        final boolean replayed
        final boolean conflict

        private DecisionAppendOutcome(Kind kind, DecisionRecord persisted,
                                      DecisionRecord existing,
                                      boolean accepted, boolean replayed, boolean conflict) {
            this.kind = kind
            this.persisted = persisted
            this.existing = existing
            this.accepted = accepted
            this.replayed = replayed
            this.conflict = conflict
        }

        static DecisionAppendOutcome newAccepted(DecisionRecord persisted) {
            new DecisionAppendOutcome(Kind.NEW_ACCEPTED, persisted, null, true, false, false)
        }

        /** Non-accepted new record (e.g. REJECTED_MALFORMED) persisted under lock. */
        static DecisionAppendOutcome newRecord(DecisionRecord persisted) {
            new DecisionAppendOutcome(Kind.NEW_RECORD, persisted, null, false, false, false)
        }

        static DecisionAppendOutcome idempotentReplay(DecisionRecord replay, DecisionRecord prior) {
            new DecisionAppendOutcome(Kind.IDEMPOTENT_REPLAY, replay, prior, false, true, false)
        }

        static DecisionAppendOutcome conflict(DecisionRecord conflictRec, DecisionRecord prior) {
            new DecisionAppendOutcome(Kind.CONFLICT, conflictRec, prior, false, false, true)
        }

        boolean isNewAccepted() { kind == Kind.NEW_ACCEPTED }
        boolean isIdempotentReplay() { kind == Kind.IDEMPOTENT_REPLAY }
        boolean isConflict() { kind == Kind.CONFLICT }
    }

    /**
     * Scan orphan decision files and merge into index. Automatic on load; explicit API too.
     * @return number of orphan decisions merged
     */
    int repairIndex() {
        withStoreLock {
            Map index = loadIndexUnlocked(false)
            return recoverOrphansUnlocked(index, true)
        }
    }

    DecisionRecord load(String decisionId) {
        if (!decisionId) {
            return null
        }
        withStoreLock {
            loadUnlocked(decisionId)
        }
    }

    /**
     * Decisions for a proposal id, append order.
     */
    List<DecisionRecord> listForProposal(String proposalId) {
        if (!proposalId) {
            return []
        }
        withStoreLock {
            Map index = loadIndexUnlocked(true)
            List ids = []
            if (index.byProposal instanceof Map && index.byProposal[proposalId] instanceof List) {
                ids = new ArrayList(index.byProposal[proposalId] as List)
            } else if (index.entries instanceof Collection) {
                (index.entries as Collection).each { e ->
                    if (e instanceof Map && e.proposalId?.toString() == proposalId && e.id) {
                        ids << e.id.toString()
                    }
                }
            }
            return ids.collect { loadUnlocked(it.toString()) }.findAll { it != null }
        }
    }

    /**
     * Decisions sharing a correlation id (replay detection).
     */
    List<DecisionRecord> listForCorrelation(String correlationId) {
        if (!correlationId) {
            return []
        }
        withStoreLock {
            Map index = loadIndexUnlocked(true)
            List ids = []
            if (index.byCorrelation instanceof Map && index.byCorrelation[correlationId] instanceof List) {
                ids = new ArrayList(index.byCorrelation[correlationId] as List)
            }
            return ids.collect { loadUnlocked(it.toString()) }.findAll { it != null }
        }
    }

    List<String> listIds() {
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

    private void mergeDecisionIntoIndex(Map index, DecisionRecord decision, String fileName) {
        List entries = index.entries instanceof List ? new ArrayList(index.entries as List) : []
        boolean exists = entries.any { e ->
            e instanceof Map && e.id?.toString() == decision.id
        }
        if (!exists) {
            entries << [
                id           : decision.id,
                file         : fileName,
                proposalId   : decision.proposalId,
                action       : decision.action,
                status       : decision.status,
                correlationId: decision.correlationId,
                actorId      : decision.actorId
            ]
            index.entries = entries
        }
        if (decision.correlationId) {
            Map byCorr = index.byCorrelation instanceof Map
                ? new LinkedHashMap(index.byCorrelation as Map) : new LinkedHashMap()
            List corrList = byCorr[decision.correlationId] instanceof List
                ? new ArrayList(byCorr[decision.correlationId] as List) : []
            if (!corrList.contains(decision.id)) {
                corrList << decision.id
            }
            byCorr[decision.correlationId] = corrList
            index.byCorrelation = byCorr
        }
        if (decision.proposalId) {
            Map byProp = index.byProposal instanceof Map
                ? new LinkedHashMap(index.byProposal as Map) : new LinkedHashMap()
            List propList = byProp[decision.proposalId] instanceof List
                ? new ArrayList(byProp[decision.proposalId] as List) : []
            if (!propList.contains(decision.id)) {
                propList << decision.id
            }
            byProp[decision.proposalId] = propList
            index.byProposal = byProp
        }
    }

    private DecisionRecord loadUnlocked(String decisionId) {
        Map index = loadIndexUnlocked(true)
        if (index.entries instanceof Collection) {
            for (def e : (index.entries as Collection)) {
                if (e instanceof Map && e.id?.toString() == decisionId && e.file) {
                    Path p = StorePaths.resolveContained(directory, e.file.toString(), 'decision-index')
                    if (Files.exists(p)) {
                        return loadFromPathUnlocked(p)
                    }
                }
            }
        }
        Path primary = decisionPath(decisionId)
        if (Files.exists(primary)) {
            return loadFromPathUnlocked(primary)
        }
        return null
    }

    private DecisionRecord loadFromPathUnlocked(Path p) {
        try {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
            if (text.trim().isEmpty()) {
                throw new PlanStoreException('Decision file empty', p.toString(), 'parse')
            }
            def root = new JsonSlurper().parseText(text)
            if (!(root instanceof Map)) {
                throw new PlanStoreException('Decision root must be object', p.toString(), 'parse')
            }
            return DecisionRecord.fromMap(root as Map)
        } catch (PlanStoreException e) {
            throw e
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid decision at ${p}: ${e.message}", p.toString(), 'parse', e)
        }
    }

    private Map loadIndexUnlocked(boolean recoverOrphans) {
        Path p = indexPath()
        Map m
        if (!Files.exists(p)) {
            m = [schemaVersion: SCHEMA_VERSION, entries: [], byCorrelation: [:], byProposal: [:]]
        } else {
            try {
                def root = new JsonSlurper().parseText(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                if (!(root instanceof Map)) {
                    throw new PlanStoreException('Decision index root must be object', p.toString(), 'parse')
                }
                m = root as Map
                if (m.entries == null) m.entries = []
                if (m.byCorrelation == null) m.byCorrelation = [:]
                if (m.byProposal == null) m.byProposal = [:]
            } catch (PlanStoreException e) {
                throw e
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Malformed decision index: ${e.message}", p.toString(), 'parse', e)
            }
        }
        if (recoverOrphans) {
            recoverOrphansUnlocked(m, true)
        }
        return m
    }

    private int recoverOrphansUnlocked(Map index, boolean persistIfChanged) {
        if (!Files.isDirectory(directory)) {
            return 0
        }
        Set<String> knownIds = new HashSet<>()
        Set<String> knownFiles = new HashSet<>()
        if (index.entries instanceof Collection) {
            (index.entries as Collection).each { e ->
                if (e instanceof Map) {
                    if (e.id) knownIds << e.id.toString()
                    if (e.file) knownFiles << e.file.toString()
                }
            }
        }
        int merged = 0
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(directory, 'decision-*.json')
            try {
                List<Path> orphans = []
                for (Path path : stream) {
                    String name = path.fileName.toString()
                    if (!DECISION_FILE_PATTERN.matcher(name).matches()) {
                        continue
                    }
                    if (knownFiles.contains(name)) {
                        continue
                    }
                    if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        continue
                    }
                    if (Files.isSymbolicLink(path)) {
                        continue
                    }
                    orphans << path
                }
                orphans.sort { a, b -> a.fileName.toString() <=> b.fileName.toString() }
                for (Path path : orphans) {
                    try {
                        DecisionRecord d = loadFromPathUnlocked(path)
                        if (d == null || !d.id || knownIds.contains(d.id)) {
                            continue
                        }
                        mergeDecisionIntoIndex(index, d, path.fileName.toString())
                        knownIds << d.id
                        knownFiles << path.fileName.toString()
                        merged++
                    } catch (Exception ignored) {
                        // reject corruption
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
            }
        }
        return merged
    }

    private Path allocateUniquePath(String decisionId) {
        String base = ApplicationStateStore.encodeKey(decisionId)
        int n = 1
        while (true) {
            Path candidate = directory.resolve("decision-${base}-${n}.json")
            if (!Files.exists(candidate)) {
                return candidate
            }
            n++
            if (n > 10000) {
                throw new PlanStoreException(
                    "Unable to allocate unique decision path for ${decisionId}",
                    directory.toString(), 'save')
            }
        }
    }

    private <T> T withStoreLock(Closure<T> action) {
        try {
            Files.createDirectories(directory)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Failed to create decision store directory: ${directory}",
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
            throw new IllegalStateException('DecisionStore lock must not nest on the same thread')
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
            } catch (IllegalStateException | IllegalArgumentException | PlanStoreException e) {
                throw e
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Failed under decision store lock: ${e.message}", lp.toString(), 'lock', e)
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
                "Failed to save decision to ${target}: ${e.message}", target.toString(), 'save', e)
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
