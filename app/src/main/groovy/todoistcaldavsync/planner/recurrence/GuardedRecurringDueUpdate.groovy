package todoistcaldavsync.planner.recurrence

import org.apache.log4j.Logger
import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.adapters.TodoistLifecycleGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task

/**
 * The single write boundary for changing the next date of a Todoist recurrence.
 * It preserves the provider-owned recurrence tuple and all non-due task state.
 */
final class GuardedRecurringDueUpdate {
    private static final Logger LOG = Logger.getLogger(GuardedRecurringDueUpdate)
    private static final List<String> DUE_FIELDS =
        Collections.unmodifiableList(['date', 'string', 'is_recurring', 'lang', 'timezone'])

    /**
     * Reread, update, and verify a recurring task. The optional command ID is used only for
     * the initial write; a repair always receives a fresh ID.
     *
     * @return the verified provider task
     * @throws GuardedRecurringDueUpdateException for skipped, unsuccessful, or ambiguous outcomes
     */
    static Task update(TodoistLifecycleGateway gateway, PlannerConfig config, String taskId,
                       String replacementDate, String commandId = null) {
        if (gateway == null || config == null || !taskId || !replacementDate) {
            throw new IllegalArgumentException('gateway, config, taskId, and replacementDate are required')
        }
        String initialCommandId = commandId ?: UUID.randomUUID().toString()
        validateCommandId(initialCommandId)

        Map originalRaw = readRaw(gateway, taskId)
        if (originalRaw == null) {
            throw failure(GuardedRecurringDueOutcome.AMBIGUOUS,
                "Recurring due prewrite task reread unavailable for task ${taskId}")
        }
        String title = field(originalRaw, 'content').displayValue()
        DueObservation originalDue = DueObservation.from(originalRaw)
        if (!originalDue.completeRecurringTuple()) {
            throw failure(GuardedRecurringDueOutcome.UNSUCCESSFUL,
                "Recurring due update skipped for task ${taskId} title=${title}: " +
                    "incomplete prewrite tuple originalDue=${originalDue.render()}")
        }
        Task original
        try {
            original = toTask(originalRaw, config)
        } catch (Exception e) {
            throw failure(GuardedRecurringDueOutcome.UNSUCCESSFUL,
                "Recurring due update skipped for task ${taskId} title=${title}: " +
                    "unusable prewrite task originalDue=${originalDue.render()}", e)
        }
        DueObservation intendedDue = originalDue.withDate(replacementDate)

        if (original.todoistDue.date == replacementDate) return original

        def initial
        try {
            initial = gateway.updateRecurringDue(taskId, original.todoistDue, replacementDate, initialCommandId)
        } catch (Throwable e) {
            GuardedRecurringDueOutcome outcome = ambiguousWrite(e) ?
                GuardedRecurringDueOutcome.AMBIGUOUS : GuardedRecurringDueOutcome.UNSUCCESSFUL
            throw failure(outcome,
                "Todoist initial recurring due command failed for task ${taskId} title=${title}; " +
                    "originalDue=${originalDue.render()} intendedDue=${intendedDue.render()}", e)
        }
        if (initial == null || initial.state == TodoistCommandState.REJECTED) {
            String reason = initial == null ? 'missing command outcome' :
                "rejected (${initial.errorCode ?: 'no error code'})"
            throw failure(GuardedRecurringDueOutcome.UNSUCCESSFUL,
                "Todoist initial recurring due command ${reason} for task ${taskId} title=${title}; " +
                    "originalDue=${originalDue.render()} intendedDue=${intendedDue.render()}")
        }

        ReadResult first = verifyRead(gateway, config, taskId, original, intendedDue)
        if (first.verified != null) return first.verified
        if (first.observation?.sameAs(intendedDue)) {
            throw diagnosedFailure(GuardedRecurringDueOutcome.UNSUCCESSFUL, taskId, title,
                originalDue, intendedDue, first, null,
                'non-due guarded postconditions failed; due repair is not authorized', first.error)
        }

        // This is intentionally the only repair. Never derive it from the possibly damaged reread.
        String repairCommandId = newCommandId(initialCommandId)
        def repair
        Throwable repairError = null
        try {
            repair = gateway.updateRecurringDue(taskId, original.todoistDue, replacementDate, repairCommandId)
        } catch (Throwable e) {
            repairError = e
        }
        // Even a rejected/failed repair gets a final observation, but cannot claim success.
        ReadResult second = verifyRead(gateway, config, taskId, original, intendedDue)
        if (repairError != null || repair == null || repair.state == TodoistCommandState.REJECTED) {
            GuardedRecurringDueOutcome outcome = ambiguousWrite(repairError) ||
                repair?.state == TodoistCommandState.AMBIGUOUS ||
                initial.state == TodoistCommandState.AMBIGUOUS || second.unavailable ?
                GuardedRecurringDueOutcome.AMBIGUOUS : GuardedRecurringDueOutcome.UNSUCCESSFUL
            String repairReason = repairError != null ?
                "failed (${repairError.class.simpleName}: ${repairError.message})" :
                repair == null ? 'returned no outcome' :
                    "${repair.state.name().toLowerCase()} (${repair.errorCode ?: 'no error code'})"
            throw diagnosedFailure(outcome, taskId, title, originalDue, intendedDue,
                first, second, "repair command ${repairReason}", repairError)
        }

        if (second.verified != null) return second.verified

        GuardedRecurringDueOutcome outcome = repair.state == TodoistCommandState.AMBIGUOUS ||
            initial.state == TodoistCommandState.AMBIGUOUS || second.unavailable ?
            GuardedRecurringDueOutcome.AMBIGUOUS : GuardedRecurringDueOutcome.UNSUCCESSFUL
        throw diagnosedFailure(outcome, taskId, title, originalDue, intendedDue,
            first, second, 'repair could not be verified', second.error)
    }

