package todoistcaldavsync.planner.feedback

import todoistcaldavsync.planner.adapters.LifecycleMutation
import todoistcaldavsync.planner.adapters.TodoistCommandResult
import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.adapters.TodoistLifecycleGateway
import todoistcaldavsync.planner.adapters.TodoistWriteGateway
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.messaging.DeadlineRiskInventory
import todoistcaldavsync.planner.recurrence.LifecycleMarker
import todoistcaldavsync.planner.recurrence.LifecycleMarkerCodec
import todoistcaldavsync.planner.scheduling.DeadlinePolicy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Narrow executor for a fully resolved authorized Deadline-risk reply.
 * Interpretation and preflight are all-or-nothing. Remote writes are not a transaction:
 * a later rejection does not roll back or replay an earlier verified write.
 */
final class DeadlineRiskFeedback {
    private final TodoistWriteGateway writer
    private final TodoistLifecycleGateway lifecycle
    private final ZoneId zone

    DeadlineRiskFeedback(TodoistWriteGateway writer, ZoneId zone, TodoistLifecycleGateway lifecycle = null) {
        if (writer == null) throw new IllegalArgumentException('Todoist writer is required')
        this.writer = writer
        this.lifecycle = lifecycle
        this.zone = zone ?: ZoneId.of('UTC')
    }

    /**
     * Validate every action against the bound inventory and current live maps before the first write.
     * {@code liveTasks} is keyed by task id and must be the re-read state, not the proposal snapshot.
     */
    Preflight preflight(DeadlineRiskInventory inventory, List<Action> actions, Map<String, Map> liveTasks,
                        String proposalId, String policyIdentity) {
        List<String> problems = []
        if (inventory == null || inventory.policyIdentity != policyIdentity) {
            problems << 'proposal deadline policy is stale; request a fresh review'
        }
        List<Action> accepted = []
        Set<Integer> seen = new LinkedHashSet<>()
        (actions ?: []).each { Action action ->
            if (action == null || action.number == null) {
                problems << 'an instruction has no proposal row'
                return
            }
            if (!seen.add(action.number)) problems << "row ${action.number} is requested more than once"
            DeadlineRiskInventory.Row row = inventory.byNumber(action.number)
            if (row == null) {
                problems << "row ${action.number} is not in this proposal"
                return
            }
            if (action.proposalId && action.proposalId != proposalId) {
                problems << "row ${action.number} belongs to another proposal"
            }
            Map live = liveTasks?.get(row.task.id)
            if (live == null) {
                problems << "row ${action.number} (${row.task.id}) is no longer present"
                return
            }
            if (live.checked == true || live.is_completed == true) {
                problems << "row ${action.number} is completed; request a fresh review"
            }
            String liveDeadline = liveDeadline(live)
            String boundDeadline = row.deadlineDate?.toString()
            if (boundDeadline != liveDeadline) {
                problems << "row ${action.number} Deadline changed from ${boundDeadline} to ${liveDeadline}; request a fresh review"
            }
            if (live.priority == null || live.priority.toString() != row.task.priority?.toString()) {
                problems << "row ${action.number} priority changed or is unavailable; request a fresh review"
            }
            if (action.priority != null && !(action.priority in 1..4)) {
                problems << "row ${action.number} priority must be P1–P4"
            }
            if (action.deadlineDate == null && action.priority == null) {
                problems << "row ${action.number} requests neither a Deadline nor a priority"
            }
            accepted << action.bound(row.task.id, liveDeadline, live.priority as Integer)
        }
        new Preflight(problems.isEmpty(), problems, problems.isEmpty() ? accepted : [])
    }

    /**
     * Execute a preflighted batch. Caller must persist the returned receipt before retrying.
     * Already-recorded command ids are not sent again.
     */
    Execution execute(Preflight preflight, Set<String> alreadyAppliedCommandIds = [] as Set, Closure checkpoint = null,
                      String eventId = UUID.randomUUID().toString(), Closure readTask = null) {
        if (preflight == null || !preflight.accepted) {
            return new Execution([], ['preflight rejected the batch'])
        }
        List<Outcome> outcomes = []
        List<String> notes = []
        preflight.actions.each { Action action ->
            boolean deadlineVerified = false
            if (action.deadlineDate != null) {
                String id = commandId(eventId, action, 'deadline')
                if (checkpoint != null && !alreadyAppliedCommandIds.contains(id)) checkpoint.call(outcomes + [new Outcome(action.taskId, 'deadline', 'unknown', id, 'write may have started')])
                outcomes << (alreadyAppliedCommandIds.contains(id) || matchesPrecondition(action, readTask, action.currentDeadline)
                    ? applyDeadline(action, id, alreadyAppliedCommandIds, readTask)
                    : new Outcome(action.taskId, 'deadline', 'rejected', id, 'live state changed or unavailable; request fresh review'))
                deadlineVerified = outcomes.last().state == 'verified'
                if (checkpoint != null) checkpoint.call(outcomes)
            }
            if (action.priority != null) {
                String id = commandId(eventId, action, 'priority')
                if (checkpoint != null && !alreadyAppliedCommandIds.contains(id)) checkpoint.call(outcomes + [new Outcome(action.taskId, 'priority', 'unknown', id, 'write may have started')])
                String expectedDeadline = deadlineVerified ? action.deadlineDate.toString() : action.currentDeadline
                outcomes << (alreadyAppliedCommandIds.contains(id) || matchesPrecondition(action, readTask, expectedDeadline)
                    ? applyPriority(action, id, alreadyAppliedCommandIds, readTask)
                    : new Outcome(action.taskId, 'priority', 'rejected', id, 'live state changed or unavailable; request fresh review'))
                if (checkpoint != null) checkpoint.call(outcomes)
            }
        }
        new Execution(outcomes, notes)
    }

