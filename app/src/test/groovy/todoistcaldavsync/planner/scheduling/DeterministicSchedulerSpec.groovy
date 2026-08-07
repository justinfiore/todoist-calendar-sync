package todoistcaldavsync.planner.scheduling

import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.state.PlanStore

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DeterministicSchedulerSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')
    Instant dayStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
    Instant dayEnd = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()
    Instant now = LocalDate.of(2026, 8, 6).atTime(8, 0).atZone(zone).toInstant()

    PlannerConfig baseConfig() {
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00', '13:00-17:00']]],
            tasks       : [
                default_duration_minutes: 30,
                contexts                : [
                    phone: [
                        match_labels     : ['phone'],
                        preferred_windows: ['weekday 12:00-13:00', 'weekday 13:00-14:00'],
                        preferred_bonus  : 30,
                        avoid_penalty    : 20
                    ],
                    home : [
                        match_labels     : ['home'],
                        preferred_windows: ['weekday 16:00-17:00'],
                        preferred_bonus  : 30,
                        avoid_penalty    : 20
                    ]
                ]
            ],
            batching    : [
                enabled                    : true,
                project_batch_bonus        : 25,
                max_focus_block_minutes    : 90,
                minimum_focus_block_minutes: 30,
                context_switch_penalty     : 15
            ],
            stability   : [
                freeze_within                       : 'PT48H',
                keep_manual_moves                   : true,
                minimum_buffer_between_blocks_minutes: 10,
                churn_penalty                       : 40
            ]
        ])
    }

    DeterministicScheduler scheduler = new DeterministicScheduler(baseConfig())

    private Task task(Map args) {
        Task.builder()
            .id(args.id as String)
            .content(args.content ?: args.id)
            .projectId(args.projectId as String)
            .projectName(args.projectName ?: args.projectId)
            .labels((args.labels ?: ['schedule']) as List)
            .priority(args.priority != null ? args.priority as int : 2)
            .deadline(args.deadline as Instant)
            .dueTime(args.dueTime as Instant)
            .effectiveDuration(Duration.ofMinutes((args.minutes ?: 30) as long))
            .durationSource('test')
            .manual(args.manual == true)
            .build()
    }

    private TimeSlot slot(String startLocal, String endLocal, boolean soft = false) {
        def d = LocalDate.of(2026, 8, 6)
        def st = java.time.LocalTime.parse(startLocal)
        def en = java.time.LocalTime.parse(endLocal)
        TimeSlot.builder()
            .start(d.atTime(st).atZone(zone).toInstant())
            .end(d.atTime(en).atZone(zone).toInstant())
            .softBlocked(soft)
            .windowName('weekday')
            .build()
    }

    private List<TimeSlot> fullDaySlots() {
        [slot('09:00', '12:00'), slot('13:00', '17:00')]
    }

    def "P1 schedules before lower priority when capacity is scarce"() {
        given:
        def slots = [slot('09:00', '10:00')] // 60m only
        def tasks = [
            task(id: 'low', priority: 1, minutes: 60, deadline: dayEnd),
            task(id: 'high', priority: 4, minutes: 60, deadline: dayEnd)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)

        then:
        plan.scheduledBlocks.size() == 1
        plan.scheduledBlocks[0].taskIds == ['high']
        plan.unscheduled*.task*.id == ['low']
        plan.unscheduled[0].reason.toLowerCase().contains('fit') || plan.unscheduled[0].reason.toLowerCase().contains('capacity')
    }

    def "impossible deadline-infeasible task is unscheduled with reason"() {
        given:
        def slots = fullDaySlots()
        def earlyDeadline = LocalDate.of(2026, 8, 6).atTime(10, 0).atZone(zone).toInstant()
        def tasks = [
            task(id: 'impossible', priority: 4, minutes: 90, deadline: earlyDeadline)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)

        then:
        plan.scheduledBlocks.isEmpty()
        plan.unscheduled.size() == 1
        plan.unscheduled[0].task.id == 'impossible'
        plan.unscheduled[0].code == 'deadline_infeasible' || plan.unscheduled[0].reason.toLowerCase().contains('deadline')
        plan.humanDiff.toLowerCase().contains('unscheduled')
        plan.humanDiff.contains('impossible') || plan.humanDiff.contains('Impossible')
    }

    def "context preference places phone task in preferred afternoon window when available"() {
        given:
        def slots = fullDaySlots()
        def tasks = [
            task(id: 'phone-task', labels: ['schedule', 'phone'], priority: 2, minutes: 30, deadline: dayEnd)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def block = plan.scheduledBlocks[0]
        def localHour = block.start.atZone(zone).hour

        then:
        plan.unscheduled.isEmpty()
        // preferred windows include 13:00-14:00
        localHour == 13
    }

    def "interior preferred window inside larger free slot is chosen over slot start"() {
        given:
        // Continuous free slot 09:00–17:00; phone preferred only 13:00–14:00 (interior).
        def slots = [slot('09:00', '17:00')]
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            tasks       : [
                default_duration_minutes: 30,
                contexts                : [
                    phone: [
                        match_labels     : ['phone'],
                        preferred_windows: ['weekday 13:00-14:00'],
                        preferred_bonus  : 30,
                        avoid_penalty    : 20
                    ]
                ]
            ],
            batching    : [enabled: false],
            stability   : [freeze_within: 'PT48H', keep_manual_moves: true,
                           minimum_buffer_between_blocks_minutes: 10, churn_penalty: 40]
        ])
        def sched = new DeterministicScheduler(cfg)
        def tasks = [
            task(id: 'phone-interior', labels: ['schedule', 'phone'], priority: 2, minutes: 30, deadline: dayEnd)
        ]

        when:
        def plan = sched.propose(tasks, slots, dayStart, dayEnd, now)
        def block = plan.scheduledBlocks[0]
        def local = block.start.atZone(zone)

        then:
        plan.unscheduled.isEmpty()
        local.hour == 13
        local.minute == 0
        block.end == block.start + Duration.ofMinutes(30)
    }

    def "competing contexts: preferred wins over avoid when both candidates fit"() {
        given:
        // home prefers 16:00–17:00; phone prefers 13:00–14:00. Home task should land at 16:00
        // even though continuous free capacity starts at 09:00.
        def slots = [slot('09:00', '17:00')]
        def tasks = [
            task(id: 'home-task', labels: ['schedule', 'home'], priority: 2, minutes: 30, deadline: dayEnd)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def local = plan.scheduledBlocks[0].start.atZone(zone)

        then:
        plan.unscheduled.isEmpty()
        local.hour == 16
        local.minute == 0
    }

    def "preferred start that cannot fit duration falls back to feasible alternative"() {
        given:
        // Preferred window 13:00–14:00 but task needs 90m — 13:00+90m = 14:30 exceeds preferred
        // window scoring but still fits in slot; interior candidate at 13:00 is valid placement
        // only if duration fits usable slot. Use a preferred window that starts too late to fit:
        // free 09:00–14:00, preferred 13:00–14:00, duration 90m → 13:00 cannot fit; choose 09:00.
        def slots = [slot('09:00', '14:00')]
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            tasks       : [
                default_duration_minutes: 30,
                contexts                : [
                    phone: [
                        match_labels     : ['phone'],
                        preferred_windows: ['weekday 13:00-14:00'],
                        preferred_bonus  : 80,
                        avoid_penalty    : 5
                    ]
                ]
            ],
            batching    : [enabled: false],
            stability   : [freeze_within: 'PT48H', keep_manual_moves: true,
                           minimum_buffer_between_blocks_minutes: 0, churn_penalty: 40]
        ])
        def sched = new DeterministicScheduler(cfg)
        def tasks = [
            task(id: 'phone-long', labels: ['schedule', 'phone'], priority: 2, minutes: 90, deadline: dayEnd)
        ]

        when:
        def plan = sched.propose(tasks, slots, dayStart, dayEnd, now)
        def block = plan.scheduledBlocks[0]
        def local = block.start.atZone(zone)

        then:
        plan.unscheduled.isEmpty()
        // 13:00 + 90m = 14:30 is past slot end 14:00 → must use earlier feasible start
        local.hour == 9
        local.minute == 0
        block.durationMinutes() == 90
    }

    def "same-project tasks form explicit aggregate focus block when feasible"() {
        given:
        def slots = fullDaySlots()
        def tasks = [
            task(id: 's1', projectId: 'Scouts', content: 'advancement', minutes: 15, priority: 2, deadline: dayEnd),
            task(id: 's2', projectId: 'Scouts', content: 'parent email', minutes: 10, priority: 2, deadline: dayEnd),
            task(id: 's3', projectId: 'Scouts', content: 'den plan', minutes: 20, priority: 3, deadline: dayEnd)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def focus = plan.scheduledBlocks.find { it.focusBlock }

        then:
        focus != null
        focus.taskIds as Set == ['s1', 's2', 's3'] as Set
        focus.title.toLowerCase().contains('focus')
        focus.durationMinutes() == 45
        focus.memberIntervals.size() == 3
        focus.memberIntervals*.taskId as Set == ['s1', 's2', 's3'] as Set
        focus.memberIntervals[0].start == focus.start
        focus.memberIntervals[-1].end == focus.end
        // contiguous member spans sum to block duration
        focus.memberIntervals.sum { it.durationMinutes() } == 45
        plan.unscheduled.isEmpty()
        plan.changes.findAll { it.taskId in ['s1', 's2', 's3'] }.every {
            it.metadata?.focusBlockId == focus.id
        }
        plan.changes.findAll { it.taskId in ['s1', 's2', 's3'] }.size() == 3
    }

    def "bilateral buffer rejects morning candidate ending inside leading buffer of afternoon block"() {
        given:
        // Continuous free day; phone prefers 13:00 so it places first at 13:00.
        // Morning P1 task must not land at 12:50 (inside 10m leading buffer of 13:00).
        // Exact boundary 12:50 is rejected; 12:50 would end at 13:20 overlapping — use 30m tasks.
        def slots = [slot('09:00', '17:00')]
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            tasks       : [
                default_duration_minutes: 30,
                contexts                : [
                    phone: [
                        match_labels     : ['phone'],
                        preferred_windows: ['weekday 13:00-14:00'],
                        preferred_bonus  : 200,
                        avoid_penalty    : 5
                    ]
                ]
            ],
            batching    : [enabled: false],
            stability   : [freeze_within: 'PT48H', keep_manual_moves: true,
                           minimum_buffer_between_blocks_minutes: 10, churn_penalty: 40]
        ])
        def sched = new DeterministicScheduler(cfg)
        // phone-afternoon schedules first due to high context score at 13:00 (still lower priority)
        // morning-high is P1 so may schedule first by urgency — force order via deadline/priority:
        // Use same priority but phone has huge context bonus so when morning is placed after phone...
        // Actually unit order is deadline then priority. Give phone earlier deadline so it places first.
        def phoneDeadline = LocalDate.of(2026, 8, 6).atTime(14, 0).atZone(zone).toInstant()
        def tasks = [
            task(id: 'phone-afternoon', labels: ['schedule', 'phone'], priority: 4, minutes: 30, deadline: phoneDeadline),
            task(id: 'morning-high', priority: 3, minutes: 30, deadline: dayEnd)
        ]

        when:
        def plan = sched.propose(tasks, slots, dayStart, dayEnd, now)
        def phone = plan.scheduledBlocks.find { it.taskIds.contains('phone-afternoon') }
        def morning = plan.scheduledBlocks.find { it.taskIds.contains('morning-high') }

        then:
        plan.unscheduled.isEmpty()
        phone != null
        morning != null
        phone.start.atZone(zone).hour == 13
        // Morning must respect 10m bilateral buffer around phone
        morning.end <= phone.start - Duration.ofMinutes(10) ||
            morning.start >= phone.end + Duration.ofMinutes(10)
        Duration.between(morning.end, phone.start).toMinutes() >= 10 ||
            Duration.between(phone.end, morning.start).toMinutes() >= 10 ||
            morning.start >= phone.end + Duration.ofMinutes(10)
    }

    def "bilateral buffer accepts exact buffer boundary and both insertion orders"() {
        given:
        def slots = [slot('09:00', '12:00')]
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            batching    : [enabled: false],
            stability   : [freeze_within: 'PT0H', keep_manual_moves: true,
                           minimum_buffer_between_blocks_minutes: 10, churn_penalty: 0]
        ])
        def sched = new DeterministicScheduler(cfg)
        // Two 30m tasks in 3h window with 10m buffer → 09:00 and 09:40 exact boundary
        def tasksA = [
            task(id: 'first', minutes: 30, priority: 4, deadline: dayEnd),
            task(id: 'second', minutes: 30, priority: 3, deadline: dayEnd)
        ]
        def tasksB = [
            task(id: 'second', minutes: 30, priority: 4, deadline: dayEnd),
            task(id: 'first', minutes: 30, priority: 3, deadline: dayEnd)
        ]

        when:
        def planA = sched.propose(tasksA, slots, dayStart, dayEnd, now)
        def planB = sched.propose(tasksB, slots, dayStart, dayEnd, now)
        def blocksA = planA.scheduledBlocks.toSorted { it.start }
        def blocksB = planB.scheduledBlocks.toSorted { it.start }

        then:
        planA.unscheduled.isEmpty()
        planB.unscheduled.isEmpty()
        blocksA.size() == 2
        blocksB.size() == 2
        Duration.between(blocksA[0].end, blocksA[1].start).toMinutes() >= 10
        Duration.between(blocksB[0].end, blocksB[1].start).toMinutes() >= 10
        // exact boundary is valid (30m + 10m buffer → second at first.start+40m)
        Duration.between(blocksA[0].end, blocksA[1].start).toMinutes() == 10
    }

    def "later-clock interior block placed first does not cause buffer assertion throw for earlier block"() {
        given:
        // Reproduces the bug: afternoon context block placed first; morning task must
        // choose a valid alternative or unschedule — never throw from final assertion.
        def slots = [slot('09:00', '17:00')]
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            tasks       : [
                contexts: [
                    late: [
                        match_labels     : ['late'],
                        preferred_windows: ['weekday 10:00-11:00'],
                        preferred_bonus  : 500,
                        avoid_penalty    : 0
                    ]
                ]
            ],
            batching    : [enabled: false],
            stability   : [freeze_within: 'PT0H', keep_manual_moves: true,
                           minimum_buffer_between_blocks_minutes: 15, churn_penalty: 0]
        ])
        def sched = new DeterministicScheduler(cfg)
        def earlyDl = LocalDate.of(2026, 8, 6).atTime(11, 0).atZone(zone).toInstant()
        def tasks = [
            task(id: 'late-pref', labels: ['schedule', 'late'], priority: 4, minutes: 60, deadline: earlyDl),
            task(id: 'earlier-fill', priority: 2, minutes: 60, deadline: dayEnd)
        ]

        when:
        def plan = sched.propose(tasks, slots, dayStart, dayEnd, now)
        def blocks = plan.scheduledBlocks.toSorted { it.start }

        then:
        noExceptionThrown()
        blocks.size() == 2
        Duration.between(blocks[0].end, blocks[1].start).toMinutes() >= 15
    }

    def "frozen multi-task focus block preserves per-member intervals without duplication"() {
        given:
        def slots = fullDaySlots()
        def t1 = task(id: 'fm1', projectId: 'Scouts', minutes: 20, priority: 2, deadline: dayEnd)
        def t2 = task(id: 'fm2', projectId: 'Scouts', minutes: 25, priority: 2, deadline: dayEnd)
        def t3 = task(id: 'fm3', projectId: 'Scouts', minutes: 15, priority: 2, deadline: dayEnd)
        def bStart = LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        def m1s = bStart
        def m1e = m1s + Duration.ofMinutes(20)
        def m2e = m1e + Duration.ofMinutes(25)
        def m3e = m2e + Duration.ofMinutes(15)
        def previous = Plan.builder()
            .id('prev-focus')
            .createdAt(now)
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-focus-scouts')
                    .start(bStart)
                    .end(m3e)
                    .taskIds(['fm1', 'fm2', 'fm3'])
                    .memberIntervals([
                        new todoistcaldavsync.planner.domain.MemberInterval('fm1', m1s, m1e),
                        new todoistcaldavsync.planner.domain.MemberInterval('fm2', m1e, m2e),
                        new todoistcaldavsync.planner.domain.MemberInterval('fm3', m2e, m3e)
                    ])
                    .projectId('Scouts')
                    .projectName('Scouts')
                    .title('Scouts focus block')
                    .focusBlock(true)
                    .frozen(true)
                    .reason('prior focus')
                    .build()
            ])
            .build()

        when:
        def plan = scheduler.propose([t1, t2, t3], slots, dayStart, dayEnd, now, previous)
        def focus = plan.scheduledBlocks.find { it.focusBlock }

        then:
        focus != null
        focus.taskIds == ['fm1', 'fm2', 'fm3']
        focus.start == bStart
        focus.end == m3e
        focus.memberIntervals.size() == 3
        focus.memberIntervals[0].start == m1s
        focus.memberIntervals[0].end == m1e
        focus.memberIntervals[1].start == m1e
        focus.memberIntervals[1].end == m2e
        focus.memberIntervals[2].start == m2e
        focus.memberIntervals[2].end == m3e
        plan.scheduledBlocks.size() == 1
        plan.changes.findAll { it.type == 'keep' }.size() == 3
        plan.unscheduled.isEmpty()
    }

    def "legacy frozen focus block without memberIntervals derives sequential member ranges"() {
        given:
        def slots = fullDaySlots()
        def t1 = task(id: 'lg1', projectId: 'Home', minutes: 20, priority: 2, deadline: dayEnd)
        def t2 = task(id: 'lg2', projectId: 'Home', minutes: 10, priority: 2, deadline: dayEnd)
        def bStart = LocalDate.of(2026, 8, 6).atTime(13, 0).atZone(zone).toInstant()
        def bEnd = bStart + Duration.ofMinutes(30)
        def previous = Plan.builder()
            .id('prev-legacy')
            .createdAt(now)
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-legacy')
                    .start(bStart)
                    .end(bEnd)
                    .taskIds(['lg1', 'lg2'])
                    .projectId('Home')
                    .title('Home focus block')
                    .focusBlock(true)
                    .frozen(true)
                    .reason('legacy')
                    .build()
            ])
            .build()

        when:
        def plan = scheduler.propose([t1, t2], slots, dayStart, dayEnd, now, previous)
        def focus = plan.scheduledBlocks.find { it.focusBlock }

        then:
        focus != null
        focus.memberIntervals[0].taskId == 'lg1'
        focus.memberIntervals[0].durationMinutes() == 20
        focus.memberIntervals[1].taskId == 'lg2'
        focus.memberIntervals[1].durationMinutes() == 10
        focus.memberIntervals[0].start == bStart
        focus.memberIntervals[1].end == bEnd
        plan.changes.findAll { it.type == 'keep' }.size() == 2
    }

    def "move within requireApprovalForMoveWithin tags PlanChange with approvalRequired"() {
        given:
        def slots = fullDaySlots()
        def t = task(id: 'move-me', minutes: 30, priority: 2, deadline: dayEnd)
        // Previous placement outside freeze (48h) but inside approval horizon (7d) — use freeze 0
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00', '13:00-17:00']]],
            batching    : [enabled: false],
            stability   : [
                freeze_within                        : 'PT0H',
                keep_manual_moves                    : true,
                require_approval_for_move_within     : 'P7D',
                minimum_buffer_between_blocks_minutes: 10,
                churn_penalty                        : 0
            ]
        ])
        def sched = new DeterministicScheduler(cfg)
        def prevStart = LocalDate.of(2026, 8, 6).atTime(16, 0).atZone(zone).toInstant()
        def prevEnd = prevStart + Duration.ofMinutes(30)
        def previous = Plan.builder()
            .id('prev')
            .createdAt(now)
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-mm')
                    .start(prevStart)
                    .end(prevEnd)
                    .taskIds(['move-me'])
                    .title('move-me')
                    .reason('prior')
                    .build()
            ])
            .build()
        // Without freeze, scorer may still keep 16:00 — prefer morning so it moves.
        def cfg2 = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00', '13:00-17:00']]],
            tasks       : [
                contexts: [
                    am: [
                        match_labels     : ['am'],
                        preferred_windows: ['weekday 09:00-10:00'],
                        preferred_bonus  : 500,
                        avoid_penalty    : 0
                    ]
                ]
            ],
            batching    : [enabled: false],
            stability   : [
                freeze_within                        : 'PT0H',
                keep_manual_moves                    : true,
                require_approval_for_move_within     : 'P7D',
                minimum_buffer_between_blocks_minutes: 0,
                churn_penalty                        : 0
            ]
        ])
        def sched2 = new DeterministicScheduler(cfg2)
        def t2 = task(id: 'move-me', labels: ['schedule', 'am'], minutes: 30, priority: 2, deadline: dayEnd)

        when:
        def plan = sched2.propose([t2], slots, dayStart, dayEnd, now, previous)
        def move = plan.changes.find { it.taskId == 'move-me' }

        then:
        move != null
        move.type == 'move'
        move.newStart != prevStart
        move.metadata.approvalRequired == true
        move.metadata.approvalReason == 'move_within_require_approval_horizon'
    }

    def "does not delay urgent deadline task merely for batching"() {
        given:
        // Urgent 30m must finish by 09:30. Sequential batching may place fillers after urgent
        // in the same focus block; urgent's own member end must still meet its deadline.
        def slots = [slot('09:00', '12:00')]
        def urgentDeadline = LocalDate.of(2026, 8, 6).atTime(9, 30).atZone(zone).toInstant()
        def laterDeadline = dayEnd
        def tasks = [
            task(id: 'urgent', projectId: 'Scouts', minutes: 30, priority: 4, deadline: urgentDeadline),
            task(id: 'filler1', projectId: 'Scouts', minutes: 15, priority: 1, deadline: laterDeadline),
            task(id: 'filler2', projectId: 'Scouts', minutes: 15, priority: 1, deadline: laterDeadline)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def urgentBlock = plan.scheduledBlocks.find { it.taskIds.contains('urgent') }
        def urgentMemberEnd = urgentBlock?.memberIntervals?.find { it.taskId == 'urgent' }?.end
            ?: urgentBlock?.end

        then:
        urgentBlock != null
        urgentMemberEnd != null
        !urgentMemberEnd.isAfter(urgentDeadline)
        // urgent must not be left unscheduled for the sake of a larger batch
        !plan.unscheduled.any { it.task.id == 'urgent' }
        // and must not start after a non-urgent peer solely for batch packing
        urgentBlock.memberIntervals.find { it.taskId == 'urgent' }.start ==
            urgentBlock.memberIntervals*.start.min()
    }

    def "buffers ensure no hard conflicts between scheduled blocks"() {
        given:
        def slots = [slot('09:00', '12:00')]
        def tasks = [
            task(id: 'a', minutes: 30, priority: 4, deadline: dayEnd),
            task(id: 'b', minutes: 30, priority: 3, deadline: dayEnd),
            task(id: 'c', minutes: 30, priority: 2, deadline: dayEnd)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def blocks = plan.scheduledBlocks.toSorted { it.start }

        then:
        blocks.size() >= 2
        // 10-minute buffer between blocks
        for (int i = 0; i < blocks.size() - 1; i++) {
            Duration gap = Duration.between(blocks[i].end, blocks[i + 1].start)
            assert !gap.isNegative()
            assert gap.toMinutes() >= 10 || blocks[i + 1].start >= blocks[i].end + Duration.ofMinutes(10)
        }
    }

    def "same fixture always returns identical schedule"() {
        given:
        def slots = fullDaySlots()
        def tasks = [
            task(id: 't-a', projectId: 'P', minutes: 30, priority: 4, deadline: dayEnd),
            task(id: 't-b', projectId: 'P', minutes: 20, priority: 3, deadline: dayEnd),
            task(id: 't-c', projectId: 'Q', minutes: 45, priority: 2, deadline: dayEnd),
            task(id: 't-d', labels: ['schedule', 'phone'], minutes: 30, priority: 2, deadline: dayEnd)
        ]

        when:
        def p1 = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def p2 = scheduler.propose(tasks.toList().reverse(), slots.toList().reverse(), dayStart, dayEnd, now)
        def sig = { Plan p ->
            [
                id    : p.id,
                blocks: p.scheduledBlocks.collect { [it.id, it.start.toString(), it.end.toString(), it.taskIds, it.focusBlock] },
                unsched: p.unscheduled.collect { [it.task.id, it.code, it.reason] },
                changes: p.changes.collect { [it.type, it.taskId, it.newStart?.toString(), it.newEnd?.toString()] }
            ]
        }

        then:
        sig(p1) == sig(p2)
        p1.id == p2.id
        p1.humanDiff == p2.humanDiff
    }

    def "frozen window preserves previous near-term placement"() {
        given:
        def slots = fullDaySlots()
        def t = task(id: 'stable', minutes: 30, priority: 2, deadline: dayEnd)
        def prevStart = LocalDate.of(2026, 8, 6).atTime(14, 0).atZone(zone).toInstant()
        def prevEnd = prevStart + Duration.ofMinutes(30)
        def previous = Plan.builder()
            .id('prev')
            .createdAt(now)
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-stable')
                    .start(prevStart)
                    .end(prevEnd)
                    .taskIds(['stable'])
                    .title('stable')
                    .reason('prior')
                    .build()
            ])
            .build()

        when:
        def plan = scheduler.propose([t], slots, dayStart, dayEnd, now, previous)

        then:
        plan.scheduledBlocks.size() == 1
        plan.scheduledBlocks[0].start == prevStart
        plan.scheduledBlocks[0].frozen
        plan.changes.any { it.type == 'keep' && it.taskId == 'stable' }
    }

    def "prior single-task frozen=true outside freezeWithin is kept exactly"() {
        given:
        // freezeWithin=0 so placement is outside freeze window; prev.frozen still preserves
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00', '13:00-17:00']]],
            batching    : [enabled: false],
            stability   : [
                freeze_within                        : 'PT0H',
                keep_manual_moves                    : true,
                minimum_buffer_between_blocks_minutes: 0,
                churn_penalty                        : 0
            ],
            tasks       : [
                contexts: [
                    am: [
                        match_labels     : ['am'],
                        preferred_windows: ['weekday 09:00-10:00'],
                        preferred_bonus  : 500,
                        avoid_penalty    : 0
                    ]
                ]
            ]
        ])
        def sched = new DeterministicScheduler(cfg)
        def slots = fullDaySlots()
        def t = task(id: 'frozen-far', labels: ['schedule', 'am'], minutes: 30, priority: 2, deadline: dayEnd)
        def prevStart = LocalDate.of(2026, 8, 6).atTime(16, 0).atZone(zone).toInstant()
        def prevEnd = prevStart + Duration.ofMinutes(30)
        def previous = Plan.builder()
            .id('prev-frozen-flag')
            .createdAt(now)
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-frozen-far')
                    .start(prevStart)
                    .end(prevEnd)
                    .taskIds(['frozen-far'])
                    .title('frozen-far')
                    .frozen(true)
                    .reason('prior frozen')
                    .build()
            ])
            .build()

        when:
        def plan = sched.propose([t], slots, dayStart, dayEnd, now, previous)

        then:
        plan.scheduledBlocks.size() == 1
        plan.scheduledBlocks[0].start == prevStart
        plan.scheduledBlocks[0].end == prevEnd
        plan.scheduledBlocks[0].frozen
        plan.changes.any { it.type == 'keep' && it.taskId == 'frozen-far' }
        plan.changes.find { it.taskId == 'frozen-far' }.reason.toLowerCase().contains('frozen')
    }

    def "prior single-task frozen=false outside freezeWithin may move by score"() {
        given:
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00', '13:00-17:00']]],
            batching    : [enabled: false],
            stability   : [
                freeze_within                        : 'PT0H',
                keep_manual_moves                    : true,
                minimum_buffer_between_blocks_minutes: 0,
                churn_penalty                        : 0
            ],
            tasks       : [
                contexts: [
                    am: [
                        match_labels     : ['am'],
                        preferred_windows: ['weekday 09:00-10:00'],
                        preferred_bonus  : 500,
                        avoid_penalty    : 0
                    ]
                ]
            ]
        ])
        def sched = new DeterministicScheduler(cfg)
        def slots = fullDaySlots()
        def t = task(id: 'movable', labels: ['schedule', 'am'], minutes: 30, priority: 2, deadline: dayEnd)
        def prevStart = LocalDate.of(2026, 8, 6).atTime(16, 0).atZone(zone).toInstant()
        def prevEnd = prevStart + Duration.ofMinutes(30)
        def previous = Plan.builder()
            .id('prev-unfrozen')
            .createdAt(now)
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-movable')
                    .start(prevStart)
                    .end(prevEnd)
                    .taskIds(['movable'])
                    .title('movable')
                    .frozen(false)
                    .reason('prior')
                    .build()
            ])
            .build()

        when:
        def plan = sched.propose([t], slots, dayStart, dayEnd, now, previous)

        then:
        plan.scheduledBlocks.size() == 1
        plan.scheduledBlocks[0].start != prevStart
        plan.changes.any { it.type == 'move' && it.taskId == 'movable' }
    }

    def "manual move is preserved by default with keep change"() {
        given:
        def slots = fullDaySlots()
        def t = task(id: 'manual-moved', minutes: 30, priority: 1, deadline: dayEnd)
        def prevStart = LocalDate.of(2026, 8, 6).atTime(16, 0).atZone(zone).toInstant()
        def prevEnd = prevStart + Duration.ofMinutes(30)
        def previous = Plan.builder()
            .id('prev')
            .createdAt(now)
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-mm')
                    .start(prevStart)
                    .end(prevEnd)
                    .taskIds(['manual-moved'])
                    .title('manual-moved')
                    .manualOverride(true)
                    .reason('user moved')
                    .build()
            ])
            .build()

        when:
        def plan = scheduler.propose([t], slots, dayStart, dayEnd, now, previous, ['manual-moved'] as Set)

        then:
        plan.scheduledBlocks[0].start == prevStart
        plan.scheduledBlocks[0].manualOverride
        plan.changes.any { it.type == 'keep' }
    }

    def "multi-member focus block rejects when non-primary member is deadline-infeasible"() {
        given:
        // Primary (p-primary) is feasible at 09:00 for 30m; non-primary (p-late) ends after its
        // own tight deadline when ordered after primary. Fold must reject; never win via later
        // feasible member scores recovering from INFEASIBLE sentinel.
        def slots = [slot('09:00', '12:00')]
        def primaryDl = LocalDate.of(2026, 8, 6).atTime(12, 0).atZone(zone).toInstant()
        // Non-primary needs 30m but deadline is only 20m after block start → infeasible as second member
        def lateDl = LocalDate.of(2026, 8, 6).atTime(9, 20).atZone(zone).toInstant()
        def tasks = [
            task(id: 'p-primary', projectId: 'Batch', projectName: 'Batch', minutes: 30, priority: 4, deadline: primaryDl),
            task(id: 'p-late', projectId: 'Batch', projectName: 'Batch', minutes: 30, priority: 2, deadline: lateDl),
            // Third feasible member after infeasible one — ordering catches sentinel recovery
            task(id: 'p-ok', projectId: 'Batch', projectName: 'Batch', minutes: 30, priority: 1, deadline: primaryDl)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def focus = plan.scheduledBlocks.find { it.focusBlock && it.taskIds.containsAll(['p-primary', 'p-late', 'p-ok']) }

        then:
        focus == null
        // p-late cannot complete by deadline in any multi-member placement that includes it after primary;
        // either unscheduled or only placed alone/without violating deadline
        def lateBlock = plan.scheduledBlocks.find { it.taskIds.contains('p-late') }
        if (lateBlock != null) {
            assert lateBlock.end <= lateDl
            assert !lateBlock.focusBlock || lateBlock.taskIds == ['p-late']
        } else {
            assert plan.unscheduled.any { it.task.id == 'p-late' }
        }
        // Aggregate multi-member candidate must never schedule p-late past its deadline
        plan.scheduledBlocks.every { b ->
            if (!b.taskIds.contains('p-late')) return true
            def mi = b.memberIntervals?.find { it.taskId == 'p-late' }
            def end = mi?.end ?: b.end
            return !end.isAfter(lateDl)
        }
    }

    def "sequential focus members with different deadlines form feasible block not clipped to earliest"() {
        given:
        // 30m @ 10:00 + 60m @ 17:00 from 09:00 → 09:00–10:30 is sequentially feasible.
        // Clipping the whole 90m block to earliest deadline 10:00 would wrongly reject.
        def slots = [slot('09:00', '17:00')]
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            batching    : [
                enabled                    : true,
                project_batch_bonus        : 25,
                max_focus_block_minutes    : 120,
                minimum_focus_block_minutes: 30,
                context_switch_penalty     : 15
            ],
            stability   : [
                freeze_within                        : 'PT0H',
                keep_manual_moves                    : true,
                minimum_buffer_between_blocks_minutes: 0,
                churn_penalty                        : 0
            ]
        ])
        def sched = new DeterministicScheduler(cfg)
        def dlEarly = LocalDate.of(2026, 8, 6).atTime(10, 0).atZone(zone).toInstant()
        def dlLate = LocalDate.of(2026, 8, 6).atTime(17, 0).atZone(zone).toInstant()
        def tEarly = task(id: 'early-30', projectId: 'Seq', projectName: 'Seq', minutes: 30, priority: 2, deadline: dlEarly)
        def tLate = task(id: 'late-60', projectId: 'Seq', projectName: 'Seq', minutes: 60, priority: 2, deadline: dlLate)
        def expectStart = LocalDate.of(2026, 8, 6).atTime(9, 0).atZone(zone).toInstant()
        def expectEnd = expectStart + Duration.ofMinutes(90)

        when:
        def planForward = sched.propose([tEarly, tLate], slots, dayStart, dayEnd, now)
        def planReverse = sched.propose([tLate, tEarly], slots, dayStart, dayEnd, now)
        def focusF = planForward.scheduledBlocks.find { it.focusBlock }
        def focusR = planReverse.scheduledBlocks.find { it.focusBlock }

        then:
        planForward.unscheduled.isEmpty()
        planReverse.unscheduled.isEmpty()
        focusF != null
        focusR != null
        focusF.start == expectStart
        focusF.end == expectEnd
        focusF.taskIds == ['early-30', 'late-60']
        focusF.memberIntervals*.taskId == ['early-30', 'late-60']
        focusF.memberIntervals[0].start == expectStart
        focusF.memberIntervals[0].end == expectStart + Duration.ofMinutes(30)
        focusF.memberIntervals[1].start == expectStart + Duration.ofMinutes(30)
        focusF.memberIntervals[1].end == expectEnd
        !focusF.memberIntervals[0].end.isAfter(dlEarly)
        !focusF.memberIntervals[1].end.isAfter(dlLate)
        // Reverse input order yields identical member order and placement
        focusR.taskIds == focusF.taskIds
        focusR.memberIntervals*.taskId == focusF.memberIntervals*.taskId
        focusR.start == focusF.start
        focusR.end == focusF.end
        focusR.memberIntervals*.start == focusF.memberIntervals*.start
        focusR.memberIntervals*.end == focusF.memberIntervals*.end
    }

    def "focus block rejected when sequential member end misses its own deadline then split"() {
        given:
        // Total 80m fits before max deadline 10:30, but neither order keeps both under their deadlines:
        // A 40m @ 09:30 then B 40m @ 10:30 → A ends 09:40 > 09:30
        // B first then A → A ends 10:20 > 09:30
        def slots = [slot('09:00', '12:00')]
        def cfg = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            batching    : [
                enabled                    : true,
                max_focus_block_minutes    : 120,
                minimum_focus_block_minutes: 30
            ],
            stability   : [
                freeze_within                        : 'PT0H',
                minimum_buffer_between_blocks_minutes: 0,
                churn_penalty                        : 0
            ]
        ])
        def sched = new DeterministicScheduler(cfg)
        def dlA = LocalDate.of(2026, 8, 6).atTime(9, 30).atZone(zone).toInstant()
        def dlB = LocalDate.of(2026, 8, 6).atTime(10, 30).atZone(zone).toInstant()
        def tasks = [
            task(id: 'seq-a', projectId: 'Tight', projectName: 'Tight', minutes: 40, priority: 3, deadline: dlA),
            task(id: 'seq-b', projectId: 'Tight', projectName: 'Tight', minutes: 40, priority: 2, deadline: dlB)
        ]

        when:
        def plan = sched.propose(tasks, slots, dayStart, dayEnd, now)
        def joint = plan.scheduledBlocks.find {
            it.focusBlock && it.taskIds.containsAll(['seq-a', 'seq-b'])
        }
        def blockA = plan.scheduledBlocks.find { it.taskIds == ['seq-a'] || (it.taskIds.contains('seq-a') && it.taskIds.size() == 1) }
        def blockB = plan.scheduledBlocks.find { it.taskIds.contains('seq-b') }

        then:
        joint == null
        // A cannot finish 40m by 09:30 from day open → unscheduled or never past deadline
        if (blockA != null) {
            assert !blockA.end.isAfter(dlA)
        } else {
            assert plan.unscheduled.any { it.task.id == 'seq-a' }
        }
        // B is feasible alone before 10:30
        blockB != null
        !blockB.end.isAfter(dlB)
        plan.scheduledBlocks.every { b ->
            b.memberIntervals.every { mi ->
                def t = tasks.find { it.id == mi.taskId }
                t?.deadline == null || !mi.end.isAfter(t.deadline)
            }
        }
    }

    def "foldMemberScores returns INFEASIBLE atomically when any member is infeasible"() {
        given:
        long ok = 100L
        long ok2 = 200L

        expect:
        DeterministicScheduler.foldMemberScores([ok, ok2]) == ok + ok2 / 4
        DeterministicScheduler.foldMemberScores([ok, PlanScorer.INFEASIBLE, ok2]) == PlanScorer.INFEASIBLE
        DeterministicScheduler.foldMemberScores([PlanScorer.INFEASIBLE, ok2]) == PlanScorer.INFEASIBLE
        DeterministicScheduler.foldMemberScores([ok]) == ok
        DeterministicScheduler.foldMemberScores([]) == 0L
    }

    def "proposal diff lists scheduled moved kept and unscheduled with reasons"() {
        given:
        def slots = [slot('09:00', '10:00')]
        def tasks = [
            task(id: 'fit', minutes: 30, priority: 4, deadline: dayEnd),
            task(id: 'nofit', minutes: 120, priority: 4, deadline: dayEnd)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def md = plan.humanDiff

        then:
        md.contains('AM') || md.contains('PM')
        md.toLowerCase().contains('scheduled') || md.contains('Added') || md.contains('fit')
        md.toLowerCase().contains('unscheduled')
        plan.unscheduled.any { it.task.id == 'nofit' && it.reason }
        // machine JSON remains ISO
        def json = PlanDiffFormatter.toJson(plan)
        json.contains('2026-08-')
        !json.contains('"h:mm a"')
    }

    def "snapshot state persistence round-trip without external writes"() {
        given:
        def slots = fullDaySlots()
        def tasks = [
            task(id: 'persist-a', projectId: 'Home', minutes: 30, priority: 3, deadline: dayEnd),
            task(id: 'persist-b', projectId: 'Home', minutes: 20, priority: 2, deadline: dayEnd)
        ]
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)
        def dir = Files.createTempDirectory('plan-store-test')
        def store = new PlanStore(dir)

        when:
        store.save(plan)
        def loaded = store.load(plan.id)

        then:
        loaded != null
        loaded.id == plan.id
        loaded.scheduledBlocks.size() == plan.scheduledBlocks.size()
        loaded.scheduledBlocks*.taskIds == plan.scheduledBlocks*.taskIds
        loaded.scheduledBlocks*.start == plan.scheduledBlocks*.start
        loaded.unscheduled*.task*.id == plan.unscheduled*.task*.id
        loaded.mode == 'preview'
        Files.exists(store.pathFor(plan.id))
        // No network — only local path under temp dir
        store.pathFor(plan.id).toAbsolutePath().startsWith(dir.toAbsolutePath())

        cleanup:
        dir.toFile().deleteDir()
    }

    def "excludes manual tasks from scheduling"() {
        given:
        def slots = fullDaySlots()
        def tasks = [
            task(id: 'auto', minutes: 30, priority: 2, deadline: dayEnd),
            task(id: 'hand', minutes: 30, priority: 4, deadline: dayEnd, manual: true)
        ]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)

        then:
        plan.scheduledBlocks.every { !it.taskIds.contains('hand') }
        !plan.unscheduled.any { it.task.id == 'hand' }
        plan.tasks.every { !it.manual }
    }

    def "soft-blocked slots remain usable but do not create hard conflicts"() {
        given:
        def slots = [
            slot('09:00', '10:00', false),
            slot('10:00', '11:00', true),
            slot('11:00', '12:00', false)
        ]
        def tasks = [task(id: 'span', minutes: 90, priority: 4, deadline: dayEnd)]

        when:
        def plan = scheduler.propose(tasks, slots, dayStart, dayEnd, now)

        then:
        plan.scheduledBlocks.size() == 1
        plan.scheduledBlocks[0].durationMinutes() == 90
        plan.unscheduled.isEmpty()
    }
}
