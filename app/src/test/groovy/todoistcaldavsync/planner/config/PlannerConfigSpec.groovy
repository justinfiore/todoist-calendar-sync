package todoistcaldavsync.planner.config

import spock.lang.Specification
import todoistcaldavsync.planner.domain.EventRole

import java.time.DayOfWeek
import java.time.LocalTime

class PlannerConfigSpec extends Specification {

    private static File repoFile(String relative) {
        def fromApp = new File("..", relative)
        if (fromApp.exists()) {
            return fromApp
        }
        def fromRoot = new File(relative)
        if (fromRoot.exists()) {
            return fromRoot
        }
        throw new IllegalStateException("Cannot locate ${relative}")
    }

    def "loads example config with preview safe defaults"() {
        when:
        def config = PlannerConfig.load(repoFile('conf/todoist-planner.conf.example.yaml'))

        then:
        config.mode == 'preview'
        config.timezone.id == 'America/New_York'
        config.manualLabel == 'manual'
        config.defaultDurationMinutes == 30
        config.durationLabels.t60 == 60
        config.eventRules.size() >= 1
        config.calendarDefaults.any { it.calendarName == 'Work' && it.defaultRole == EventRole.HARD_BLOCKER }
        config.unknownCalendarFallback == EventRole.INFORMATIONAL
        config.workingWindows.any { it.dayOfWeek == DayOfWeek.MONDAY && it.start == LocalTime.of(6, 30) }
        config.stability.freezeWithin.toHours() == 48
        config.stability.keepManualMoves
        config.stability.minimumBufferBetweenBlocksMinutes == 10
        config.batching.enabled
        config.batching.projectBatchBonus == 25
        config.batching.maxFocusBlockMinutes == 90
        config.taskContexts.any { it.name == 'phone' }
    }

