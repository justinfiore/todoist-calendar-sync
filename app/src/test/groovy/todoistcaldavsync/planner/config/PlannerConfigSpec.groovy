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
        !config.weather.enabled
        !config.messaging.enabled
    }

    def "parses enabled messaging section with schedules and env secret refs"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled             : true,
                provider            : 'slack',
                destination         : '#todoist-planner',
                webhook_url_env     : 'SLACK_WEBHOOK_URL',
                daily_summary       : true,
                capacity_risk_alerts: true,
                schedules           : [
                    [name: 'daily', kind: 'daily_summary', schedule: '06:00', horizon: 'P1D'],
                    [name: 'weekly', kind: 'weekly_summary', schedule: 'mon 09:00', horizon: 'P7D']
                ]
            ]
        ])

        then:
        config.messaging.enabled
        config.messaging.provider == 'slack'
        config.messaging.destination == '#todoist-planner'
        config.messaging.webhookUrlEnv == 'SLACK_WEBHOOK_URL'
        config.messaging.schedules.size() == 2
        config.messaging.isKindEnabled('daily_summary')
        config.messaging.isKindEnabled('capacity_risk_alert')
    }

    def "explicit enabled_kinds is exclusive allowlist; omitted kinds false despite default booleans"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'UTC',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled        : true,
                provider       : 'slack',
                destination    : '#p',
                webhook_url_env: 'SLACK_WEBHOOK_URL',
                enabled_kinds  : ['daily_summary']
            ]
        ])

        then:
        config.messaging.isKindEnabled('daily_summary')
        !config.messaging.isKindEnabled('weekly_summary')
        !config.messaging.isKindEnabled('medium_horizon_summary')
        !config.messaging.isKindEnabled('capacity_risk_alert')
        !config.messaging.isKindEnabled('proposal')
        config.messaging.enabledKinds == ['daily_summary'] as Set
        config.messaging.kindAllowlistExplicit
    }

    def "explicit enabled_kinds risk only enables risk"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled        : true,
                provider       : 'in_memory',
                destination    : '#p',
                webhook_url_env: 'X',
                enabled_kinds  : ['capacity_risk_alert']
            ]
        ])

        then:
        config.messaging.isKindEnabled('capacity_risk_alert')
        !config.messaging.isKindEnabled('daily_summary')
        !config.messaging.isKindEnabled('weekly_summary')
        !config.messaging.isKindEnabled('medium_horizon_summary')
        !config.messaging.isKindEnabled('proposal')
    }

    def "absent enabled_kinds preserves per-kind boolean flags and defaults"() {
        when:
        def defaults = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled        : true,
                provider       : 'in_memory',
                destination    : '#p',
                webhook_url_env: 'X'
            ]
        ])
        def selective = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled                 : true,
                provider                : 'in_memory',
                destination             : '#p',
                webhook_url_env         : 'X',
                daily_summary           : true,
                weekly_summary          : false,
                medium_horizon_summary  : false,
                capacity_risk_alerts    : false,
                proposal_summary        : false
            ]
        ])

        then:
        !defaults.messaging.kindAllowlistExplicit
        defaults.messaging.isKindEnabled('daily_summary')
        defaults.messaging.isKindEnabled('weekly_summary')
        defaults.messaging.isKindEnabled('medium_horizon_summary')
        defaults.messaging.isKindEnabled('capacity_risk_alert')
        defaults.messaging.isKindEnabled('proposal')

        !selective.messaging.kindAllowlistExplicit
        selective.messaging.isKindEnabled('daily_summary')
        !selective.messaging.isKindEnabled('weekly_summary')
        !selective.messaging.isKindEnabled('medium_horizon_summary')
        !selective.messaging.isKindEnabled('capacity_risk_alert')
        !selective.messaging.isKindEnabled('proposal')
    }

    def "explicit empty enabled_kinds disables all message kinds"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled        : true,
                provider       : 'in_memory',
                destination    : '#p',
                webhook_url_env: 'X',
                enabled_kinds  : []
            ]
        ])

        then:
        config.messaging.kindAllowlistExplicit
        config.messaging.enabledKinds.isEmpty()
        !config.messaging.isKindEnabled('daily_summary')
        !config.messaging.isKindEnabled('weekly_summary')
        !config.messaging.isKindEnabled('medium_horizon_summary')
        !config.messaging.isKindEnabled('capacity_risk_alert')
        !config.messaging.isKindEnabled('proposal')
    }

    def "enabled_kinds normalizes aliases and case to canonical kinds"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled        : true,
                provider       : 'in_memory',
                destination    : '#p',
                webhook_url_env: 'X',
                enabled_kinds  : ['Daily', 'WEEKLY_SUMMARY', 'medium-horizon', 'risk', 'Proposal_Summary']
            ]
        ])

        then:
        config.messaging.enabledKinds == [
            'daily_summary', 'weekly_summary', 'medium_horizon_summary',
            'capacity_risk_alert', 'proposal'
        ] as Set
        config.messaging.isKindEnabled('daily_summary')
        config.messaging.isKindEnabled('DAILY')
        config.messaging.isKindEnabled('capacity_risk_alert')
        config.messaging.isKindEnabled('risk')
    }

    def "schedule kinds normalize aliases and case to canonical DeliverySchedule.kind"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled        : true,
                provider       : 'in_memory',
                destination    : '#p',
                webhook_url_env: 'X',
                enabled_kinds  : ['daily_summary', 'weekly_summary', 'medium_horizon_summary',
                                  'capacity_risk_alert', 'proposal'],
                schedules      : [
                    [name: 'd', kind: 'Daily', schedule: '06:00', horizon: 'P1D'],
                    [name: 'w', kind: 'WEEKLY', schedule: 'mon 09:00', horizon: 'P7D'],
                    [name: 'm', kind: 'medium-horizon', schedule: 'mon 09:30', horizon: 'P14D'],
                    [name: 'r', kind: '  risk  ', schedule: '06:15', horizon: 'P5D'],
                    [name: 'p', kind: 'Proposal_Summary', schedule: '07:00', horizon: 'P1D']
                ]
            ]
        ])

        then:
        config.messaging.schedules*.kind == [
            'daily_summary', 'weekly_summary', 'medium_horizon_summary',
            'capacity_risk_alert', 'proposal'
        ]
        config.messaging.schedules.every { PlannerConfig.CANONICAL_MESSAGE_KINDS.contains(it.kind) }
    }

    def "schedule kinds reject unknown blank and null with index and value"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
                schedules: [
                    [name: 'ok', kind: 'daily_summary', schedule: '06:00'],
                    [name: 'bad', kind: 'not_a_kind', schedule: '07:00']
                ]
            ]
        ])
        then:
        def unknown = thrown(IllegalArgumentException)
        unknown.message.contains('schedules[1]')
        unknown.message.contains('not_a_kind')
        unknown.message.toLowerCase().contains('unknown') || unknown.message.toLowerCase().contains('kind')

        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
                schedules: [[name: 'blank', kind: '   ', schedule: '06:00']]
            ]
        ])
        then:
        def blank = thrown(IllegalArgumentException)
        blank.message.contains('schedules[0]')
        blank.message.toLowerCase().contains('blank') || blank.message.toLowerCase().contains('kind')

        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
                schedules: [[name: 'n', kind: null, schedule: '06:00']]
            ]
        ])
        then:
        def nul = thrown(IllegalArgumentException)
        nul.message.contains('schedules[0]')
        nul.message.toLowerCase().contains('kind')
    }

    def "duplicate schedules after kind normalization are rejected"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
                schedules: [
                    [name: 'a', kind: 'daily', schedule: '06:00', horizon: 'P1D', window: 'PT30M'],
                    [name: 'b', kind: 'daily_summary', schedule: '06:00', horizon: 'P1D', window: 'PT30M']
                ]
            ]
        ])
        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('duplicate')
        e.message.contains('daily_summary') || e.message.toLowerCase().contains('schedule')
    }

    def "normalizeMessageKind is shared single canonical normalizer"() {
        expect:
        PlannerConfig.normalizeMessageKind('daily') == 'daily_summary'
        PlannerConfig.normalizeMessageKind('DAILY_SUMMARY') == 'daily_summary'
        PlannerConfig.normalizeMessageKind('  risk  ') == 'capacity_risk_alert'
        PlannerConfig.normalizeMessageKind('medium-horizon') == 'medium_horizon_summary'
        PlannerConfig.normalizeMessageKind('weekly') == 'weekly_summary'
        PlannerConfig.normalizeMessageKind('proposal') == 'proposal'
        PlannerConfig.normalizeMessageKind('') == null
        PlannerConfig.normalizeMessageKind(null) == null
        PlannerConfig.normalizeMessageKind('nope') == null
    }

    def "enabled_kinds rejects unknown blank and duplicate-after-normalization entries"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
                enabled_kinds: ['daily_summary', 'not_a_kind']
            ]
        ])
        then:
        def unknown = thrown(IllegalArgumentException)
        unknown.message.toLowerCase().contains('enabled_kinds')
        unknown.message.toLowerCase().contains('unknown') || unknown.message.contains('not_a_kind')

        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
                enabled_kinds: ['daily_summary', '  ']
            ]
        ])
        then:
        def blank = thrown(IllegalArgumentException)
        blank.message.toLowerCase().contains('enabled_kinds')
        blank.message.toLowerCase().contains('blank') || blank.message.toLowerCase().contains('empty')

        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled: true, provider: 'in_memory', destination: '#p', webhook_url_env: 'X',
                enabled_kinds: ['daily', 'daily_summary']
            ]
        ])
        then:
        def dup = thrown(IllegalArgumentException)
        dup.message.toLowerCase().contains('enabled_kinds')
        dup.message.toLowerCase().contains('duplicate')
    }

    def "rejects explicit per-kind true boolean conflicting with enabled_kinds allowlist"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled             : true,
                provider            : 'in_memory',
                destination         : '#p',
                webhook_url_env     : 'X',
                enabled_kinds       : ['daily_summary'],
                capacity_risk_alerts: true
            ]
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('enabled_kinds') || e.message.toLowerCase().contains('capacity_risk')
        e.message.toLowerCase().contains('conflict') || e.message.toLowerCase().contains('omitted')
    }

    def "rejects raw messaging secrets in config"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'UTC',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [
                enabled    : true,
                provider   : 'slack',
                destination: '#x',
                bot_token  : 'xoxb-inline-secret'
            ]
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('secret') || e.message.contains('bot_token')
    }

    def "disabled messaging without extras does not affect planning config"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'UTC',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            messaging   : [enabled: false]
        ])

        then:
        !config.messaging.enabled
        config.mode == 'preview'
    }

    def "parses enabled weather section with task rules"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather     : [
                enabled   : true,
                provider  : 'open_meteo',
                latitude  : 40.71,
                longitude : -74.01,
                max_age   : 'PT6H',
                fallback  : 'fail_closed',
                task_rules: [
                    [
                        name        : 'deck-paint',
                        match_labels: ['paint', 'deck'],
                        require     : [
                            precipitation_probability_max: 15,
                            precipitation_mm_max         : 0,
                            temperature_min_c            : 10,
                            wind_speed_kph_max           : 20
                        ],
                        preferred   : [daylight: true, forecast_confidence_min: 0.7]
                    ]
                ]
            ]
        ])

        then:
        config.weather.enabled
        config.weather.provider == 'open_meteo'
        config.weather.latitude == 40.71d
        config.weather.taskRules.size() == 1
        config.weather.taskRules[0].precipitationProbabilityMax == 15d
        config.weather.taskRules[0].preferredDaylight == true
        config.weather.taskRules[0].preferredForecastConfidenceMin == 0.7d
    }

    def "rejects weather enabled without coordinates"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather     : [enabled: true, provider: 'open_meteo']
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('latitude') || e.message.toLowerCase().contains('longitude')
    }

    def "rejects invalid weather threshold ranges"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather     : [
                enabled  : true,
                latitude : 40.0,
                longitude: -74.0,
                task_rules: [[
                    match_labels: ['outdoor'],
                    require     : [precipitation_probability_max: 150]
                ]]
            ]
        ])

        then:
        thrown(IllegalArgumentException)
    }

    def "disabled weather without provider does not fail startup"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather     : [enabled: false]
        ])

        then:
        !config.weather.enabled
    }

    def "disabled weather with invalid task rule fails validation"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather     : [
                enabled   : false,
                task_rules: [[
                    match_labels: ['outdoor'],
                    require     : [precipitation_probability_max: 150]
                ]]
            ]
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('precipitation_probability') ||
            e.message.toLowerCase().contains('task_rules')
    }

    def "disabled weather with valid rules parses and stores but remains disabled"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather     : [
                enabled   : false,
                provider  : 'open_meteo',
                latitude  : 40.71,
                longitude : -74.01,
                task_rules: [[
                    name        : 'deck',
                    match_labels: ['outdoor'],
                    require     : [precipitation_probability_max: 15]
                ]]
            ]
        ])

        then:
        !config.weather.enabled
        config.weather.taskRules.size() == 1
        config.weather.taskRules[0].precipitationProbabilityMax == 15d
        config.weather.latitude == 40.71d
    }

    def "rejects non-finite weather coordinates when enabled"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather     : [
                enabled  : true,
                latitude : 'NaN',
                longitude: -74.0,
                task_rules: [[match_labels: ['o'], require: [daylight: true]]]
            ]
        ])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('finite') || e.message.toLowerCase().contains('latitude')
    }

    def "rejects non-finite weather coordinates when provided while disabled"() {
        when:
        PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            weather     : [
                enabled  : false,
                latitude : 'Infinity',
                longitude: -74.0
            ]
        ])

        then:
        thrown(IllegalArgumentException)
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
