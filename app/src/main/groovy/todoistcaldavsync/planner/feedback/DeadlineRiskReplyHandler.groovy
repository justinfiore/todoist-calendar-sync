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
    private final Closure successor
    private final Closure currentPolicyIdentity
    private final Closure reportOutcome
    private final Closure beforeWrites

    DeadlineRiskReplyHandler(DeadlineRiskInterpreter interpreter, DeadlineRiskFeedback feedback,
                             ZoneId zone, BiConsumer<String, String> reply,
                             Closure liveTasks, Closure receipts, Closure successor = null,
                             Closure currentPolicyIdentity = null, Closure reportOutcome = null,
                             Closure beforeWrites = null) {
        this.interpreter = interpreter
        this.feedback = feedback
        this.zone = zone
        this.reply = reply
        this.liveTasks = liveTasks
        this.receipts = receipts
        this.successor = successor
        this.currentPolicyIdentity = currentPolicyIdentity
        this.reportOutcome = reportOutcome
        this.beforeWrites = beforeWrites
    }

    boolean handle(MessagingEvent event, ConversationRecord conversation, List storedRows) {
        if (!event?.eventId?.trim()) {
            reply.accept(event?.threadTs, 'Cannot safely process this reply without an event identity; request a fresh review.')
            return false
        }
        def previous = receipts.call('load-record', event.eventId)
        if (previous instanceof Map && !previous.isEmpty()) {
            List outcomes = previous.outcomes instanceof List ? previous.outcomes : []
            String summary = outcomes.collect { "${it.taskId} ${it.field}: ${it.state}" }.join('; ')
            reply.accept(event.threadTs, "Previously recorded Deadline reply outcomes: ${summary ?: 'unknown'}. No writes were replayed; request a fresh review for any remaining changes. This reply is not calendar approval.")
            return false
        }
        DeadlineRiskInventory inventory = StoredDeadlineRiskInventory.restore(storedRows, zone)
        String pendingKey = "clarify:${conversation.proposalId}:${event.threadTs}"
        def pending = receipts.call('load-record', pendingKey)
        String replyText = event.text
        String replyTime = java.time.Instant.now().toString()
        if (pending instanceof Map && pending.pending == true && pending.originalReply) {
            replyText = "${pending.originalReply}\n${event.text}"
            replyTime = pending.replyTime?.toString() ?: replyTime
        }
        def interpreted = interpreter.interpret(new DeadlineRiskInterpreter.Request(
            proposalId: conversation.proposalId, planId: conversation.planId, threadTs: event.threadTs, actorId: event.actorId,
            authorized: true, reply: replyText, replyTime: java.time.Instant.parse(replyTime),
            zone: zone, inventory: inventory))
        if (!interpreted.complete()) {
            reply.accept(event.threadTs, interpreted.clarification)
            receipts.call('save', pendingKey, [[
                clarification: interpreted.clarification,
                originalReply: replyText,
                proposalId: conversation.proposalId,
                replyTime: replyTime,
                pending: true
            ]])
            return false
        }
        List bound = interpreted.actions.collect { action ->
            def row = inventory.byNumber(action.number)
            row ? action.bound(row.task.id, row.deadlineDate?.toString()) : action
        }
        Map<String, Map> live = liveTasks.call(bound*.taskId) as Map
        def preflight = feedback.preflight(inventory, bound, live,
            conversation.proposalId, currentPolicyIdentity != null ? currentPolicyIdentity.call()?.toString() : conversation.overrides?.deadlinePolicyIdentity?.toString())
        if (!preflight.accepted) {
            reply.accept(event.threadTs, "No task was changed. ${preflight.problems.join('; ')}")
            return false
        }
        int plannedFields = preflight.actions.sum { (it.deadlineDate != null ? 1 : 0) + (it.priority != null ? 1 : 0) } as int
        receipts.call('merge-record', event.eventId, [[
            deadlineConversation: conversation.toMap(), actorId: event.actorId, plannedFields: plannedFields
        ]])
        beforeWrites?.call(event, conversation)
        def execution = feedback.execute(preflight, [] as Set, { List outcomes ->
            receipts.call('save', event.eventId, outcomes.collect { it.toMap() })
        }, event.eventId, { String id -> (liveTasks.call([id]) as Map)[id] })
        receipts.call('save', event.eventId, execution.outcomes.collect { it.toMap() })
        receipts.call('save', pendingKey, [[pending: false, originalReply: replyText, replyTime: replyTime]])
        def successorResult = successor == null ? null : successor.call(conversation, event)
        boolean posted = successorResult?.posted == true
        String successorNote = posted ? "Fresh proposal ${successorResult.proposalId} posted. "
            : (successorResult ? "Fresh proposal was not posted (${successorResult.status ?: 'undelivered'}). " : "")
        String text = outcomeText(execution.outcomes.collect { it.toMap() }, successorNote, plannedFields)
        if (reportOutcome != null) reportOutcome.call(event, text)
        else reply.accept(event.threadTs, text)
        return beforeWrites != null || execution.outcomes.any { it.state in ['verified', 'unknown'] }
    }

    static String outcomeText(List outcomes, String successorNote = '', Integer plannedFields = null) {
        String summary = (outcomes ?: []).collect { "${it.taskId} ${it.field}: ${it.state}" }.join('; ')
        String incomplete = plannedFields != null && (outcomes ?: []).size() < plannedFields
            ? 'Some requested changes were not completed; request a fresh review. ' : ''
        "Deadline reply processed without Calendar changes. ${summary}. ${incomplete}${successorNote}This reply is not calendar approval."
    }
}
