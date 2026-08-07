package todoistcaldavsync.planner.scheduling

import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.MemberInterval
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.PlanningExplanation
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.domain.UnscheduledTask
import todoistcaldavsync.planner.scheduling.ProjectBatcher.SchedulingUnit

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.ArrayList
import java.util.Collections
import java.util.NavigableSet
import java.util.TreeSet

/**
 * Preview-only deterministic greedy scheduler.
 * Pure: no HTTP, credentials, Slack, weather-provider, LLM, or remote I/O.
 *
 * Inter-block buffer: candidate acceptance rejects any placement that overlaps
 * an occupied interval expanded by {@code minimumBufferBetweenBlocksMinutes}
 * on both sides (clamped to horizon/window boundaries). Placement does not rely
 * on the final hard-conflict assertion to catch buffer violations.
 *
 * {@code requireApprovalForMoveWithin} is preview-only metadata for a later
 * approval/apply step — this scheduler never writes remote calendars. Moves of
 * a previously placed task whose previous start falls within that horizon from
 * {@code now} are tagged with {@code approvalRequired=true} on the PlanChange.
 */
class DeterministicScheduler {
    private final PlannerConfig config
    private final PlanScorer scorer
    private final ProjectBatcher batcher

    DeterministicScheduler(PlannerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException('PlannerConfig is required')
        }
        this.config = config
        this.scorer = new PlanScorer(config)
        this.batcher = new ProjectBatcher(config)
    }

    /**
     * @param tasks planner candidates (caller should exclude @manual)
     * @param reportingSlots availability slots (may include soft splits)
     * @param rangeStart inclusive
     * @param rangeEnd exclusive
     * @param now planning "now" for freeze/churn (fixed for determinism)
     * @param previousPlan optional prior preview/applied plan for stability
     * @param manualOverrideTaskIds task ids the user manually moved (preserve by default)
     */
    Plan schedule(List<Task> tasks,
                  List<TimeSlot> reportingSlots,
                  Instant rangeStart,
                  Instant rangeEnd,
                  Instant now = rangeStart,
                  Plan previousPlan = null,
                  Set<String> manualOverrideTaskIds = [] as Set) {
        return propose(tasks, reportingSlots, rangeStart, rangeEnd, now, previousPlan, manualOverrideTaskIds)
    }

    /**
     * Default entry: schedule with automatic single-task split fallback for failed focus blocks.
     */
    Plan propose(List<Task> tasks, List<TimeSlot> reportingSlots,
                 Instant rangeStart, Instant rangeEnd, Instant now = rangeStart,
                 Plan previousPlan = null, Set<String> manualOverrideTaskIds = [] as Set) {
        if (rangeStart == null || rangeEnd == null || !rangeEnd.isAfter(rangeStart)) {
            throw new IllegalArgumentException('rangeStart/rangeEnd must form a positive interval')
        }
        Instant clock = now ?: rangeStart
        List<Task> candidates = (tasks ?: []).findAll { it != null && !it.manual }
        candidates = candidates.toSorted { a, b -> PlanScorer.compareTaskOrder(a, b) }
        Map<String, Task> taskById = candidates.collectEntries { [(it.id): it] }

        List<TimeSlot> placeable = AvailabilityCalculator.toPlaceableIntervals(reportingSlots ?: [])
        List<MutableSlot> remaining = placeable.collect { new MutableSlot(it.start, it.end) }
        // Occupied blocks used for bilateral buffer checks during placement (not post-hoc only).
        List<OccupiedInterval> occupied = []

        Map<String, PreviousPlacement> previousByTask = indexPrevious(previousPlan, taskById)
        Set<String> manualIds = new TreeSet<>(manualOverrideTaskIds ?: [])
        previousByTask.each { id, prev -> if (prev.manualOverride) manualIds.add(id) }

        List<ScheduledBlock> blocks = []
        List<PlanChange> changes = []
        List<PlanningExplanation> explanations = []
        List<UnscheduledTask> unscheduled = []
        Set<String> scheduledTaskIds = new LinkedHashSet<>()

        int bufferMinutes = config.stability.minimumBufferBetweenBlocksMinutes
        Instant freezeUntil = clock + config.stability.freezeWithin
        Instant approvalHorizon = clock + config.stability.requireApprovalForMoveWithin

        // Preserve multi-task frozen/manual focus blocks as whole units first
        Set<String> preservedFocusBlockIds = new HashSet<>()
        previousPlan?.scheduledBlocks?.each { prevBlock ->
            if (!(prevBlock.focusBlock && prevBlock.taskIds.size() > 1)) {
                return
            }
            boolean anyManual = prevBlock.taskIds.any { manualIds.contains(it) } || prevBlock.manualOverride
            boolean inFreeze = prevBlock.start != null && !prevBlock.start.isAfter(freezeUntil) &&
                !prevBlock.start.isBefore(rangeStart)
            if (!((anyManual && config.stability.keepManualMoves) || inFreeze || prevBlock.frozen)) {
                return
            }
            List<PreviousPlacement> members = prevBlock.taskIds.collect { previousByTask[it] }.findAll { it != null }
            if (members.size() != prevBlock.taskIds.size()) {
                return
            }
            // All members still candidates and not already scheduled
            if (prevBlock.taskIds.any { !taskById.containsKey(it) || scheduledTaskIds.contains(it) }) {
                return
            }
            Instant bStart = members.collect { it.start }.min()
            Instant bEnd = members.collect { it.end }.max()
            boolean deadlinesOk = prevBlock.taskIds.every { tid ->
                Task t = taskById[tid]
                t.deadline == null || !previousByTask[tid].end.isAfter(t.deadline)
            }
            if (!deadlinesOk) {
                return
            }
            if (!canOccupy(remaining, occupied, bStart, bEnd, bufferMinutes, rangeStart, rangeEnd)) {
                return
            }
            occupy(remaining, occupied, bStart, bEnd, bufferMinutes, rangeStart, rangeEnd)
            List<MemberInterval> memberIntervals = prevBlock.taskIds.collect { tid ->
                def p = previousByTask[tid]
                new MemberInterval(tid, p.start, p.end)
            }
            boolean frozen = inFreeze || prevBlock.frozen
            boolean isManual = anyManual
            Task primary = taskById[prevBlock.taskIds[0]]
            def block = ScheduledBlock.builder()
                .id(prevBlock.id ?: "block-focus-${prevBlock.taskIds.join('+')}")
                .start(bStart)
                .end(bEnd)
                .taskIds(prevBlock.taskIds)
                .memberIntervals(memberIntervals)
                .projectId(prevBlock.projectId ?: primary?.projectId)
                .projectName(prevBlock.projectName ?: primary?.projectName)
                .title(prevBlock.title ?: "${primary?.projectName ?: 'Project'} focus block")
                .focusBlock(true)
                .frozen(frozen)
                .manualOverride(isManual)
                .reason(isManual
                    ? 'Preserved manual multi-task focus block within stability policy'
                    : 'Preserved frozen multi-task focus block')
                .metadata([source: 'previous', stability: isManual ? 'manual' : 'freeze',
                           memberTaskIds: prevBlock.taskIds])
                .build()
            blocks << block
            preservedFocusBlockIds.add(block.id)
            prevBlock.taskIds.each { tid ->
                scheduledTaskIds.add(tid)
                def p = previousByTask[tid]
                changes << PlanChange.builder()
                    .id("chg-keep-${tid}")
                    .type('keep')
                    .taskId(tid)
                    .previousStart(p.start)
                    .previousEnd(p.end)
                    .newStart(p.start)
                    .newEnd(p.end)
                    .reason(block.reason)
                    .metadata([focusBlockId: block.id])
                    .build()
            }
        }

        List<Task> stillToSchedule = []
        candidates.each { task ->
            if (scheduledTaskIds.contains(task.id)) {
                return
            }
            PreviousPlacement prev = previousByTask[task.id]
            boolean isManual = manualIds.contains(task.id) || (prev?.manualOverride == true)
            boolean inFreeze = prev != null && prev.start != null && !prev.start.isAfter(freezeUntil) &&
                !prev.start.isBefore(rangeStart)
            boolean prevFrozen = prev?.frozen == true
            // Match multi-task preserve: inFreeze || prev.frozen, plus keepManualMoves for manuals
            boolean shouldPreserve = prev != null && prev.start != null && prev.end != null &&
                ((isManual && config.stability.keepManualMoves) || inFreeze || prevFrozen)

            if (shouldPreserve) {
                if (canOccupy(remaining, occupied, prev.start, prev.end, bufferMinutes, rangeStart, rangeEnd) &&
                    (task.deadline == null || !prev.end.isAfter(task.deadline))) {
                    occupy(remaining, occupied, prev.start, prev.end, bufferMinutes, rangeStart, rangeEnd)
                    boolean frozen = inFreeze || prevFrozen
                    List<MemberInterval> singles = [new MemberInterval(task.id, prev.start, prev.end)]
                    String stability
                    String reason
                    if (isManual && config.stability.keepManualMoves) {
                        stability = 'manual'
                        reason = 'Preserved manual move within stability policy'
                    } else if (prevFrozen && !inFreeze) {
                        stability = 'frozen'
                        reason = 'Preserved frozen prior placement outside freeze window'
                    } else {
                        stability = 'freeze'
                        reason = 'Preserved frozen near-term placement'
                    }
                    def block = ScheduledBlock.builder()
                        .id("block-${task.id}")
                        .start(prev.start)
                        .end(prev.end)
                        .taskIds([task.id])
                        .memberIntervals(singles)
                        .projectId(task.projectId)
                        .projectName(task.projectName)
                        .title(task.content)
                        .frozen(frozen)
                        .manualOverride(isManual)
                        .reason(reason)
                        .metadata([source: 'previous', stability: stability])
                        .build()
                    blocks << block
                    scheduledTaskIds.add(task.id)
                    changes << PlanChange.builder()
                        .id("chg-keep-${task.id}")
                        .type('keep')
                        .taskId(task.id)
                        .previousStart(prev.start)
                        .previousEnd(prev.end)
                        .newStart(prev.start)
                        .newEnd(prev.end)
                        .reason(block.reason)
                        .build()
                    return
                }
            }
            stillToSchedule << task
        }

        List<SchedulingUnit> units = batcher.buildUnits(stillToSchedule, clock)
        units = units.toSorted { a, b ->
            Instant da = a.earliestDeadline() ?: Instant.MAX
            Instant db = b.earliestDeadline() ?: Instant.MAX
            int c = da <=> db
            if (c != 0) return c
            int pa = a.tasks.collect { it.priority }.max() ?: 1
            int pb = b.tasks.collect { it.priority }.max() ?: 1
            c = pb <=> pa
            if (c != 0) return c
            return a.primaryId() <=> b.primaryId()
        }

        String lastProjectId = null
        def preservedSorted = blocks.toSorted { a, b -> a.start <=> b.start }
        if (preservedSorted) {
            lastProjectId = preservedSorted[-1].projectId
        }

        Queue<SchedulingUnit> queue = new ArrayDeque<>(units)
        while (!queue.isEmpty()) {
            SchedulingUnit unit = queue.remove()
            def pendingTasks = unit.tasks.findAll { !scheduledTaskIds.contains(it.id) }
            if (!pendingTasks) {
                continue
            }
            if (pendingTasks.size() != unit.tasks.size()) {
                unit = pendingTasks.size() == 1
                    ? SchedulingUnit.single(pendingTasks[0])
                    : SchedulingUnit.focus(pendingTasks, pendingTasks.sum { it.effectiveDuration.toMinutes() } as long)
            }

            Placement best = findBestPlacement(
                unit, remaining, occupied, reportingSlots ?: [], clock, rangeStart, rangeEnd,
                lastProjectId, previousByTask, manualIds, bufferMinutes
            )
            if (best == null && unit.focusBlock && unit.tasks.size() > 1) {
                def singles = unit.tasks.toSorted { a, b -> PlanScorer.compareTaskOrder(a, b) }
                    .collect { SchedulingUnit.single(it) }
                List<SchedulingUnit> rest = new ArrayList<>(queue)
                queue.clear()
                singles.each { queue.add(it) }
                rest.each { queue.add(it) }
                continue
            }
            if (best == null) {
                unit.tasks.each { task ->
                    if (scheduledTaskIds.contains(task.id)) {
                        return
                    }
                    String reason = unscheduledReason(task, remaining, rangeStart, rangeEnd)
                    unscheduled << new UnscheduledTask(task, reason, reasonCode(task, remaining, rangeStart, rangeEnd))
                    explanations << PlanningExplanation.of(
                        'unscheduled', reason, 'task', task.id,
                        [requiredMinutes: task.effectiveDuration.toMinutes()]
                    )
                }
                continue
            }

            occupy(remaining, occupied, best.start, best.end, bufferMinutes, rangeStart, rangeEnd)
            boolean isFocus = unit.focusBlock && unit.tasks.size() > 1
            // Member order is deterministic (deadline/priority/id) from placement; do not reorder.
            List<String> orderedTaskIds = best.orderedTaskIds ?: unit.tasks*.id
            List<MemberInterval> memberIntervals = orderedTaskIds.collect { tid ->
                Instant ms = best.memberStarts[tid] ?: best.start
                Instant me = best.memberEnds[tid] ?: best.end
                new MemberInterval(tid, ms, me)
            }
            def block = ScheduledBlock.builder()
                .id(best.blockId)
                .start(best.start)
                .end(best.end)
                .taskIds(orderedTaskIds)
                .memberIntervals(memberIntervals)
                .projectId(unit.projectId())
                .projectName(unit.projectName())
                .title(unit.title())
                .focusBlock(isFocus)
                .reason(best.reason)
                .metadata([score: best.score, memberTaskIds: orderedTaskIds])
                .build()
            blocks << block
            orderedTaskIds.each { tid ->
                scheduledTaskIds.add(tid)
                PreviousPlacement prev = previousByTask[tid]
                Instant taskStart = best.memberStarts[tid] ?: best.start
                Instant taskEnd = best.memberEnds[tid] ?: best.end
                String changeType = 'add'
                if (prev?.start != null) {
                    changeType = prev.start == taskStart ? 'keep' : 'move'
                }
                Map meta = isFocus ? [focusBlockId: block.id] : [:]
                if (changeType == 'move' && prev?.start != null &&
                    !prev.start.isAfter(approvalHorizon) && !prev.start.isBefore(rangeStart)) {
                    // Preview-only indicator for later apply/approval step — no remote writes.
                    meta = new LinkedHashMap<>(meta)
                    meta.approvalRequired = true
                    meta.approvalReason = 'move_within_require_approval_horizon'
                }
                changes << PlanChange.builder()
                    .id("chg-${changeType}-${tid}")
                    .type(changeType)
                    .taskId(tid)
                    .previousStart(prev?.start)
                    .previousEnd(prev?.end)
                    .newStart(taskStart)
                    .newEnd(taskEnd)
                    .reason(best.reason)
                    .metadata(meta)
                    .build()
            }
            explanations << PlanningExplanation.of(
                isFocus ? 'scheduled_focus_block' : 'scheduled',
                best.reason, 'block', block.id,
                [taskIds: block.taskIds, start: block.start.toString(), end: block.end.toString(), score: best.score]
            )
            lastProjectId = unit.projectId()
        }

        blocks = blocks.toSorted { a, b ->
            int c = a.start <=> b.start
            c != 0 ? c : a.id <=> b.id
        }
        unscheduled = unscheduled.toSorted { a, b -> PlanScorer.compareTaskOrder(a.task, b.task) }
        changes = changes.toSorted { a, b ->
            int c = (a.newStart ?: Instant.EPOCH) <=> (b.newStart ?: Instant.EPOCH)
            c != 0 ? c : (a.id <=> b.id)
        }
        assertNoHardConflicts(blocks, bufferMinutes)

        String planId = deterministicPlanId(candidates, reportingSlots ?: [], rangeStart, rangeEnd, clock)
        Plan plan = Plan.builder()
            .id(planId)
            .version(1)
            .createdAt(clock)
            .mode(config.mode ?: 'preview')
            .tasks(candidates)
            .slots(reportingSlots ?: [])
            .scheduledBlocks(blocks)
            .unscheduled(unscheduled)
            .changes(changes)
            .explanations(explanations)
            .metrics([
                scheduledTaskCount  : scheduledTaskIds.size() as long,
                unscheduledTaskCount: unscheduled.size() as long,
                scheduledBlockCount : blocks.size() as long,
                bufferMinutes       : bufferMinutes as long
            ])
            .build()
        String diff = PlanDiffFormatter.toMarkdown(plan, config.timezone)
        return Plan.builder()
            .id(plan.id).version(plan.version).createdAt(plan.createdAt).mode(plan.mode)
            .tasks(plan.tasks).slots(plan.slots).scheduledBlocks(plan.scheduledBlocks)
            .unscheduled(plan.unscheduled).changes(plan.changes).explanations(plan.explanations)
            .metrics(plan.metrics).humanDiff(diff).build()
    }

    private Placement findBestPlacement(SchedulingUnit unit, List<MutableSlot> remaining,
                                        List<OccupiedInterval> occupied,
                                        List<TimeSlot> reportingSlots, Instant now,
                                        Instant rangeStart, Instant rangeEnd,
                                        String previousProjectId,
                                        Map<String, PreviousPlacement> previousByTask,
                                        Set<String> manualIds, int bufferMinutes) {
        long need = unit.totalMinutes
        if (need <= 0) {
            return null
        }

        // Deterministic member order: deadline → priority → id (same as batching / memberIntervals).
        List<Task> orderedMembers = unit.tasks.toSorted { a, b -> PlanScorer.compareTaskOrder(a, b) }
        // Outer bound may use max relevant deadline (or rangeEnd). Do NOT clip the whole block
        // to earliestDeadline — that over-rejects sequential members with staggered deadlines.
        // Each member is still validated to end <= its own deadline below.
        Instant maxMemberDeadline = null
        orderedMembers.each { t ->
            if (t.deadline != null && (maxMemberDeadline == null || t.deadline.isAfter(maxMemberDeadline))) {
                maxMemberDeadline = t.deadline
            }
        }
        Instant outerBound = rangeEnd
        if (maxMemberDeadline != null && maxMemberDeadline.isBefore(outerBound) &&
            orderedMembers.every { it.deadline != null }) {
            outerBound = maxMemberDeadline
        }

        Task primary = orderedMembers[0]
        PreviousPlacement prevPrimary = previousByTask[primary.id]

        Placement best = null
        for (int i = 0; i < remaining.size(); i++) {
            MutableSlot slot = remaining[i]
            Instant usableStart = slot.start.isBefore(rangeStart) ? rangeStart : slot.start
            Instant usableEnd = slot.end.isAfter(outerBound) ? outerBound : slot.end
            if (!usableEnd.isAfter(usableStart)) {
                continue
            }
            long avail = Duration.between(usableStart, usableEnd).toMinutes()
            if (avail < need) {
                continue
            }

            List<Instant> candidateStarts = candidateStartsForSlot(
                unit, orderedMembers, usableStart, usableEnd, need, previousByTask
            )
            // Usable fragment for scoring: clip to outer bound so fragmentation ignores capacity past it
            TimeSlot faux = TimeSlot.builder().start(usableStart).end(usableEnd).build()
            boolean batched = unit.focusBlock && orderedMembers.size() > 1

            for (Instant start : candidateStarts) {
                Map<String, Instant> memberStarts = new LinkedHashMap<>()
                Map<String, Instant> memberEnds = new LinkedHashMap<>()
                Instant cursor = start
                boolean membersOk = true
                orderedMembers.each { t ->
                    Instant mEnd = cursor + t.effectiveDuration
                    if (t.deadline != null && mEnd.isAfter(t.deadline)) {
                        membersOk = false
                    }
                    if (mEnd.isAfter(usableEnd)) {
                        membersOk = false
                    }
                    memberStarts[t.id] = cursor
                    memberEnds[t.id] = mEnd
                    cursor = mEnd
                }
                if (!membersOk) {
                    continue
                }
                Instant end = cursor
                if (end.isAfter(usableEnd)) {
                    continue
                }
                // Bilateral buffer vs already occupied blocks (and remaining free capacity).
                if (!canOccupy(remaining, occupied, start, end, bufferMinutes, rangeStart, rangeEnd)) {
                    continue
                }

                boolean manual = manualIds.contains(primary.id)
                // Score primary on its own member interval (not full multi-task span)
                Instant primaryEnd = memberEnds[primary.id] ?: end
                Instant primaryStart = memberStarts[primary.id] ?: start
                long primaryScore = scorer.scorePlacement(
                    primary, primaryStart, primaryEnd, faux, reportingSlots, now, rangeEnd,
                    previousProjectId, prevPrimary?.start, manual, batched
                )
                List<Long> memberScores = [primaryScore]
                if (orderedMembers.size() > 1) {
                    orderedMembers.each { t ->
                        if (t.id == primary.id) {
                            return
                        }
                        long s = scorer.scorePlacement(
                            t, memberStarts[t.id], memberEnds[t.id], faux, reportingSlots, now, rangeEnd,
                            unit.projectId(), previousByTask[t.id]?.start, manualIds.contains(t.id), true
                        )
                        memberScores << s
                    }
                }
                long score = foldMemberScores(memberScores)
                if (score == PlanScorer.INFEASIBLE) {
                    continue
                }

                String reason
                if (batched) {
                    reason = "Project batching for ${unit.projectName() ?: unit.projectId()}; score=${score}"
                } else {
                    reason = "Best feasible slot for '${primary.content}' (P${5 - primary.priority}); score=${score}"
                }

                // Persist member intervals in the same deterministic order as placement.
                Map<String, Instant> orderedStarts = new LinkedHashMap<>()
                Map<String, Instant> orderedEnds = new LinkedHashMap<>()
                orderedMembers.each { t ->
                    orderedStarts[t.id] = memberStarts[t.id]
                    orderedEnds[t.id] = memberEnds[t.id]
                }

                Placement candidate = new Placement(
                    start, end, score, reason, "block-${unit.primaryId()}-${start.toEpochMilli()}",
                    orderedStarts, orderedEnds, orderedMembers*.id
                )
                if (best == null || candidate.score > best.score ||
                    (candidate.score == best.score && candidate.start.isBefore(best.start)) ||
                    (candidate.score == best.score && candidate.start == best.start && candidate.blockId < best.blockId)) {
                    best = candidate
                }
            }
        }
        return best
    }

    /**
     * Deterministic placement candidate starts within a placeable fragment.
     * Includes slot start, interior preferred-context window starts, and prior
     * frozen/manual placement starts when they lie inside the fragment.
     * Does not enumerate minute-by-minute.
     */
    private List<Instant> candidateStartsForSlot(SchedulingUnit unit,
                                                 List<Task> orderedMembers,
                                                 Instant usableStart, Instant usableEnd,
                                                 long needMinutes,
                                                 Map<String, PreviousPlacement> previousByTask) {
        NavigableSet<Instant> starts = new TreeSet<>()
        starts.add(usableStart)

        ZoneId zone = config.timezone
        List<Task> members = orderedMembers ?: unit.tasks
        members.each { task ->
            config.contextsFor(task).each { ctx ->
                ctx.preferredWindows.each { win ->
                    preferredWindowStartsInRange(win, usableStart, usableEnd, zone).each { ws ->
                        starts.add(ws)
                    }
                }
            }
            PreviousPlacement prev = previousByTask[task.id]
            if (prev?.start != null &&
                !prev.start.isBefore(usableStart) && prev.start.isBefore(usableEnd)) {
                starts.add(prev.start)
            }
        }

        Instant latestStart = usableEnd - Duration.ofMinutes(needMinutes)
        return starts.findAll { !it.isBefore(usableStart) && !it.isAfter(latestStart) }
            .toList()
    }

    /**
     * Preferred-window local starts that fall inside [rangeStart, rangeEnd) as instants.
     */
    private static List<Instant> preferredWindowStartsInRange(
        PlannerConfig.PreferredWindow win, Instant rangeStart, Instant rangeEnd, ZoneId zone) {
        List<Instant> out = []
        ZonedDateTime cursor = rangeStart.atZone(zone).toLocalDate().atStartOfDay(zone)
        ZonedDateTime endZ = rangeEnd.atZone(zone)
        while (cursor.isBefore(endZ)) {
            def day = cursor.dayOfWeek
            if (!win.days || win.days.contains(day)) {
                Instant winStart = cursor.toLocalDate().atTime(win.start).atZone(zone).toInstant()
                if (!winStart.isBefore(rangeStart) && winStart.isBefore(rangeEnd)) {
                    out << winStart
                }
            }
            cursor = cursor.plusDays(1)
        }
        return out
    }

    /**
     * Index prior placements per task. Prefer explicit memberIntervals; else changes;
     * for legacy multi-task focus blocks without member intervals, derive sequential
     * member ranges from task effective durations and aggregate block start.
     */
    static Map<String, PreviousPlacement> indexPrevious(Plan previousPlan, Map<String, Task> taskById = [:]) {
        Map<String, PreviousPlacement> map = new LinkedHashMap<>()
        if (previousPlan == null) {
            return map
        }
        previousPlan.scheduledBlocks?.each { b ->
            if (b.memberIntervals) {
                b.memberIntervals.each { mi ->
                    map[mi.taskId] = new PreviousPlacement(
                        mi.start, mi.end, b.manualOverride, b.frozen)
                }
                return
            }
            if (b.focusBlock && b.taskIds.size() > 1) {
                // Legacy fallback: sequential member ranges from task durations.
                Instant cursor = b.start
                boolean derivedOk = true
                List<PreviousPlacement> derived = []
                b.taskIds.each { tid ->
                    Task t = taskById[tid]
                    long mins = t?.effectiveDuration?.toMinutes() ?: 0L
                    if (mins <= 0) {
                        // Fall back to equal share if duration unknown
                        mins = Math.max(1L, Duration.between(b.start, b.end).toMinutes() / b.taskIds.size())
                    }
                    Instant mEnd = cursor + Duration.ofMinutes(mins)
                    if (mEnd.isAfter(b.end)) {
                        derivedOk = false
                    }
                    derived << new PreviousPlacement(cursor, derivedOk ? mEnd : b.end, b.manualOverride, b.frozen)
                    cursor = mEnd
                }
                if (!derivedOk || cursor.isAfter(b.end)) {
                    // Malformed: do not exceed block end — clamp last, keep sequential starts if possible
                    cursor = b.start
                    b.taskIds.eachWithIndex { tid, idx ->
                        Task t = taskById[tid]
                        long mins = t?.effectiveDuration?.toMinutes() ?: 0L
                        if (mins <= 0) {
                            mins = Math.max(1L, Duration.between(b.start, b.end).toMinutes() / b.taskIds.size())
                        }
                        Instant mEnd = cursor + Duration.ofMinutes(mins)
                        if (mEnd.isAfter(b.end)) {
                            mEnd = b.end
                        }
                        if (!mEnd.isAfter(cursor)) {
                            // Zero-length remainder: assign residual empty — use whole remaining
                            mEnd = b.end
                        }
                        map[tid] = new PreviousPlacement(cursor, mEnd, b.manualOverride, b.frozen)
                        cursor = mEnd
                    }
                } else {
                    b.taskIds.eachWithIndex { tid, idx ->
                        map[tid] = derived[idx]
                    }
                }
            } else {
                b.taskIds.each { tid ->
                    map[tid] = new PreviousPlacement(b.start, b.end, b.manualOverride, b.frozen)
                }
            }
        }
        // Changes fill single-task placements; multi-task focus members already have
        // explicit or derived per-member intervals and must not be collapsed.
        Set<String> focusMemberIds = new HashSet<>()
        previousPlan.scheduledBlocks?.each { b ->
            if (b.focusBlock && b.taskIds != null && b.taskIds.size() > 1) {
                focusMemberIds.addAll(b.taskIds)
            }
        }
        previousPlan.changes?.each { c ->
            if (c.taskId && c.newStart != null && c.newEnd != null && c.newEnd.isAfter(c.newStart)) {
                if (focusMemberIds.contains(c.taskId)) {
                    return
                }
                def existing = map[c.taskId]
                map[c.taskId] = new PreviousPlacement(
                    c.newStart,
                    c.newEnd,
                    existing?.manualOverride ?: false,
                    existing?.frozen ?: false
                )
            }
        }
        return map
    }

    /**
     * True if [start, end) fits in remaining free capacity and does not violate
     * bilateral minimum buffer against every occupied block. Buffer is clamped
     * so it does not extend outside [rangeStart, rangeEnd).
     */
    static boolean canOccupy(List<MutableSlot> remaining, List<OccupiedInterval> occupied,
                             Instant start, Instant end, int bufferMinutes,
                             Instant rangeStart, Instant rangeEnd) {
        if (start == null || end == null || !end.isAfter(start)) {
            return false
        }
        // Must fit entirely inside some remaining free fragment
        boolean fitsFree = false
        for (MutableSlot slot : remaining) {
            if (!start.isBefore(slot.start) && !end.isAfter(slot.end)) {
                fitsFree = true
                break
            }
        }
        if (!fitsFree) {
            return false
        }
        int buf = Math.max(0, bufferMinutes)
        for (OccupiedInterval occ : occupied) {
            if (violatesBilateralBuffer(start, end, occ.start, occ.end, buf, rangeStart, rangeEnd)) {
                return false
            }
        }
        return true
    }

    /**
     * True when candidate [bStart,bEnd) overlaps occupied [aStart,aEnd) expanded by
     * bufferMinutes on both sides (clamped to horizon). Half-open intervals: touching
     * exactly at the expanded boundary is allowed (e.g. bStart == aEnd + buffer).
     */
    static boolean violatesBilateralBuffer(Instant aStart, Instant aEnd,
                                           Instant bStart, Instant bEnd,
                                           int bufferMinutes,
                                           Instant rangeStart, Instant rangeEnd) {
        Duration buf = Duration.ofMinutes(Math.max(0, bufferMinutes))
        Instant aExpStart = clampLower(aStart - buf, rangeStart)
        Instant aExpEnd = clampUpper(aEnd + buf, rangeEnd)
        return aExpStart.isBefore(bEnd) && bStart.isBefore(aExpEnd)
    }

    private static Instant clampLower(Instant value, Instant rangeStart) {
        if (rangeStart != null && value.isBefore(rangeStart)) {
            return rangeStart
        }
        return value
    }

    private static Instant clampUpper(Instant value, Instant rangeEnd) {
        if (rangeEnd != null && value.isAfter(rangeEnd)) {
            return rangeEnd
        }
        return value
    }

    /**
     * Remove [start, end) plus bilateral buffer (clamped to horizon) from free slots
     * and record the core occupied interval for future bilateral checks.
     */
    static void occupy(List<MutableSlot> remaining, List<OccupiedInterval> occupied,
                       Instant start, Instant end, int bufferMinutes,
                       Instant rangeStart, Instant rangeEnd) {
        int buf = Math.max(0, bufferMinutes)
        Instant blockedStart = clampLower(start - Duration.ofMinutes(buf), rangeStart)
        Instant blockedEnd = clampUpper(end + Duration.ofMinutes(buf), rangeEnd)
        List<MutableSlot> next = []
        remaining.each { slot ->
            next.addAll(subtract(slot, blockedStart, blockedEnd))
        }
        remaining.clear()
        remaining.addAll(next.findAll { it.end.isAfter(it.start) })
        occupied << new OccupiedInterval(start, end)
    }

    private static List<MutableSlot> subtract(MutableSlot slot, Instant bStart, Instant bEnd) {
        if (!bEnd.isAfter(slot.start) || !bStart.isBefore(slot.end)) {
            return [slot]
        }
        List<MutableSlot> parts = []
        if (bStart.isAfter(slot.start)) {
            parts << new MutableSlot(slot.start, bStart.isBefore(slot.end) ? bStart : slot.end)
        }
        if (bEnd.isBefore(slot.end)) {
            parts << new MutableSlot(bEnd.isAfter(slot.start) ? bEnd : slot.start, slot.end)
        }
        return parts.findAll { it.end.isAfter(it.start) }
    }

    private static void assertNoHardConflicts(List<ScheduledBlock> blocks, int bufferMinutes) {
        def sorted = blocks.toSorted { a, b -> a.start <=> b.start }
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                def a = sorted[i]
                def b = sorted[j]
                if (b.start.isBefore(a.end + Duration.ofMinutes(bufferMinutes))) {
                    throw new IllegalStateException(
                        "Hard conflict/buffer violation between ${a.id} [${a.start},${a.end}) and ${b.id} [${b.start},${b.end}) buffer=${bufferMinutes}m")
                }
            }
        }
    }

    private static String unscheduledReason(Task task, List<MutableSlot> remaining, Instant rangeStart, Instant rangeEnd) {
        long need = task.effectiveDuration.toMinutes()
        if (task.deadline != null && task.deadline.isBefore(rangeStart)) {
            return "Deadline ${task.deadline} is before planning range start; cannot schedule ${need}m task"
        }
        long maxSlot = remaining.collect { Duration.between(it.start, it.end).toMinutes() }.max() ?: 0L
        if (task.deadline != null) {
            long maxBeforeDeadline = 0L
            remaining.each { slot ->
                Instant uStart = slot.start.isBefore(rangeStart) ? rangeStart : slot.start
                Instant uEnd = slot.end.isAfter(task.deadline) ? task.deadline : slot.end
                if (uEnd.isAfter(uStart)) {
                    maxBeforeDeadline = Math.max(maxBeforeDeadline, Duration.between(uStart, uEnd).toMinutes())
                }
            }
            if (maxBeforeDeadline < need) {
                return "Cannot fit ${need}m before deadline ${task.deadline}; largest feasible free block is ${maxBeforeDeadline}m"
            }
        }
        if (maxSlot < need) {
            return "Insufficient free capacity for ${need}m task; largest free block is ${maxSlot}m"
        }
        return "No feasible slot for ${need}m task within planning horizon"
    }

    private static String reasonCode(Task task, List<MutableSlot> remaining, Instant rangeStart, Instant rangeEnd) {
        if (task.deadline != null && task.deadline.isBefore(rangeStart)) {
            return 'deadline_passed'
        }
        long need = task.effectiveDuration.toMinutes()
        if (task.deadline != null) {
            long maxBeforeDeadline = 0L
            remaining.each { slot ->
                Instant uStart = slot.start.isBefore(rangeStart) ? rangeStart : slot.start
                Instant uEnd = slot.end.isAfter(task.deadline) ? task.deadline : slot.end
                if (uEnd.isAfter(uStart)) {
                    maxBeforeDeadline = Math.max(maxBeforeDeadline, Duration.between(uStart, uEnd).toMinutes())
                }
            }
            if (maxBeforeDeadline < need) {
                return 'deadline_infeasible'
            }
        }
        return 'no_capacity'
    }

    private static String deterministicPlanId(List<Task> tasks, List<TimeSlot> slots,
                                              Instant rangeStart, Instant rangeEnd, Instant now) {
        def sb = new StringBuilder()
        sb << rangeStart << '|' << rangeEnd << '|' << now << '|'
        tasks.toSorted { a, b -> a.id <=> b.id }.each { t ->
            sb << t.id << ':' << t.priority << ':' << t.effectiveDuration.toMinutes() << ':' << t.deadline << ';'
        }
        slots.toSorted { a, b -> a.start <=> b.start ?: a.end <=> b.end }.each { s ->
            sb << s.start << '-' << s.end << ','
        }
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] dig = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8))
        def hex = dig[0..7].collect { String.format('%02x', it) }.join()
        return "plan-${hex}"
    }

    /**
     * Fold per-member placement scores into one candidate score.
     * Any member {@link PlanScorer#INFEASIBLE} makes the whole candidate infeasible permanently;
     * later member scores are never added to the sentinel.
     * Primary (first) score is full weight; subsequent members contribute score/4.
     */
    static long foldMemberScores(List<Long> memberScores) {
        if (memberScores == null || memberScores.isEmpty()) {
            return 0L
        }
        long total = 0L
        boolean first = true
        for (Long s : memberScores) {
            if (s == null) {
                continue
            }
            if (s == PlanScorer.INFEASIBLE) {
                return PlanScorer.INFEASIBLE
            }
            if (first) {
                total = s
                first = false
            } else {
                total += s / 4
            }
        }
        return total
    }

    private static final class MutableSlot {
        Instant start
        Instant end
        MutableSlot(Instant s, Instant e) {
            this.start = s
            this.end = e
        }
    }

    static final class OccupiedInterval {
        final Instant start
        final Instant end
        OccupiedInterval(Instant start, Instant end) {
            this.start = start
            this.end = end
        }
    }

    static final class PreviousPlacement {
        final Instant start
        final Instant end
        final boolean manualOverride
        final boolean frozen
        PreviousPlacement(Instant start, Instant end, boolean manualOverride, boolean frozen) {
            this.start = start
            this.end = end
            this.manualOverride = manualOverride
            this.frozen = frozen
        }
    }

    private static final class Placement {
        final Instant start
        final Instant end
        final long score
        final String reason
        final String blockId
        final Map<String, Instant> memberStarts
        final Map<String, Instant> memberEnds
        final List<String> orderedTaskIds

        Placement(Instant start, Instant end, long score, String reason, String blockId,
                  Map<String, Instant> memberStarts, Map<String, Instant> memberEnds,
                  List<String> orderedTaskIds = null) {
            this.start = start
            this.end = end
            this.score = score
            this.reason = reason
            this.blockId = blockId
            this.memberStarts = memberStarts ?: [:]
            this.memberEnds = memberEnds ?: [:]
            this.orderedTaskIds = orderedTaskIds != null
                ? Collections.unmodifiableList(new ArrayList<>(orderedTaskIds))
                : null
        }
    }
}
