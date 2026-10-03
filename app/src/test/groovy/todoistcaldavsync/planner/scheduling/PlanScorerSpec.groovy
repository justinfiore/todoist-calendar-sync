package todoistcaldavsync.planner.scheduling

import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class PlanScorerSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')

    PlannerConfig config = PlannerConfig.fromMap(planner: [
        mode        : 'preview',
        timezone    : 'America/New_York',
        availability: [working_windows: [weekday: ['09:00-17:00']]],
        tasks       : [
            default_duration_minutes: 30,
            contexts                : [
                phone: [
                    match_labels     : ['phone'],
                    preferred_windows: ['weekday 12:00-13:00'],
                    preferred_bonus  : 20,
                    avoid_penalty    : 25
                ],
                home : [
                    match_labels     : ['home'],
                    preferred_windows: ['weekday 20:00-22:00'],
                    preferred_bonus  : 20,
                    avoid_penalty    : 25
                ]
            ]
        ],
        batching    : [enabled: true, project_batch_bonus: 25, context_switch_penalty: 15],
        stability   : [freeze_within: 'PT48H', churn_penalty: 40, minimum_buffer_between_blocks_minutes: 10]
    ])

    PlanScorer scorer = new PlanScorer(config)
    Instant now = LocalDate.of(2026, 8, 6).atTime(8, 0).atZone(zone).toInstant()
    Instant rangeEnd = LocalDate.of(2026, 8, 8).atStartOfDay(zone).toInstant()

    private Task task(Map args) {
        Task.builder()
            .id(args.id ?: 't1')
            .content(args.content ?: 'Task')
            .projectId(args.projectId)
            .projectName(args.projectName)
            .labels(args.labels ?: ['schedule'])
            .priority(args.priority != null ? args.priority as int : 1)
            .deadline(args.deadline as Instant)
            .dueTime(args.dueTime as Instant)
            .effectiveDuration(Duration.ofMinutes((args.minutes ?: 30) as long))
            .durationSource('test')
            .build()
    }

    private PlanScorer recurrenceScorer(int soonDays = 5) {
        new PlanScorer(PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            tasks       : [
                default_duration_minutes: 30,
                recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z',
                    seen_label: 'smartplanner-seen', onboard_label: 'smartplanner-onboard',
                    hard_label: 'hard', hard_deadline_soon_days: soonDays]
            ]
        ]))
    }

    def "P1–P4 priority weights order P1 highest"() {
        expect:
        PlanScorer.priorityWeight(4) > PlanScorer.priorityWeight(3)
        PlanScorer.priorityWeight(3) > PlanScorer.priorityWeight(2)
        PlanScorer.priorityWeight(2) > PlanScorer.priorityWeight(1)
    }

    def "higher priority yields higher placement score when other factors equal"() {
        given:
        def start = LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        def end = start + Duration.ofMinutes(30)
        def p1 = task(id: 'p1', priority: 4, deadline: rangeEnd)
        def p4 = task(id: 'p4', priority: 1, deadline: rangeEnd)

        when:
        long s1 = scorer.scorePlacement(p1, start, end, null, [], now, rangeEnd, null, null, false, false)
        long s4 = scorer.scorePlacement(p4, start, end, null, [], now, rangeEnd, null, null, false, false)

        then:
        s1 > s4
        s1 != PlanScorer.INFEASIBLE
    }

    def "deadline after placement end is infeasible regardless of priority"() {
        given:
        def start = LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        def end = start + Duration.ofMinutes(60)
        def deadline = start + Duration.ofMinutes(30)
        def p1 = task(id: 'late', priority: 4, minutes: 60, deadline: deadline)

        expect:
        scorer.scorePlacement(p1, start, end, null, [], now, rangeEnd, null, null, false, false) == PlanScorer.INFEASIBLE
    }

    def "recurrence-enabled soft Deadline permits lateness with a monotonic penalty"() {
        given:
        def enabled = recurrenceScorer()
        def deadline = now + Duration.ofHours(2)
        def soft = task(deadline: deadline, priority: 2)

        expect:
        enabled.deadlineUrgency(soft, deadline + Duration.ofMinutes(15), now, rangeEnd) >
            enabled.deadlineUrgency(soft, deadline + Duration.ofHours(3), now, rangeEnd)
        enabled.deadlineUrgency(soft, deadline + Duration.ofHours(3), now, rangeEnd) != PlanScorer.INFEASIBLE
    }

    def "hard Deadline urgency starts strictly inside configured soon window"() {
        given:
        def enabled = recurrenceScorer(5)

        expect:
        enabled.deadlineUrgency(task(labels: ['schedule', 'hard'], deadline: now + Duration.ofDays(6)),
            now + Duration.ofMinutes(30), now, rangeEnd) == 0L
        enabled.deadlineUrgency(task(labels: ['schedule', 'hard'], deadline: now + Duration.ofDays(5)),
            now + Duration.ofMinutes(30), now, rangeEnd) == 0L
        enabled.deadlineUrgency(task(labels: ['schedule', 'hard'], deadline: now + Duration.ofDays(5) - Duration.ofMinutes(1)),
            now + Duration.ofMinutes(30), now, rangeEnd) >= 600L
    }

    def "hard soon boundary advances by local days across DST rather than fixed hours"() {
        given:
        def enabled = recurrenceScorer(2)
        def beforeSpringForward = LocalDate.of(2026, 3, 7).atTime(8, 0).atZone(zone).toInstant()
        def localBoundary = beforeSpringForward.atZone(zone).plusDays(2).toInstant()

        expect:
        Duration.between(beforeSpringForward, localBoundary).toHours() == 47L
        enabled.deadlineUrgency(task(labels: ['schedule', 'hard'], deadline: localBoundary),
            beforeSpringForward + Duration.ofMinutes(30), beforeSpringForward,
            beforeSpringForward + Duration.ofDays(7)) == 0L
        enabled.deadlineUrgency(task(labels: ['schedule', 'hard'], deadline: localBoundary - Duration.ofMinutes(1)),
            beforeSpringForward + Duration.ofMinutes(30), beforeSpringForward,
            beforeSpringForward + Duration.ofDays(7)) >= 600L
    }

    def "approaching hard P4 outranks ordinary P1 while late hard placement is infeasible"() {
        given:
        def enabled = recurrenceScorer(5)
        def start = now + Duration.ofHours(1)
        def end = start + Duration.ofMinutes(30)
        def hardP4 = task(id: 'hard-p4', labels: ['schedule', 'hard'], priority: 1,
            deadline: now + Duration.ofDays(1))
        def softP1 = task(id: 'soft-p1', priority: 4, deadline: now + Duration.ofDays(10))

        expect:
        enabled.scorePlacement(hardP4, start, end, null, [], now, now + Duration.ofDays(14),
            null, null, false, false) >
            enabled.scorePlacement(softP1, start, end, null, [], now, now + Duration.ofDays(14),
                null, null, false, false)
        enabled.scorePlacement(hardP4, hardP4.deadline, hardP4.deadline + Duration.ofMinutes(30),
            null, [], now, now + Duration.ofDays(14), null, null, false, false) == PlanScorer.INFEASIBLE
    }

    def "preferred context window scores higher than avoided window"() {
        given:
        def phone = task(id: 'phone', labels: ['schedule', 'phone'], priority: 2, deadline: rangeEnd)
        def lunchStart = LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        def lunchEnd = lunchStart + Duration.ofMinutes(30)
        def morningStart = LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        def morningEnd = morningStart + Duration.ofMinutes(30)

        when:
        long preferred = scorer.scorePlacement(phone, lunchStart, lunchEnd, null, [], now, rangeEnd, null, null, false, false)
        long avoided = scorer.scorePlacement(phone, morningStart, morningEnd, null, [], now, rangeEnd, null, null, false, false)

        then:
        preferred > avoided
    }

    def "stable task order: deadline then priority then id"() {
        given:
        def a = task(id: 'b-task', priority: 4, deadline: Instant.parse('2026-08-07T00:00:00Z'))
        def b = task(id: 'a-task', priority: 4, deadline: Instant.parse('2026-08-07T00:00:00Z'))
        def c = task(id: 'c-task', priority: 3, deadline: Instant.parse('2026-08-06T00:00:00Z'))

        when:
        def ordered = [a, b, c].toSorted { x, y -> PlanScorer.compareTaskOrder(x, y) }

        then:
        ordered*.id == ['c-task', 'a-task', 'b-task']
    }

    def "churn penalty applies when moving a frozen prior placement"() {
        given:
        def t = task(id: 'move-me', priority: 2, deadline: rangeEnd)
        def start = LocalDate.of(2026, 8, 6).atTime(10, 0).atZone(zone).toInstant()
        def end = start + Duration.ofMinutes(30)
        def prev = LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()

        when:
        long keep = scorer.scorePlacement(t, prev, prev + Duration.ofMinutes(30), null, [], now, rangeEnd, null, prev, false, false)
        long move = scorer.scorePlacement(t, start, end, null, [], now, rangeEnd, null, prev, false, false)
        long manualMove = scorer.scorePlacement(t, start, end, null, [], now, rangeEnd, null, prev, true, false)

        then:
        keep > move
        move > manualMove || manualMove < keep
    }

    def "fragmentedSlotPenalty uses leftover under 15 minutes only"() {
        expect:
        scorer.fragmentedSlotPenalty(60, 50) == 10L
        scorer.fragmentedSlotPenalty(60, 45) == 0L
        scorer.fragmentedSlotPenalty(60, 60) == 0L
        scorer.fragmentedSlotPenalty(0, 30) == 0L
    }

    def "scorePlacement fragmented penalty uses deadline-clipped usable span not full slot beyond deadline"() {
        given:
        // Full slot is 09:00-12:00 (180m). Task needs 30m with deadline at 09:40 so usable is 40m.
        // Leftover on usable span = 10m (<15) → penalty; leftover on full slot = 150m → no penalty.
        def start = LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        def end = start + Duration.ofMinutes(30)
        def deadline = start + Duration.ofMinutes(40)
        def slotEnd = start + Duration.ofMinutes(180)
        def fullSlot = todoistcaldavsync.planner.domain.TimeSlot.builder().start(start).end(slotEnd).build()
        def clippedSlot = todoistcaldavsync.planner.domain.TimeSlot.builder().start(start).end(deadline).build()
        def t = task(id: 'frag', priority: 2, minutes: 30, deadline: deadline)

        when:
        long withFull = scorer.scorePlacement(t, start, end, fullSlot, [], now, rangeEnd, null, null, false, false)
        long withClipped = scorer.scorePlacement(t, start, end, clippedSlot, [], now, rangeEnd, null, null, false, false)
        // Same placement with null placeableSlot uses placement duration only (no frag leftover)
        long withNull = scorer.scorePlacement(t, start, end, null, [], now, rangeEnd, null, null, false, false)

        then:
        // usableSlotMinutes clips full slot at deadline → same 40m usable as pre-clipped slot
        withFull == withClipped
        // leftover 10m (<15) applies exact fragmented penalty of 10 vs null placeable (no leftover)
        withFull == withNull - 10L
        scorer.fragmentedSlotPenalty(40, 30) == 10L
        scorer.fragmentedSlotPenalty(180, 30) == 0L
        PlanScorer.usableSlotMinutes(fullSlot, start, end, deadline) == 40L
        PlanScorer.usableSlotMinutes(clippedSlot, start, end, deadline) == 40L
    }
}
