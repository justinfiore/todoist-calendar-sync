package todoistcaldavsync.planner.adapters

import spock.lang.Specification
import todoistcaldavsync.TodoistCalDavSync
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.report.CapacityReportService

import java.lang.reflect.Constructor
import java.time.Instant

class ManualExclusionAndGatewaySpec extends Specification {

    private static File fixture(String name) {
        def url = ManualExclusionAndGatewaySpec.classLoader.getResource("planner/fixtures/${name}")
        if (url) {
            return new File(url.toURI())
        }
        def candidates = [
            new File("src/test/resources/planner/fixtures/${name}"),
            new File("app/src/test/resources/planner/fixtures/${name}")
        ]
        def hit = candidates.find { it.exists() }
        if (!hit) {
            throw new IllegalStateException("Cannot locate ${name}")
        }
        return hit
    }

    def "planner excludes @manual from candidates"() {
        given:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'UTC',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            tasks       : [manual_label: 'manual', scheduling_eligible_labels: ['schedule'], default_duration_minutes: 30]
        ])
        def tasks = [
            [id: '1', content: 'Auto', labels: ['schedule'], priority: 1],
            [id: '2', content: 'Manual one', labels: ['schedule', 'manual'], priority: 1,
             due: [date: '2026-08-06T15:00:00Z']]
        ]
        def todoist = new FixtureTodoistGateway(tasks)
        def calendar = new FixtureCalendarGateway([])
        def service = new CapacityReportService(config, todoist, calendar)

        when:
        def report = service.generate(Instant.parse('2026-08-06T00:00:00Z'), Instant.parse('2026-08-07T00:00:00Z'))

        then:
        report.candidateTasks*.id == ['1']
        report.manualExcludedTasks*.id == ['2']
        report.explanations.any { it.code == 'manual_excluded' }
    }

    def "legacy sync still includes eligible manual-labeled tasks"() {
        given:
        File configFile = File.createTempFile('todoist-calendar-sync-manual-', '.yaml')
        File stateFile = File.createTempFile('todoist-calendar-sync-manual-', '.state')
        configFile.text = '''
            dryRun: true
            todoist:
              labelsToInclude: [focus]
            caldav:
              calendars: []
              rules:
                - calendarName: Work
                  rule: "focus"
        '''.stripIndent()
        stateFile.delete()
        def syncer = new TodoistCalDavSync(configFile, stateFile)

        def items = [
            [id: 'manual-task', labels: ['focus', 'manual'], project_name: 'Home',
             due: [date: '2026-08-06T15:00:00Z'], label_names: ['focus', 'manual'], content: 'Doctor', priority: 1],
            [id: 'other', labels: ['other'], project_name: 'Home',
             due: [date: '2026-08-06T16:00:00Z'], label_names: ['other'], content: 'Skip', priority: 1]
        ]

        when:
        def included = syncer.filterItemsForInclusionInCalendar(items, ['focus'], [])

        then:
        included*.id == ['manual-task']

        when:
        def event = syncer.itemToEvent(42, included[0], 'Work')

        then:
        event != null
        event.getUid() != null
    }

    def "capacity report constructor requires TodoistReadGateway and CalendarReadGateway types"() {
        given:
        def ctors = CapacityReportService.declaredConstructors
        Constructor ctor = ctors.find { it.parameterCount == 3 }

        expect:
        ctor != null
        ctor.parameterTypes[1] == TodoistReadGateway
        ctor.parameterTypes[2] == CalendarReadGateway
        ctor.parameterTypes[1] != TodoistGateway
        ctor.parameterTypes[2] != CalendarGateway
        // Write-only types are not constructor parameters
        !ctor.parameterTypes.contains(TodoistWriteGateway)
        !ctor.parameterTypes.contains(CalendarWriteGateway)
    }

    def "write-only gateways cannot satisfy constructor API"() {
        given:
        def config = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            availability: [working_windows: [weekday: ['09:00-17:00']]]
        ])
        def writeOnlyTodoist = new WriteOnlyTodoist()
        def writeOnlyCalendar = new WriteOnlyCalendar()
        def readCalendar = new FixtureCalendarGateway([])
        def readTodoist = new FixtureTodoistGateway([])

        when: 'compile-time types are Read gateways — write-only fakes are not assignable'
        boolean todoistIsRead = writeOnlyTodoist instanceof TodoistReadGateway
        boolean calendarIsRead = writeOnlyCalendar instanceof CalendarReadGateway
        boolean writeTodoistOk = false
        try {
            // Groovy may coerce; force typed call via explicit cast failure path
            CapacityReportService.class.getConstructor(
                PlannerConfig, TodoistReadGateway, CalendarReadGateway
            ).newInstance(config, writeOnlyTodoist, readCalendar)
            writeTodoistOk = true
        } catch (Exception ignored) {
            writeTodoistOk = false
        }
        boolean writeCalendarOk = false
        try {
            CapacityReportService.class.getConstructor(
                PlannerConfig, TodoistReadGateway, CalendarReadGateway
            ).newInstance(config, readTodoist, writeOnlyCalendar)
            writeCalendarOk = true
        } catch (Exception ignored) {
            writeCalendarOk = false
        }

        then:
        !todoistIsRead
        !calendarIsRead
        !writeTodoistOk
        !writeCalendarOk
    }

    def "fixture gateways are read-only and load files"() {
        given:
        def tasksFile = fixture('capacity-tasks.yaml')
        def eventsFile = fixture('capacity-events.yaml')

        when:
        def tg = FixtureTodoistGateway.fromFile(tasksFile)
        def cg = FixtureCalendarGateway.fromFile(eventsFile)

        then:
        tg.fetchTasks().size() >= 4
        cg.fetchEvents(Instant.parse('2026-08-06T00:00:00Z'), Instant.parse('2026-08-08T00:00:00Z')).size() >= 5
        tg instanceof TodoistReadGateway
        cg instanceof CalendarReadGateway
        !(tg instanceof TodoistWriteGateway)
        !(cg instanceof CalendarWriteGateway)
    }

    def "fixture calendar gateway rejects missing or blank event id"() {
        when:
        FixtureCalendarGateway.parseEvent([
            title: 'No id',
            calendar: 'Work',
            start: '2026-08-06T14:00:00Z',
            end: '2026-08-06T15:00:00Z'
        ])

        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.toLowerCase().contains('id')

        when:
        FixtureCalendarGateway.parseEvent([
            id: '   ',
            title: 'Blank id',
            calendar: 'Work',
            start: '2026-08-06T14:00:00Z',
            end: '2026-08-06T15:00:00Z'
        ])

        then:
        def e2 = thrown(IllegalArgumentException)
        e2.message.toLowerCase().contains('id')
    }

    def "fixture calendar gateway interprets date-only and local datetimes in planner timezone"() {
        given:
        def ny = java.time.ZoneId.of('America/New_York')

        when:
        def dateOnly = FixtureCalendarGateway.parseEvent([
            id: 'all-day',
            title: 'Holiday',
            calendar: 'Work',
            start: '2026-08-06',
            end: '2026-08-07',
            all_day: true
        ], ny)
        def localT = FixtureCalendarGateway.parseEvent([
            id: 'local',
            title: 'Local meeting',
            calendar: 'Work',
            start: '2026-08-06T10:00:00',
            end: '2026-08-06T11:00:00'
        ], ny)
        def zulu = FixtureCalendarGateway.parseEvent([
            id: 'z',
            title: 'Zulu',
            calendar: 'Work',
            start: '2026-08-06T14:00:00Z',
            end: '2026-08-06T15:00:00Z'
        ], ny)

        then:
        dateOnly.start == java.time.LocalDate.of(2026, 8, 6).atStartOfDay(ny).toInstant()
        dateOnly.end == java.time.LocalDate.of(2026, 8, 7).atStartOfDay(ny).toInstant()
        dateOnly.allDay
        // 10:00 EDT = 14:00Z — not forced to 10:00Z
        localT.start == Instant.parse('2026-08-06T14:00:00Z')
        localT.end == Instant.parse('2026-08-06T15:00:00Z')
        zulu.start == Instant.parse('2026-08-06T14:00:00Z')

        when:
        def fromFile = FixtureCalendarGateway.fromFile(fixture('capacity-events.yaml'), ny)

        then:
        fromFile.timezone == ny
        fromFile.fetchEvents(Instant.parse('2026-08-06T00:00:00Z'), Instant.parse('2026-08-08T00:00:00Z')).size() >= 5
    }

    /** Write-only fake — does not implement read gateway */
    static class WriteOnlyTodoist implements TodoistWriteGateway {
        @Override
        void updateTaskDue(String taskId, String dueDateTimeIso) {}

        @Override
        void updateTaskDeadline(String taskId, String deadlineIso) {}
    }

    static class WriteOnlyCalendar implements CalendarWriteGateway {
        @Override
        void upsertEvent(CalendarEvent event) {}

        @Override
        void deleteOwnedEvent(String eventUid, String expectedBlockId) {}
    }
}
