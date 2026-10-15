package todoistcaldavsync.planner.writeback

import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.recurrence.GuardedRecurringDueUpdate
import todoistcaldavsync.planner.state.ApplicationStateStore

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.function.Supplier

/** Planner-only orchestration around the shared conflict decision and cursor/echo store. */
final class PlannerCalendarWritebackService {
    private final PlannerConfig config
    private final CalendarReadGateway calendarRead
    private final TodoistReadGateway todoistRead
    private final TodoistWriteGateway todoistWrite
    private final ApplicationStateStore applicationState
    private final CalendarWritebackStore writebackStore
    private final Supplier<Instant> clock

    PlannerCalendarWritebackService(PlannerConfig config,
                                    CalendarReadGateway calendarRead,
                                    TodoistReadGateway todoistRead,
                                    TodoistWriteGateway todoistWrite,
                                    ApplicationStateStore applicationState,
                                    CalendarWritebackStore writebackStore,
                                    Supplier<Instant> clock = { Instant.now() }) {
        if ([config, calendarRead, todoistRead, todoistWrite, applicationState, writebackStore].any { it == null }) {
            throw new IllegalArgumentException('planner writeback dependencies are required')
        }
        this.config = config
        this.calendarRead = calendarRead
        this.todoistRead = todoistRead
        this.todoistWrite = todoistWrite
        this.applicationState = applicationState
        this.writebackStore = writebackStore
        this.clock = clock ?: ({ Instant.now() } as Supplier<Instant>)
    }

    /** Poll and classify the managed output calendar. Checkpoint only after every result is classified. */
    List<Map> pollAndApply(Instant rangeStart, Instant rangeEnd) {
        if (!config.calendarWritebackEnabled) return Collections.emptyList()
        if (!(calendarRead instanceof CalendarChangeGateway)) {
            throw new IllegalStateException('selected planner calendar provider does not support change detection')
        }
        Map<String, AppliedMapping> mappings = applicationState.loadMappings()
        Set<String> candidateUids = mappings.values()*.eventUid.findAll { it } as Set<String>

        String calendarName = config.outputCalendar
        Map priorState = writebackStore.calendar(calendarName)
        Map changes
        try {
            changes = (calendarRead as CalendarChangeGateway).readChanges(
                calendarName, priorState, candidateUids, rangeStart, rangeEnd)
        } catch (CalendarCursorExpiredException ignored) {
            // Only the rejected cursor is invalid. Baselines and the separate echo ledger survive.
            Map recoveryState = new LinkedHashMap(priorState)
            recoveryState.remove('cursor')
            changes = (calendarRead as CalendarChangeGateway).readChanges(
                calendarName, recoveryState, candidateUids, rangeStart, rangeEnd)
        }

        Map<String, Map> tasks = tasksById()
        List<Map> outcomes = []
        Map disagreements = priorState.disagreements instanceof Map
            ? new LinkedHashMap(priorState.disagreements as Map) : [:]
        (changes.events ?: []).each { CalendarEvent event ->
            Map outcome = applyOwnedMove(event, mappings.values(), tasks[eventTaskId(event, mappings.values())])
            outcomes << outcome
            if (outcome.action == CalendarWritebackDecision.SKIP && outcome.task instanceof Task) {
                Task task = outcome.task as Task
                disagreements[event.uid] = [taskId: task.id, due: task.dueTime?.toString(),
                    start: event.start?.toString(), taskUpdated: task.updatedAt?.toString(),
                    calendarModified: event.lastModified?.toString(), recordedAt: clock.get().toString()]
            } else if (outcome.action != CalendarWritebackDecision.SKIP) {
                disagreements.remove(event.uid)
            }
            if (outcome.adopted == true) {
                AppliedMapping prior = outcome.mapping as AppliedMapping
                AppliedMapping adopted = adoptedMapping(prior, event, clock.get())
                applicationState.putMapping(adopted)
                mappings[adopted.taskId] = adopted
            }
        }
        (changes.deleted ?: []).each { Map deleted ->
            outcomes << [action: 'deleted', uid: deleted.uid?.toString(), classified: true]
        }
        // Provider state is intentionally committed after classification/mutations, never before.
        Map checkpoint = new LinkedHashMap(changes.state ?: priorState)
        checkpoint.disagreements = disagreements
        writebackStore.saveCalendar(calendarName, checkpoint)
        Collections.unmodifiableList(outcomes)
    }