    private static String commandId(String eventId, Action action, String field) {
        UUID.nameUUIDFromBytes(groovy.json.JsonOutput.toJson(['deadline-risk', eventId, action.taskId, field]).getBytes('UTF-8')).toString()
    }

    private static boolean matchesPrecondition(Action action, Closure readTask, String expectedDeadline) {
        try {
            Map live = readTask?.call(action.taskId) as Map
            return live != null && live.checked != true && live.is_completed != true &&
                liveDeadline(live) == expectedDeadline && live.priority?.toString() == action.currentPriority?.toString()
        } catch (RuntimeException ignored) {
            return false
        }
    }

    private Outcome applyDeadline(Action action, String commandId, Set<String> alreadyApplied, Closure readTask) {
        if (alreadyApplied.contains(commandId)) {
            return new Outcome(action.taskId, 'deadline', 'verified', commandId, 'already applied')
        }
        try {
            if (writer instanceof CommandWriter) ((CommandWriter) writer).updateDeadline(action.taskId, action.deadlineDate.toString(), commandId)
            else writer.updateTaskDeadline(action.taskId, action.deadlineDate.toString())
        } catch (AmbiguousWrite e) {
            return new Outcome(action.taskId, 'deadline', 'unknown', commandId, 'ambiguous')
        } catch (RejectedWrite e) {
            return new Outcome(action.taskId, 'deadline', 'rejected', commandId, e.message)
        } catch (RuntimeException e) {
            return new Outcome(action.taskId, 'deadline', 'unknown', commandId, e.message)
        }
        try {
            Map verified = readTask?.call(action.taskId) as Map
            if (verified == null || liveDeadline(verified) != action.deadlineDate.toString())
                return new Outcome(action.taskId, 'deadline', 'unknown', commandId, 'live Deadline did not verify')
        } catch (RuntimeException e) {
            return new Outcome(action.taskId, 'deadline', 'unknown', commandId, 'live verification failed')
        }
        if (lifecycle != null) {
            try {
                Map live = lifecycle.fetchTask(action.taskId)
                if (live == null || liveDeadline(live) != action.deadlineDate.toString())
                    return new Outcome(action.taskId, 'deadline', 'unknown', commandId, 'Deadline changed before marker update')
                def read = new LifecycleMarkerCodec().read(live?.description?.toString())
                if (read?.status?.name() == 'VALID' && read.marker != null) {
                    LifecycleMarker marker = read.marker
                    LifecycleMarker next = new LifecycleMarker(
                        taskId: marker.taskId, completedCount: marker.completedCount,
                        deadlineMode: marker.deadlineMode, deadlineSource: 'authorized_reply',
                        deadlineDate: action.deadlineDate, pendingLegacySourceDate: marker.pendingLegacySourceDate,
                        lastVerifiedDue: marker.lastVerifiedDue, recurrenceFingerprint: marker.recurrenceFingerprint,
                        lastPlannerDue: marker.lastPlannerDue, markerGeneration: marker.markerGeneration + 1,
                        lastCommandId: "deadline-risk:${commandId}")
                    String description = new LifecycleMarkerCodec().merge(live.description?.toString(), next)
                    TodoistCommandResult markerWrite = lifecycle.updateLifecycleFields(LifecycleMutation.validated(
                        action.taskId, UUID.nameUUIDFromBytes("deadline-risk-marker:${commandId}".getBytes('UTF-8')).toString(),
                        null, null, description))
                    if (markerWrite?.state != TodoistCommandState.COMMITTED) {
                        return new Outcome(action.taskId, 'deadline', 'unknown', commandId, 'marker update not committed')
                    }
                    Map afterMarker = lifecycle.fetchTask(action.taskId)
                    def verifiedMarker = new LifecycleMarkerCodec().read(afterMarker?.description?.toString())
                    if (afterMarker == null || liveDeadline(afterMarker) != action.deadlineDate.toString() ||
                            verifiedMarker?.status?.name() != 'VALID' ||
                            verifiedMarker.marker?.lastCommandId != "deadline-risk:${commandId}") {
                        return new Outcome(action.taskId, 'deadline', 'unknown', commandId, 'marker or Deadline did not verify')
                    }
                }
                if (writer instanceof LifecycleInboxWriter) {
                    ((LifecycleInboxWriter) writer).enqueueCurrentTask(action.taskId)
                }
            } catch (RuntimeException e) {
                return new Outcome(action.taskId, 'deadline', 'unknown', commandId, e.message)
            }
        }
        return new Outcome(action.taskId, 'deadline', 'verified', commandId, null)
    }

