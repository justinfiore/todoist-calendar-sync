package todoistcaldavsync.planner.state

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.domain.ApplicationReceipt
import todoistcaldavsync.planner.domain.AppliedMapping

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

    private static Map<String, AppliedMapping> parseMappings(String text, String path) {
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
                out[am.taskId] = am
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
