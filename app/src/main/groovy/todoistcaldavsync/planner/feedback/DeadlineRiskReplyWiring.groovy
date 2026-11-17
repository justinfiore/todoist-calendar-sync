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
            orchestrator.aiSuggestions(request.planId, 'deadline_risk_mitigation',
                "risk-${request.threadTs}", request.reply, request.inventory.rows.collect { it.number },
                DeadlineRiskInterpreter.context(request))
        }))
        def gateway = new TodoistRestGateway(config.todoist + [
            allowInsecureHttp: config.todoist.allowInsecureHttp == true,
            secretResolver: config.todoist.secretResolver,
            httpClient: config.todoist.httpClient
        ])
        def feedback = new DeadlineRiskFeedback(new SyncDeadlineWriter(gateway, config.applicationsDir), zone, gateway)
        BiConsumer<String, String> post = { String thread, String text ->
            reply.call(thread, text, "deadline-risk:${thread}:${UUID.randomUUID()}")
        } as BiConsumer
        new DeadlineRiskReplyHandler(interpreter, feedback, zone, post,
            { List ids -> ids.collectEntries { [(it): gateway.fetchTask(it)] } },
            { String op, String id, List outcomes = null ->
                DeadlineRiskReceipts.handle(config.applicationsDir.resolve('deadline-risk'), op, id, outcomes)
            },
            null)
    }

    /** Adapts authorized Sync writes onto the existing feedback executor. */
    static final class SyncDeadlineWriter implements todoistcaldavsync.planner.adapters.TodoistWriteGateway,
            DeadlineRiskFeedback.PriorityWriter {
        private final TodoistRestGateway gateway
        private final java.nio.file.Path applicationsDir
        SyncDeadlineWriter(TodoistRestGateway gateway, java.nio.file.Path applicationsDir = null) {
            this.gateway = gateway
            this.applicationsDir = applicationsDir
        }

        void updateTaskDue(String taskId, String dueDateTimeIso) {
            throw new UnsupportedOperationException('Deadline risk replies do not change Due')
        }

        void updateTaskDeadline(String taskId, String deadlineIso) {
            String commandId = UUID.nameUUIDFromBytes("deadline-risk:${taskId}:${deadlineIso}".getBytes('UTF-8')).toString()
            def result = gateway.updateAuthorizedDeadline(taskId, deadlineIso, commandId)
            if (result.state == todoistcaldavsync.planner.adapters.TodoistCommandState.AMBIGUOUS) {
                throw new DeadlineRiskFeedback.AmbiguousWrite(commandId)
            }
            if (result.state != todoistcaldavsync.planner.adapters.TodoistCommandState.COMMITTED) {
                throw new IllegalStateException("deadline write ${result.state}")
            }
            Map live = gateway.fetchTask(taskId)
            if (live != null && applicationsDir != null && gateway.lastSyncToken) {
                new todoistcaldavsync.planner.recurrence.ItemsSyncStateStore(applicationsDir)
                    .enqueue(live, gateway.lastSyncToken)
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
