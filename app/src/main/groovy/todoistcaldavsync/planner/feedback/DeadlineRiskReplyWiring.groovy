package todoistcaldavsync.planner.feedback

import todoistcaldavsync.planner.ProductionIntegrationConfig
import todoistcaldavsync.planner.ProductionPlannerOrchestrator
import todoistcaldavsync.planner.adapters.TodoistRestGateway
import java.time.ZoneId
import java.util.function.BiConsumer

/**
 * Production composition for numbered Deadline replies. Uses the configured LLM
 * assistance service and the Todoist REST/Sync gateway. Not used by tests that
 * inject their own handler.
 */
final class DeadlineRiskReplyWiring {
    static DeadlineRiskReplyHandler wire(ProductionPlannerOrchestrator orchestrator,
                                         ProductionIntegrationConfig config,
                                         Closure reply) {
        if (orchestrator?.plannerConfig?.ai?.enabled != true) return null
        ZoneId zone = orchestrator.plannerConfig.timezone
        def interpreter = new DeadlineRiskInterpreter(new DeadlineRiskInterpreter.LlmGateway({ request ->
            orchestrator.aiSuggestions(request.proposalId, 'deadline_risk_mitigation',
                "risk-${request.threadTs}", request.reply)
        }))
        def gateway = new TodoistRestGateway(config.todoist)
        def feedback = new DeadlineRiskFeedback(new SyncDeadlineWriter(gateway), zone, gateway)
        BiConsumer<String, String> post = { String thread, String text ->
            reply.call(thread, text, "deadline-risk:${thread}")
        } as BiConsumer
        new DeadlineRiskReplyHandler(interpreter, feedback, zone, post,
            { List ids -> ids.collectEntries { [(it): gateway.fetchTask(it)] } },
            { String op, String id, List outcomes = null ->
                DeadlineRiskReceipts.handle(config.applicationsDir.resolve('deadline-risk'), op, id, outcomes)
            },
            { conversation, event ->
                orchestrator.preview(java.time.Instant.now(),
                    java.time.Instant.now().plus(java.time.Duration.ofDays(7)))?.id
            })
    }

    /** Adapts authorized Sync writes onto the existing feedback executor. */
    static final class SyncDeadlineWriter implements todoistcaldavsync.planner.adapters.TodoistWriteGateway,
            DeadlineRiskFeedback.PriorityWriter {
        private final TodoistRestGateway gateway
        SyncDeadlineWriter(TodoistRestGateway gateway) { this.gateway = gateway }

        void updateTaskDue(String taskId, String dueDateTimeIso) {
            throw new UnsupportedOperationException('Deadline risk replies do not change Due')
        }

        void updateTaskDeadline(String taskId, String deadlineIso) {
            String commandId = UUID.nameUUIDFromBytes("deadline-risk:${taskId}:${deadlineIso}".getBytes('UTF-8')).toString()
            def result = gateway.updateAuthorizedDeadline(taskId, deadlineIso, commandId)
            if (result.state != todoistcaldavsync.planner.adapters.TodoistCommandState.COMMITTED) {
                throw new IllegalStateException("deadline write ${result.state}")
            }
        }

        void updateTaskPriority(String taskId, int apiPriority) {
            String commandId = UUID.nameUUIDFromBytes("deadline-risk:${taskId}:priority:${apiPriority}".getBytes('UTF-8')).toString()
            def result = gateway.updateAuthorizedPriority(taskId, apiPriority, commandId)
            if (result.state != todoistcaldavsync.planner.adapters.TodoistCommandState.COMMITTED) {
                throw new IllegalStateException("priority write ${result.state}")
            }
        }
    }
}
