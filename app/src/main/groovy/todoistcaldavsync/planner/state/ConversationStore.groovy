package todoistcaldavsync.planner.state

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Restart-safe Slack conversation correlation and inbound event deduplication. */
final class ConversationStore {
    static final int SCHEMA_VERSION = 1
    private static final ConcurrentHashMap<String, Object> PROCESS_LOCKS = new ConcurrentHashMap<>()
    private final Path directory

    ConversationStore(Path directory) {
        if (directory == null) throw new IllegalArgumentException('conversation directory is required')
        this.directory = directory.toAbsolutePath().normalize()
    }

    ConversationRecord save(ConversationRecord record) {
        if (record == null) throw new IllegalArgumentException('conversation is required')
        withLock {
            Map root = loadUnlocked()
            Map conversations = root.conversations instanceof Map ? new LinkedHashMap(root.conversations as Map) : [:]
            conversations[key(record.channelId, record.threadTs)] = record.toMap()
            root.conversations = conversations
            writeUnlocked(root)
            record
        }
    }

    ConversationRecord find(String channelId, String threadTs) {
        if (!channelId || !threadTs) return null
        withLock {
            def raw = loadUnlocked().conversations?.get(key(channelId, threadTs))
            raw instanceof Map ? ConversationRecord.fromMap(raw as Map) : null
        }
    }

    boolean claimEvent(String eventId, String channelId, String messageTs, Instant at = Instant.now()) {
        String id = eventId ?: (channelId && messageTs ? "${channelId}:${messageTs}" : null)
        if (!id) return false
        withLock {
            Map root = loadUnlocked()
            Map events = root.events instanceof Map ? new LinkedHashMap(root.events as Map) : [:]
            if (events.containsKey(id)) return false
            events[id] = [at: (at ?: Instant.now()).toString(), channelId: channelId, messageTs: messageTs]
            // Bound state growth while retaining deterministic newest insertion order.
            while (events.size() > 10_000) events.remove(events.keySet().iterator().next())
            root.events = events
            writeUnlocked(root)
            true
        }
    }

    List<ConversationRecord> list() {
        withLock {
            Map values = loadUnlocked().conversations instanceof Map ? loadUnlocked().conversations as Map : [:]
            values.values().findAll { it instanceof Map }.collect { ConversationRecord.fromMap(it as Map) }
        }
    }

    private static String key(String channelId, String threadTs) { "${channelId}|${threadTs}" }
    private Path statePath() { directory.resolve('conversations.json') }
    private Path lockPath() { directory.resolve('.conversations.lock') }

    private Map loadUnlocked() {
        Path p = statePath()
        if (!Files.exists(p)) return [schemaVersion: SCHEMA_VERSION, conversations: [:], events: [:]]
        try {
            def parsed = new JsonSlurper().parseText(Files.readString(p, StandardCharsets.UTF_8))
            if (!(parsed instanceof Map)) throw new IllegalArgumentException('root must be an object')
            Map root = parsed as Map
            if (!(root.conversations instanceof Map)) root.conversations = [:]
            if (!(root.events instanceof Map)) root.events = [:]
            return root
        } catch (Exception e) {
            throw new PlanStoreException("Malformed conversation state: ${e.message}", p.toString(), 'parse', e)
        }
    }

    private void writeUnlocked(Map root) {
        Files.createDirectories(directory)
        root.schemaVersion = SCHEMA_VERSION
        Path temp = Files.createTempFile(directory, '.conversations.', '.tmp')
        try {
            Files.writeString(temp, JsonOutput.prettyPrint(JsonOutput.toJson(root)), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING)
            try (def ch = Files.newByteChannel(temp, StandardOpenOption.WRITE)) { ch.force(true) }
            try { Files.move(temp, statePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, statePath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            try { Files.deleteIfExists(temp) } catch (Exception ignored) {}
        }
    }

    private <T> T withLock(Closure<T> action) {
        Files.createDirectories(directory)
        String k = lockPath().toString()
        Object monitor = PROCESS_LOCKS.computeIfAbsent(k) { new Object() }
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(lockPath(), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                return action.call()
            }
        }
    }
}

final class ConversationRecord {
    final String channelId
    final String threadTs
    final String runName
    final String planId
    final int planVersion
    final String planHash
    final String proposalId
    final int iteration
    final String previousPlanId
    final String previousProposalId
    final Instant createdAt
    final Instant updatedAt
    final String status
    final Map overrides
    final Map pendingConfirmation

    ConversationRecord(Map values) {
        this.channelId = values.channelId?.toString()
        this.threadTs = values.threadTs?.toString()
        this.runName = values.runName?.toString()
        this.planId = values.planId?.toString()
        this.planVersion = values.planVersion as int
        this.planHash = values.planHash?.toString()
        this.proposalId = values.proposalId?.toString()
        this.iteration = values.iteration == null ? 1 : values.iteration as int
        this.previousPlanId = values.previousPlanId?.toString()
        this.previousProposalId = values.previousProposalId?.toString()
        this.createdAt = values.createdAt instanceof Instant ? values.createdAt as Instant : Instant.parse(values.createdAt.toString())
        this.updatedAt = values.updatedAt instanceof Instant ? values.updatedAt as Instant : Instant.parse(values.updatedAt.toString())
        this.status = values.status?.toString() ?: 'ACTIVE'
        this.overrides = Collections.unmodifiableMap(new LinkedHashMap(values.overrides instanceof Map ? values.overrides as Map : [:]))
        this.pendingConfirmation = Collections.unmodifiableMap(new LinkedHashMap(
            values.pendingConfirmation instanceof Map ? values.pendingConfirmation as Map : [:]))
        if (!channelId || !threadTs || !runName || !planId || planVersion < 1 || !planHash || !proposalId) {
            throw new IllegalArgumentException('conversation requires channel/thread/run and exact plan/proposal identity')
        }
    }

    ConversationRecord next(Map values) {
        new ConversationRecord([
            channelId: channelId, threadTs: threadTs, runName: runName,
            planId: values.planId, planVersion: values.planVersion, planHash: values.planHash,
            proposalId: values.proposalId, iteration: iteration + 1,
            previousPlanId: planId, previousProposalId: proposalId,
            createdAt: createdAt, updatedAt: values.updatedAt ?: Instant.now(),
            status: values.status ?: 'ACTIVE', overrides: values.overrides ?: [:], pendingConfirmation: [:]
        ])
    }

    ConversationRecord withStatus(String value, Instant at = Instant.now()) {
        new ConversationRecord(toMap() + [status: value, updatedAt: (at ?: Instant.now()).toString()])
    }

    ConversationRecord withPendingConfirmation(Map value, Instant at = Instant.now()) {
        new ConversationRecord(toMap() + [pendingConfirmation: value ?: [:], updatedAt: (at ?: Instant.now()).toString()])
    }

    Map toMap() {
        [channelId: channelId, threadTs: threadTs, runName: runName,
         planId: planId, planVersion: planVersion, planHash: planHash, proposalId: proposalId,
         iteration: iteration, previousPlanId: previousPlanId, previousProposalId: previousProposalId,
         createdAt: createdAt.toString(), updatedAt: updatedAt.toString(), status: status,
         overrides: overrides, pendingConfirmation: pendingConfirmation]
    }

    static ConversationRecord fromMap(Map map) { new ConversationRecord(map) }
}
