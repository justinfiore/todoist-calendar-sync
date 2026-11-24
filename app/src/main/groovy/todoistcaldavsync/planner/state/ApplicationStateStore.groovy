package todoistcaldavsync.planner.state

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.domain.ApplicationReceipt
import todoistcaldavsync.planner.domain.AppliedMapping
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ProjectionMetadata

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Atomic local persistence for application mappings and append-only receipts.
 * Process-wide filesystem locking (FileChannel/FileLock) around read-modify-write.
 * Machine state uses ISO-8601 JSON. Collision-free paths via SHA-256 of keys.
 *
 * <p><b>Locking invariant:</b> public lock-taking methods ({@link #loadMappings},
 * {@link #saveMappings}, {@link #putMapping}, {@link #saveReceipt}, {@link #loadReceipt},
 * {@link #listReceiptIds}) must not call each other while already holding the store lock
 * on the same thread. Nested acquisition is rejected with {@link IllegalStateException}.
 * Internal helpers are unlocked and may only run under an outer {@code withStoreLock}.
 * Prefer {@link #putMapping} for single-key merges; avoid stale full-snapshot
 * {@link #saveMappings} after concurrent writers.
 */
class ApplicationStateStore {
    static final int SCHEMA_VERSION = 1
    static final int FILENAME_HASH_HEX_CHARS = 16

    private final Path directory
    private final Runnable beforeMoveHook
    private final ThreadLocal<Boolean> mutationHeld = ThreadLocal.withInitial { false }

    /**
     * Process-wide mutex registry keyed by absolute lock-file path (cross-instance same JVM).
     * Not reentrant across public APIs — see class javadoc.
     */
    private static final ConcurrentHashMap<String, Object> PROCESS_LOCKS = new ConcurrentHashMap<>()

    /** Tracks store-lock hold depth per thread to reject nested public lock entry. */
    private static final ThreadLocal<IdentityHashMap<Object, Integer>> LOCK_HOLD_DEPTH =
        ThreadLocal.withInitial { new IdentityHashMap<>() }

    ApplicationStateStore(Path directory) {
        this(directory, null)
    }

    ApplicationStateStore(Path directory, Runnable beforeMoveHook) {
        if (directory == null) {
            throw new IllegalArgumentException('directory is required')
        }
        this.directory = directory
        this.beforeMoveHook = beforeMoveHook
    }

    Path getDirectory() {
        directory
    }

    Path mappingsPath() {
        directory.resolve('mappings.json')
    }

    Path mappingHistoryPath() { directory.resolve('mapping-history.json') }

    Path syncOperationsPath() { directory.resolve('sync-operations.json') }

    Path projectionsPath() { directory.resolve('projections.json') }

    Map<String, Map> loadProjections() {
        withStoreLock { immutableJson(loadProjectionsUnlocked()) }
    }

    boolean syncProjectionsCommitted(String operationId) {
        withStoreLock {
            loadProjectionsUnlocked()
            committedProjectionOperationsUnlocked().contains(operationId)
        }
    }

    private List committedProjectionOperationsUnlocked() {
        Files.exists(projectionsPath()) ?
            ((new JsonSlurper().parse(projectionsPath().toFile()) as Map).committedOperations ?: []) as List : []
    }

    /** Membership commits are atomic across the entire replacement set, after verified remote outcomes. */
    void commitProjections(Collection<CalendarEvent> verified, Collection<CalendarEvent> retired = [],
                           String operationId = null) {
        withStoreLock {
            Map operation = operationId != null ? loadSyncOperationsUnlocked()[operationId] : null
            if ((operationId != null && operation?.status != 'verified') || (retired && operation == null)) {
                throw new IllegalStateException('projection membership requires a fully verified operation')
            }
            Map records = loadProjectionsUnlocked()
            retired.each { CalendarEvent event ->
                String key = projectionKey(event)
                Map previous = records[key]
                if (previous != null && previous.operationId != operationId &&
                    (previous.status in ['retired', 'cancelled'] ||
                        ProjectionMetadata.read(ProjectionMetadata.eventFromMap(previous.event as Map)).value.revision >
                            ProjectionMetadata.read(event).value.revision)) {
                    throw new IllegalStateException('newer local projection state blocks stale retirement replay')
                }
                String resource = 'calendar:' + event.calendarName + ':' + event.id
                if (operation.intent.resources[resource]?.event != ProjectionMetadata.eventMap(event) ||
                    operation.intent.resources[resource]?.action != 'retire' ||
                    operation.outcomes[resource]?.evidence?.absent != true) {
                    throw new IllegalStateException('retirement does not match verified journal evidence')
                }
                records[key] = [event: ProjectionMetadata.eventMap(event), status: 'retired', operationId: operationId]
            }
            verified.each { CalendarEvent event ->
                ProjectionMetadata metadata = ProjectionMetadata.read(event)
                if (!event.etag || event.start.toString() != metadata.value.members[0].start ||
                    event.end.toString() != metadata.value.members[-1].end || event.allDay != metadata.value.allDay) {
                    throw new IllegalStateException('projection baseline requires verified schedule and provider version')
                }
                if (operation != null && !operation.outcomes.values().any { it.evidence?.event == ProjectionMetadata.eventMap(event) }) {
                    throw new IllegalStateException('projection does not match verified journal evidence')
                }
                Map previous = records[projectionKey(event)]
                boolean recreated = previous?.status == 'retired' && operation != null &&
                    operation.intent.retiredBaselines?.get(projectionKey(event)) == previous
                if (previous != null && previous.operationId != operationId &&
                    ((previous.status == 'retired' && !recreated) || previous.status == 'cancelled' ||
                        (!recreated && ProjectionMetadata.read(ProjectionMetadata.eventFromMap(previous.event as Map)).value.revision > metadata.value.revision))) {
                    throw new IllegalStateException('newer local projection state blocks stale operation replay')
                }
                if (operation == null && previous != null && (previous.status in ['retired', 'cancelled'] ||
                    previous.event != ProjectionMetadata.eventMap(event))) {
                    throw new IllegalStateException('portable and local baselines disagree; reconciliation required')
                }
                records[projectionKey(event)] = [event: ProjectionMetadata.eventMap(event),
                    status: metadata.value.historical ? 'historical' : 'current', operationId: operationId]
            }
            validateProjections(records)
            List committed = committedProjectionOperationsUnlocked()
            if (operationId != null && !committed.contains(operationId)) committed << operationId
            atomicWriteJsonUnlocked(projectionsPath(), JsonOutput.toJson([
                schemaVersion: 1, records: records, committedOperations: committed]))
        }
    }

    void commitSyncProjections(String operationId) {
        Map operation = loadSyncOperations()[operationId]
        if (operation?.status != 'verified') throw new IllegalStateException('verified operation required')
        List<CalendarEvent> verified = operation.outcomes.values().findAll { it.evidence?.event instanceof Map }
            .collect { ProjectionMetadata.eventFromMap(it.evidence.event as Map) }
        List<CalendarEvent> retired = operation.intent.resources.values().findAll { it.action == 'retire' }
            .collect { ProjectionMetadata.eventFromMap(it.event as Map) }
        commitProjections(verified, retired, operationId)
    }

    void cancelProjection(CalendarEvent expected) {
        withStoreLock {
            Map records = loadProjectionsUnlocked()
            Map record = records[projectionKey(expected)]
            if (record?.status != 'current' || record.event != ProjectionMetadata.eventMap(expected)) {
                throw new IllegalStateException('cancellation requires the exact verified current baseline')
            }
            record.status = 'cancelled'
            atomicWriteJsonUnlocked(projectionsPath(), JsonOutput.toJson([
                schemaVersion: 1, records: records, committedOperations: committedProjectionOperationsUnlocked()]))
        }
    }

    private Map loadProjectionsUnlocked() {
        if (!Files.exists(projectionsPath())) return [:]
        try {
            Object root = new JsonSlurper().parseText(Files.readString(projectionsPath(), StandardCharsets.UTF_8))
            if (!(root instanceof Map) || root.schemaVersion != 1 || !(root.records instanceof Map) ||
                (root.containsKey('committedOperations') && (!(root.committedOperations instanceof List) ||
                    root.committedOperations.any { !(it instanceof String) || !it }))) {
                throw new IllegalArgumentException('unsupported projection state')
            }
            validateProjections(root.records as Map)
            root.records as Map
        } catch (Exception e) {
            throw new PlanStoreException('Projection state is corrupt; reconcile/restore before mutation',
                projectionsPath().toString(), 'load', e)
        }
    }

    private static void validateProjections(Map records) {
        Set<String> current = [] as Set
        records.each { String key, Object record ->
            if (!(record instanceof Map) || !(record.event instanceof Map) ||
                !(record.status in ['current', 'historical', 'retired', 'cancelled'])) {
                throw new IllegalArgumentException('invalid projection record')
            }
            CalendarEvent event = ProjectionMetadata.eventFromMap(record.event as Map)
            ProjectionMetadata metadata = ProjectionMetadata.read(event)
            if (key != projectionKey(event) || !event.etag ||
                event.start.toString() != metadata.value.members[0].start ||
                event.end.toString() != metadata.value.members[-1].end || event.allDay != metadata.value.allDay ||
                (record.status == 'historical' && !metadata.value.historical) ||
                (record.status == 'current' && metadata.value.historical)) {
                throw new IllegalArgumentException('projection baseline identity/schedule mismatch')
            }
            if (record.status == 'current') {
                metadata.value.members.each { Map member ->
                    if (!current.add(member.occurrenceKey.toString())) {
                        throw new IllegalArgumentException('duplicate active occurrence coverage requires reconciliation')
                    }
                }
            }
        }
    }

    static String projectionKey(CalendarEvent event) { event.calendarName + '\u0000' + event.id }

    /** Intent is a JSON snapshot, never a mutable reference to partially updated provider data. */
    Map beginSyncOperation(String id, Map intent) {
        if (!id || !intent || !(intent.resources instanceof Map) || !intent.resources ||
            !(intent.taskIds instanceof Collection) || !intent.taskIds ||
            intent.taskIds.any { !(it instanceof String) || !it } ||
            intent.resources.keySet().any { !(it instanceof String) || !it }) {
            throw new IllegalArgumentException('sync operation requires id, taskIds and resource intents')
        }
        Map frozenIntent = jsonCopy(intent)
        withStoreLock {
            Map operations = loadSyncOperationsUnlocked()
            if (operations.containsKey(id)) {
                Map original = jsonCopy(operations[id].intent as Map)
                original.remove('retiredBaselines')
                if (original != frozenIntent) {
                    throw new IllegalStateException('sync operation intent cannot change')
                }
                return immutableJson(operations[id])
            }
            operations.values().findAll { it.status != 'verified' }.each { Map pending ->
                if (pending.intent.taskIds.intersect(frozenIntent.taskIds) ||
                    pending.intent.resources.keySet().intersect(frozenIntent.resources.keySet())) {
                    throw new IllegalStateException('overlapping sync operation requires reconciliation')
                }
            }
            // A deterministic individual/group ID may legitimately return after regrouping.
            // Bind that new lifetime to its exact tombstone and live retiring source membership;
            // a historical successful journal can never resurrect a later tombstone/cancellation.
            if (frozenIntent.containsKey('retiredBaselines')) throw new IllegalArgumentException('retired baselines are store-owned evidence')
            Map records = loadProjectionsUnlocked(), baselines = [:]
            Set<String> replacing = frozenIntent.resources.values().findAll { it.action == 'retire' }.collectMany { Map resource ->
                CalendarEvent source = ProjectionMetadata.eventFromMap(resource.event as Map)
                Map record = records[projectionKey(source)]
                record?.status == 'current' && record.event == resource.event ? ProjectionMetadata.read(source).value.members*.occurrenceKey : []
            } as Set<String>
            frozenIntent.resources.values().findAll { it.action == 'create' }.each { Map resource ->
                String destination = (resource.event?.calendarName ?: resource.destination)?.toString()
                String resourceId = (resource.event?.id ?: resource.id)?.toString()
                String key = destination + '\u0000' + resourceId
                Map previous = records[key]
                if (previous?.status == 'cancelled') throw new IllegalStateException('cancelled projection requires explicit reconciliation')
                if (previous?.status != 'retired') return
                CalendarEvent before = ProjectionMetadata.eventFromMap(previous.event as Map)
                if (!(frozenIntent.type in ['projection-replacement', 'routed-plan']) ||
                    before.uid != (resource.event?.uid ?: resource.uid) ||
                    !replacing.containsAll(ProjectionMetadata.read(before).value.members*.occurrenceKey)) {
                    throw new IllegalStateException('retired resource requires current journal-bound replacement membership')
                }
                baselines[key] = previous
            }
            if (baselines) frozenIntent.retiredBaselines = baselines
            Map operation = [id: id, intent: frozenIntent, status: 'pending',
                outcomes: frozenIntent.resources.collectEntries { key, value -> [(key): [state: 'pending']] }]
            operations[id] = operation
            saveSyncOperationsUnlocked(operations)
            immutableJson(operation)
        }
    }

    /** Unknown outcomes can become verified only through caller-supplied live readback evidence. */
    Map checkpointSyncResource(String id, String resource, String state, Map evidence = [:]) {
        if (!(state in ['pending', 'verified', 'conflict', 'unknown', 'failed']) ||
            (state == 'verified' && !evidence)) {
            throw new IllegalArgumentException('resource outcome requires a valid state and verified evidence')
        }
        withStoreLock {
            Map operations = loadSyncOperationsUnlocked()
            Map operation = operations[id]
            if (operation == null || !operation.outcomes.containsKey(resource)) {
                throw new IllegalArgumentException('sync operation/resource is not journaled')
            }
            Map outcome = [state: state, evidence: jsonCopy(evidence)]
            if (operation.outcomes[resource].state == 'verified' && operation.outcomes[resource] != outcome) {
                throw new IllegalStateException('verified resource outcomes are immutable')
            }
            operation.outcomes[resource] = outcome
            operation.status = operation.outcomes.values().every { it.state == 'verified' } ? 'verified' : 'pending'
            saveSyncOperationsUnlocked(operations)
            immutableJson(operation)
        }
    }

    Map<String, Map> loadSyncOperations() {
        withStoreLock { immutableJson(loadSyncOperationsUnlocked()) }
    }

    /** Hold across live preflight, mutation and verification; a second writer fails, not waits. */
    <T> T withMutationLock(Closure<T> action) {
        if (action == null) throw new IllegalArgumentException('mutation action is required')
        // Composition may invoke a journaled service under this same store's outer writer lease.
        // Another store instance/thread still has to acquire the OS lock and must fail closed.
        if (mutationHeld.get()) return action.call()
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve('.mutation.lock'),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).withCloseable { FileChannel channel ->
            FileLock lock
            try { lock = channel.tryLock() }
            catch (java.nio.channels.OverlappingFileLockException ignored) {
                throw new IllegalStateException('another planner writer owns this application state')
            }
            if (lock == null) throw new IllegalStateException('another planner writer owns this application state')
            mutationHeld.set(true)
            try { action.call() }
            finally { mutationHeld.remove(); lock.release() }
        }
    }

    private Map loadSyncOperationsUnlocked() {
        Path path = syncOperationsPath()
        if (!Files.exists(path)) return [:]
        try {
            Object root = new JsonSlurper().parseText(Files.readString(path, StandardCharsets.UTF_8))
            if (!(root instanceof Map) || root.schemaVersion != 1 || !(root.operations instanceof Map)) {
                throw new IllegalArgumentException('unsupported sync journal schema')
            }
            root.operations.each { id, operation ->
                if (!(operation instanceof Map) || operation.id != id ||
                    !(operation.intent instanceof Map) || !(operation.intent.resources instanceof Map) ||
                    !operation.intent.resources || !(operation.intent.taskIds instanceof Collection) ||
                    !operation.intent.taskIds || !(operation.outcomes instanceof Map) ||
                    operation.outcomes.keySet() != operation.intent.resources.keySet() ||
                    !(operation.status in ['pending', 'verified']) ||
                    operation.outcomes.values().any { !(it instanceof Map) ||
                        !(it.state in ['pending', 'verified', 'conflict', 'unknown', 'failed']) ||
                        (it.state == 'verified' && !(it.evidence instanceof Map && it.evidence)) } ||
                    (operation.status == 'verified') != operation.outcomes.values().every { it.state == 'verified' }) {
                    throw new IllegalArgumentException('invalid sync operation')
                }
            }
            root.operations as Map
        } catch (Exception e) {
            throw new PlanStoreException('Sync journal is corrupt; restore/reconcile before mutations',
                path.toString(), 'load', e)
        }
    }

    private void saveSyncOperationsUnlocked(Map operations) {
        atomicWriteJsonUnlocked(syncOperationsPath(), JsonOutput.toJson([schemaVersion: 1, operations: operations]))
    }

    private static Map jsonCopy(Map value) {
        new JsonSlurper().parseText(JsonOutput.toJson(value)) as Map
    }

    private static Object immutableJson(Object value) {
        if (value instanceof Map) return Collections.unmodifiableMap(value.collectEntries { k, v -> [(k): immutableJson(v)] })
        if (value instanceof Collection) return Collections.unmodifiableList(value.collect { immutableJson(it) })
        value
    }

    Path receiptPath(String receiptId) {
        directory.resolve("receipt-${encodeKey(receiptId)}.json")
    }

    Path receiptIndexPath() {
        directory.resolve('receipt-index.json')
    }

    private Path lockPath() {
        directory.resolve('.app-state.lock')
    }

    /**
     * Load all task→mapping entries. Missing file → empty map. Corrupt → exception.
     */
    Map<String, AppliedMapping> loadMappings() {
        withStoreLock {
            loadMappingsUnlocked()
        }
    }

    AppliedMapping loadMapping(String taskId) {
        loadMappings()[taskId]
    }

    /**
     * Atomically replace the full mappings snapshot (deterministic key order).
     */
    void saveMappings(Map<String, AppliedMapping> mappings) {
        withStoreLock {
            saveMappingsUnlocked(mappings)
        }
    }

    /**
     * Upsert one mapping and persist atomically under a single lock
     * (read-modify-write without nested deadlock).
     */
    void putMapping(AppliedMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException('mapping is required')
        }
        withStoreLock {
            Map<String, AppliedMapping> all = new LinkedHashMap<>(loadMappingsUnlocked())
            all[mapping.taskId] = mapping
            saveMappingsUnlocked(all)
            Map<String, AppliedMapping> history = loadMappingHistoryUnlocked()
            history[mapping.occurrenceKey] = mapping
            saveMappingHistoryUnlocked(history)
        }
    }

    Map<String, AppliedMapping> loadHistoricalMappings() {
        withStoreLock {
            Map<String, AppliedMapping> history = loadMappingHistoryUnlocked()
            boolean migrated = false
            loadMappingsUnlocked().values().each { AppliedMapping active ->
                if (!history.containsKey(active.occurrenceKey)) {
                    history[active.occurrenceKey] = active
                    migrated = true
                }
            }
            if (migrated) saveMappingHistoryUnlocked(history)
            Collections.unmodifiableMap(history)
        }
    }

    /**
     * Persist receipt append-only. Never overwrites an existing receipt id/path;
     * allocates a unique path via index when the primary path is already taken.
     */
    void saveReceipt(ApplicationReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException('receipt is required')
        }
        withStoreLock {
            Path primary = receiptPath(receipt.id)
            Path target = primary
            if (Files.exists(primary)) {
                // Collision: same receipt id content path already used — allocate unique sibling
                target = allocateUniqueReceiptPath(receipt.id)
            }
            atomicWriteJsonUnlocked(target, JsonOutput.prettyPrint(JsonOutput.toJson(receipt.toMap())))
            appendReceiptIndexUnlocked(receipt.id, target.fileName.toString())
        }
    }

    ApplicationReceipt loadReceipt(String receiptId) {
        if (receiptId == null) {
            return null
        }
        withStoreLock {
            // Prefer index entries (handles collision-allocated paths), fall back to primary path
            List<Path> candidates = receiptPathsForIdUnlocked(receiptId)
            for (Path target : candidates) {
                if (Files.exists(target)) {
                    String text
                    try {
                        text = new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
                    } catch (Exception e) {
                        throw new PlanStoreException(
                            "Failed to read receipt '${receiptId}' from ${target}: ${e.message}",
                            target.toString(), 'load', e)
                    }
                    return parseReceipt(text, target.toString())
                }
            }
            return null
        }
    }

    /**
     * List receipt ids from filenames/index, sorted. Corrupt files skipped for listing.
     */
    List<String> listReceiptIds() {
        withStoreLock {
            if (!Files.isDirectory(directory)) {
                return []
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>()
            // Index first (preserves append order of allocation)
            Path index = receiptIndexPath()
            if (Files.exists(index)) {
                try {
                    def root = new JsonSlurper().parseText(
                        new String(Files.readAllBytes(index), StandardCharsets.UTF_8))
                    if (root instanceof Map && root.entries instanceof Collection) {
                        (root.entries as Collection).each { e ->
                            if (e instanceof Map && e.id) {
                                ids << e.id.toString()
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // fall through to directory scan
                }
            }
            Files.list(directory).withCloseable { stream ->
                stream.each { Path p ->
                    def name = p.fileName.toString()
                    def m = name =~ /^receipt-(.+)\.json$/
                    if (!m.matches()) {
                        return
                    }
                    try {
                        ApplicationReceipt r = loadReceiptFromPathUnlocked(p)
                        if (r?.id) {
                            ids << r.id
                        }
                    } catch (Exception ignored) {
                        // skip corrupt for listing
                    }
                }
            }
            return ids.toList().toSorted()
        }
    }

    List<ApplicationReceipt> listReceipts() {
        listReceiptIds().collect { loadReceipt(it) }.findAll { it != null }
    }

    private Map<String, AppliedMapping> loadMappingsUnlocked() {
        Path target = mappingsPath()
        if (!Files.exists(target)) {
            return new LinkedHashMap<>()
        }
        String text
        try {
            text = new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Failed to read mappings from ${target}: ${e.message}", target.toString(), 'load', e)
        }
        return parseMappings(text, target.toString())
    }

    private void saveMappingsUnlocked(Map<String, AppliedMapping> mappings) {
        Map<String, AppliedMapping> safe = mappings ?: [:]
        Map root = [
            schemaVersion: SCHEMA_VERSION,
            mappings     : safe.keySet().toSorted().collect { String taskId ->
                safe[taskId].toMap()
            }
        ]
        atomicWriteJsonUnlocked(mappingsPath(), JsonOutput.prettyPrint(JsonOutput.toJson(root)))
    }

    private Map<String, AppliedMapping> loadMappingHistoryUnlocked() {
        if (!Files.exists(mappingHistoryPath())) return new LinkedHashMap<>()
        return parseMappings(
            Files.readString(mappingHistoryPath(), StandardCharsets.UTF_8),
            mappingHistoryPath().toString(),
            true)
    }

    private void saveMappingHistoryUnlocked(Map<String, AppliedMapping> history) {
        Map root = [schemaVersion: SCHEMA_VERSION, mappings: (history ?: [:]).keySet().toSorted().collect {
            history[it].toMap()
        }]
        atomicWriteJsonUnlocked(mappingHistoryPath(), JsonOutput.prettyPrint(JsonOutput.toJson(root)))
    }

    private ApplicationReceipt loadReceiptFromPathUnlocked(Path target) {
        String text = new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
        return parseReceipt(text, target.toString())
    }

    private List<Path> receiptPathsForIdUnlocked(String receiptId) {
        List<Path> out = []
        Path index = receiptIndexPath()
        if (Files.exists(index)) {
            try {
                def root = new JsonSlurper().parseText(
                    new String(Files.readAllBytes(index), StandardCharsets.UTF_8))
                if (root instanceof Map && root.entries instanceof Collection) {
                    (root.entries as Collection).each { e ->
                        if (e instanceof Map && e.id?.toString() == receiptId && e.file) {
                            out << StorePaths.resolveContained(
                                directory, e.file.toString(), 'receipt-index')
                        }
                    }
                }
            } catch (PlanStoreException e) {
                throw e
            } catch (Exception ignored) {
            }
        }
        Path primary = receiptPath(receiptId)
        if (!out.contains(primary)) {
            out.add(0, primary)
        }
        return out
    }

    private Path allocateUniqueReceiptPath(String receiptId) {
        String base = encodeKey(receiptId)
        int n = 1
        while (true) {
            Path candidate = directory.resolve("receipt-${base}-${n}.json")
            if (!Files.exists(candidate)) {
                return candidate
            }
            n++
            if (n > 10000) {
                throw new PlanStoreException(
                    "Unable to allocate unique receipt path for ${receiptId}", directory.toString(), 'save')
            }
        }
    }

    private void appendReceiptIndexUnlocked(String receiptId, String fileName) {
        Path index = receiptIndexPath()
        List entries = []
        if (Files.exists(index)) {
            try {
                def root = new JsonSlurper().parseText(
                    new String(Files.readAllBytes(index), StandardCharsets.UTF_8))
                if (root instanceof Map && root.entries instanceof Collection) {
                    entries = new ArrayList(root.entries as Collection)
                }
            } catch (Exception ignored) {
                entries = []
            }
        }
        entries << [id: receiptId, file: fileName]
        Map root = [schemaVersion: SCHEMA_VERSION, entries: entries]
        atomicWriteJsonUnlocked(index, JsonOutput.prettyPrint(JsonOutput.toJson(root)))
    }

    private static Map<String, AppliedMapping> parseMappings(String text, String path,
                                                              boolean occurrenceKeys = false) {
        if (text == null || text.trim().isEmpty()) {
            throw new PlanStoreException('Mappings snapshot is empty or truncated', path, 'parse')
        }
        Object root
        try {
            root = new JsonSlurper().parseText(text)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Malformed or truncated JSON in mappings: ${e.message}", path, 'parse', e)
        }
        if (!(root instanceof Map)) {
            throw new PlanStoreException('Mappings root must be a JSON object', path, 'parse')
        }
        Map map = root as Map
        if (map.schemaVersion != null) {
            int ver
            try {
                ver = map.schemaVersion as int
            } catch (Exception e) {
                throw new PlanStoreException("Invalid schemaVersion: ${map.schemaVersion}", path, 'parse', e)
            }
            if (ver < 1 || ver > SCHEMA_VERSION) {
                throw new PlanStoreException(
                    "Unsupported mappings schemaVersion ${ver} (supported 1..${SCHEMA_VERSION})",
                    path, 'parse')
            }
        }
        Map<String, AppliedMapping> out = new LinkedHashMap<>()
        def list = map.mappings
        if (list == null && map instanceof Map && map.taskId != null) {
            list = []
        }
        if (!(list instanceof Collection)) {
            throw new PlanStoreException('Mappings snapshot requires mappings array', path, 'parse')
        }
        (list as Collection).each { item ->
            if (!(item instanceof Map)) {
                throw new PlanStoreException("Invalid mapping entry: ${item}", path, 'parse')
            }
            try {
                AppliedMapping am = AppliedMapping.fromMap(item as Map)
                out[occurrenceKeys ? am.occurrenceKey : am.taskId] = am
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Invalid mapping entry: ${e.message}", path, 'parse', e)
            }
        }
        return out
    }

    private static ApplicationReceipt parseReceipt(String text, String path) {
        if (text == null || text.trim().isEmpty()) {
            throw new PlanStoreException('Receipt snapshot is empty or truncated', path, 'parse')
        }
        Object root
        try {
            root = new JsonSlurper().parseText(text)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Malformed or truncated JSON in receipt: ${e.message}", path, 'parse', e)
        }
        if (!(root instanceof Map)) {
            throw new PlanStoreException('Receipt root must be a JSON object', path, 'parse')
        }
        try {
            return ApplicationReceipt.fromMap(root as Map)
        } catch (PlanStoreException e) {
            throw e
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid receipt snapshot${path ? " at ${path}" : ''}: ${e.message}",
                path, 'parse', e)
        }
    }

    /**
     * Run action under process-wide JVM monitor + exclusive FileLock.
     * Not reentrant: nested public lock-taking on the same thread is rejected.
     * Callers must use unlocked helpers inside a single lock scope, not nest public APIs.
     */
    private <T> T withStoreLock(Closure<T> action) {
        try {
            Files.createDirectories(directory)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Failed to create application state directory: ${directory}", directory.toString(), 'save', e)
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
                "ApplicationStateStore lock must not nest on the same thread; " +
                    "public lock-taking methods must not call each other while holding the store lock")
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
            } catch (IllegalStateException e) {
                throw e
            } catch (PlanStoreException e) {
                throw e
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Failed under store lock ${lp}: ${e.message}", lp.toString(), 'lock', e)
            } finally {
                depths.remove(monitor)
                if (lock != null) {
                    try {
                        lock.release()
                    } catch (Exception ignored) {
                    }
                }
                if (channel != null) {
                    try {
                        channel.close()
                    } catch (Exception ignored) {
                    }
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
            atomicReplace(temp, target)
            temp = null
        } catch (PlanStoreException e) {
            cleanupTemp(temp)
            throw e
        } catch (Exception e) {
            cleanupTemp(temp)
            throw new PlanStoreException(
                "Failed to save state to ${target}: ${e.message}", target.toString(), 'save', e)
        } finally {
            cleanupTemp(temp)
        }
    }

    private static void atomicReplace(Path temp, Path target) {
        try {
            Files.move(temp, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private static void cleanupTemp(Path temp) {
        if (temp != null) {
            try {
                Files.deleteIfExists(temp)
            } catch (Exception ignored) {
            }
        }
    }

    static String encodeKey(String key) {
        String raw = key ?: 'unknown'
        String prefix = readablePrefix(raw)
        String hash = idHashHex(raw)
        return "${prefix}-${hash}"
    }

    static String readablePrefix(String id) {
        String s = (id ?: 'unknown')
            .replace('\\', '_')
            .replace('/', '_')
            .replaceAll(/[^A-Za-z0-9._-]/, '_')
            .replaceAll(/_+/, '_')
            .replaceAll(/^_+|_+$/, '')
        if (s.isEmpty() || s == '.' || s == '..') {
            s = 'id'
        }
        if (s.length() > 48) {
            s = s.substring(0, 48)
        }
        return s
    }

    static String idHashHex(String key) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] dig = md.digest((key ?: '').getBytes(StandardCharsets.UTF_8))
        def hex = dig.collect { String.format('%02x', it & 0xff) }.join()
        return hex.substring(0, FILENAME_HASH_HEX_CHARS)
    }
}
