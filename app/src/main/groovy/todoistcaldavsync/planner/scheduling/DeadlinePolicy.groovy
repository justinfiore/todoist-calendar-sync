package todoistcaldavsync.planner.scheduling

import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * One recurrence-independent hard/soft classification for native Deadlines.
 * Scoring, clipping, retained placements, focus validation, and risk reporting
 * all ask this type rather than reading recurrence labels themselves.
 *
 * A missing Deadline is unclassified. Configured hard and soft labels override
 * {@code default_deadline_mode}; when both match, hard wins and the conflict is named.
 */
final class DeadlinePolicy {
    static final String MODE_HARD = 'hard'
    static final String MODE_SOFT = 'soft'
    static final int DEFAULT_WINDOW_DAYS = 7

    /** Time component grows by this much from the window edge to the Deadline instant. */
    static final long TIME_URGENCY_SPAN = 600L
    /** Scarcity adds at most this much on top of time urgency. Overdue uses the same ceiling. */
    static final long SCARCITY_SPAN = 400L
    /**
     * Floor for an in-window soft task. P4 weight is 40 and P1 weight is 400, so this
     * plus any positive time or scarcity term outranks a no-Deadline P1 (400 + filler 10).
     */
    static final long SOFT_WINDOW_FLOOR = 420L
    /** Hard urgency stays strictly above the soft ceiling. */
    static final long HARD_WINDOW_FLOOR = 1100L

    final String defaultMode
    final String hardLabel
    final String softLabel
    final int hardUrgencyDays
    final int softUrgencyDays
    final int hardWarningDays
    final int softWarningDays
    final ZoneId zone

    DeadlinePolicy(String defaultMode, String hardLabel, String softLabel,
                   int hardUrgencyDays, int softUrgencyDays,
                   int hardWarningDays, int softWarningDays, ZoneId zone) {
        this.defaultMode = defaultMode == MODE_SOFT ? MODE_SOFT : MODE_HARD
        this.hardLabel = blankToNull(hardLabel)
        this.softLabel = blankToNull(softLabel)
        this.hardUrgencyDays = hardUrgencyDays
        this.softUrgencyDays = softUrgencyDays
        this.hardWarningDays = hardWarningDays
        this.softWarningDays = softWarningDays
        this.zone = zone ?: ZoneId.of('UTC')
    }

    static DeadlinePolicy from(PlannerConfig config) {
        if (config == null) {
            return new DeadlinePolicy(MODE_HARD, null, null,
                DEFAULT_WINDOW_DAYS, DEFAULT_WINDOW_DAYS, DEFAULT_WINDOW_DAYS, DEFAULT_WINDOW_DAYS, ZoneId.of('UTC'))
        }
        PlannerConfig.DeadlinePolicyConfig policy = config.deadlinePolicy
        return new DeadlinePolicy(
            policy?.defaultMode ?: MODE_HARD,
            policy?.hardLabel, policy?.softLabel,
            policy?.hardUrgencyDays ?: DEFAULT_WINDOW_DAYS,
            policy?.softUrgencyDays ?: DEFAULT_WINDOW_DAYS,
            policy?.hardWarningDays ?: DEFAULT_WINDOW_DAYS,
            policy?.softWarningDays ?: DEFAULT_WINDOW_DAYS,
            config.timezone)
    }

    /** Stable identity mixed into plan hashes so a policy change cannot reuse an approval. */
    String identity() {
        return "mode=${defaultMode}|hardLabel=${hardLabel ?: ''}|softLabel=${softLabel ?: ''}|" +
            "hardUrgency=${hardUrgencyDays}|softUrgency=${softUrgencyDays}|" +
            "hardWarning=${hardWarningDays}|softWarning=${softWarningDays}|zone=${zone.id}"
    }

    Classification classify(Task task) {
        if (task?.deadline == null && task?.deadlineDate == null) {
            return Classification.none()
        }
        boolean hardMatch = matches(task, hardLabel)
        boolean softMatch = matches(task, softLabel)
        String mode
        if (hardMatch) {
            mode = MODE_HARD
        } else if (softMatch) {
            mode = MODE_SOFT
        } else {
            mode = defaultMode
        }
        String conflict = (hardMatch && softMatch)
            ? "Deadline labels conflict: '${hardLabel}' and '${softLabel}' both match; hard treatment wins"
            : null
        return new Classification(true, mode == MODE_HARD, mode, conflict)
    }

    boolean isHard(Task task) {
        classify(task).hard
    }

    /** Finish-by instant for a hard Deadline; null when the task is soft or has no Deadline. */
    Instant constraintDeadline(Task task) {
        if (!isHard(task)) return null
        return task.deadline
    }

    int urgencyDays(Task task) {
        isHard(task) ? hardUrgencyDays : softUrgencyDays
    }

    int warningDays(Task task) {
        isHard(task) ? hardWarningDays : softWarningDays
    }

    /**
     * Local calendar-day membership. Deadline date on or before today plus the window,
     * including overdue dates. Not a fixed 24-hour multiple.
     */
    boolean inWindow(LocalDate deadlineDate, LocalDate today, int windowDays) {
        if (deadlineDate == null || today == null || windowDays < 1) return false
        return !deadlineDate.isAfter(today.plusDays(windowDays))
    }