    private static ReadResult verifyRead(TodoistLifecycleGateway gateway, PlannerConfig config,
                                         String taskId, Task original, DueObservation intendedDue) {
        Map raw
        try {
            raw = gateway.fetchTask(taskId)
        } catch (Throwable e) {
            return ReadResult.unavailable(e)
        }
        if (raw == null) return ReadResult.unavailable(null)
        DueObservation due = DueObservation.from(raw)
        Task task
        try {
            task = toTask(raw, config)
        } catch (Throwable e) {
            return ReadResult.observed(due, null, e)
        }
        boolean nonDueMatches = task.deadlineDate == original.deadlineDate &&
            task.labels.toSet() == original.labels.toSet() &&
            task.description == original.description &&
            task.completedCount == original.completedCount
        if (due.sameAs(intendedDue) && nonDueMatches) return ReadResult.success(due, task)
        ReadResult.observed(due, task, null)
    }

    private static Task toTask(Map raw, PlannerConfig config) {
        Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone)
    }

    private static Map readRaw(TodoistLifecycleGateway gateway, String taskId) {
        try {
            return gateway.fetchTask(taskId)
        } catch (Throwable ignored) {
            return null
        }
    }

    private static GuardedRecurringDueUpdateException diagnosedFailure(
        GuardedRecurringDueOutcome outcome, String taskId, String title,
        DueObservation originalDue, DueObservation intendedDue,
        ReadResult first, ReadResult latest, String reason, Throwable cause) {
        ReadResult selected = latest ?: first
        String availability
        if (selected?.unavailable) {
            availability = first?.observation != null ?
                "latest reread unavailable; last available observation=${first.observation.render()}" :
                'latest reread unavailable'
        } else {
            availability = "latestRereadDue=${selected?.observation?.render() ?: '<unavailable>'}"
        }
        DueObservation compared = selected?.observation ?: first?.observation
        String differences = intendedDue.differences(compared)
        String message = "Guarded recurring due repair failed outcome=${outcome} task=${taskId} " +
            "title=${title}; reason=${reason}; originalDue=${originalDue.render()}; " +
            "intendedDue=${intendedDue.render()}; ${availability}; differences=${differences}"
        LOG.error(message, cause)
        failure(outcome, message, cause)
    }

    private static boolean ambiguousWrite(Throwable error) {
        Throwable current = error
        while (current != null) {
            try {
                if (current.hasProperty('classification') &&
                    current.classification == 'AMBIGUOUS_WRITE') return true
            } catch (Exception ignored) {
                // Continue through wrapped provider failures.
            }
            current = current.cause
        }
        false
    }

    private static GuardedRecurringDueUpdateException failure(GuardedRecurringDueOutcome outcome,
                                                               String message, Throwable cause = null) {
        new GuardedRecurringDueUpdateException(outcome, message, cause)
    }

    private static String newCommandId(String prior) {
        String value
        do { value = UUID.randomUUID().toString() } while (value == prior)
        value
    }

    private static void validateCommandId(String commandId) {
        try { UUID.fromString(commandId) }
        catch (Exception e) { throw new IllegalArgumentException('commandId must be a UUID', e) }
    }

    private static FieldValue field(Map source, String name) {
        source.containsKey(name) ? FieldValue.present(source[name]) : FieldValue.missing()
    }

    private static final class ReadResult {
        final DueObservation observation
        final Task task
        final Task verified
        final boolean unavailable
        final Throwable error

        private ReadResult(DueObservation observation, Task task, Task verified,
                           boolean unavailable, Throwable error) {
            this.observation = observation; this.task = task; this.verified = verified
            this.unavailable = unavailable; this.error = error
        }

        static ReadResult success(DueObservation due, Task task) {
            new ReadResult(due, task, task, false, null)
        }
        static ReadResult observed(DueObservation due, Task task, Throwable error) {
            new ReadResult(due, task, null, false, error)
        }
        static ReadResult unavailable(Throwable error) {
            new ReadResult(null, null, null, true, error)
        }
    }

    private static final class DueObservation {
        final FieldValue due
        final Map<String, FieldValue> fields
        final Map<String, Object> rawFields

        private DueObservation(FieldValue due, Map<String, FieldValue> fields, Map<String, Object> rawFields) {
            this.due = due
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields))
            this.rawFields = Collections.unmodifiableMap(new LinkedHashMap<>(rawFields))
        }

        static DueObservation from(Map task) {
            FieldValue due = field(task, 'due')
            Map raw = due.present && due.value instanceof Map ? due.value as Map : [:]
            Map<String, FieldValue> values = DUE_FIELDS.collectEntries { String name ->
                [(name): due.present && due.value instanceof Map ? field(raw, name) : FieldValue.missing()]
            }
            Map<String, Object> snapshot = raw.collectEntries { key, value ->
                [(key.toString()): snapshotValue(value)]
            }
            new DueObservation(due, values, snapshot)
        }

        boolean completeRecurringTuple() {
            due.present && due.value instanceof Map &&
                fields.date.nonBlank() && fields.string.nonBlank() &&
                fields.is_recurring.present && fields.is_recurring.value == true &&
                fields.lang.nonBlank() && fields.timezone.present
        }

        DueObservation withDate(String replacement) {
            Map<String, FieldValue> changed = new LinkedHashMap<>(fields)
            changed.date = FieldValue.present(replacement)
            Map<String, Object> intendedRaw = new LinkedHashMap<>(rawFields)
            intendedRaw.date = replacement
            new DueObservation(due, changed, intendedRaw)
        }

        boolean sameAs(DueObservation other) {
            other != null && DUE_FIELDS.every { fields[it] == other.fields[it] }
        }

        String differences(DueObservation actual) {
            if (actual == null) {
                return DUE_FIELDS.collect { String name ->
                    "${name}: expected=${fields[name].displayValue()}, actual=<unavailable>" +
                        (name == 'date' ? ' (intended date change)' : ' (unexpected/unverifiable)')
                }.join(', ')
            }
            DUE_FIELDS.collect { String name ->
                FieldValue expected = fields[name]
                FieldValue observed = actual.fields[name]
                String kind = name == 'date' ? 'intended date change' :
                    (expected == observed ? 'preserved' : 'unexpected difference')
                "${name}: expected=${expected.displayValue()}, actual=${observed.displayValue()} (${kind})"
            }.join(', ')
        }

        String render() {
            if (!due.present) return '<missing due>'
            if (due.value == null) return '<null due>'
            if (!(due.value instanceof Map)) return "<non-object due ${due.displayValue()}>"
            List<String> ordered = new ArrayList<>(DUE_FIELDS)
            ordered.addAll(rawFields.keySet().findAll { !(it in DUE_FIELDS) }.sort())
            '{' + ordered.collect { String name ->
                FieldValue value = name in DUE_FIELDS ? fields[name] : FieldValue.present(rawFields[name])
                "${name}=${value.displayValue()}"
            }.join(', ') + '}'
        }

        private static Object snapshotValue(Object value) {
            if (value instanceof Map) {
                return Collections.unmodifiableMap((value as Map).collectEntries { key, nested ->
                    [(key.toString()): snapshotValue(nested)]
                })
            }
            if (value instanceof Collection) {
                return Collections.unmodifiableList((value as Collection).collect { snapshotValue(it) })
            }
            value
        }
    }

    private static final class FieldValue {
        final boolean present
        final Object value
        private FieldValue(boolean present, Object value) { this.present = present; this.value = value }
        static FieldValue present(Object value) { new FieldValue(true, value) }
        static FieldValue missing() { new FieldValue(false, null) }
        boolean nonBlank() { present && value != null && value.toString() }
        String displayValue() {
            !present ? '<missing>' : value == null ? '<null>' : "'${value}'"
        }
        boolean equals(Object other) {
            other instanceof FieldValue && present == other.present && value == other.value
        }
        int hashCode() { 31 * Boolean.hashCode(present) + Objects.hashCode(value) }
    }
}

enum GuardedRecurringDueOutcome { UNSUCCESSFUL, AMBIGUOUS }

final class GuardedRecurringDueUpdateException extends RuntimeException {
    final GuardedRecurringDueOutcome outcome
    GuardedRecurringDueUpdateException(GuardedRecurringDueOutcome outcome, String message,
                                       Throwable cause = null) {
        super(message, cause)
        this.outcome = outcome
    }
}