    private Outcome applyPriority(Action action, String commandId, Set<String> alreadyApplied, Closure readTask) {
        if (alreadyApplied.contains(commandId)) {
            return new Outcome(action.taskId, 'priority', 'verified', commandId, 'already applied')
        }
        if (!(writer instanceof PriorityWriter)) {
            return new Outcome(action.taskId, 'priority', 'rejected', commandId, 'priority writes are not available on this gateway')
        }
        try {
            if (writer instanceof CommandWriter) ((CommandWriter) writer).updatePriority(action.taskId, action.priority, commandId)
            else ((PriorityWriter) writer).updateTaskPriority(action.taskId, action.priority)
            Map verified = readTask?.call(action.taskId) as Map
            if (verified == null || verified.priority?.toString() != action.priority.toString())
                return new Outcome(action.taskId, 'priority', 'unknown', commandId, 'live priority did not verify')
            return new Outcome(action.taskId, 'priority', 'verified', commandId, null)
        } catch (AmbiguousWrite e) {
            return new Outcome(action.taskId, 'priority', 'unknown', commandId, 'ambiguous')
        } catch (RejectedWrite e) {
            return new Outcome(action.taskId, 'priority', 'rejected', commandId, e.message)
        } catch (RuntimeException e) {
            return new Outcome(action.taskId, 'priority', 'unknown', commandId, e.message)
        }
    }

    private static String liveDeadline(Map live) {
        def deadline = live.deadline
        if (deadline instanceof Map) return deadline.date?.toString()
        return deadline?.toString()
    }

    /** Gateway extension. Existing due/deadline methods do not carry priority. */
    interface PriorityWriter {
        void updateTaskPriority(String taskId, int apiPriority)
    }

    interface CommandWriter {
        void updateDeadline(String taskId, String deadlineIso, String commandId)
        void updatePriority(String taskId, int apiPriority, String commandId)
    }

    interface LifecycleInboxWriter {
        void enqueueCurrentTask(String taskId)
    }

    static final class RejectedWrite extends RuntimeException {
        RejectedWrite(String message) { super(message) }
    }

    static final class AmbiguousWrite extends RuntimeException {
        final String commandId
        AmbiguousWrite(String commandId) {
            super('ambiguous')
            this.commandId = commandId
        }
    }

    static final class Action {
        final Integer number
        final String proposalId
        final String taskId
        final LocalDate deadlineDate
        final Integer priority
        final String currentDeadline
        final Integer currentPriority

        Action(Integer number, String proposalId, LocalDate deadlineDate, Integer priority,
               String taskId = null, String currentDeadline = null, Integer currentPriority = null) {
            this.number = number
            this.proposalId = proposalId
            this.deadlineDate = deadlineDate
            this.priority = priority
            this.taskId = taskId
            this.currentDeadline = currentDeadline
            this.currentPriority = currentPriority
        }

        Action bound(String taskId, String currentDeadline, Integer currentPriority = null) {
            new Action(number, proposalId, deadlineDate, priority, taskId, currentDeadline, currentPriority)
        }
    }

    static final class Preflight {
        final boolean accepted
        final List<String> problems
        final List<Action> actions
        Preflight(boolean accepted, List<String> problems, List<Action> actions) {
            this.accepted = accepted
            this.problems = Collections.unmodifiableList(problems ?: [])
            this.actions = Collections.unmodifiableList(actions ?: [])
        }
    }

    static final class Outcome {
        final String taskId
        final String field
        final String state
        final String commandId
        final String detail
        Outcome(String taskId, String field, String state, String commandId, String detail) {
            this.taskId = taskId
            this.field = field
            this.state = state
            this.commandId = commandId
            this.detail = detail
        }

        Map toMap() {
            [taskId: taskId, field: field, state: state, commandId: commandId, detail: detail]
        }
    }

    static final class Execution {
        final List<Outcome> outcomes
        final List<String> notes
        Execution(List<Outcome> outcomes, List<String> notes) {
            this.outcomes = Collections.unmodifiableList(outcomes ?: [])
            this.notes = Collections.unmodifiableList(notes ?: [])
        }

        boolean anyVerified() { outcomes.any { it.state == 'verified' } }
        boolean allVerified() { outcomes && outcomes.every { it.state == 'verified' } }
    }
}
