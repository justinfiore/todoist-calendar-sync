package todoistcaldavsync.planner.scheduling

import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task

import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.NavigableSet
import java.util.TreeSet

/**
 * Groups same-project tasks into focus-block candidates when batching is enabled.
 * Does not delay urgent deadline tasks merely for a batching bonus — callers must
 * still validate deadline feasibility per member and may split groups.
 *
 * Context compatibility: tasks are only packed into the same focus block when their
 * configured context signatures are compatible. Signature is the sorted set of matched
 * {@link PlannerConfig#contextsFor} context names (normalized lower-case). Empty signature
 * (no matched context) batches only with other empty-signature tasks — never with a
 * labeled context group. Distinct non-empty signatures never batch together.
 */
class ProjectBatcher {
    private final PlannerConfig config

    ProjectBatcher(PlannerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException('PlannerConfig is required')
        }
        this.config = config
    }

    /**
     * Build ordered scheduling units: multi-task focus groups when feasible, else singles.
     * Input tasks should already be planner candidates (non-manual).
     */
    List<SchedulingUnit> buildUnits(List<Task> tasks, Instant now) {
        if (!tasks) {
            return []
        }
        def ordered = tasks.toSorted { a, b -> PlanScorer.compareTaskOrder(a, b) }
        if (!config.batching.enabled) {
            return ordered.collect { SchedulingUnit.single(it) }
        }

        Map<String, List<Task>> byProject = new LinkedHashMap<>()
        ordered.each { t ->
            def key = t.projectId ?: ''
            if (key) {
                byProject.computeIfAbsent(key) { [] as List<Task> } << t
            }
        }

        List<SchedulingUnit> units = []
        Set<String> emitted = new HashSet<>()
        ordered.each { t ->
            if (!t.projectId) {
                if (!emitted.contains(t.id)) {
                    units << SchedulingUnit.single(t)
                    emitted.add(t.id)
                }
                return
            }
            if (emitted.contains(t.id)) {
                return
            }
            List<Task> group = byProject[t.projectId] ?: [t]
            List<SchedulingUnit> split = splitIntoFocusUnits(group, now)
            split.each { u ->
                u.tasks.each { emitted.add(it.id) }
                units << u
            }
        }
        return units
    }

    /**
     * Pack same-project tasks into focus blocks respecting max focus duration and
     * context compatibility. Member order inside each unit is {@link PlanScorer#compareTaskOrder}.
     */
    private List<SchedulingUnit> splitIntoFocusUnits(List<Task> projectTasks, Instant now) {
        def sorted = projectTasks.toSorted { a, b -> PlanScorer.compareTaskOrder(a, b) }
        int maxFocus = config.batching.maxFocusBlockMinutes
        int minFocus = config.batching.minimumFocusBlockMinutes

        // Group by context signature first so incompatible contexts never share a pack.
        Map<String, List<Task>> bySignature = new LinkedHashMap<>()
        sorted.each { t ->
            String sig = contextSignature(t)
            bySignature.computeIfAbsent(sig) { [] as List<Task> } << t
        }

        List<SchedulingUnit> units = []
        // Emit signature groups in order of first appearance in sorted list
        Set<String> seenSigs = new LinkedHashSet<>()
        sorted.each { t -> seenSigs.add(contextSignature(t)) }
        seenSigs.each { sig ->
            List<Task> cohort = bySignature[sig]
            packByDuration(cohort, maxFocus, minFocus, units)
        }
        return units
    }

    private static void packByDuration(List<Task> sortedCohort, int maxFocus, int minFocus,
                                       List<SchedulingUnit> units) {
        List<Task> current = []
        long currentMinutes = 0L
        sortedCohort.each { task ->
            long need = task.effectiveDuration.toMinutes()
            if (need > maxFocus) {
                flushCurrent(current, currentMinutes, minFocus, units)
                current = []
                currentMinutes = 0L
                units << SchedulingUnit.single(task)
                return
            }
            if (current && currentMinutes + need > maxFocus) {
                flushCurrent(current, currentMinutes, minFocus, units)
                current = []
                currentMinutes = 0L
            }
            current << task
            currentMinutes += need
        }
        flushCurrent(current, currentMinutes, minFocus, units)
    }

    /**
     * Deterministic context signature for batching compatibility.
     * Sorted matched configured context names, lower-case, joined by ','.
     * Empty string means no matched context (contextless / general).
     */
    String contextSignature(Task task) {
        def contexts = config.contextsFor(task)
        if (!contexts) {
            return ''
        }
        NavigableSet<String> names = new TreeSet<>()
        contexts.each { ctx ->
            if (ctx?.name) {
                names.add(ctx.name.toLowerCase(Locale.ROOT))
            }
        }
        return names.join(',')
    }

    /**
     * True when two tasks may share a focus block under context rules.
     */
    boolean contextsCompatible(Task a, Task b) {
        contextSignature(a) == contextSignature(b)
    }

    private static void flushCurrent(List<Task> current, long currentMinutes, int minFocus, List<SchedulingUnit> units) {
        if (!current) {
            return
        }
        if (current.size() == 1) {
            units << SchedulingUnit.single(current[0])
            return
        }
        // Only form a multi-task focus block when total duration meets minimumFocusBlockMinutes.
        // Below the minimum, emit singles so tiny groups are not marked as focus blocks.
        if (currentMinutes >= minFocus) {
            units << SchedulingUnit.focus(new ArrayList<>(current), currentMinutes)
        } else {
            current.each { units << SchedulingUnit.single(it) }
        }
    }

    /**
     * A single schedulable unit: one task or an explicit multi-task focus block.
     */
    static final class SchedulingUnit {
        final List<Task> tasks
        final boolean focusBlock
        final long totalMinutes

        private SchedulingUnit(List<Task> tasks, boolean focusBlock, long totalMinutes) {
            this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks))
            this.focusBlock = focusBlock
            this.totalMinutes = totalMinutes
        }

        static SchedulingUnit single(Task task) {
            new SchedulingUnit([task], false, task.effectiveDuration.toMinutes())
        }

        static SchedulingUnit focus(List<Task> tasks, long totalMinutes) {
            new SchedulingUnit(tasks, true, totalMinutes)
        }

        String projectId() {
            tasks[0]?.projectId
        }

        String projectName() {
            tasks[0]?.projectName
        }

        Instant earliestDeadline() {
            Instant best = null
            tasks.each { t ->
                if (t.deadline != null && (best == null || t.deadline.isBefore(best))) {
                    best = t.deadline
                }
            }
            return best
        }

        String primaryId() {
            tasks.collect { it.id }.sort().join('+')
        }

        String title() {
            if (!focusBlock || tasks.size() == 1) {
                return tasks[0].content
            }
            def pname = projectName() ?: 'Project'
            return "${pname} focus block"
        }
    }
}
