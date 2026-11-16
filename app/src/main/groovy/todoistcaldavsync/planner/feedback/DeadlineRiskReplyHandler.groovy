package todoistcaldavsync.planner.feedback

import todoistcaldavsync.planner.messaging.DeadlineRiskInventory
import todoistcaldavsync.planner.messaging.MessagingEvent
import todoistcaldavsync.planner.state.ConversationRecord

import java.time.ZoneId
import java.util.function.BiConsumer

/**
 * Same-thread Deadline reply handler. Clarification and stale preflight send no writes.
 * A complete authorized interpretation is applied once and reported with verified,
 * rejected, and unknown outcomes. It does not approve a Calendar apply.
 */
final class DeadlineRiskReplyHandler {
    private final DeadlineRiskInterpreter interpreter
    private final DeadlineRiskFeedback feedback
    private final ZoneId zone
    private final BiConsumer<String, String> reply
    private final Closure liveTasks
    private final Closure receipts

    DeadlineRiskReplyHandler(DeadlineRiskInterpreter interpreter, DeadlineRiskFeedback feedback,
                             ZoneId zone, BiConsumer<String, String> reply,
                             Closure liveTasks, Closure receipts) {
        this.interpreter = interpreter
        this.feedback = feedback
        this.zone = zone
        this.reply = reply
        this.liveTasks = liveTasks
        this.receipts = receipts
    }

    void handle(MessagingEvent event, ConversationRecord conversation, List storedRows) {
        DeadlineRiskInventory inventory = StoredDeadlineRiskInventory.restore(storedRows, zone)
        def interpreted = interpreter.interpret(new DeadlineRiskInterpreter.Request(
            proposalId: conversation.proposalId, threadTs: event.threadTs, actorId: event.actorId,
            authorized: true, reply: event.text, replyTime: java.time.Instant.now(),
            zone: zone, inventory: inventory))
        if (!interpreted.complete()) {
            reply.accept(event.threadTs, interpreted.clarification)
            return
        }
        List bound = interpreted.actions.collect { action ->
            def row = inventory.byNumber(action.number)
            row ? action.bound(row.task.id, row.deadlineDate?.toString()) : action
        }
        Map<String, Map> live = liveTasks.call(bound*.taskId) as Map
        def preflight = feedback.preflight(inventory, bound, live,
            conversation.proposalId, conversation.overrides?.deadlinePolicyIdentity?.toString())
        if (!preflight.accepted) {
            reply.accept(event.threadTs, "No task was changed. ${preflight.problems.join('; ')}")
            return
        }
        Set<String> applied = receipts.call('load', event.eventId) as Set ?: [] as Set
        def execution = feedback.execute(preflight, applied)
        receipts.call('save', event.eventId, execution.outcomes.collect { it.toMap() })
        String summary = execution.outcomes.collect { "${it.taskId} ${it.field}: ${it.state}" }.join('; ')
        reply.accept(event.threadTs,
            "Deadline reply applied without Calendar changes. ${summary}. A fresh proposal is required before any calendar apply.")
    }
}
