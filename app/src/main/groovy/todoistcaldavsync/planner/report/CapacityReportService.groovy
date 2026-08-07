package todoistcaldavsync.planner.report

import todoistcaldavsync.planner.adapters.CalendarReadGateway
import todoistcaldavsync.planner.adapters.TodoistReadGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.PlanningExplanation
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.policy.EventClassifier
import todoistcaldavsync.planner.scheduling.AvailabilityCalculator

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Read-only capacity diagnostics. Accepts only TodoistReadGateway and CalendarReadGateway.
 * Write interfaces remain separate and unwired.
 */
class CapacityReportService {
    private final PlannerConfig config
    private final TodoistReadGateway todoistGateway
    private final CalendarReadGateway calendarGateway
    private final EventClassifier classifier
    private final AvailabilityCalculator availabilityCalculator

    CapacityReportService(PlannerConfig config, TodoistReadGateway todoistGateway, CalendarReadGateway calendarGateway) {
        if (config == null) {
            throw new IllegalArgumentException('config required')
        }
        if (todoistGateway == null || calendarGateway == null) {
            throw new IllegalArgumentException('read gateways required')
        }
        this.config = config
        this.todoistGateway = todoistGateway
        this.calendarGateway = calendarGateway
        this.classifier = new EventClassifier(config)
        this.availabilityCalculator = new AvailabilityCalculator(config)
    }

    CapacityReport generate(Instant rangeStart, Instant rangeEnd) {
        def rawTasks = todoistGateway.fetchTasks() ?: []
        List<Task> allTasks = rawTasks.collect {
            Task.fromTodoistMap(it as Map, config.durationResolver, config.manualLabel, config.timezone)
        }

        List<Task> manualExcluded = allTasks.findAll { it.manual }
        List<Task> candidates = allTasks.findAll { !it.manual }

        if (config.schedulingEligibleLabels) {
            candidates = candidates.findAll { task ->
                task.labels.any { label -> config.schedulingEligibleLabels.any { it.equalsIgnoreCase(label) } }
            }
        }

        List<CalendarEvent> rawEvents = calendarGateway.fetchEvents(rangeStart, rangeEnd) ?: []
        List<CalendarEvent> classified = classifier.classifyAll(rawEvents)
        def availability = availabilityCalculator.calculate(rangeStart, rangeEnd, classified)

        long demandMinutes = 0L
        candidates.each { demandMinutes += it.effectiveDuration.toMinutes() }
        long usable = availability.usableCapacityMinutes

        // Placement uses contiguous placeable intervals (soft splits are diagnostic-only)
        List<TimeSlot> placeable = AvailabilityCalculator.toPlaceableIntervals(availability.slots)
        List<DeadlineRisk> risks = assessDeadlineRisks(candidates, placeable, rangeStart, rangeEnd)
        List<PlanningExplanation> explanations = []
        explanations.addAll(classifier.explanationsFor(classified))
        explanations.addAll(availability.explanations)
        manualExcluded.each { t ->
            explanations << PlanningExplanation.of(
                'manual_excluded',
                "Task '${t.content}' excluded from planner candidates due to @${config.manualLabel} label",
                'task', t.id,
                [labels: t.labels]
            )
        }

        return new CapacityReport(
            rangeStart,
            rangeEnd,
            config.timezone,
            candidates,
            manualExcluded,
            classified,
            availability.slots,
            usable,
            demandMinutes,
            availability.softPenalizedMinutes,
            risks,
            explanations
        )
    }

    /**
     * Greedy fit of tasks (by deadline soonest, then priority desc, then id) into free slots
     * to identify tasks that cannot fit before their deadline within the horizon.
     */
    private List<DeadlineRisk> assessDeadlineRisks(List<Task> candidates, List<TimeSlot> slots,
                                                   Instant rangeStart, Instant rangeEnd) {
        // Working copy of remaining slot minutes as mutable intervals
        List<MutableSlot> remaining = slots.collect { new MutableSlot(it.start, it.end) }
        def ordered = candidates.toSorted { a, b ->
            def da = a.deadline ?: Instant.MAX
            def db = b.deadline ?: Instant.MAX
            def c = da <=> db
            if (c != 0) return c
            // higher Todoist priority number = more urgent (4 is P1)
            c = (b.priority <=> a.priority)
            if (c != 0) return c
            return a.id <=> b.id
        }

        List<DeadlineRisk> risks = []
        ordered.each { task ->
            long need = task.effectiveDuration.toMinutes()
            Instant latestEnd = task.deadline ?: rangeEnd
            boolean placed = tryPlace(remaining, need, rangeStart, latestEnd)
            if (!placed) {
                String reason
                if (task.deadline == null) {
                    reason = "No deadline; insufficient free capacity in horizon for ${need}m task (demand pressure)"
                } else if (task.deadline.isBefore(rangeStart)) {
                    reason = "Deadline ${task.deadline} is before planning range start"
                } else {
                    reason = "Cannot fit ${need}m before deadline ${task.deadline} given usable free slots"
                }
                risks << new DeadlineRisk(task, reason, need)
            }
        }
        return risks
    }

