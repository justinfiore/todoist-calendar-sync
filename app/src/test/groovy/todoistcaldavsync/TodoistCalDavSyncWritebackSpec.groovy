package todoistcaldavsync

import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification
import todoistcaldavsync.planner.adapters.CalendarChangeGateway
import todoistcaldavsync.planner.adapters.CalendarReadGateway
import todoistcaldavsync.planner.adapters.TodoistLifecycleGateway
import todoistcaldavsync.planner.adapters.TodoistCommandResult
import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.domain.CalendarEvent

import java.time.Instant

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class TodoistCalDavSyncWritebackSpec extends Specification {
    WireMockServer todoistServer
    WireMockServer calendarServer
    String originalTodoistApiBaseUrl
    TodoistCalDavSync syncer

    def setup() {
        todoistServer = new WireMockServer(options().dynamicPort())
        calendarServer = new WireMockServer(options().dynamicPort())
        todoistServer.start()
        calendarServer.start()
        originalTodoistApiBaseUrl = TodoistCalDavSync.todoistApiBaseUrl
        TodoistCalDavSync.todoistApiBaseUrl = "http://localhost:${todoistServer.port()}/api/v1"
    }

    def cleanup() {
        syncer?.destroyHttpClients()
        TodoistCalDavSync.todoistApiBaseUrl = originalTodoistApiBaseUrl
        calendarServer.stop()
        todoistServer.stop()
    }

    def "calendar-only move uses durable task identity and does not restore the old event"() {
        given:
        stubTwoTodoistPolls(taskJson(false), '[]')
        stubCalendarWrites()
        syncer = newSyncer()
        CalendarChangeGateway changes = calendarGatewayMock()
        TodoistLifecycleGateway todoist = Mock()
        syncer.calendarChangeGateway = changes
        syncer.todoistWritebackGateway = todoist
        String uid = syncer.generateUidFromItem(42, [id: 'task-1'])
        CalendarEvent moved = event(uid, '2026-08-07T12:00:00Z', '2026-08-07T11:00:00Z')
        2 * changes.readChanges('Work', _, _, _, _) >>> [
            [events: [], deleted: [], state: [cursor: 'one']],
            [events: [moved], deleted: [], state: [cursor: 'two']]
        ]
        1 * (changes as CalendarReadGateway).findEventByUid(uid) >> null

        when:
        syncer.sync()
        syncer.sync()

        then:
        1 * todoist.updateTaskDue('task-1', '2026-08-07T12:00:00Z')
        1 * todoist.fetchTask('task-1') >> liveTask('2026-08-07T12:00:00Z', '2026-08-07T11:01:00Z')
        0 * todoist._
        calendarServer.verify(1, putRequestedFor(urlMatching('/caldav/work/.*\\.ics')))
        calendarServer.verify(1, deleteRequestedFor(urlMatching('/caldav/work/.*\\.ics')))
        with(syncer.calendarWritebackStore.calendar('Work').knownTasks['task-1']) {
            due.date == '2026-08-07T12:00:00Z'
            updated_at == '2026-08-07T11:01:00Z'
            label_names == ['focus']
            project_name == 'Work'
        }
    }

    def "Todoist-newer write records an echo and next inbound copy does not write Todoist"() {
        given:
        stubTwoTodoistPolls(taskJson(false, '2026-08-07T12:00:00Z', '2026-08-07T11:00:00Z'), '[]')
        stubCalendarWrites()
        syncer = newSyncer()
        CalendarChangeGateway changes = calendarGatewayMock()
        TodoistLifecycleGateway todoist = Mock()
        syncer.calendarChangeGateway = changes
        syncer.todoistWritebackGateway = todoist
        String uid = syncer.generateUidFromItem(42, [id: 'task-1'])
        CalendarEvent beforeWrite = event(uid, '2026-08-07T10:00:00Z', '2026-08-07T10:30:00Z')
        CalendarEvent echo = event(uid, '2026-08-07T12:00:00Z', '2026-08-07T10:30:00Z')
        2 * changes.readChanges('Work', _, _, _, _) >>> [
            [events: [beforeWrite], deleted: [], state: [cursor: 'one']],
            [events: [echo], deleted: [], state: [cursor: 'two']]
        ]

        when:
        syncer.sync()
        syncer.sync()

        then:
        0 * todoist._
        calendarServer.verify(1, putRequestedFor(urlMatching('/caldav/work/.*\\.ics')))
    }

    def "later Todoist due change updates a calendar echo from #source"() {
        given:
        stubTwoTodoistPolls(taskJson(false),
            taskJson(false, '2026-08-08T14:00:00Z', '2026-08-07T12:00:00Z'))
        stubCalendarWrites()
        syncer = newSyncer()
        CalendarChangeGateway changes = calendarGatewayMock()
        TodoistLifecycleGateway todoist = Mock()
        syncer.calendarChangeGateway = changes
        syncer.todoistWritebackGateway = todoist
        String uid = syncer.generateUidFromItem(42, [id: 'task-1'])
        CalendarEvent echo = event(uid, '2026-08-07T10:00:00Z', '2026-08-07T11:00:00Z')
        2 * changes.readChanges('Work', _, _, _, _) >>> [
            [events: [], deleted: [], state: [cursor: 'one']],
            [events: fromFeed ? [echo] : [], deleted: [], state: [cursor: 'two']]
        ]
        (fromFeed ? 1 : 2) * (changes as CalendarReadGateway).findEventByUid(uid) >>> [null, echo]

        when:
        syncer.sync()
        syncer.sync()

        then:
        0 * todoist._
        calendarServer.verify(2, putRequestedFor(urlEqualTo("/caldav/work/${uid}.ics")))
        calendarServer.verify(1, putRequestedFor(urlEqualTo("/caldav/work/${uid}.ics"))
            .withRequestBody(containing('DTSTART:20260808T140000Z')))
        syncer.calendarWritebackStore.echo('Work', uid).start == '2026-08-08T14:00:00Z'

        where:
        source        | fromFeed
        'change feed' | true
        'live lookup' | false
    }

    def "newer recurring move uses guarded tuple update and never datetime-only update"() {
        given:
        stubTwoTodoistPolls(taskJson(true), '[]')
        stubCalendarWrites()
        syncer = newSyncer()
        CalendarChangeGateway changes = calendarGatewayMock()
        TodoistLifecycleGateway todoist = Mock()
        syncer.calendarChangeGateway = changes
        syncer.todoistWritebackGateway = todoist
        String uid = syncer.generateUidFromItem(42, [id: 'task-1'])
        CalendarEvent moved = event(uid, '2026-08-07T12:00:00Z', '2026-08-07T11:00:00Z')
        Map original = recurringTask('2026-08-07T10:00:00Z')
        Map updated = recurringTask('2026-08-07T12:00:00Z') + [updated_at: '2026-08-07T11:01:00Z']
        1 * changes.readChanges('Work', _, _, _, _) >>
            [events: [moved], deleted: [], state: [cursor: 'one']]
        2 * todoist.fetchTask('task-1') >>> [original, updated]
        1 * todoist.updateRecurringDue('task-1', { due ->
            due.string == 'every weekday' && due.lang == 'en' && due.timezone == 'UTC'
        }, '2026-08-07T12:00:00Z', _) >>
            new TodoistCommandResult(TodoistCommandState.COMMITTED, UUID.randomUUID().toString())

        when:
        syncer.sync()

        then:
        0 * todoist.updateTaskDue(_, _)
        0 * todoist.updateTaskDeadline(_, _)
        calendarServer.verify(0, putRequestedFor(urlMatching('/caldav/work/.*\\.ics')))
    }

    def "cancelled candidate does not mutate Todoist and is recreated only on a later pass"() {
        given:
        stubTwoTodoistPolls(taskJson(false), '[]')
        stubCalendarWrites()
        syncer = newSyncer()
        CalendarChangeGateway changes = calendarGatewayMock()
        TodoistLifecycleGateway todoist = Mock()
        syncer.calendarChangeGateway = changes
        syncer.todoistWritebackGateway = todoist
        String uid = syncer.generateUidFromItem(42, [id: 'task-1'])
        2 * changes.readChanges('Work', _, _, _, _) >>> [
            [events: [], deleted: [[uid: uid, id: '/cancelled.ics']], state: [cursor: 'one']],
            [events: [], deleted: [], state: [cursor: 'two']]
        ]

        when:
        syncer.sync()

        then:
        0 * todoist._
        calendarServer.verify(0, putRequestedFor(urlMatching('/caldav/work/.*\\.ics')))

        when:
        syncer.sync()

        then:
        0 * todoist._
        calendarServer.verify(1, putRequestedFor(urlEqualTo("/caldav/work/${uid}.ics")))
    }

    def "tied calendar disagreement survives a later Todoist-only delta"() {
        given:
        stubTwoTodoistPolls(taskJson(false, '2026-08-07T10:00:00Z', '2026-08-07T11:00:00Z'),
            taskJson(false, '2026-08-07T10:00:00Z', '2026-08-07T11:00:00Z'))
        stubCalendarWrites()
        syncer = newSyncer()
        CalendarChangeGateway changes = calendarGatewayMock()
        TodoistLifecycleGateway todoist = Mock()
        syncer.calendarChangeGateway = changes
        syncer.todoistWritebackGateway = todoist
        String uid = syncer.generateUidFromItem(42, [id: 'task-1'])
        CalendarEvent unresolved = event(uid, '2026-08-07T12:00:00Z', '2026-08-07T11:00:00Z')
        2 * changes.readChanges('Work', _, _, _, _) >>> [
            [events: [unresolved], deleted: [], state: [cursor: 'one']],
            [events: [], deleted: [], state: [cursor: 'two']]
        ]
        1 * (changes as CalendarReadGateway).findEventByUid(uid) >> unresolved

        when:
        syncer.sync()
        syncer.sync()

        then:
        0 * todoist._
        calendarServer.verify(0, putRequestedFor(urlEqualTo("/caldav/work/${uid}.ics")))
    }

    def "nonrecurring move aborts before calendar write and checkpoint when verification is unavailable"() {
        given:
        stubTwoTodoistPolls(taskJson(false), '[]')
        stubCalendarWrites()
        syncer = newSyncer()
        CalendarChangeGateway changes = calendarGatewayMock()
        TodoistLifecycleGateway todoist = Mock()
        syncer.calendarChangeGateway = changes
        syncer.todoistWritebackGateway = todoist
        String uid = syncer.generateUidFromItem(42, [id: 'task-1'])
        CalendarEvent moved = event(uid, '2026-08-07T12:00:00Z', '2026-08-07T11:00:00Z')
        1 * changes.readChanges('Work', _, _, _, _) >>
            [events: [moved], deleted: [], state: [cursor: 'uncommitted']]
        1 * todoist.updateTaskDue('task-1', '2026-08-07T12:00:00Z')
        1 * todoist.fetchTask('task-1') >> null

        when:
        syncer.sync()

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('post-write verification failed')
        calendarServer.verify(0, putRequestedFor(urlMatching('/caldav/work/.*\\.ics')))
        syncer.calendarWritebackStore.calendar('Work').cursor == null
    }

    def "calendar echo uses the exact duration rendered into the vevent"() {
        given:
        stubTwoTodoistPolls(taskJsonWithDuration(45), '[]')
        stubCalendarWrites()
        syncer = newSyncer()
        CalendarChangeGateway changes = calendarGatewayMock()
        syncer.calendarChangeGateway = changes
        syncer.todoistWritebackGateway = Mock(TodoistLifecycleGateway)
        String uid = syncer.generateUidFromItem(42, [id: 'task-1'])
        1 * changes.readChanges('Work', _, _, _, _) >>
            [events: [], deleted: [], state: [cursor: 'one']]
        1 * (changes as CalendarReadGateway).findEventByUid(uid) >> null

        when:
        syncer.sync()

        then:
        Map echo = syncer.calendarWritebackStore.echo('Work', uid)
        Instant.parse(echo.end).minusSeconds(45 * 60L) == Instant.parse(echo.start)
    }

    private CalendarEvent event(String uid, String start, String modified) {
        Instant from = Instant.parse(start)
        CalendarEvent.builder().id(uid).uid(uid).title('Important task').calendarName('Work')
            .start(from).end(from.plusSeconds(1800)).lastModified(Instant.parse(modified)).build()
    }

    private void stubTwoTodoistPolls(String firstItems, String secondItems) {
        todoistServer.stubFor(post(urlEqualTo('/api/v1/sync'))
            .inScenario('polls').whenScenarioStateIs('Started')
            .withRequestBody(containing('items'))
            .willSetStateTo('second')
            .willReturn(okJson("{\"sync_token\":\"token-one\",\"items\":${firstItems}}")))
        todoistServer.stubFor(post(urlEqualTo('/api/v1/sync'))
            .inScenario('polls').whenScenarioStateIs('second')
            .withRequestBody(containing('items'))
            .willReturn(okJson("{\"sync_token\":\"token-two\",\"items\":${secondItems}}")))
        todoistServer.stubFor(post(urlEqualTo('/api/v1/sync'))
            .withRequestBody(containing('projects'))
            .willReturn(okJson('''{"user":{"id":42,"email":"test@example.com"},"labels":[],
                "projects":[{"id":"project-1","name":"Work"}]}''')))
    }

    private static String taskJson(boolean recurring, String due = '2026-08-07T10:00:00Z',
                                   String updated = '2026-08-07T10:00:00Z') {
        """[{"id":"task-1","content":"Important task","project_id":"project-1",
            "labels":["focus"],"priority":4,"updated_at":"${updated}",
            "due":{"date":"${due}","is_recurring":${recurring}}}]""".stripIndent()
    }

    private static String taskJsonWithDuration(int minutes) {
        """[{"id":"task-1","content":"Important task","project_id":"project-1",
            "labels":["focus"],"priority":4,"updated_at":"2026-08-07T10:00:00Z",
            "duration":{"amount":${minutes},"unit":"minute"},
            "due":{"date":"2026-08-07T10:00:00Z","is_recurring":false}}]""".stripIndent()
    }

    private static Map recurringTask(String date) {
        [id: 'task-1', content: 'Important task', project_id: 'project-1', labels: ['focus'],
         priority: 4, description: '', completed_count: 0,
         due: [date: date, string: 'every weekday', is_recurring: true, lang: 'en', timezone: 'UTC']]
    }

    private static Map liveTask(String date, String updated) {
        [id: 'task-1', content: 'Important task', project_id: 'project-1', labels: ['focus'],
         priority: 4, updated_at: updated, due: [date: date, is_recurring: false]]
    }

    private CalendarChangeGateway calendarGatewayMock() {
        Mock(CalendarChangeGateway, additionalInterfaces: [CalendarReadGateway])
    }

    private void stubCalendarWrites() {
        calendarServer.stubFor(delete(urlMatching('/caldav/work/.*\\.ics')).willReturn(aResponse().withStatus(404)))
        calendarServer.stubFor(put(urlMatching('/caldav/work/.*\\.ics')).willReturn(aResponse().withStatus(201)))
    }

    private TodoistCalDavSync newSyncer() {
        File directory = File.createTempDir('legacy-writeback-', '')
        File configFile = new File(directory, 'todoist-calendar-sync.conf.yaml')
        File stateFile = new File(directory, 'todoist-calendar-sync.state.yaml')
        stateFile.text = 'state:\n  v1Migrated: true\n'
        configFile.text = """
            dryRun: false
            syncIntervalMs: 0
            calendarWriteback:
              enabled: true
            todoist:
              accessToken: test-token
              labelsToInclude: [focus]
              projectsToInclude: []
            caldav:
              default:
                auth:
                  scheme: BASIC
                  basicAuth:
                    username: test-user
                    password: test-password
              calendars:
                - name: Work
                  url: http://localhost:${calendarServer.port()}/caldav/work
              rules:
                - calendarName: Work
                  rule: focus
        """.stripIndent()
        new TodoistCalDavSync(configFile, stateFile)
    }

    private static def okJson(String body) {
        aResponse().withStatus(200).withHeader('Content-Type', 'application/json').withBody(body)
    }
}
