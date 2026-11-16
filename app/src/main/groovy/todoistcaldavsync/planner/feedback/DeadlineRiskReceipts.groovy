package todoistcaldavsync.planner.feedback

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Durable command ids for Deadline-risk replies. A restart loads the same ids and does not replay them. */
final class DeadlineRiskReceipts {
    static Object handle(Path directory, String op, String eventId, List outcomes = null) {
        Files.createDirectories(directory)
        Path file = directory.resolve('receipts.json')
        Map stored = Files.exists(file) ? new JsonSlurper().parse(file.toFile()) as Map : [:]
        if (op == 'load') {
            def prior = stored[eventId]
            return prior instanceof Map ? (prior.commandIds ?: []) as Set : [] as Set
        }
        if (op == 'load-record') {
            def prior = stored[eventId]
            return prior instanceof Map ? prior : [:]
        }
        List commandIds = (outcomes ?: []).collect { it instanceof Map ? it.commandId : it['commandId'] }.findAll { it }
        Map record = [commandIds: commandIds, outcomes: outcomes]
        if (outcomes && outcomes[0] instanceof Map && outcomes[0].pending == true) {
            record.pending = true
            record.originalReply = outcomes[0].originalReply
            record.replyTime = outcomes[0].replyTime
            record.proposalId = outcomes[0].proposalId
            record.clarification = outcomes[0].clarification
        }
        stored[eventId] = record
        Path tmp = directory.resolve("receipts.json.tmp")
        Files.writeString(tmp, JsonOutput.prettyPrint(JsonOutput.toJson(stored)), StandardCharsets.UTF_8)
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        commandIds as Set
    }
}
