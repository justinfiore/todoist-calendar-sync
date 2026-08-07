package todoistcaldavsync

import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class TodoistCalDavSyncWireMockSpec extends Specification {

    WireMockServer todoistServer
    WireMockServer calendarServer
    String originalTodoistApiBaseUrl
    List<TodoistCalDavSync> syncers = []

    def setup() {
        todoistServer = new WireMockServer(options().dynamicPort())
        calendarServer = new WireMockServer(options().dynamicPort())
        todoistServer.start()
        calendarServer.start()
        originalTodoistApiBaseUrl = TodoistCalDavSync.todoistApiBaseUrl
        TodoistCalDavSync.todoistApiBaseUrl = "http://localhost:${todoistServer.port()}/api/v1"
    }

    def cleanup() {
        syncers.each { it.destroyHttpClients() }
        TodoistCalDavSync.todoistApiBaseUrl = originalTodoistApiBaseUrl
        calendarServer.stop()
        todoistServer.stop()
    }

    def "sync sends Todoist requests and writes a matched task to CalDAV"() {
        given:
        stubTodoistResponses()
        calendarServer.stubFor(delete(urlMatching('/caldav/work/.*\\.ics')).willReturn(aResponse().withStatus(404)))
        calendarServer.stubFor(put(urlMatching('/caldav/work/.*\\.ics')).willReturn(aResponse().withStatus(201)))
        def syncer = newSyncer(false)

        when:
        syncer.sync()

        then:
        todoistServer.verify(1, postRequestedFor(urlEqualTo('/api/v1/sync'))
            .withRequestBody(containing('items'))
            .withHeader('Authorization', equalTo('Bearer test-token')))
        todoistServer.verify(1, postRequestedFor(urlEqualTo('/api/v1/sync'))
            .withRequestBody(containing('projects'))
            .withHeader('Authorization', equalTo('Bearer test-token')))
        calendarServer.verify(1, deleteRequestedFor(urlMatching('/caldav/work/.*\\.ics')))
        calendarServer.verify(1, putRequestedFor(urlMatching('/caldav/work/.*\\.ics'))
            .withRequestBody(containing('SUMMARY:TD: Important task')))
    }

    def "dry run never mutates the CalDAV server"() {
        given:
        stubTodoistResponses()
        def syncer = newSyncer(true)

        when:
        syncer.sync()

        then:
        todoistServer.verify(2, postRequestedFor(urlEqualTo('/api/v1/sync')))
        calendarServer.verify(0, deleteRequestedFor(urlMatching('/caldav/work/.*')))
        calendarServer.verify(0, putRequestedFor(urlMatching('/caldav/work/.*')))
    }

    def "Todoist API failure prevents calendar mutation"() {
        given:
        todoistServer.stubFor(post(urlEqualTo('/api/v1/sync'))
            .willReturn(aResponse().withStatus(500).withBody('{"error":"unavailable"}')))
        def syncer = newSyncer(false)

        when:
        syncer.sync()

        then:
        def error = thrown(RuntimeException)
        error.message == 'API Call to Todoist failed.'
        calendarServer.verify(0, deleteRequestedFor(urlMatching('/caldav/work/.*')))
        calendarServer.verify(0, putRequestedFor(urlMatching('/caldav/work/.*')))
    }

    private void stubTodoistResponses() {
        todoistServer.stubFor(post(urlEqualTo('/api/v1/sync'))
            .withRequestBody(containing('items'))
            .willReturn(okJson('''
                {"sync_token":"next-token","items":[
                  {"id":"task-1","content":"Important task","project_id":"project-1",
                   "labels":["focus"],"priority":4,"due":{"date":"2026-08-07T10:00:00Z"}}
                ]}
            '''.stripIndent())))
        todoistServer.stubFor(post(urlEqualTo('/api/v1/sync'))
            .withRequestBody(containing('projects'))
            .willReturn(okJson('''
                {"user":{"id":42,"email":"test@example.com"},"labels":[],
                 "projects":[{"id":"project-1","name":"Work"}]}
            '''.stripIndent())))
    }

    private TodoistCalDavSync newSyncer(boolean dryRun) {
        File configFile = File.createTempFile('todoist-calendar-sync-wiremock-', '.yaml')
        File stateFile = File.createTempFile('todoist-calendar-sync-wiremock-', '.state')
        stateFile.text = 'state:\n  v1Migrated: true\n'
        configFile.text = """
            dryRun: ${dryRun}
            syncIntervalMs: 0
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
                  prefix: 'TD: '
              rules:
                - calendarName: Work
                  rule: focus
        """.stripIndent()
        def syncer = new TodoistCalDavSync(configFile, stateFile)
        syncers << syncer
        syncer
    }

    private static def okJson(String body) {
        aResponse().withStatus(200).withHeader('Content-Type', 'application/json').withBody(body)
    }
}
