package todoistcaldavsync.planner.scheduling

import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Deterministic placement scoring. Higher is better.
 * Hard infeasibility is represented as {@link #INFEASIBLE} (not a soft penalty).
 *
 * score =
 *   deadline_urgency
 * + Todoist_priority_weight
 * + project_batching_bonus
 * + preferred_context_bonus
 * + weather_suitability_bonus
 * - soft_conflict_penalty
 * - context_switch_penalty
 * - task_move_churn_penalty
 * - fragmented_slot_penalty
 */
class PlanScorer {
    static final long INFEASIBLE = Long.MIN_VALUE / 4

    private final PlannerConfig config
    private final DeadlinePolicy deadlinePolicy

    PlanScorer(PlannerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException('PlannerConfig is required')
        }
        this.config = config
        this.deadlinePolicy = DeadlinePolicy.from(config)
    }

    DeadlinePolicy getDeadlinePolicy() { deadlinePolicy }

    /**
     * Priority weight: Todoist 4 (P1) is highest urgency.
     */
    static long priorityWeight(int priority) {
        switch (priority) {
            case 4: return 400L
            case 3: return 250L
            case 2: return 120L
            case 1: return 40L
            default: return 0L
        }
    }

    /**
     * Deadline urgency: sooner deadlines score higher. No deadline is mild filler preference.
     */
    /**
     * Deadline urgency from the shared policy. Hard lateness is infeasible.
     * Inside the applicable local-day window, time urgency rises as the Deadline approaches
     * and scarcity of remaining feasible capacity raises it further. Overdue soft tasks keep
     * the elevated window urgency instead of falling through to a lateness penalty that can
     * erase it. Outside the window, only a mild horizon term remains, so a distant hard task
     * does not unconditionally outrank nearer work.
     *
     * @param scarcityMinutes bounded scarcity from {@link DeadlinePolicy#scarcity}, or null when
     *        pre-Deadline coverage is unknown. Unknown coverage adds no invented pressure.
     */
    long deadlineUrgency(Task task, Instant placementEnd, Instant now, Instant rangeEnd,
                         Long scarcityMinutes = null) {
        if (task.deadline == null) {
            return 10L
        }
        boolean hard = isHard(task)
        boolean late = placementEnd != null && placementEnd.isAfter(task.deadline)
        if (late && hard) return INFEASIBLE
        boolean inWindow = deadlinePolicy.inUrgencyWindow(task, now)
        if (!inWindow && !late) {
            Instant horizon = rangeEnd ?: (now + Duration.ofDays(14))
            long totalSec = Math.max(1L, Duration.between(now, horizon).seconds)
            long remainingSec = Duration.between(now, task.deadline).seconds
            if (remainingSec <= 0) return 40L
            double ratio = 1.0d - Math.min(1.0d, (double) remainingSec / (double) totalSec)
            return Math.round(ratio * 40.0d)
        }
        long time = deadlinePolicy.timeUrgency(task, now)
        long scarcity = scarcityMinutes == null ? 0L : Math.max(0L, Math.min(DeadlinePolicy.SCARCITY_SPAN, scarcityMinutes))
        long floor = hard ? DeadlinePolicy.HARD_WINDOW_FLOOR : DeadlinePolicy.SOFT_WINDOW_FLOOR
        // Scarcity separates otherwise equal deadlines without exceeding one priority step,
        // so a constrained P4 does not jump a roomy P1 that shares the same Deadline.
        long pressure = Math.min(30L, scarcity / 20L)
        // Overdue soft keeps the window floor plus saturated time urgency. The old capped
        // lateness penalty must not replace it.
        if (late && !hard) {
            return floor + DeadlinePolicy.TIME_URGENCY_SPAN + pressure
        }
        return floor + time + pressure
    }

    long contextScore(Task task, Instant start, Instant end) {
        ZoneId zone = config.timezone
        ZonedDateTime zs = start.atZone(zone)
        ZonedDateTime ze = end.atZone(zone)
        def contexts = config.contextsFor(task)
        if (!contexts) {
            return 0L
        }
        long best = 0L
        long worstAvoid = 0L
        contexts.each { ctx ->
            boolean preferred = ctx.preferredWindows.any { it.overlapsInstantRange(zs, ze) }
            if (preferred) {
                best = Math.max(best, (long) ctx.preferredBonus)
            } else if (ctx.preferredWindows) {
                // Has preferred windows but none match — mild avoid
                worstAvoid = Math.max(worstAvoid, (long) ctx.avoidPenalty)
            }
        }
        return best - worstAvoid
    }

    /**
     * Soft penalty when the placement interval intersects any soft-penalized reporting slot.
     */
    long softConflictPenaltyForRange(List<TimeSlot> reportingSlots, Instant start, Instant end) {
        if (!reportingSlots) {
            return 0L
        }
        long penalty = 0L
        reportingSlots.each { s ->
            if (s.softBlocked && s.end.isAfter(start) && s.start.isBefore(end)) {
                long overlap = Duration.between(
                    s.start.isBefore(start) ? start : s.start,
                    s.end.isAfter(end) ? end : s.end
                ).toMinutes()
                if (overlap > 0) {
                    penalty += 30L + Math.min(30L, overlap)
                }
            }
        }
        return penalty
    }

    long contextSwitchPenalty(String previousProjectId, Task task) {
        if (!previousProjectId || !task.projectId) {
            return 0L
        }
        if (previousProjectId == task.projectId) {
            return 0L
        }
        return (long) config.batching.contextSwitchPenalty
    }

    long projectBatchBonus(boolean batchedWithSameProject) {
        if (!batchedWithSameProject || !config.batching.enabled) {
            return 0L
        }
        return (long) config.batching.projectBatchBonus
    }

    long churnPenalty(Task task, Instant proposedStart, Instant previousStart, boolean manualOverride, Instant now) {
        if (previousStart == null || proposedStart == null) {
            return 0L
        }
        if (previousStart == proposedStart) {
            return 0L
        }
        long base = (long) config.stability.churnPenalty
        if (manualOverride && config.stability.keepManualMoves) {
            return base * 3L
        }
        Instant freezeUntil = now + config.stability.freezeWithin
        if (!previousStart.isAfter(freezeUntil)) {
            return base * 2L
        }
        return base
    }

    long fragmentedSlotPenalty(long slotMinutes, long needMinutes) {
        if (slotMinutes <= 0 || needMinutes <= 0) {
            return 0L
        }
        long leftover = slotMinutes - needMinutes
        if (leftover > 0 && leftover < 15) {
            return 10L
        }
        return 0L
    }

    /**
     * Full score for placing {@code task} at [start, end) in a candidate slot.
     * @param weatherScoreDelta optional soft weather suitability bonus (hard weather
     *        infeasibility must be applied by the caller before scoring, or pass
     *        {@link #INFEASIBLE} via rejecting the candidate). Negative deltas are ignored.
     */
    long scorePlacement(Task task, Instant start, Instant end, TimeSlot placeableSlot,
                        List<TimeSlot> reportingSlots, Instant now, Instant rangeEnd,
                        String previousProjectId, Instant previousStart, boolean manualOverride,
                        boolean batchedWithSameProject, long weatherScoreDelta = 0L) {
        if (start == null || end == null || !end.isAfter(start)) {
            return INFEASIBLE
        }
        if (isHard(task) && task.deadline != null && end.isAfter(task.deadline)) {
            return INFEASIBLE
        }
        long score = 0L
        Long scarcity = scarcityFor(task, now, placeableSlot)
        long urg = deadlineUrgency(task, end, now, rangeEnd, scarcity)
        if (urg == INFEASIBLE) {
            return INFEASIBLE
        }
        score += urg
        score += priorityWeight(task.priority)
        score += projectBatchBonus(batchedWithSameProject)
        score += contextScore(task, start, end)
        if (weatherScoreDelta > 0L) {
            score += weatherScoreDelta
        }
        score -= softConflictPenaltyForRange(reportingSlots, start, end)
        score -= contextSwitchPenalty(previousProjectId, task)
        score -= churnPenalty(task, start, previousStart, manualOverride, now)
        long needMins = Duration.between(start, end).toMinutes()
        // Fragmentation uses the native Deadline even when it is soft. Clipping only the hard
        // cutoff made a soft task's leftover look like a full slot and hid the penalty.
        long slotMins = usableSlotMinutes(placeableSlot, start, end, task.deadline)
        score -= fragmentedSlotPenalty(slotMins, needMins)
        return score
    }

    boolean isHard(Task task) {
        deadlinePolicy.isHard(task)
    }

    /**
     * Scarcity against the candidate slot only. The scheduler adds competing-work pressure
     * when it knows the rest of the queue; unknown coverage stays null rather than zero.
     */
    private Long scarcityFor(Task task, Instant now, TimeSlot placeableSlot) {
        if (task?.deadline == null || !deadlinePolicy.inUrgencyWindow(task, now)) return null
        if (placeableSlot == null) return null
        Instant end = placeableSlot.end.isAfter(task.deadline) ? task.deadline : placeableSlot.end
        if (!end.isAfter(placeableSlot.start)) return DeadlinePolicy.SCARCITY_SPAN
        long usable = Duration.between(placeableSlot.start, end).toMinutes()
        DeadlinePolicy.OptionalLong pressure = deadlinePolicy.scarcity(task, usable, 0L)
        return pressure.present ? pressure.value : null
    }

    /**
     * Minutes of placeable capacity usable for fragmentation scoring.
     * Clips the candidate slot end at the task deadline so leftover past the deadline
     * does not dilute the fragmented-slot penalty.
     */
    static long usableSlotMinutes(TimeSlot placeableSlot, Instant placementStart, Instant placementEnd,
                                  Instant deadline) {
        if (placeableSlot == null) {
            if (placementStart == null || placementEnd == null || !placementEnd.isAfter(placementStart)) {
                return 0L
            }
            return Duration.between(placementStart, placementEnd).toMinutes()
        }
        Instant slotStart = placeableSlot.start
        Instant slotEnd = placeableSlot.end
        if (deadline != null && slotEnd.isAfter(deadline)) {
            slotEnd = deadline
        }
        if (slotStart == null || slotEnd == null || !slotEnd.isAfter(slotStart)) {
            return 0L
        }
        return Duration.between(slotStart, slotEnd).toMinutes()
    }

    /**
     * Deterministic ordering of otherwise equivalent tasks.
     * Target date, then native priority, then stable id. Hardness is not an unconditional key:
     * hard-capacity protection is a placement constraint, not a sort.
     */
    static int compareTaskOrder(Task a, Task b) {
        Instant da = a.deadline ?: Instant.MAX
        Instant db = b.deadline ?: Instant.MAX
        // Equal targets keep native priority ahead of id. A lower id must not claim the only
        // slot from a higher native priority that shares the Deadline.
        int c = da <=> db
        if (c != 0) {
            return c
        }
        c = (b.priority <=> a.priority)
        if (c != 0) {
            return c
        }
        return (a.id ?: '') <=> (b.id ?: '')
    }
}
