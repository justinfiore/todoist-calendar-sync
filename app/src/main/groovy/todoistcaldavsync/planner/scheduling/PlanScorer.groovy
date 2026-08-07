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

    PlanScorer(PlannerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException('PlannerConfig is required')
        }
        this.config = config
    }

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
    long deadlineUrgency(Task task, Instant placementEnd, Instant now, Instant rangeEnd) {
        if (task.deadline == null) {
            return 10L
        }
        if (placementEnd != null && placementEnd.isAfter(task.deadline)) {
            return INFEASIBLE
        }
        Instant horizon = rangeEnd ?: (now + Duration.ofDays(14))
        long totalSec = Math.max(1L, Duration.between(now, horizon).seconds)
        long remainingSec = Duration.between(now, task.deadline).seconds
        if (remainingSec <= 0) {
            return 500L
        }
        // 0..500 scaled by how soon the deadline is relative to horizon
        double ratio = 1.0d - Math.min(1.0d, (double) remainingSec / (double) totalSec)
        return Math.round(ratio * 500.0d)
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
        if (task.deadline != null && end.isAfter(task.deadline)) {
            return INFEASIBLE
        }
        long score = 0L
        long urg = deadlineUrgency(task, end, now, rangeEnd)
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
        long slotMins = usableSlotMinutes(placeableSlot, start, end, task.deadline)
        score -= fragmentedSlotPenalty(slotMins, needMins)
        // Prefer earlier starts as mild stable tie preference inside score (still secondary to id tie-break)
        long minutesFromNow = Duration.between(now, start).toMinutes()
        score -= Math.min(50L, Math.max(0L, minutesFromNow / 60L))
        return score
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
     * Deterministic ordering of tasks before greedy placement.
     * Deadline soonest, then priority desc, then id.
     */
    static int compareTaskOrder(Task a, Task b) {
        Instant da = a.deadline ?: Instant.MAX
        Instant db = b.deadline ?: Instant.MAX
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
