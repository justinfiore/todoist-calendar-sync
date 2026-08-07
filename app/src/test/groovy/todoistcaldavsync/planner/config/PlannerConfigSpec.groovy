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
