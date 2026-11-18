package todoistcaldavsync.planner.feedback

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** Durable, event-scoped reply receipts and revision recovery metadata. */
final class DeadlineRiskReceipts {
    private static final ConcurrentHashMap<String, Object> PROCESS_LOCKS = new ConcurrentHashMap<>()

    /**
     * save replaces outcomes and clarification fields while retaining other record fields.
     * merge-record accepts a single-map list and recursively merges map fields; lists and scalar values replace existing values,
     * including null. The merged record is returned. Callers can persist arbitrary JSON-compatible
     * intent, prepared message and publication metadata without a separate read-modify-write.
     */
    static Object handle(Path directory, String op, String eventId, Object value = null) {
        Path dir = directory.toAbsolutePath().normalize()
        Files.createDirectories(dir)
        Path lockPath = dir.resolve('.receipts.lock')
        Object monitor = PROCESS_LOCKS.computeIfAbsent(lockPath.toString()) { new Object() }
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                Path file = dir.resolve('receipts.json')
                Map stored = Files.exists(file) ? new JsonSlurper().parse(file.toFile()) as Map : [:]
                def prior = stored[eventId]
                if (op == 'load') {
                    return prior instanceof Map ? ((prior.outcomes ?: []).findAll { it instanceof Map && it.state == 'verified' }.collect { it.commandId }.findAll { it }) as Set : [] as Set
                }
                if (op == 'load-record') return prior instanceof Map ? prior : [:]
                Map record = prior instanceof Map ? new LinkedHashMap(prior as Map) : [:]
                Object result
                if (op == 'merge-record') {
                    Object update = value instanceof List && value.size() == 1 ? value[0] : value
                    if (!(update instanceof Map)) throw new IllegalArgumentException('merge-record requires a single-map list')
                    record = mergeMaps(record, update as Map)
                    result = record
                } else if (op == 'save') {
                    if (value != null && !(value instanceof List)) throw new IllegalArgumentException('save requires a list')
                    List outcomes = value as List
                    List commandIds = (outcomes ?: []).collect { it instanceof Map ? it.commandId : it['commandId'] }.findAll { it }
                    record.commandIds = commandIds
                    record.outcomes = outcomes
                    // The old save replaced the whole record, clearing pending clarification on resolution.
                    ['pending', 'originalReply', 'replyTime', 'proposalId', 'clarification'].each { record.remove(it) }
                    if (outcomes && outcomes[0] instanceof Map && outcomes[0].pending == true) {
                        record.pending = true
                        record.originalReply = outcomes[0].originalReply
                        record.replyTime = outcomes[0].replyTime
                        record.proposalId = outcomes[0].proposalId
                        record.clarification = outcomes[0].clarification
                    }
                    result = commandIds as Set
                } else {
                    throw new IllegalArgumentException("Unknown receipt operation: ${op}")
                }
                stored[eventId] = record
                Path tmp = Files.createTempFile(dir, '.receipts.', '.tmp')
                try {
                    Files.writeString(tmp, JsonOutput.prettyPrint(JsonOutput.toJson(stored)), StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING)
                    try (def ch = Files.newByteChannel(tmp, StandardOpenOption.WRITE)) { ch.force(true) }
                    try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (java.nio.file.AtomicMoveNotSupportedException ignoredMove) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(tmp)
                }
                return result
            }
        }
    }

    private static Map mergeMaps(Map existing, Map update) {
        Map merged = new LinkedHashMap(existing)
        update.each { key, value ->
            merged[key] = value instanceof Map && merged[key] instanceof Map ?
                mergeMaps(merged[key] as Map, value as Map) : value
        }
        merged
    }
}
