package todoistcaldavsync.planner.report

import spock.lang.Specification
import todoistcaldavsync.planner.PlannerCli
import todoistcaldavsync.planner.adapters.FixtureCalendarGateway
import todoistcaldavsync.planner.adapters.FixtureTodoistGateway
import todoistcaldavsync.planner.config.PlannerConfig

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CapacityReportAndCliSpec extends Specification {

    private static File fixture(String name) {
        def url = CapacityReportAndCliSpec.classLoader.getResource("planner/fixtures/${name}")
        if (url) {
            return new File(url.toURI())
        }
        def candidates = [
            new File("src/test/resources/planner/fixtures/${name}"),
            new File("app/src/test/resources/planner/fixtures/${name}")
        ]
        def hit = candidates.find { it.exists() }
        if (!hit) {
            throw new IllegalStateException("Cannot locate fixture ${name}")
        }
        return hit
    }

    def configFile = fixture('capacity-config.yaml')
    def tasksFile = fixture('capacity-tasks.yaml')
    def eventsFile = fixture('capacity-events.yaml')

    def "realistic capacity report identifies demand, risks, and event capacity reasons"() {
        given:
        def config = PlannerConfig.load(configFile)
        def service = new CapacityReportService(
            config,
            FixtureTodoistGateway.fromFile(tasksFile),
            FixtureCalendarGateway.fromFile(eventsFile, config.timezone)
        )
        ZoneId zone = config.timezone
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 8).atStartOfDay(zone).toInstant()

        when:
        def report = service.generate(rangeStart, rangeEnd)
        def md = CapacityReportFormatter.toMarkdown(report)
        def json = CapacityReportFormatter.toJson(report)

        then:
        report.usableCapacityMinutes > 0
        report.taskDemandMinutes > 0
        report.candidateTasks.every { !it.manual }
        report.manualExcludedTasks*.id.contains('t-manual')
        !report.candidateTasks*.id.contains('t-manual')
        report.deadlineRisks.any { it.task.id == 't-impossible' }
        report.classifiedEvents.any { it.id == 'ev-justin-drive' && it.role.configValue == 'hard_blocker' }
        report.classifiedEvents.any { it.id == 'ev-bob-game' && it.role.configValue == 'informational' }
        report.classifiedEvents.any { it.id == 'ev-unknown' && it.unknownCalendar }
        report.explanations.any { it.message.toLowerCase().contains('consumes') || it.code == 'capacity_consumed' }
        report.explanations.any { it.code == 'informational_no_capacity' }
        report.explanations.any { it.code == 'manual_excluded' }

        and: 'native duration overrides t15 label'
        report.candidateTasks.find { it.id == 't-native-dur' }.effectiveDuration.toMinutes() == 45
        report.candidateTasks.find { it.id == 't-native-dur' }.durationSource == 'native'

        and: 'markdown uses 12-hour AM/PM'
        md.contains('AM') || md.contains('PM')
        md.contains('Capacity Report')
        md.contains('Paint the Deck')
        md.contains('Rewrite monorepo')

        and: 'json uses ISO instants'
        json.contains('usableCapacityMinutes')
        json.contains('2026-08-')
        json.contains('t-impossible')
        !json.toLowerCase().contains('"format":')
    }

    def "CLI capacity-report markdown and json via fixtures"() {
        when:
        def mdOut = new StringBuilder()
        def mdErr = new StringBuilder()
        def mdCode = PlannerCli.run([
            '--mode', 'capacity-report',
            '--format', 'markdown',
            '--config', configFile.path,
            '--tasks', tasksFile.path,
            '--events', eventsFile.path,
            '--range-start', '2026-08-06',
            '--range-end', '2026-08-08'
        ] as String[], mdOut, mdErr)

        def jsonOut = new StringBuilder()
        def jsonErr = new StringBuilder()
        def jsonCode = PlannerCli.run([
            '--mode', 'capacity-report',
            '--format', 'json',
            '--config', configFile.path,
            '--tasks', tasksFile.path,
            '--events', eventsFile.path,
            '--range-start', '2026-08-06',
            '--range-end', '2026-08-08'
        ] as String[], jsonOut, jsonErr)

        then:
        mdCode == 0
        jsonCode == 0
        mdErr.toString().isEmpty()
        jsonErr.toString().isEmpty()
        mdOut.toString().contains('# Capacity Report')
        (mdOut.toString().contains('AM') || mdOut.toString().contains('PM'))
        jsonOut.toString().contains('"usableCapacityMinutes"')
        jsonOut.toString().contains('"deadlineRisks"')
    }

    def "CLI rejects unknown mode and missing config"() {
        when:
        def err = new StringBuilder()
        def code = PlannerCli.run(['--mode', 'apply_all'] as String[], new StringBuilder(), err)

        then:
        code == 2
        err.toString().contains('unsupported mode')

        when:
        def err2 = new StringBuilder()
        def code2 = PlannerCli.run(['--mode', 'capacity-report'] as String[], new StringBuilder(), err2)

        then:
        code2 == 2
        err2.toString().toLowerCase().contains('config')
    }

    def "proof capacity-report service only invokes read methods on read gateway types"() {
        given:
        def config = PlannerConfig.load(configFile)
        def todoist = Mock(todoistcaldavsync.planner.adapters.TodoistReadGateway)
        def calendar = Mock(todoistcaldavsync.planner.adapters.CalendarReadGateway)
        todoist.fetchTasks() >> FixtureTodoistGateway.fromFile(tasksFile).fetchTasks()
        calendar.fetchEvents(_, _) >> FixtureCalendarGateway.fromFile(eventsFile, config.timezone)
            .fetchEvents(Instant.parse('2026-08-06T04:00:00Z'), Instant.parse('2026-08-08T04:00:00Z'))

        def service = new CapacityReportService(config, todoist, calendar)

        when:
        service.generate(Instant.parse('2026-08-06T04:00:00Z'), Instant.parse('2026-08-08T04:00:00Z'))

        then:
        1 * todoist.fetchTasks()
        1 * calendar.fetchEvents(_, _)
        0 * _

        and: 'constructor API is typed to read-only interfaces'
        CapacityReportService.declaredConstructors.find { it.parameterCount == 3 }
            .parameterTypes[1..2] == [
            todoistcaldavsync.planner.adapters.TodoistReadGateway,
            todoistcaldavsync.planner.adapters.CalendarReadGateway
        ] as Class[]
    }

    def "CLI rejects range-end not after range-start including defaults"() {
        when: 'explicit end before start'
        def err1 = new StringBuilder()
        def code1 = PlannerCli.run([
            '--mode', 'capacity-report',
            '--config', configFile.path,
            '--tasks', tasksFile.path,
            '--events', eventsFile.path,
            '--range-start', '2026-08-08',
            '--range-end', '2026-08-06'
        ] as String[], new StringBuilder(), err1)

        then:
        code1 == 2
        err1.toString().toLowerCase().contains('range-end') || err1.toString().toLowerCase().contains('after')

        when: 'equal bounds'
        def err2 = new StringBuilder()
        def code2 = PlannerCli.run([
            '--mode', 'capacity-report',
            '--config', configFile.path,
            '--tasks', tasksFile.path,
            '--events', eventsFile.path,
            '--range-start', '2026-08-06',
            '--range-end', '2026-08-06'
        ] as String[], new StringBuilder(), err2)

        then:
        code2 == 2
        err2.toString().contains('range-end') || err2.toString().contains('after')

        when: 'only range-end before defaulted start would still need end > start — end in the past relative to today may pass or fail; equal instant via same date default path is covered above'
        // only range-start far future with default end (today+3) => end before start
        def err3 = new StringBuilder()
        def code3 = PlannerCli.run([
            '--mode', 'capacity-report',
            '--config', configFile.path,
            '--tasks', tasksFile.path,
            '--events', eventsFile.path,
            '--range-start', '2099-01-01'
        ] as String[], new StringBuilder(), err3)

        then:
        code3 == 2
        err3.toString().toLowerCase().contains('range-end') || err3.toString().toLowerCase().contains('after')
    }

    def "date-only deadline in America/New_York keeps evening capacity eligible on deadline day"() {
        given:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [weekday: ['09:00-12:00', '13:00-17:00', '18:00-21:00']],
                calendars      : []
            ],
            tasks       : [
                default_duration_minutes: 60,
                scheduling_eligible_labels: ['schedule']
            ]
        ])
        ZoneId zone = config.timezone
        // Single 60m task with date-only deadline on Thursday 2026-08-06
        def tasks = [[
            id: 'deadline-day',
            content: 'Finish by end of local day',
            labels: ['schedule'],
            priority: 4,
            deadline: [date: '2026-08-06']
        ]]
        def service = new CapacityReportService(
            config,
            new FixtureTodoistGateway(tasks),
            new FixtureCalendarGateway([], config.timezone)
        )
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()

        when:
        def report = service.generate(rangeStart, rangeEnd)
        def task = report.candidateTasks.find { it.id == 'deadline-day' }
        Instant eveningStart = LocalDate.of(2026, 8, 6).atTime(18, 0).atZone(zone).toInstant()

        then:
        task.deadline == LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()
        eveningStart.isBefore(task.deadline)
        report.usableCapacityMinutes == (3 + 4 + 3) * 60
        // Task must fit — evening window on deadline day is before exclusive local midnight
        !report.deadlineRisks.any { it.task.id == 'deadline-day' }
    }

    def "90m task spans soft blocker inside continuous window and is not a deadline risk"() {
        given:
        // 09:00-12:00 continuous working window; soft 10:00-11:00; 90m task before noon deadline
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [weekday: ['09:00-12:00']],
                calendars      : [[calendar: 'Family', default_role: 'soft_blocker']]
            ],
            tasks       : [
                default_duration_minutes  : 90,
                scheduling_eligible_labels: ['schedule']
            ]
        ])
        ZoneId zone = config.timezone
        def tasks = [[
            id      : 'span-soft',
            content : 'Deep work spanning soft',
            labels  : ['schedule'],
            priority: 4,
            duration: [amount: 90, unit: 'minute'],
            deadline: [date: '2026-08-06', lang: 'en', string: '2026-08-06']
        ]]
        // Soft blocker 10:00-11:00 ET
        def soft = todoistcaldavsync.planner.domain.CalendarEvent.builder()
            .id('soft-mid')
            .title('Quiet')
            .calendarName('Family')
            .start(LocalDate.of(2026, 8, 6).atTime(10, 0).atZone(zone).toInstant())
            .end(LocalDate.of(2026, 8, 6).atTime(11, 0).atZone(zone).toInstant())
            .build()
        def service = new CapacityReportService(
            config,
            new FixtureTodoistGateway(tasks),
            new FixtureCalendarGateway([soft], config.timezone)
        )
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()

        when:
        def report = service.generate(rangeStart, rangeEnd)

        then: 'diagnostic soft splits remain exact'
        report.softPenalizedMinutes == 60
        report.slots.findAll { it.softBlocked }.size() == 1
        report.slots.size() == 3

        and: 'placement may continuously span free→soft→free'
        !report.deadlineRisks.any { it.task.id == 'span-soft' }
    }

    def "task longer than either side of a hard gap remains a deadline risk"() {
        given:
        // 09:00-12:00 window; hard 10:00-11:00 leaves 60m + 60m; 90m task cannot fit either side
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [weekday: ['09:00-12:00']],
                calendars      : [[calendar: 'Work', default_role: 'hard_blocker']]
            ],
            tasks       : [
                default_duration_minutes  : 90,
                scheduling_eligible_labels: ['schedule']
            ]
        ])
        ZoneId zone = config.timezone
        def tasks = [[
            id      : 'hard-gap-risk',
            content : 'Cannot span hard gap',
            labels  : ['schedule'],
            priority: 4,
            duration: [amount: 90, unit: 'minute'],
            deadline: [date: '2026-08-06']
        ]]
        def hard = todoistcaldavsync.planner.domain.CalendarEvent.builder()
            .id('hard-mid')
            .title('Meeting')
            .calendarName('Work')
            .start(LocalDate.of(2026, 8, 6).atTime(10, 0).atZone(zone).toInstant())
            .end(LocalDate.of(2026, 8, 6).atTime(11, 0).atZone(zone).toInstant())
            .build()
        def service = new CapacityReportService(
            config,
            new FixtureTodoistGateway(tasks),
            new FixtureCalendarGateway([hard], config.timezone)
        )
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()

        when:
        def report = service.generate(rangeStart, rangeEnd)

        then:
        report.usableCapacityMinutes == 120
        report.slots.size() == 2
        report.deadlineRisks.any { it.task.id == 'hard-gap-risk' }
    }

    def "multiple soft boundaries still allow contiguous placement across free and soft segments"() {
        given:
        // 09:00-12:00; soft 09:30-10:00 and 10:30-11:00; 150m task must fit continuous 09-12
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [weekday: ['09:00-12:00']],
                calendars      : [[calendar: 'Family', default_role: 'soft_blocker']]
            ],
            tasks       : [
                default_duration_minutes  : 150,
                scheduling_eligible_labels: ['schedule']
            ]
        ])
        ZoneId zone = config.timezone
        def tasks = [[
            id      : 'multi-soft-span',
            content : 'Spans two soft regions',
            labels  : ['schedule'],
            priority: 3,
            duration: [amount: 150, unit: 'minute'],
            deadline: [date: '2026-08-06']
        ]]
        def softs = [
            todoistcaldavsync.planner.domain.CalendarEvent.builder()
                .id('s-a').title('A').calendarName('Family')
                .start(LocalDate.of(2026, 8, 6).atTime(9, 30).atZone(zone).toInstant())
                .end(LocalDate.of(2026, 8, 6).atTime(10, 0).atZone(zone).toInstant())
                .build(),
            todoistcaldavsync.planner.domain.CalendarEvent.builder()
                .id('s-b').title('B').calendarName('Family')
                .start(LocalDate.of(2026, 8, 6).atTime(10, 30).atZone(zone).toInstant())
                .end(LocalDate.of(2026, 8, 6).atTime(11, 0).atZone(zone).toInstant())
                .build()
        ]
        def service = new CapacityReportService(
            config,
            new FixtureTodoistGateway(tasks),
            new FixtureCalendarGateway(softs, config.timezone)
        )
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()

        when:
        def report = service.generate(rangeStart, rangeEnd)

        then:
        report.softPenalizedMinutes == 60
        report.usableCapacityMinutes == 180
        !report.deadlineRisks.any { it.task.id == 'multi-soft-span' }
    }

    def "deadline risk placement prefers sooner deadlines then higher priority then id"() {
        given:
        // Single day, 60m free; two 60m tasks same deadline — higher priority places first
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [
                working_windows: [weekday: ['09:00-10:00']],
                calendars      : []
            ],
            tasks       : [
                default_duration_minutes  : 60,
                scheduling_eligible_labels: ['schedule']
            ]
        ])
        ZoneId zone = config.timezone
        def tasks = [
            [id: 'low-pri', content: 'Low', labels: ['schedule'], priority: 1,
             duration: [amount: 60, unit: 'minute'], deadline: [date: '2026-08-06']],
            [id: 'high-pri', content: 'High', labels: ['schedule'], priority: 4,
             duration: [amount: 60, unit: 'minute'], deadline: [date: '2026-08-06']]
        ]
        def service = new CapacityReportService(
            config,
            new FixtureTodoistGateway(tasks),
            new FixtureCalendarGateway([], config.timezone)
        )
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 7).atStartOfDay(zone).toInstant()

        when:
        def report = service.generate(rangeStart, rangeEnd)

        then:
        !report.deadlineRisks.any { it.task.id == 'high-pri' }
        report.deadlineRisks.any { it.task.id == 'low-pri' }
    }
}