    private static boolean tryPlace(List<MutableSlot> remaining, long needMinutes, Instant notBefore, Instant latestEnd) {
        for (int i = 0; i < remaining.size(); i++) {
            def slot = remaining[i]
            Instant usableStart = slot.start.isBefore(notBefore) ? notBefore : slot.start
            Instant usableEnd = slot.end.isAfter(latestEnd) ? latestEnd : slot.end
            if (!usableEnd.isAfter(usableStart)) {
                continue
            }
            long avail = Duration.between(usableStart, usableEnd).toMinutes()
            if (avail >= needMinutes) {
                Instant newStart = usableStart.plus(Duration.ofMinutes(needMinutes))
                // shrink from left of this conceptual placement within slot
                if (usableStart == slot.start && newStart == slot.end) {
                    remaining.remove(i)
                } else if (usableStart == slot.start) {
                    slot.start = newStart
                } else if (newStart == slot.end) {
                    slot.end = usableStart
                } else {
                    // split: keep left remnant, shrink right
                    def right = new MutableSlot(newStart, slot.end)
                    slot.end = usableStart
                    remaining.add(i + 1, right)
                }
                // remove zero-length
                remaining.removeAll { !it.end.isAfter(it.start) }
                return true
            }
        }
        return false
    }

    private static final class MutableSlot {
        Instant start
        Instant end
        MutableSlot(Instant s, Instant e) {
            this.start = s
            this.end = e
        }
    }

    static final class DeadlineRisk {
        final Task task
        final String reason
        final long requiredMinutes

        DeadlineRisk(Task task, String reason, long requiredMinutes) {
            this.task = task
            this.reason = reason
            this.requiredMinutes = requiredMinutes
        }
    }

    static final class CapacityReport {
        final Instant rangeStart
        final Instant rangeEnd
        final ZoneId timezone
        final List<Task> candidateTasks
        final List<Task> manualExcludedTasks
        final List<CalendarEvent> classifiedEvents
        final List<TimeSlot> slots
        final long usableCapacityMinutes
        final long taskDemandMinutes
        final long softPenalizedMinutes
        final List<DeadlineRisk> deadlineRisks
        final List<PlanningExplanation> explanations

        CapacityReport(Instant rangeStart, Instant rangeEnd, ZoneId timezone,
                       List<Task> candidateTasks, List<Task> manualExcludedTasks,
                       List<CalendarEvent> classifiedEvents, List<TimeSlot> slots,
                       long usableCapacityMinutes, long taskDemandMinutes, long softPenalizedMinutes,
                       List<DeadlineRisk> deadlineRisks, List<PlanningExplanation> explanations) {
            this.rangeStart = rangeStart
            this.rangeEnd = rangeEnd
            this.timezone = timezone
            this.candidateTasks = Collections.unmodifiableList(new ArrayList<>(candidateTasks ?: []))
            this.manualExcludedTasks = Collections.unmodifiableList(new ArrayList<>(manualExcludedTasks ?: []))
            this.classifiedEvents = Collections.unmodifiableList(new ArrayList<>(classifiedEvents ?: []))
            this.slots = Collections.unmodifiableList(new ArrayList<>(slots ?: []))
            this.usableCapacityMinutes = usableCapacityMinutes
            this.taskDemandMinutes = taskDemandMinutes
            this.softPenalizedMinutes = softPenalizedMinutes
            this.deadlineRisks = Collections.unmodifiableList(new ArrayList<>(deadlineRisks ?: []))
            this.explanations = Collections.unmodifiableList(new ArrayList<>(explanations ?: []))
        }
    }
}