    /**
     * Classify and, only for a strictly newer owned single-task move, update Todoist Due.
     * UPDATE_CALENDAR is returned to the apply caller; daemon polling leaves it for normal apply.
     */
    Map applyOwnedMove(CalendarEvent event, Collection<AppliedMapping> mappings, Map rawTask = null) {
        if (!config.calendarWritebackEnabled) return [action: 'disabled', classified: true]
        if (!CalendarWritebackDecision.plannerCandidate(event, config.outputCalendar, mappings ?: [])) {
            return [action: CalendarWritebackDecision.SKIP, classified: true, candidate: false]
        }
        List<AppliedMapping> matching = (mappings ?: []).findAll { it.eventUid == event.uid }
        AppliedMapping mapping = matching[0]
        Map taskRaw = rawTask ?: tasksById()[mapping.taskId]
        if (taskRaw == null) {
            return [action: CalendarWritebackDecision.SKIP, classified: true,
                    candidate: false, reason: 'missing_task', mapping: mapping]
        }
        Task task = Task.fromTodoistMap(taskRaw, config.durationResolver, config.manualLabel, config.timezone)
        boolean recurring = task.todoistDue?.recurring == true
        boolean echo = CalendarWritebackDecision.echo(event,
            writebackStore.echo(event.calendarName, event.uid))
        String action = CalendarWritebackDecision.decide(task.dueTime, task.updatedAt,
            event.start, event.lastModified, recurring, echo)

        if (action == CalendarWritebackDecision.UPDATE_TODOIST) {
            todoistWrite.updateTaskDue(task.id, DateTimeFormatter.ISO_INSTANT.format(event.start))
            return [action: action, classified: true, adopted: true, mapping: mapping, task: task]
        }
        if (action == CalendarWritebackDecision.GUARDED_RECURRING) {
            if (!(todoistWrite instanceof TodoistLifecycleGateway)) {
                throw new IllegalStateException('recurring calendar writeback requires TodoistLifecycleGateway')
            }
            String replacement = task.todoistDue.timezone
                ? DateTimeFormatter.ISO_INSTANT.format(event.start)
                : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(event.start.atZone(config.timezone).toLocalDateTime())
            GuardedRecurringDueUpdate.update(todoistWrite as TodoistLifecycleGateway,
                config, task.id, replacement)
            return [action: action, classified: true, adopted: true, mapping: mapping, task: task]
        }
        [action: action, classified: true, adopted: false, mapping: mapping, task: task]
    }

    void recordCalendarWrite(CalendarEvent event, Instant writtenAt = clock.get()) {
        if (config.calendarWritebackEnabled) writebackStore.recordWrite(event, writtenAt)
    }

    private Map<String, Map> tasksById() {
        todoistRead.fetchTasks().findAll { it.id != null }.collectEntries { [(it.id.toString()): it] }
    }

    private static String eventTaskId(CalendarEvent event, Collection<AppliedMapping> mappings) {
        List<String> ids = (mappings ?: []).findAll { it.eventUid == event.uid }*.taskId.unique()
        ids.size() == 1 ? ids[0] : null
    }

    static AppliedMapping adoptedMapping(AppliedMapping prior, CalendarEvent event, Instant at) {
        Map metadata = new LinkedHashMap<>(prior.metadata ?: [:])
        metadata.putAll([calendarWritebackAdopted: true,
                         blockStart: event.start.toString(), blockEnd: event.end.toString()])
        AppliedMapping.builder()
            .taskId(prior.taskId).seriesTaskId(prior.seriesTaskId).occurrenceKey(prior.occurrenceKey)
            .blockId(prior.blockId).eventUid(prior.eventUid)
            .slotStart(event.start).slotEnd(event.end)
            .planId(prior.planId).planVersion(prior.planVersion).planHash(prior.planHash)
            .approvalId(prior.approvalId).approvalTime(prior.approvalTime).appliedAt(at)
            .calendarStatus(ApplyItemStatus.SKIPPED_IDEMPOTENT).todoistStatus(ApplyItemStatus.APPLIED)
            .metadata(metadata).build()
    }
}