    boolean inUrgencyWindow(Task task, Instant now) {
        Classification c = classify(task)
        if (!c.classified) return false
        LocalDate today = now.atZone(zone).toLocalDate()
        LocalDate deadlineDate = task.deadlineDate ?: now.atZone(zone).toLocalDate()
        // An overdue deadline whose date is missing still counts: the instant has passed.
        if (task.deadline != null && !task.deadline.isAfter(now)) return true
        return inWindow(civilDeadline(task, deadlineDate), today, urgencyDays(task))
    }

    /**
     * Date-only Deadline is stored as exclusive next local midnight. That instant belongs to the
     * previous civil date. A missing date falls back to the instant's own local date.
     */
    LocalDate civilDeadline(Task task, LocalDate fallback = null) {
        if (task?.deadlineDate != null) return task.deadlineDate
        if (task?.deadline == null) return fallback
        def zoned = task.deadline.atZone(zone)
        if (zoned.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
            return zoned.toLocalDate().minusDays(1)
        }
        return zoned.toLocalDate()
    }

    boolean inWarningWindow(Task task, Instant now) {
        Classification c = classify(task)
        if (!c.classified) return false
        LocalDate today = now.atZone(zone).toLocalDate()
        LocalDate deadlineDate = civilDeadline(task)
        if (deadlineDate == null) return false
        return inWindow(deadlineDate, today, warningDays(task))
    }

    /**
     * Monotonic time urgency in {@code [0, TIME_URGENCY_SPAN]}. Less remaining time scores higher.
     * Overdue saturates at the span. Outside the urgency window this is 0.
     */
    long timeUrgency(Task task, Instant now) {
        if (task?.deadline == null || now == null) return 0L
        if (!inUrgencyWindow(task, now)) return 0L
        if (!task.deadline.isAfter(now)) return TIME_URGENCY_SPAN
        long remaining = Math.max(0L, Duration.between(now, task.deadline).toMinutes())
        long window = Math.max(1L, urgencyDays(task) * 24L * 60L)
        double ratio = 1.0d - Math.min(1.0d, remaining / (double) window)
        return Math.round(ratio * TIME_URGENCY_SPAN)
    }

    /**
     * Bounded scarcity in {@code [0, SCARCITY_SPAN]}. {@code usableMinutesBeforeDeadline} is eligible
     * pre-Deadline capacity with overlapping intervals already merged. {@code competingMinutes} is
     * other not-yet-placed work that can also use that capacity. Unknown coverage returns empty.
     */
    OptionalLong scarcity(Task task, Long usableMinutesBeforeDeadline, long competingMinutes) {
        if (task?.deadline == null) return OptionalLong.empty()
        if (usableMinutesBeforeDeadline == null) return OptionalLong.empty()
        long need = task.effectiveDuration != null ? Math.max(1L, task.effectiveDuration.toMinutes()) : 30L
        long usable = Math.max(0L, usableMinutesBeforeDeadline)
        long competing = Math.max(0L, competingMinutes)
        long slack = usable - need - competing
        if (slack <= 0L) return OptionalLong.of(SCARCITY_SPAN)
        // Slack of one full duration is no pressure; tighter slack rises monotonically.
        double pressure = 1.0d - Math.min(1.0d, slack / (double) need)
        return OptionalLong.of(Math.round(pressure * SCARCITY_SPAN))
    }

    private static boolean matches(Task task, String label) {
        if (!label || task?.labels == null) return false
        task.labels.any { it != null && it.equalsIgnoreCase(label) }
    }

    private static String blankToNull(String value) {
        if (value == null) return null
        String trimmed = value.trim()
        trimmed.isEmpty() ? null : trimmed
    }

    static final class Classification {
        final boolean classified
        final boolean hard
        final String mode
        final String conflict

        Classification(boolean classified, boolean hard, String mode, String conflict) {
            this.classified = classified
            this.hard = hard
            this.mode = mode
            this.conflict = conflict
        }

        static Classification none() {
            new Classification(false, false, null, null)
        }

        String modeLabel() {
            hard ? MODE_HARD : (classified ? MODE_SOFT : 'none')
        }
    }

    /** Java 8 stand-in so callers can tell unknown coverage from a real zero. */
    static final class OptionalLong {
        final boolean present
        final long value

        private OptionalLong(boolean present, long value) {
            this.present = present
            this.value = value
        }

        static OptionalLong of(long value) { new OptionalLong(true, value) }
        static OptionalLong empty() { new OptionalLong(false, 0L) }
    }

    /** User-facing P1–P4 from Todoist API priority 4–1. */
    static String displayPriority(int apiPriority) {
        int p = 5 - apiPriority
        if (p < 1 || p > 4) return 'P?'
        return "P${p}"
    }

    static int apiPriority(String displayed) {
        if (displayed == null) {
            throw new IllegalArgumentException('priority is required')
        }
        String token = displayed.trim().toUpperCase(Locale.ROOT)
        if (token ==~ /P[1-4]/) return 5 - (token[1] as int)
        if (token ==~ /[1-4]/) {
            int api = token as int
            // Bare digits in user replies are user-facing P levels, not API values.
            return 5 - api
        }
        throw new IllegalArgumentException("priority must be P1, P2, P3, or P4, got: ${displayed}")
    }
}
