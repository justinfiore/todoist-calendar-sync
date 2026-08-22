package todoistcaldavsync.planner.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import spock.lang.Specification
import todoistcaldavsync.planner.CalendarProviderConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class GoogleCalendarApiGatewayWireMockSpec extends Specification {
    WireMockServer server

    def setup() {
        server = new WireMockServer(options().dynamicPort())
        server.start()
    }

    def cleanup() { server.stop() }

    def "bounded event list traverses pages for every configured calendar and preserves names"() {
        given:
        server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .withQueryParam('timeMin', equalTo('2026-08-14T00:00:00Z'))
            .withQueryParam('timeMax', equalTo('2026-08-16T00:00:00Z'))
            .withQueryParam('singleEvents', equalTo('true'))
            .withQueryParam('orderBy', equalTo('startTime'))
            .withQueryParam('maxResults', equalTo('2'))
            .withQueryParam('pageToken', absent())
            .willReturn(okJson(eventsResponse([timed('provider-a', 'uid-a', 'Timed event',
                '2026-08-14T13:00:00-04:00', '2026-08-14T13:30:00-04:00')], 'next page'))))
        server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .withQueryParam('pageToken', equalTo('next page'))
            .willReturn(okJson(eventsResponse([allDay('provider-b', 'uid-b', 'All day', '2026-08-15', '2026-08-16')]))))
        server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/blocker%2Fid/events'))
            .willReturn(okJson(eventsResponse([]))))

        when:
        def events = gateway(maxResultsPerPage: 2).fetchEvents(
            Instant.parse('2026-08-14T00:00:00Z'), Instant.parse('2026-08-16T00:00:00Z'))

        then:
        events*.id == ['provider-a', 'provider-b']
        events*.uid == ['uid-a', 'uid-b']
        events*.calendarName == ['Output', 'Output']
        events*.allDay == [false, true]
        events[0].start == Instant.parse('2026-08-14T17:00:00Z')
        events[1].start == Instant.parse('2026-08-15T04:00:00Z')
        events[1].end == Instant.parse('2026-08-16T04:00:00Z')
        server.verify(3, getRequestedFor(urlPathMatching('/calendar/v3/calendars/.*/events'))
            .withHeader('Authorization', equalTo('Bearer access-token')))
        noMutations()
    }

    def "event reads reject invalid ranges and enforce per-calendar page and result bounds"() {
        given:
        def gateway = gateway(maxPagesPerCalendar: 1, maxEventsPerCalendar: 1, maxResultsPerPage: 1)

        when: 'the caller does not provide a positive bounded range'
        gateway.fetchEvents(Instant.parse('2026-08-14T00:00:00Z'), Instant.parse('2026-08-14T00:00:00Z'))

        then:
        thrown(IllegalArgumentException)
        server.allServeEvents.empty

        when: 'Google claims another page beyond the configured per-calendar page bound'
        server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(okJson(eventsResponse([], 'more'))))
        gateway.fetchEvents(Instant.parse('2026-08-14T00:00:00Z'), Instant.parse('2026-08-15T00:00:00Z'))

        then:
        def pageFailure = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        pageFailure.classification == 'PAGINATION_LIMIT'
        server.verify(1, getRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events')))

        when: 'a page exceeds the configured total result bound'
        server.resetAll()
        server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(okJson(eventsResponse([
                timed('one', 'one-uid', 'One', '2026-08-14T10:00:00Z', '2026-08-14T11:00:00Z'),
                timed('two', 'two-uid', 'Two', '2026-08-14T12:00:00Z', '2026-08-14T13:00:00Z')]))))
        gateway.fetchEvents(Instant.parse('2026-08-14T00:00:00Z'), Instant.parse('2026-08-15T00:00:00Z'))

        then:
        def resultFailure = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        resultFailure.classification == 'RESULT_LIMIT'
        noMutations()
    }

    def "malformed and oversized event-list responses fail closed"() {
        given:
        server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(okJson('{not-json')))

        when:
        gateway().fetchEvents(Instant.parse('2026-08-14T00:00:00Z'), Instant.parse('2026-08-15T00:00:00Z'))

        then:
        def malformed = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        malformed.classification == 'MALFORMED_RESPONSE'
        noMutations()

        when:
        server.resetAll()
        server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(okJson(JsonOutput.toJson([items: [], padding: 'x' * 1024]))))
        gateway(maxResponseBytes: 128).fetchEvents(
            Instant.parse('2026-08-14T00:00:00Z'), Instant.parse('2026-08-15T00:00:00Z'))

        then:
        def oversized = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        oversized.classification == 'CONTENT_LIMIT'
        noMutations()
    }

    def "global iCalUID lookup returns absent or one live event and detects cross-calendar collisions"() {
        given:
        stubUidLookup('output-id', 'planner-uid', [])
        stubUidLookup('blocker/id', 'planner-uid', [])

        expect:
        gateway().findEventByUid('planner-uid') == null
        server.verify(2, getRequestedFor(urlPathMatching('/calendar/v3/calendars/.*/events'))
            .withQueryParam('iCalUID', equalTo('planner-uid')))
        noMutations()

        when:
        server.resetAll()
        stubUidLookup('output-id', 'planner-uid', [timed('live-google-id', 'planner-uid', 'Owned',
            '2026-08-14T10:00:00Z', '2026-08-14T11:00:00Z', ManagedEventIds.buildDescription('b1', 'p1'))])
        stubUidLookup('blocker/id', 'planner-uid', [])
        def found = gateway().findEventByUid('planner-uid')

        then:
        found.id == 'live-google-id'
        found.calendarName == 'Output'

        when:
        server.resetAll()
        stubUidLookup('output-id', 'planner-uid', [timed('one', 'planner-uid', 'One',
            '2026-08-14T10:00:00Z', '2026-08-14T11:00:00Z')])
        stubUidLookup('blocker/id', 'planner-uid', [timed('two', 'planner-uid', 'Two',
            '2026-08-14T12:00:00Z', '2026-08-14T13:00:00Z')])
        gateway().findEventByUid('planner-uid')

        then:
        def collision = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        collision.classification == 'UID_COLLISION'
        noMutations()
    }

    def "a failed calendar in global UID lookup is never treated as absence"() {
        given:
        stubUidLookup('output-id', 'planner-uid', [])
        server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/blocker%2Fid/events'))
            .willReturn(aResponse().withStatus(status)))

        when:
        gateway(readAttempts: 1).findEventByUid('planner-uid')

        then:
        def failure = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        failure.classification == expected
        noMutations()

        where:
        status | expected
        401    | 'AUTHENTICATION'
        403    | 'AUTHORIZATION'
        404    | 'NOT_FOUND'
        409    | 'CONFLICT'
        429    | 'RATE_LIMIT'
        503    | 'SERVER_ERROR'
    }

    def "owned upsert creates on the managed calendar with Google event fields"() {
        given:
        String uid = ManagedEventIds.uidForBlock('block-create')
        stubUidLookup('output-id', uid, [])
        stubUidLookup('blocker/id', uid, [])
        server.stubFor(post(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(okJson(JsonOutput.toJson(timed('new-live-id', uid, 'Scheduled work',
                '2026-08-14T13:00:00Z', '2026-08-14T13:30:00Z',
                ManagedEventIds.buildDescription('block-create', 'plan-1'))))))

        when:
        gateway().upsertEvent(ownedEvent(uid, 'block-create'))

        then:
        server.verify(1, postRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .withHeader('Authorization', equalTo('Bearer access-token'))
            .withHeader('Content-Type', containing('application/json'))
            .withRequestBody(notMatching('(?s).*"iCalUID".*'))
            .withRequestBody(matchingJsonPath('$.extendedProperties.private.plannerUid', equalTo(uid)))
            .withRequestBody(matchingJsonPath('$.summary', equalTo('Scheduled work')))
            .withRequestBody(matchingJsonPath('$.description', containing(ManagedEventIds.OWNERSHIP_MARKER)))
            .withRequestBody(matchingJsonPath('$.start.dateTime', equalTo('2026-08-14T13:00:00Z')))
            .withRequestBody(matchingJsonPath('$.end.dateTime', equalTo('2026-08-14T13:30:00Z')))
            .withRequestBody(matchingJsonPath('$.extendedProperties.private.blockId', equalTo('block-create'))))
        server.verify(0, postRequestedFor(urlPathMatching('/calendar/v3/calendars/blocker.*')))
        server.verify(0, putRequestedFor(anyUrl()))
    }

    def "owned upsert globally resolves and updates by the live Google provider event ID"() {
        given:
        String uid = ManagedEventIds.uidForBlock('block-update')
        String description = ManagedEventIds.buildDescription('block-update', 'old-plan')
        stubUidLookup('output-id', uid, [timed('live/provider id', uid, 'Old',
            '2026-08-14T12:00:00Z', '2026-08-14T12:30:00Z', description)])
        stubUidLookup('blocker/id', uid, [])
        server.stubFor(put(urlPathEqualTo('/calendar/v3/calendars/output-id/events/live%2Fprovider%20id'))
            .willReturn(okJson(JsonOutput.toJson(timed('live/provider id', uid, 'Scheduled work',
                '2026-08-14T13:00:00Z', '2026-08-14T13:30:00Z',
                ManagedEventIds.buildDescription('block-update', 'plan-1'))))))

        when:
        gateway().upsertEvent(ownedEvent(uid, 'block-update'))

        then:
        server.verify(1, putRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events/live%2Fprovider%20id'))
            .withRequestBody(notMatching('(?s).*"iCalUID".*'))
            .withRequestBody(matchingJsonPath('$.extendedProperties.private.plannerUid', equalTo(uid))))
        server.verify(0, postRequestedFor(anyUrl()))
    }

    def "upsert refuses cross-calendar, missing ownership, and mismatched live block before mutation"() {
        given:
        String uid = ManagedEventIds.uidForBlock('block-guard')

        when: 'the incoming target is not the configured managed output'
        gateway().upsertEvent(ownedEvent(uid, 'block-guard', 'Blockers'))

        then:
        thrown(IllegalStateException)
        server.allServeEvents.empty

        when: 'the incoming event lacks required planner ownership metadata'
        gateway().upsertEvent(CalendarEvent.builder().id(uid).uid(uid).title('Unsafe').description('block-id:block-guard')
            .calendarName('Output').start(Instant.parse('2026-08-14T13:00:00Z'))
            .end(Instant.parse('2026-08-14T13:30:00Z')).build())

        then:
        thrown(IllegalStateException)
        server.allServeEvents.empty

        when: 'the globally found live event has different block ownership metadata'
        stubUidLookup('output-id', uid, [timed('live-id', uid, 'Old', '2026-08-14T12:00:00Z',
            '2026-08-14T12:30:00Z', ManagedEventIds.buildDescription('other-block', 'old-plan'))])
        stubUidLookup('blocker/id', uid, [])
        gateway().upsertEvent(ownedEvent(uid, 'block-guard'))

        then:
        thrown(IllegalStateException)
        server.verify(0, postRequestedFor(anyUrl()))
        server.verify(0, putRequestedFor(anyUrl()))
    }

    def "delete rereads globally then uses only the live managed Google event ID"() {
        given:
        String uid = ManagedEventIds.uidForBlock('block-delete')
        stubUidLookup('output-id', uid, [timed('live-delete-id', uid, 'Delete me',
            '2026-08-14T12:00:00Z', '2026-08-14T12:30:00Z',
            ManagedEventIds.buildDescription('block-delete', 'plan-1'))])
        stubUidLookup('blocker/id', uid, [])
        server.stubFor(delete(urlPathEqualTo('/calendar/v3/calendars/output-id/events/live-delete-id'))
            .willReturn(aResponse().withStatus(204)))

        when:
        gateway().deleteOwnedEvent(uid, 'block-delete')

        then:
        server.verify(2, getRequestedFor(urlPathMatching('/calendar/v3/calendars/.*/events'))
            .withQueryParam('iCalUID', equalTo(uid)))
        server.verify(1, deleteRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events/live-delete-id')))

        when: 'the live event block no longer matches'
        server.resetAll()
        stubUidLookup('output-id', uid, [timed('live-delete-id', uid, 'Changed',
            '2026-08-14T12:00:00Z', '2026-08-14T12:30:00Z',
            ManagedEventIds.buildDescription('different-block', 'plan-1'))])
        stubUidLookup('blocker/id', uid, [])
        gateway().deleteOwnedEvent(uid, 'block-delete')

        then:
        thrown(IllegalStateException)
        server.verify(0, deleteRequestedFor(anyUrl()))
    }

    def "determinate mutation HTTP failures are classified and never retried"() {
        given:
        String uid = ManagedEventIds.uidForBlock("block-${status}")
        stubUidLookup('output-id', uid, [])
        stubUidLookup('blocker/id', uid, [])
        server.stubFor(post(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(aResponse().withStatus(status).withBody('{"error":{}}')))

        when:
        gateway(readAttempts: 3).upsertEvent(ownedEvent(uid, "block-${status}"))

        then:
        def failure = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        failure.classification == expected
        server.verify(1, postRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events')))

        where:
        status | expected
        401    | 'AUTHENTICATION'
        403    | 'AUTHORIZATION'
        404    | 'NOT_FOUND'
        409    | 'CONFLICT'
        429    | 'RATE_LIMIT'
        503    | 'SERVER_ERROR'
    }

    def "timeout and malformed post-dispatch mutation outcomes are ambiguous and not retried"() {
        given:
        String uid = ManagedEventIds.uidForBlock('block-timeout')
        stubUidLookup('output-id', uid, [])
        stubUidLookup('blocker/id', uid, [])
        server.stubFor(post(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(aResponse().withStatus(200).withFixedDelay(300).withBody('{}')))

        when:
        gateway(timeout: Duration.ofMillis(50), readAttempts: 3).upsertEvent(ownedEvent(uid, 'block-timeout'))

        then:
        def timeout = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        timeout.classification == 'AMBIGUOUS_WRITE'
        server.verify(1, postRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events')))

        when: 'a successful status contains a malformed event resource'
        server.resetAll()
        String malformedUid = ManagedEventIds.uidForBlock('block-malformed')
        stubUidLookup('output-id', malformedUid, [])
        stubUidLookup('blocker/id', malformedUid, [])
        server.stubFor(post(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(okJson('{}')))
        gateway().upsertEvent(ownedEvent(malformedUid, 'block-malformed'))

        then:
        def malformed = thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        malformed.classification == 'AMBIGUOUS_WRITE'
        malformed.cause.classification == 'MALFORMED_RESPONSE'
        server.verify(1, postRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events')))
    }

    def "an interrupted dispatched mutation is ambiguous and is not replayed"() {
        given:
        String uid = ManagedEventIds.uidForBlock('block-interrupted')
        stubUidLookup('output-id', uid, [])
        stubUidLookup('blocker/id', uid, [])
        server.stubFor(post(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(aResponse().withStatus(200).withFixedDelay(5_000).withBody('{}')))
        AtomicReference<Throwable> failure = new AtomicReference<>()
        Thread worker = new Thread({
            try { gateway(timeout: Duration.ofSeconds(10)).upsertEvent(ownedEvent(uid, 'block-interrupted')) }
            catch (Throwable t) { failure.set(t) }
        })

        when:
        worker.start()
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (server.findAll(postRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))).empty &&
            System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        worker.interrupt()
        worker.join(3_000)

        then:
        !worker.alive
        failure.get() instanceof GoogleCalendarApiGateway.GoogleCalendarGatewayException
        (failure.get() as GoogleCalendarApiGateway.GoogleCalendarGatewayException).classification == 'AMBIGUOUS_WRITE'
        server.verify(1, postRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events')))
    }

    private GoogleCalendarApiGateway gateway(Map overrides = [:]) {
        Map options = [
            config: googleConfig(), managedCalendarName: 'Output', timezone: ZoneId.of('America/New_York'),
            baseUrl: "http://localhost:${server.port()}/calendar/v3", allowInsecureHttp: true,
            accessTokenSupplier: { 'access-token' }, timeout: Duration.ofSeconds(2),
            maxPagesPerCalendar: 3, maxResultsPerPage: 50, maxEventsPerCalendar: 100,
            maxResponseBytes: 65_536, readAttempts: 1
        ]
        options.putAll(overrides)
        new GoogleCalendarApiGateway(options)
    }

    private static CalendarProviderConfig.GoogleCalendarApiConfig googleConfig() {
        new CalendarProviderConfig.GoogleCalendarApiConfig(
            Path.of('/ignored/client.json'), Path.of('/ignored/token'), Path.of('/ignored/qa-token'),
            'owner@example.test', 8787, [
                [name: 'Output', id: 'output-id', role: 'managed_output'],
                [name: 'Blockers', id: 'blocker/id', role: 'hard_blocker']
            ])
    }

    private void stubUidLookup(String calendarId, String uid, List<Map> items) {
        server.stubFor(get(urlPathEqualTo("/calendar/v3/calendars/${encoded(calendarId)}/events"))
            .withQueryParam('iCalUID', equalTo(uid))
            .withQueryParam('maxResults', matching('\\d+'))
            .willReturn(okJson(eventsResponse(items))))
        server.stubFor(get(urlPathEqualTo("/calendar/v3/calendars/${encoded(calendarId)}/events"))
            .withQueryParam('privateExtendedProperty', equalTo("plannerUid=${uid}"))
            .withQueryParam('maxResults', matching('\\d+'))
            .willReturn(okJson(eventsResponse(items))))
    }

    private static Map timed(String id, String uid, String summary, String start, String end,
                             String description = '') {
        [id: id, iCalUID: uid, summary: summary, description: description,
         extendedProperties: [private: [plannerUid: uid]],
         start: [dateTime: start], end: [dateTime: end]]
    }

    private static Map allDay(String id, String uid, String summary, String start, String end) {
        [id: id, iCalUID: uid, summary: summary, start: [date: start], end: [date: end]]
    }

    private static CalendarEvent ownedEvent(String uid, String blockId, String calendarName = 'Output') {
        CalendarEvent.builder().id(uid).uid(uid).title('Scheduled work')
            .description(ManagedEventIds.buildDescription(blockId, 'plan-1')).calendarName(calendarName)
            .start(Instant.parse('2026-08-14T13:00:00Z')).end(Instant.parse('2026-08-14T13:30:00Z'))
            .build()
    }

    private static String eventsResponse(List<Map> items, String nextPageToken = null) {
        JsonOutput.toJson(nextPageToken ? [items: items, nextPageToken: nextPageToken] : [items: items])
    }

    private static String encoded(String value) {
        java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace('+', '%20')
    }

    private void noMutations() {
        server.verify(0, postRequestedFor(anyUrl()))
        server.verify(0, putRequestedFor(anyUrl()))
        server.verify(0, patchRequestedFor(anyUrl()))
        server.verify(0, deleteRequestedFor(anyUrl()))
    }
}