    def "parses stability batching and task contexts"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            stability   : [
                freeze_within                       : 'PT24H',
                keep_manual_moves                   : false,
                minimum_buffer_between_blocks_minutes: 5,
                churn_penalty                       : 50
            ],
            batching    : [
                enabled                    : true,
                project_batch_bonus        : 10,
                max_focus_block_minutes    : 60,
                minimum_focus_block_minutes: 20,
                context_switch_penalty     : 8
            ],
            tasks       : [
                contexts: [
                    phone: [match_labels: ['phone'], preferred_windows: ['weekday 12:00-13:00']]
                ]
            ]
        ])

        then:
        config.stability.freezeWithin.toHours() == 24
        !config.stability.keepManualMoves
        config.stability.minimumBufferBetweenBlocksMinutes == 5
        config.batching.projectBatchBonus == 10
        config.batching.minimumFocusBlockMinutes == 20
        config.taskContexts.size() == 1
        config.taskContexts[0].preferredWindows.size() == 1
    }

    def "rejects min focus greater than max focus"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            batching    : [minimum_focus_block_minutes: 120, max_focus_block_minutes: 60]
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('minimum_focus') || e.message.toLowerCase().contains('max_focus')
    }

    def "rejects invalid mode and empty working windows"() {
        when:
        PlannerConfig.fromMap(planner: [mode: 'explode', availability: [working_windows: [weekday: ['09:00-10:00']]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('mode')

        when:
        PlannerConfig.fromMap(planner: [mode: 'preview', availability: [working_windows: [:]]])

        then:
        thrown(IllegalArgumentException)
    }

    def "rejects bad timezone and bad duration labels"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode    : 'preview',
            timezone: 'Not/AZone',
            availability: [working_windows: [weekday: ['09:00-10:00']]],
            tasks   : [duration_labels: [t30: -5]]
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('timezone') || e.message.contains('duration_labels')
    }

    def "rejects invalid event rule role and regex"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [
                working_windows: [weekday: ['09:00-12:00']],
                event_rules    : [[name: 'bad', role: 'explode', title_regex: '(']]
            ]
        ])

        then:
        thrown(IllegalArgumentException)
    }

    def "rejects empty event rule with no match criteria"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [
                working_windows: [weekday: ['09:00-12:00']],
                event_rules    : [[name: 'empty-catch-all', role: 'hard_blocker']]
            ]
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('calendar_regex') || e.message.toLowerCase().contains('title_regex') || e.message.toLowerCase().contains('text_regex')
        e.message.contains('empty-catch-all')
    }

    def "rejects overnight working windows as unsupported in Phase 1"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [
                working_windows: [weekday: ['22:00-06:00']]
            ]
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('overnight')
    }

    def "parses weekend and weekday windows"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [
                working_windows: [
                    weekday: ['09:00-12:00'],
                    weekend: ['10:00-14:00']
                ]
            ]
        ])

        then:
        config.workingWindows.count { it.dayOfWeek == DayOfWeek.SATURDAY } == 1
        config.workingWindows.count { it.dayOfWeek == DayOfWeek.TUESDAY } == 1
        config.workingWindows.find { it.dayOfWeek == DayOfWeek.SUNDAY }.end == LocalTime.of(14, 0)
    }

    def "rejects buffers on informational and managed_output event rules"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [
                working_windows: [weekday: ['09:00-12:00']],
                event_rules    : [[
                    name                 : 'info with buffer',
                    calendar_regex       : '^Bob$',
                    role                 : 'informational',
                    buffer_before_minutes: 15
                ]]
            ]
        ])

        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.toLowerCase().contains('buffer')
        e1.message.toLowerCase().contains('informational')

        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [
                working_windows: [weekday: ['09:00-12:00']],
                event_rules    : [[
                    name                 : 'managed with buffer',
                    calendar_regex       : '^Todoist Planned$',
                    role                 : 'managed_output',
                    buffer_after_minutes : 10
                ]]
            ]
        ])

        then:
        def e2 = thrown(IllegalArgumentException)
        e2.message.toLowerCase().contains('buffer')
        e2.message.toLowerCase().contains('managed_output')
    }

    def "allows buffers on hard_blocker and soft_blocker rules"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [
                working_windows: [weekday: ['09:00-12:00']],
                event_rules    : [
                    [name: 'hard', calendar_regex: '^W$', role: 'hard_blocker', buffer_before_minutes: 5],
                    [name: 'soft', calendar_regex: '^F$', role: 'soft_blocker', buffer_after_minutes: 10]
                ]
            ]
        ])

        then:
        config.eventRules.find { it.name == 'hard' }.bufferBeforeMinutes == 5
        config.eventRules.find { it.name == 'soft' }.bufferAfterMinutes == 10
    }

    def "PreferredWindow.overlapsInstantRange is same-day only and DST-safe"() {
        given:
        def zone = java.time.ZoneId.of('America/New_York')
        // US DST spring forward 2026-03-08; autumn back 2026-11-01
        def win = PlannerConfig.PreferredWindow.parse('weekday 09:00-12:00', [], 'test')
        def day = java.time.LocalDate.of(2026, 3, 9) // Monday after spring forward
        def start = day.atTime(9, 30).atZone(zone)
        def end = day.atTime(10, 30).atZone(zone)
        def crossMidnightStart = day.atTime(23, 0).atZone(zone)
        def crossMidnightEnd = day.plusDays(1).atTime(1, 0).atZone(zone)
        // DST fall-back day afternoon still same local date
        def fallDay = java.time.LocalDate.of(2026, 11, 2) // Monday
        def fallStart = fallDay.atTime(10, 0).atZone(zone)
        def fallEnd = fallDay.atTime(11, 0).atZone(zone)

        expect:
        win.overlapsInstantRange(start, end)
        !win.overlapsInstantRange(crossMidnightStart, crossMidnightEnd)
        win.overlapsInstantRange(fallStart, fallEnd)
        !win.overlapsInstantRange(start, start) // zero-length
    }

    def "requireApprovalForMoveWithin is documented preview-only stability field"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            stability   : [require_approval_for_move_within: 'P3D']
        ])

        then:
        config.stability.requireApprovalForMoveWithin.toDays() == 3
    }

    def "Builder.build rejects invalid state that cannot escape"() {
        when:
        PlannerConfig.builder()
            .mode('preview')
            .workingWindows([])
            .build()

        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.toLowerCase().contains('working_windows')

        when:
        PlannerConfig.builder()
            .mode('not_a_mode')
            .workingWindows([
                new PlannerConfig.WorkingWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 'weekday')
            ])
            .build()

        then:
        def e2 = thrown(IllegalArgumentException)
        e2.message.contains('mode')

        when:
        PlannerConfig.builder()
            .mode('preview')
            .defaultDurationMinutes(-1)
            .workingWindows([
                new PlannerConfig.WorkingWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 'weekday')
            ])
            .build()

        then:
        def e3 = thrown(IllegalArgumentException)
        e3.message.contains('default_duration')

        when:
        def ok = PlannerConfig.builder()
            .mode('preview')
            .workingWindows([
                new PlannerConfig.WorkingWindow(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 'weekday')
            ])
            .build()

        then:
        ok.workingWindows.size() == 1
        ok.mode == 'preview'
    }
}
