package todoistcaldavsync.planner.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds

import java.time.Instant

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class CalDavChangeGatewayWireMockSpec extends Specification {
    private static final Instant RANGE_START = Instant.parse('2026-08-14T00:00:00Z')
    private static final Instant RANGE_END = Instant.parse('2026-08-15T00:00:00Z')
    private static final String CANDIDATE_UID = 'candidate-1'

    WireMockServer server

    def setup() {
        server = new WireMockServer(options().dynamicPort())
        server.start()
    }

    def cleanup() {
        server.stop()
    }

    def "advertised sync-collection downloads bodies only for changed candidate members"() {
        given:
        stubCapabilities(true)
        stubSyncReport(syncMultistatus('token-2', [
            propertyResponse('/cal/work/candidate.ics', '"candidate-2"'),
            propertyResponse('/cal/work/unrelated.ics', '"unrelated-2"')
        ]))
        stubEvent('/cal/work/candidate.ics', CANDIDATE_UID)
        stubEvent('/cal/work/unrelated.ics', 'unrelated-1')
        Map state = [cursor: 'token-1', resourceUids: [(absolute('/cal/work/candidate.ics')): CANDIDATE_UID]]

        when:
        Map result = gateway().readChanges('Work', state, [CANDIDATE_UID] as Set, RANGE_START, RANGE_END)

        then:
        result.events*.uid == [CANDIDATE_UID]
        result.state.cursor == 'token-2'
        result.state.syncSupported
        requests('PROPFIND', '/cal/work').size() == 1
        server.verify(1, getRequestedFor(urlEqualTo('/cal/work/candidate.ics')))
        server.verify(0, getRequestedFor(urlEqualTo('/cal/work/unrelated.ics')))
    }

    def "sync-collection refuses a successful response without a replacement sync-token"() {
        given:
        stubSyncReport(multistatus([propertyResponse('/cal/work/candidate.ics', '"candidate-2"')]))
        Map state = [cursor: 'token-1', syncSupported: true,
                     resourceUids: [(absolute('/cal/work/candidate.ics')): CANDIDATE_UID]]

        when:
        gateway().readChanges('Work', state, [CANDIDATE_UID] as Set, RANGE_START, RANGE_END)

        then:
        def error = thrown(CalDavHttpGateway.CalDavGatewayException)
        error.classification == 'SCHEMA'
        error.message.contains('sync-token')
        server.verify(0, getRequestedFor(urlPathMatching('/cal/work/.*\\.ics')))
    }

    def "only DAV valid-sync-token evidence expires a cursor and no partial result is consumed"() {
        given: 'a generic forbidden response without the RFC 6578 precondition'
        server.stubFor(request('REPORT', urlEqualTo('/cal/work'))
            .willReturn(aResponse().withStatus(403).withHeader('Content-Type', 'application/xml')
                .withBody('<d:error xmlns:d="DAV:"><d:need-privileges/></d:error>')))
        Map state = [cursor: 'token-1', syncSupported: true, stamps: [(CANDIDATE_UID): 'old'],
                     resourceUids: [(absolute('/cal/work/candidate.ics')): CANDIDATE_UID]]

        when:
        gateway().readChanges('Work', state, [CANDIDATE_UID] as Set, RANGE_START, RANGE_END)

        then:
        def forbidden = thrown(CalDavHttpGateway.CalDavGatewayException)
        forbidden.classification == 'HTTP_STATUS'

        when: 'the server supplies the DAV valid-sync-token precondition alongside would-be partial data'
        server.resetAll()
        server.stubFor(request('REPORT', urlEqualTo('/cal/work'))
            .willReturn(aResponse().withStatus(403).withHeader('Content-Type', 'application/xml').withBody("""
<d:error xmlns:d="DAV:"><d:valid-sync-token/>
  <d:response><d:href>/cal/work/candidate.ics</d:href></d:response>
</d:error>""")))
        gateway().readChanges('Work', state, [CANDIDATE_UID] as Set, RANGE_START, RANGE_END)

        then:
        thrown(CalendarCursorExpiredException)
        state.cursor == 'token-1'
        state.stamps == [(CANDIDATE_UID): 'old']
        server.verify(0, getRequestedFor(urlPathMatching('/cal/work/.*\\.ics')))
    }

    def "response deletion status is distinct from failed property status"() {
        given:
        stubSyncReport(syncMultistatus('token-2', [
            failedPropertyResponse('/cal/work/candidate.ics'),
            deletedResponse('/cal/work/deleted.ics')
        ]))
        stubEvent('/cal/work/candidate.ics', CANDIDATE_UID)
        Map state = [cursor: 'token-1', syncSupported: true,
                     resourceUids: [(absolute('/cal/work/candidate.ics')): CANDIDATE_UID,
                                    (absolute('/cal/work/deleted.ics')): 'candidate-2']]

        when:
        Map result = gateway().readChanges('Work', state,
            [CANDIDATE_UID, 'candidate-2'] as Set, RANGE_START, RANGE_END)

        then:
        result.events*.uid == [CANDIDATE_UID]
        result.deleted*.uid == ['candidate-2']
        server.verify(1, getRequestedFor(urlEqualTo('/cal/work/candidate.ics')))
        server.verify(0, getRequestedFor(urlEqualTo('/cal/work/deleted.ics')))
    }

    def "sync-collection rejects truncated multistatus instead of treating it as a page"() {
        given:
        stubSyncReport("""<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
  ${propertyResponse('/cal/work/candidate.ics', '"candidate-2"')}
  <d:sync-token>token-page</d:sync-token>
  <d:error><d:number-of-matches-within-limits/></d:error>
</d:multistatus>""")

        when:
        gateway().readChanges('Work', [cursor: 'token-1', syncSupported: true],
            [CANDIDATE_UID] as Set, RANGE_START, RANGE_END)

        then:
        def error = thrown(CalDavHttpGateway.CalDavGatewayException)
        error.classification == 'TRUNCATED'
        server.verify(0, getRequestedFor(urlPathMatching('/cal/work/.*\\.ics')))
    }

    def "unchanged ctag skips range report and body download"() {
        given:
        stubCapabilities(false)
        stubCtag('ctag-1')

        when:
        Map result = gateway().readChanges('Work', [collectionTag: 'ctag-1'],
            [CANDIDATE_UID] as Set, RANGE_START, RANGE_END)

        then:
        result.events.empty
        result.state.collectionTag == 'ctag-1'
        !result.state.syncSupported
        requests('REPORT', '/cal/work').empty
        server.verify(0, getRequestedFor(urlPathMatching('/cal/work/.*\\.ics')))
    }

    def "changed ctag rereads metadata and downloads only a changed candidate body"() {
        given:
        stubCtag('ctag-2')
        stubMetadataReport([
            propertyResponse('/cal/work/candidate.ics', '"candidate-2"'),
            propertyResponse('/cal/work/unrelated.ics', '"unrelated-2"')
        ])
        stubEvent('/cal/work/candidate.ics', CANDIDATE_UID)
        Map state = fallbackState('ctag-1', '"candidate-1"')

        when:
        Map result = gateway().readChanges('Work', state, [CANDIDATE_UID] as Set, RANGE_START, RANGE_END)

        then:
        result.events*.uid == [CANDIDATE_UID]
        result.state.collectionTag == 'ctag-2'
        server.verify(1, getRequestedFor(urlEqualTo('/cal/work/candidate.ics')))
        server.verify(0, getRequestedFor(urlEqualTo('/cal/work/unrelated.ics')))
    }

    def "server with neither sync-collection nor ctag rereads metadata and compares candidate stamps"() {
        given:
        stubCapabilities(false)
        stubMissingCtag()
        stubMetadataReport([propertyResponse('/cal/work/candidate.ics', '"candidate-2"')])
        stubEvent('/cal/work/candidate.ics', CANDIDATE_UID)
        Map state = fallbackState(null, '"candidate-1"')
        state.remove('syncSupported')

        when:
        Map result = gateway().readChanges('Work', state, [CANDIDATE_UID] as Set, RANGE_START, RANGE_END)

        then:
        result.events*.uid == [CANDIDATE_UID]
        result.state.collectionTag == null
        result.state.stamps[CANDIDATE_UID] == '"candidate-2"'
        server.verify(1, getRequestedFor(urlEqualTo('/cal/work/candidate.ics')))
    }

    def "CalDAV updates preserve etag concurrency with If-Match"() {
        given:
        String uid = ManagedEventIds.uidForBlock('block-1')
        CalendarEvent event = CalendarEvent.builder().id(absolute('/cal/work/existing.ics')).uid(uid)
            .title('Scheduled work').description(ManagedEventIds.buildDescription('block-1', 'plan-1'))
            .calendarName('Work').start(Instant.parse('2026-08-14T13:00:00Z'))
            .end(Instant.parse('2026-08-14T13:30:00Z')).etag('"etag-7"').build()
        server.stubFor(put(urlPathMatching('/cal/work/.*\\.ics')).willReturn(aResponse().withStatus(204)))

        when:
        gateway().upsertEvent(event)

        then:
        server.verify(putRequestedFor(urlPathMatching('/cal/work/.*\\.ics'))
            .withHeader('If-Match', equalTo('"etag-7"')))
    }

    private CalDavHttpGateway gateway() {
        new CalDavHttpGateway(calendars: [[name: 'Work',
            url: "http://localhost:${server.port()}/cal/work", auth: [type: 'none']]],
            managedCalendarName: 'Work', allowInsecureHttp: true)
    }

    private Map fallbackState(String ctag, String stamp) {
        Map state = [syncSupported: false, stamps: [(CANDIDATE_UID): stamp],
                     resourceUids: [(absolute('/cal/work/candidate.ics')): CANDIDATE_UID]]
        if (ctag != null) state.collectionTag = ctag
        state
    }

    private void stubCapabilities(boolean supported) {
        String report = supported
            ? '<d:supported-report><d:report><d:sync-collection/></d:report></d:supported-report>' : ''
        server.stubFor(request('PROPFIND', urlEqualTo('/cal/work'))
            .withRequestBody(containing('supported-report-set'))
            .willReturn(xmlResponse(multistatus(["""<d:response><d:href>/cal/work</d:href><d:propstat><d:prop>
<d:supported-report-set>${report}</d:supported-report-set>
</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""]))))
    }

    private void stubCtag(String ctag) {
        server.stubFor(request('PROPFIND', urlEqualTo('/cal/work')).withRequestBody(containing('getctag'))
            .willReturn(xmlResponse(multistatus(["""<d:response><d:href>/cal/work</d:href><d:propstat><d:prop>
<cs:getctag xmlns:cs="http://calendarserver.org/ns/">${ctag}</cs:getctag>
</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""]))))
    }

    private void stubMissingCtag() {
        server.stubFor(request('PROPFIND', urlEqualTo('/cal/work')).withRequestBody(containing('getctag'))
            .willReturn(xmlResponse(multistatus(["""<d:response><d:href>/cal/work</d:href><d:propstat><d:prop>
<cs:getctag xmlns:cs="http://calendarserver.org/ns/"/>
</d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"""]))))
    }

    private void stubSyncReport(String body) {
        server.stubFor(request('REPORT', urlEqualTo('/cal/work')).withRequestBody(containing('sync-collection'))
            .willReturn(xmlResponse(body)))
    }

    private void stubMetadataReport(List<String> responses) {
        server.stubFor(request('REPORT', urlEqualTo('/cal/work')).withRequestBody(containing('time-range'))
            .willReturn(xmlResponse(multistatus(responses))))
    }

    private void stubEvent(String path, String uid) {
        server.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
            .withHeader('Content-Type', 'text/calendar').withBody("""BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:${uid}
DTSTART:20260814T090000Z
DTEND:20260814T100000Z
LAST-MODIFIED:20260814T080000Z
SUMMARY:Changed event
END:VEVENT
END:VCALENDAR
""")))
    }

    private String absolute(String path) {
        "http://localhost:${server.port()}${path}"
    }

    private static String syncMultistatus(String token, List<String> responses) {
        multistatus(responses + ["<d:sync-token>${token}</d:sync-token>"])
    }

    private static String multistatus(List<String> children) {
        """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
${children.join('\n')}
</d:multistatus>"""
    }

    private static String propertyResponse(String href, String etag) {
        """<d:response><d:href>${href}</d:href><d:propstat><d:prop>
<d:getetag>${etag}</d:getetag><d:getlastmodified>Fri, 14 Aug 2026 08:00:00 GMT</d:getlastmodified>
</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
    }

    private static String failedPropertyResponse(String href) {
        """<d:response><d:href>${href}</d:href><d:propstat><d:prop><d:getetag/></d:prop>
<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"""
    }

    private static String deletedResponse(String href) {
        "<d:response><d:href>${href}</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>"
    }

    private static def xmlResponse(String body) {
        aResponse().withStatus(207).withHeader('Content-Type', 'application/xml').withBody(body)
    }

    private List requests(String method, String path) {
        server.allServeEvents*.request.findAll { request ->
            request.method.value() == method && request.url == path
        }
    }
}
