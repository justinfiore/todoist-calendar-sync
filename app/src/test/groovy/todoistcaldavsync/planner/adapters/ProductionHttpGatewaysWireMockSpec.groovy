package todoistcaldavsync.planner.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds

import java.time.Instant
import java.time.ZoneId

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class ProductionHttpGatewaysWireMockSpec extends Specification {
    WireMockServer server

    def setup() {
        server = new WireMockServer(options().dynamicPort())
        server.start()
    }

    def cleanup() { server.stop() }

    private String fixture(String name) {
        getClass().classLoader.getResource("planner/fixtures/${name}").text
    }

    def "Todoist reads paginated tasks/projects with bearer auth and writes due only"() {
        given:
        server.stubFor(get(urlEqualTo('/api/v1/tasks?limit=200'))
            .willReturn(okJson(fixture('todoist-tasks-page-1.json'))))
        server.stubFor(get(urlEqualTo('/api/v1/tasks?limit=200&cursor=page-2'))
            .willReturn(okJson(fixture('todoist-tasks-page-2.json'))))
        server.stubFor(get(urlEqualTo('/api/v1/projects?limit=200'))
            .willReturn(okJson(fixture('todoist-projects.json'))))
        server.stubFor(post(urlEqualTo('/api/v1/tasks/t1')).willReturn(okJson('{"id":"t1"}')))
        def gateway = new TodoistRestGateway(
            baseUrl: "http://localhost:${server.port()}/api/v1",
            tokenEnv: 'TODOIST_TEST_TOKEN', tokenOverride: 'todoist-secret', allowInsecureHttp: true)

        when:
        def tasks = gateway.fetchTasks()
        gateway.updateTaskDue('t1', '2026-08-14T14:00:00Z')

        then:
        tasks*.id == ['t1', 't2']
        tasks*.project_name == ['Operations', 'Operations']
        server.verify(3, getRequestedFor(urlPathMatching('/api/v1/(tasks|projects)'))
            .withHeader('Authorization', equalTo('Bearer todoist-secret')))
        server.verify(postRequestedFor(urlEqualTo('/api/v1/tasks/t1'))
            .withHeader('Authorization', equalTo('Bearer todoist-secret'))
            .withHeader('Content-Type', containing('application/json'))
            .withRequestBody(equalToJson('{"due_datetime":"2026-08-14T14:00:00Z"}', true, true)))
        server.findAll(postRequestedFor(urlEqualTo('/api/v1/tasks/t1')))[0].bodyAsString.contains('due_datetime')
        !server.findAll(postRequestedFor(urlEqualTo('/api/v1/tasks/t1')))[0].bodyAsString.contains('deadline')
    }

    def "Todoist transient reads retry boundedly while writes and deadline refusal are not retried"() {
        given:
        server.stubFor(get(urlEqualTo('/api/v1/tasks?limit=200')).inScenario('retry')
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(503)).willSetStateTo('ok'))
        server.stubFor(get(urlEqualTo('/api/v1/tasks?limit=200')).inScenario('retry')
            .whenScenarioStateIs('ok').willReturn(okJson('{"results":[],"next_cursor":null}')))
        def gateway = new TodoistRestGateway(baseUrl: "http://localhost:${server.port()}/api/v1",
            tokenOverride: 'token', allowInsecureHttp: true, includeProjectNames: false)

        expect:
        gateway.fetchTasks() == []
        server.verify(2, getRequestedFor(urlEqualTo('/api/v1/tasks?limit=200')))

        when:
        gateway.updateTaskDeadline('t1', '2026-08-15')

        then:
        thrown(UnsupportedOperationException)
        server.verify(0, postRequestedFor(urlPathMatching('/api/v1/tasks/.*')))
        server.verify(0, patchRequestedFor(urlPathMatching('/api/v1/tasks/.*')))
    }

    def "CalDAV REPORT preserves calendar name and parses embedded event"() {
        given:
        stubRangeReport('/cal/work', '/cal/work/external.ics', '''BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:external-1
DTSTART:20260814T090000Z
DTEND:20260814T100000Z
SUMMARY:Team meeting
DESCRIPTION:Owned by a person
END:VEVENT
END:VCALENDAR
''')
        stubEmptyReport('/cal/other')
        def gateway = caldavGateway()

        when:
        def events = gateway.fetchEvents(Instant.parse('2026-08-14T00:00:00Z'), Instant.parse('2026-08-15T00:00:00Z'))

        then:
        events.size() == 1
        events[0].calendarName == 'Work'
        events[0].uid == 'external-1'
        events[0].title == 'Team meeting'
        !ManagedEventIds.hasOwnershipMarker(events[0].description)
        reportRequests('/cal/work')*.getHeader('Authorization') == ['Basic dXNlcjpwYXNz']
        reportRequests('/cal/other')*.getHeader('Authorization') == ['Basic dXNlcjpwYXNz']
    }

    def "CalDAV UID lookup REPORTs all calendars then GETs live resource and rejects collision"() {
        given:
        stubUidReport('/cal/work', '/cal/work/owned.ics')
        stubEmptyReport('/cal/other')
        server.stubFor(get(urlEqualTo('/cal/work/owned.ics')).willReturn(aResponse().withStatus(200)
            .withHeader('Content-Type', 'text/calendar').withBody(fixture('caldav-owned-event.ics'))))
        def gateway = caldavGateway()

        when:
        def found = gateway.findEventByUid('planner-owned@todoist-planner.local')

        then:
        found.calendarName == 'Work'
        ManagedEventIds.hasOwnershipMarker(found.description)
        server.verify(1, getRequestedFor(urlEqualTo('/cal/work/owned.ics'))
            .withHeader('Authorization', equalTo('Basic dXNlcjpwYXNz')))
        reportRequests('/cal/other').size() == 1

        when: 'the same UID appears in another configured calendar'
        server.resetAll()
        stubUidReport('/cal/work', '/cal/work/owned.ics')
        stubUidReport('/cal/other', '/cal/other/collision.ics')
        server.stubFor(get(urlEqualTo('/cal/work/owned.ics')).willReturn(okIcs(fixture('caldav-owned-event.ics'))))
        server.stubFor(get(urlEqualTo('/cal/other/collision.ics')).willReturn(okIcs(fixture('caldav-owned-event.ics'))))
        gateway.findEventByUid('planner-owned@todoist-planner.local')

        then:
        def collision = thrown(CalDavHttpGateway.CalDavGatewayException)
        collision.classification == 'UID_COLLISION'
        reportRequests('/cal/work').size() == 1
        reportRequests('/cal/other').size() == 1
    }

    def "CalDAV refuses unowned or external PUT before transport"() {
        given:
        def gateway = caldavGateway()
        def external = CalendarEvent.builder().id('external').uid('external-1')
            .title('External').description('not planner owned').calendarName('Work')
            .start(Instant.parse('2026-08-14T13:00:00Z')).end(Instant.parse('2026-08-14T13:30:00Z')).build()

        when:
        gateway.upsertEvent(external)

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('ownership')
        server.verify(0, putRequestedFor(urlPathMatching('/cal/work/.*')))
    }

    def "CalDAV PUT shape and ownership-checked GET DELETE are real HTTP boundaries"() {
        given:
        def gateway = caldavGateway()
        def owned = CalendarEvent.builder().id('new').uid(ManagedEventIds.uidForBlock('block-1'))
            .title('Scheduled work').description(ManagedEventIds.buildDescription('block-1', 'plan-1'))
            .calendarName('Work').start(Instant.parse('2026-08-14T13:00:00Z'))
            .end(Instant.parse('2026-08-14T13:30:00Z')).build()
        server.stubFor(put(urlPathMatching('/cal/work/planner-.*\\.ics')).willReturn(aResponse().withStatus(201)))

        when:
        gateway.upsertEvent(owned)

        then:
        server.verify(putRequestedFor(urlPathMatching('/cal/work/planner-.*\\.ics'))
            .withHeader('Authorization', equalTo('Basic dXNlcjpwYXNz'))
            .withHeader('Content-Type', containing('text/calendar'))
            .withRequestBody(containing("UID:${owned.uid}"))
            .withRequestBody(containing(ManagedEventIds.OWNERSHIP_MARKER))
            .withRequestBody(containing('DTSTART:20260814T130000Z')))

        when:
        server.resetAll()
        stubUidReport('/cal/work', '/cal/work/owned.ics')
        stubEmptyReport('/cal/other')
        server.stubFor(get(urlEqualTo('/cal/work/owned.ics')).willReturn(okIcs(fixture('caldav-owned-event.ics'))))
        server.stubFor(delete(urlEqualTo('/cal/work/owned.ics')).willReturn(aResponse().withStatus(204)))
        gateway.deleteOwnedEvent('planner-owned@todoist-planner.local', 'block-1')

        then:
        server.verify(deleteRequestedFor(urlEqualTo('/cal/work/owned.ics'))
            .withHeader('Authorization', equalTo('Basic dXNlcjpwYXNz')))
    }

    private CalDavHttpGateway caldavGateway() {
        new CalDavHttpGateway(
            calendars: [
                [name: 'Work', url: "http://localhost:${server.port()}/cal/work",
                 auth: [type: 'basic', username: 'user', password_override: 'pass']],
                [name: 'Other', url: "http://localhost:${server.port()}/cal/other",
                 auth: [type: 'basic', username: 'user', password_override: 'pass']]
            ], managedCalendarName: 'Work', timezone: ZoneId.of('UTC'), allowInsecureHttp: true)
    }

    private void stubRangeReport(String path, String href, String ics) {
        server.stubFor(request('REPORT', urlEqualTo(path)).withRequestBody(containing('time-range'))
            .willReturn(multistatus(href, ics)))
    }

    private void stubUidReport(String path, String href) {
        server.stubFor(request('REPORT', urlEqualTo(path)).withRequestBody(containing('prop-filter name="UID"'))
            .willReturn(multistatus(href, null)))
    }

    private void stubEmptyReport(String path) {
        server.stubFor(request('REPORT', urlEqualTo(path))
            .willReturn(aResponse().withStatus(207).withHeader('Content-Type', 'application/xml')
                .withBody('<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"/>')))
    }

    private static def multistatus(String href, String data) {
        String calData = data != null ? "<c:calendar-data><![CDATA[${data}]]></c:calendar-data>" : ''
        aResponse().withStatus(207).withHeader('Content-Type', 'application/xml').withBody("""<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>${href}</d:href><d:propstat><d:prop>${calData}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
    }

    private List reportRequests(String path) {
        server.allServeEvents*.request.findAll { it.method.value() == 'REPORT' && it.url == path }
    }
    private static def okIcs(String body) { aResponse().withStatus(200).withHeader('Content-Type', 'text/calendar').withBody(body) }
}
