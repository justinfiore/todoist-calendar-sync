package todoistcaldavsync.planner.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir
import todoistcaldavsync.planner.CalendarProviderConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.domain.ProjectionMetadata
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.sync.ProjectionReplacementService
import todoistcaldavsync.planner.migration.MigrationState
import todoistcaldavsync.planner.migration.LegacyMigrationPermit

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class RoutedProjectionGatewayWireMockSpec extends Specification {
    WireMockServer server
    @TempDir Path stateDirectory
    def setup() { server = new WireMockServer(options().dynamicPort()); server.start() }
    def cleanup() { server.stop() }

    def 'CalDAV persists date-only creation and both timed conversions in a non-UTC timezone'() {
        given:
        server.stop()
        String payload
        int version = 0
        def transformer = [getName: { 'caldav-storage' }, transform: { served ->
            switch (served.request.method.value()) {
                case 'REPORT': return aResponse().withStatus(207)
                    .withBody('<d:multistatus xmlns:d="DAV:"/>').build()
                case 'PUT':
                    payload = served.request.bodyAsString
                    version++
                    return aResponse().withStatus(version == 1 ? 201 : 204).build()
                case 'GET': return payload == null ? aResponse().withStatus(404).build() :
                    aResponse().withStatus(200).withHeader('ETag', '"v' + version + '"').withBody(payload).build()
                default: throw new AssertionError('unexpected request')
            }
        }] as ResponseDefinitionTransformerV2
        server = new WireMockServer(options().dynamicPort().extensions(transformer))
        server.start()
        server.stubFor(any(anyUrl()).willReturn(aResponse().withTransformers('caldav-storage')))
        def provider = new CalDavHttpGateway(calendars: [[name: 'Work', url: server.baseUrl() + '/work/',
            managed_destination: true, prefix: 'Joint: ']],
            managedCalendarNames: ['Work'], allowInsecureHttp: true, timezone: 'Pacific/Auckland')
        def config = todoistcaldavsync.planner.config.PlannerConfig.fromMap(planner: [timezone: 'Pacific/Auckland',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            routing: [mode: 'legacy_rules', labelsToInclude: ['work'], rules: [[rule: 'work', calendarName: 'Work']]]])
        def projections = new todoistcaldavsync.planner.sync.RoutedProjections(config, provider)
        Closure target = { String due, int revision = 1 ->
            def task = todoistcaldavsync.planner.domain.Task.fromTodoistMap(
                [id: 'a', content: 'A', labels: ['work'], due: [date: due]],
                config.durationResolver, config.manualLabel, config.timezone)
            CalendarEvent candidate = projections.fromTask(task)
            projections.fromMembers([projections.member(task)], 'Work', candidate.title, 'Human note', false, revision, task.allDayDue)
        }

        when:
        CalendarEvent created = provider.createProjection(target('2026-11-24'))

        then:
        created.allDay
        created.title == 'Joint: A'
        created.start == Instant.parse('2026-11-23T11:00:00Z')
        created.end == Instant.parse('2026-11-24T11:00:00Z')
        payload.contains('DTSTART;VALUE=DATE:20261124\r\nDTEND;VALUE=DATE:20261125')

        when:
        CalendarEvent timed = provider.replaceProjection(created, target('2026-11-24T14:13:00Z', 2))

        then:
        !timed.allDay
        timed.start == Instant.parse('2026-11-24T14:13:00Z')
        !payload.contains('VALUE=DATE')
        payload.contains('DTSTART:20261124T141300Z')

        when:
        CalendarEvent dateOnly = provider.replaceProjection(timed, target('2026-11-26', 3))

        then:
        dateOnly.allDay
        dateOnly.start == Instant.parse('2026-11-25T11:00:00Z')
        dateOnly.end == Instant.parse('2026-11-26T11:00:00Z')
        payload.contains('DTSTART;VALUE=DATE:20261126\r\nDTEND;VALUE=DATE:20261127')
        server.verify(1, putRequestedFor(anyUrl()).withHeader('If-None-Match', equalTo('*')))
        server.verify(1, putRequestedFor(anyUrl()).withHeader('If-Match', equalTo('"v1"')))
        server.verify(1, putRequestedFor(anyUrl()).withHeader('If-Match', equalTo('"v2"')))
    }

    private ProjectionCalendarGateway gateway(boolean google) {
        if (google) {
            def config = new CalendarProviderConfig.GoogleCalendarApiConfig(Path.of('unused'), Path.of('unused-tokens'), null,
                'operator@example.test', 8787, [
                    [name: 'Work', id: 'work', role: 'informational', managed_destination: true],
                    [name: 'Family', id: 'family', role: 'hard_blocker', managed_destination: true],
                    [name: 'Busy', id: 'busy', role: 'hard_blocker']])
            return new GoogleCalendarApiGateway(config: config, managedCalendarNames: ['Work', 'Family'],
                baseUrl: server.baseUrl() + '/calendar/v3', allowInsecureHttp: true,
                accessTokenSupplier: { 'test-token' }, timezone: ZoneId.of('UTC'))
        }
        new CalDavHttpGateway(calendars: [
            [name: 'Work', url: server.baseUrl() + '/work/', managed_destination: true],
            [name: 'Family', url: server.baseUrl() + '/family/', managed_destination: true],
            [name: 'Busy', url: server.baseUrl() + '/busy/']], managedCalendarNames: ['Work', 'Family'],
            allowInsecureHttp: true, timezone: ZoneId.of('UTC'))
    }

    private CalendarEvent event(ProjectionCalendarGateway gateway, String destination = 'Work', int revision = 1,
                                int minutes = 30, String etag = null) {
        String uid = ManagedEventIds.uidForOccurrences(['a:0'])
        Instant start = Instant.parse('2026-11-24T14:00:00Z')
        Instant end = start.plusSeconds(minutes * 60L)
        def metadata = new ProjectionMetadata([schemaVersion: 1, uid: uid, destination: destination,
            revision: revision, routingFingerprint: 'a' * 64, historical: false, allDay: false,
            members: [[taskId: 'a', occurrenceKey: 'a:0', start: start.toString(), end: end.toString(),
                due: [date: start.toString()], nativeMinutes: null, effectiveMinutes: minutes,
                taskUpdated: '2026-11-24T12:00:00Z']]])
        CalendarEvent.builder().id(gateway.projectionResourceId(destination, uid)).uid(uid).title('A')
            .description(metadata.description('Human note', 'group-a', 'sync')).calendarName(destination)
            .start(start).end(end).etag(etag).build()
    }

    private String path(boolean google, CalendarEvent event) {
        google ? '/calendar/v3/calendars/' + event.calendarName.toLowerCase() + '/events/' + event.id : URI.create(event.id).rawPath
    }

    private String resource(boolean google, CalendarEvent event, String etag) {
        if (google) return JsonOutput.toJson([id: event.id, iCalUID: 'server-generated', etag: etag,
            extendedProperties: [private: [plannerUid: event.uid]], summary: event.title, description: event.description,
            updated: '2026-11-24T13:00:00Z', start: [dateTime: event.start.toString()], end: [dateTime: event.end.toString()]])
        String start = event.start.toString().replaceAll(/[-:]/, '')
        String end = event.end.toString().replaceAll(/[-:]/, '')
        String description = event.description.replace('\\', '\\\\').replace('\n', '\\n').replace(',', '\\,').replace(';', '\\;')
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Fixture//EN\r\nBEGIN:VEVENT\r\nUID:${event.uid}\r\nDTSTAMP:20261124T130000Z\r\nDTSTART:${start}\r\nDTEND:${end}\r\nSUMMARY:A\r\nDESCRIPTION:${description}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    }

    def 'CalDAV migration exports original payload and conditionally deletes and restores only the reviewed resource'() {
        given:
        def provider = gateway(false) as CalDavHttpGateway
        String id = server.baseUrl() + '/work/legacy.ics'
        String payload = '''BEGIN:VCALENDAR\r
VERSION:2.0\r
PRODID:-//Fixture//EN\r
BEGIN:VEVENT\r
UID:legacy-task\r
DTSTAMP:20261124T130000Z\r
DTSTART:20261124T140000Z\r
DTEND:20261124T143000Z\r
SUMMARY:Legacy\r
DESCRIPTION:Keep human note\r
LOCATION:Keep location\r
X-EXAMPLE-PRIVATE:keep unknown property\r
BEGIN:VALARM\r
TRIGGER:-PT7M\r
ACTION:DISPLAY\r
DESCRIPTION:Reminder\r
END:VALARM\r
END:VEVENT\r
END:VCALENDAR\r
'''
        String empty = '<d:multistatus xmlns:d="DAV:"/>'
        String report = '<d:multistatus xmlns:d="DAV:"><d:response><d:href>/work/legacy.ics</d:href>' +
            '<d:propstat><d:prop><d:getetag>"legacy"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>'
        server.stubFor(request('REPORT', urlEqualTo('/work/')).inScenario('migration').whenScenarioStateIs('Started')
            .willReturn(aResponse().withStatus(207).withBody(report)))
        server.stubFor(request('REPORT', urlEqualTo('/work/')).inScenario('migration').whenScenarioStateIs('deleted')
            .willReturn(aResponse().withStatus(207).withBody(empty)))
        ['/family/', '/busy/'].each { path -> server.stubFor(request('REPORT', urlEqualTo(path)).willReturn(aResponse().withStatus(207).withBody(empty))) }
        server.stubFor(get(urlEqualTo('/work/legacy.ics')).inScenario('migration').whenScenarioStateIs('Started')
            .willReturn(aResponse().withHeader('ETag', '"legacy"').withBody(payload)))
        server.stubFor(delete(urlEqualTo('/work/legacy.ics')).withHeader('If-Match', equalTo('"legacy"'))
            .inScenario('migration').whenScenarioStateIs('Started').willReturn(aResponse().withStatus(204)).willSetStateTo('deleted'))
        server.stubFor(get(urlEqualTo('/work/legacy.ics')).inScenario('migration').whenScenarioStateIs('deleted')
            .willReturn(aResponse().withStatus(404)))
        server.stubFor(put(urlEqualTo('/work/legacy.ics')).withHeader('If-None-Match', equalTo('*')).withRequestBody(equalTo(payload))
            .inScenario('migration').whenScenarioStateIs('deleted').willReturn(aResponse().withStatus(201)).willSetStateTo('restored'))
        server.stubFor(get(urlEqualTo('/work/legacy.ics')).inScenario('migration').whenScenarioStateIs('restored')
            .willReturn(aResponse().withHeader('ETag', '"restored"').withBody(payload)))

        when:
        List<Map> inventory = provider.inventoryLegacyResources()

        then:
        inventory.size() == 1
        inventory[0].payload == payload
        inventory[0].restorable
        inventory[0].id == id
        server.allServeEvents.every { it.request.url != '/busy/' }

        when:
        Map manifest = [candidates: [[resource: inventory[0]]]]
        String hash = MigrationState.hash(manifest)
        def state = new MigrationState(stateDirectory)
        Map current = [version: 1, stage: 'DELETION_APPROVED', manifest: manifest, manifestHash: hash,
            deletions: [:], created: [:], approval: [manifestHash: hash, expiresAt: '2026-11-24T15:00:00Z']]
        state.save(current)
        provider.deleteLegacyResource('Work', id, new LegacyMigrationPermit(state, hash, 'delete', { Instant.parse('2026-11-24T14:00:00Z') }))

        then:
        provider.readLegacyResource('Work', id) == null

        when:
        current.stage = 'ROLLING_BACK'
        current.rollbackApproval = current.approval
        state.save(current)
        Map restored = provider.restoreLegacyResource('Work', id, new LegacyMigrationPermit(state, hash, 'restore', { Instant.parse('2026-11-24T14:00:00Z') }))

        then:
        restored.payload == payload
        restored.etag == '"restored"'
        server.verify(1, putRequestedFor(urlEqualTo('/work/legacy.ics')))

        when:
        provider.deleteLegacyResource('Family', server.baseUrl() + '/family/unreviewed.ics',
            new LegacyMigrationPermit(state, hash, 'delete', { Instant.parse('2026-11-24T14:00:00Z') }))

        then:
        thrown(IllegalStateException)
        server.verify(0, deleteRequestedFor(urlEqualTo('/family/unreviewed.ics')))
    }

    def 'old single upgrade is conditional and preserves identity and schedule on both providers'() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent target = event(provider, 'Work', 1, 30)
        CalendarEvent expected = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(target) + [etag: '"v1"',
            description: ManagedEventIds.buildDescription('group-a', 'old') +
                'todoist-series-id:a\ntodoist-occurrence-id:a:0\nHuman note'])
        inventory(google, 'Started', [expected])
        String url = path(google, expected)
        server.stubFor(get(urlEqualTo(url)).inScenario('relocate').whenScenarioStateIs('Started')
            .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody(resource(google, expected, '"v1"'))))
        server.stubFor(request(google ? 'PATCH' : 'PUT', urlEqualTo(url)).inScenario('relocate').whenScenarioStateIs('Started')
            .withHeader('If-Match', equalTo('"v1"')).willReturn(aResponse().withStatus(200)).willSetStateTo('upgraded'))
        server.stubFor(get(urlEqualTo(url)).inScenario('relocate').whenScenarioStateIs('upgraded')
            .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v2"').withBody(resource(google, target, '"v2"'))))

        when:
        CalendarEvent actual = new ManagedCalendarWriteGateway(provider, provider, ['Work', 'Family'])
            .upgradeSingleProjection(expected, target)

        then:
        actual.id == expected.id
        actual.uid == expected.uid
        actual.start == expected.start
        actual.end == expected.end
        actual.etag == '"v2"'
        ProjectionMetadata.read(actual).value.members*.occurrenceKey == ['a:0']
        server.verify(1, (google ? patchRequestedFor(urlEqualTo(url)) : putRequestedFor(urlEqualTo(url)))
            .withHeader('If-Match', equalTo('"v1"')))

        where:
        google << [true, false]
    }

    def "exact routed update and retirement require live membership and conditional provider version"() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent expected = event(provider, 'Family', 1, 30, '"v1"')
        CalendarEvent target = event(provider, 'Family', 2, 45)
        String url = path(google, expected)
        server.stubFor(get(urlEqualTo(url)).inScenario('edit').whenScenarioStateIs('Started')
            .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody(resource(google, expected, '"v1"'))))
        server.stubFor(get(urlEqualTo(url)).inScenario('edit').whenScenarioStateIs('updated')
            .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v2"').withBody(resource(google, target, '"v2"'))))
        server.stubFor(get(urlEqualTo(url)).inScenario('edit').whenScenarioStateIs('deleted').willReturn(aResponse().withStatus(404)))
        server.stubFor(request(google ? 'PATCH' : 'PUT', urlEqualTo(url)).inScenario('edit').whenScenarioStateIs('Started')
            .withHeader('If-Match', equalTo('"v1"')).willReturn(aResponse().withStatus(200)).willSetStateTo('updated'))
        server.stubFor(delete(urlEqualTo(url)).inScenario('edit').whenScenarioStateIs('updated')
            .withHeader('If-Match', equalTo('"v2"')).willReturn(aResponse().withStatus(204)).willSetStateTo('deleted'))
        def guarded = new ManagedCalendarWriteGateway(provider, provider, ['Work', 'Family'])

        when:
        CalendarEvent updated = guarded.replaceProjection(expected, target)
        guarded.retireProjection(updated)

        then:
        updated.start == Instant.parse('2026-11-24T14:00:00Z')
        updated.end == Instant.parse('2026-11-24T14:45:00Z')
        ProjectionMetadata.read(updated).value.members[0].nativeMinutes == null
        server.verify(1, (google ? patchRequestedFor(urlEqualTo(url)) : putRequestedFor(urlEqualTo(url)))
            .withHeader('If-Match', equalTo('"v1"')))
        server.verify(1, deleteRequestedFor(urlEqualTo(url)).withHeader('If-Match', equalTo('"v2"')))
        provider.readResource('Family', expected.id) == null

        where:
        google << [true, false]
    }

    def "routed writes refuse stripped metadata stale versions and read-only destinations before mutation"() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent expected = event(provider, 'Work', 1, 30, '"v1"')
        CalendarEvent target = event(provider, 'Work', 2, 45)
        String url = path(google, expected)
        String body = resource(google, expected, version)
        if (strip) body = body.replace('todoist-projection:', 'stripped-projection:')
        server.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200).withHeader('ETag', version).withBody(body)))

        when:
        provider.replaceProjection(expected, target)

        then:
        thrown(IllegalStateException)
        server.allServeEvents.every { it.request.method.value() == 'GET' }

        when:
        provider.projectionResourceId('Busy', expected.uid)

        then:
        thrown(IllegalStateException)

        where:
        google | strip | version
        true   | true  | '"v1"'
        true   | false | '"newer"'
        false  | true  | '"v1"'
        false  | false | '"newer"'
    }

    def "provider 412 races are not retried or converted to an unconditional write"() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent expected = event(provider, 'Work', 1, 30, '"v1"')
        CalendarEvent target = event(provider, 'Work', 2, 45)
        String url = path(google, expected)
        server.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"')
            .withBody(resource(google, expected, '"v1"'))))
        server.stubFor(request(google ? 'PATCH' : 'PUT', urlEqualTo(url)).willReturn(aResponse().withStatus(412)))

        when:
        provider.replaceProjection(expected, target)

        then:
        thrown(RuntimeException)
        server.verify(1, (google ? patchRequestedFor(urlEqualTo(url)) : putRequestedFor(urlEqualTo(url)))
            .withHeader('If-Match', equalTo('"v1"')))

        where:
        google << [true, false]
    }

    private void inventory(boolean google, String state, List<CalendarEvent> events) {
        ['Work', 'Family', 'Busy'].each { String name ->
            List<CalendarEvent> rows = events.findAll { it.calendarName == name }
            if (google) {
                server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/' + name.toLowerCase() + '/events'))
                    .withQueryParam('iCalUID', matching('.*')).inScenario('relocate').whenScenarioStateIs(state)
                    .willReturn(okJson('{"items":[]}')))
                server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/' + name.toLowerCase() + '/events'))
                    .withQueryParam('privateExtendedProperty', matching('.*')).inScenario('relocate').whenScenarioStateIs(state)
                    .willReturn(okJson(JsonOutput.toJson([items: rows.collect {
                        new JsonSlurper().parseText(resource(true, it, it.etag))
                    }]))))
            } else {
                String responses = rows.collect { CalendarEvent event ->
                    '<d:response><d:href>' + URI.create(event.id).rawPath + '</d:href><d:propstat><d:prop>' +
                        '<d:getetag>' + event.etag + '</d:getetag></d:prop>' +
                        '<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>'
                }.join('')
                server.stubFor(request('REPORT', urlEqualTo('/' + name.toLowerCase() + '/'))
                    .inScenario('relocate').whenScenarioStateIs(state).willReturn(aResponse().withStatus(207)
                        .withBody('<d:multistatus xmlns:d="DAV:">' + responses + '</d:multistatus>')))
            }
        }
    }

    private void relocateFixture(boolean google, CalendarEvent source, CalendarEvent target) {
        ['Started', 'created', 'retry', 'retired'].each { String state ->
            inventory(google, state, state == 'Started' ? [source] : state == 'retired' ? [target] : [source, target])
            server.stubFor(get(urlEqualTo(path(google, source))).inScenario('relocate').whenScenarioStateIs(state)
                .willReturn(state == 'retired' ? aResponse().withStatus(404) : aResponse().withStatus(200)
                    .withHeader('ETag', source.etag).withBody(resource(google, source, source.etag))))
            server.stubFor(get(urlEqualTo(path(google, target))).inScenario('relocate').whenScenarioStateIs(state)
                .willReturn(state == 'Started' ? aResponse().withStatus(404) : aResponse().withStatus(200)
                    .withHeader('ETag', target.etag).withBody(resource(google, target, target.etag))))
        }
        String createPath = google ? '/calendar/v3/calendars/family/events' : path(false, target)
        server.stubFor(request(google ? 'POST' : 'PUT', urlEqualTo(createPath)).inScenario('relocate')
            .whenScenarioStateIs('Started').willReturn(aResponse().withStatus(201)).willSetStateTo('created'))
        server.stubFor(delete(urlEqualTo(path(google, source))).inScenario('relocate').whenScenarioStateIs('created')
            .willReturn(aResponse().withStatus(503)).willSetStateTo('retry'))
        server.stubFor(delete(urlEqualTo(path(google, source))).inScenario('relocate').whenScenarioStateIs('retry')
            .withHeader('If-Match', equalTo(source.etag)).willReturn(aResponse().withStatus(204)).willSetStateTo('retired'))
    }

    def 'routed relocation E2E survives provider retirement failure and process restart without weakening UID lookup'() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent source = event(provider, 'Work', 1, 30, '"v1"')
        CalendarEvent target = event(provider, 'Family', 2, 30)
        CalendarEvent liveTarget = event(provider, 'Family', 2, 30, '"v2"')
        relocateFixture(google, source, liveTarget)
        def store = new ApplicationStateStore(stateDirectory)

        when: 'source retirement fails after the destination has been verified'
        new ProjectionReplacementService(store, provider).replace([source], [target])

        then:
        def failure = thrown(RuntimeException)
        failure.message.contains('503')
        store.loadSyncOperations().size() == 1
        store.loadSyncOperations().values().first().status == 'pending'
        store.loadSyncOperations().values().first().outcomes.values()*.state.toSet() == ['verified', 'unknown'] as Set

        when: 'ordinary UID lookup sees the temporary pair'
        provider.findEventByUid(source.uid)

        then:
        thrown(RuntimeException)

        when: 'a new service and store reopen the durable journal'
        String id = store.loadSyncOperations().keySet().first()
        Map result = new ProjectionReplacementService(new ApplicationStateStore(stateDirectory), gateway(google)).resume(id)

        then:
        result.status == 'verified'
        provider.readResource('Work', source.id) == null
        provider.findEventByUid(source.uid).calendarName == 'Family'
        String createPath = google ? '/calendar/v3/calendars/family/events' : path(false, target)
        server.verify(1, google ? postRequestedFor(urlEqualTo(createPath)) :
            putRequestedFor(urlEqualTo(createPath)).withHeader('If-None-Match', equalTo('*')))
        server.verify(2, deleteRequestedFor(urlEqualTo(path(google, source))).withHeader('If-Match', equalTo('"v1"')))

        where:
        google << [true, false]
    }

    def 'relocation recovery preserves a newer human source edit instead of conditionally deleting its new version'() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent source = event(provider, 'Work', 1, 30, '"v1"')
        CalendarEvent target = event(provider, 'Family', 2, 30)
        relocateFixture(google, source, event(provider, 'Family', 2, 30, '"v2"'))
        def store = new ApplicationStateStore(stateDirectory)

        when:
        new ProjectionReplacementService(store, provider).replace([source], [target])

        then:
        def failure = thrown(RuntimeException)
        failure.message.contains('503')

        when:
        server.stubFor(get(urlEqualTo(path(google, source))).inScenario('relocate').whenScenarioStateIs('retry')
            .willReturn(aResponse().withStatus(200).withHeader('ETag', '"human"')
                .withBody(resource(google, event(provider, 'Work', 1, 55, '"human"'), '"human"'))))
        String id = store.loadSyncOperations().keySet().first()
        new ProjectionReplacementService(new ApplicationStateStore(stateDirectory), gateway(google)).resume(id)

        then:
        thrown(IllegalStateException)
        store.loadSyncOperations()[id].status == 'pending'
        store.loadSyncOperations()[id].outcomes.values().any { it.state == 'conflict' }
        server.verify(1, deleteRequestedFor(urlEqualTo(path(google, source))))

        where:
        google << [true, false]
    }

    def 'ambiguous routed create reconciles its exact destination before retry and does not duplicate the event'() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent source = event(provider, 'Work', 1, 30, '"v1"')
        CalendarEvent target = event(provider, 'Family', 2, 30)
        relocateFixture(google, source, event(provider, 'Family', 2, 30, '"v2"'))
        String createPath = google ? '/calendar/v3/calendars/family/events' : path(false, target)
        server.stubFor(request(google ? 'POST' : 'PUT', urlEqualTo(createPath)).inScenario('relocate')
            .whenScenarioStateIs('Started').willReturn(aResponse().withStatus(503)).willSetStateTo('created'))
        server.stubFor(delete(urlEqualTo(path(google, source))).inScenario('relocate').whenScenarioStateIs('created')
            .withHeader('If-Match', equalTo('"v1"')).willReturn(aResponse().withStatus(204)).willSetStateTo('retired'))
        def store = new ApplicationStateStore(stateDirectory)

        when:
        new ProjectionReplacementService(store, provider).replace([source], [target])

        then:
        def failure = thrown(RuntimeException)
        failure.message.contains('503')
        store.loadSyncOperations().values().first().outcomes.values()*.state.toSet() == ['pending', 'unknown'] as Set
        server.verify(0, deleteRequestedFor(urlEqualTo(path(google, source))))

        when:
        String id = store.loadSyncOperations().keySet().first()
        Map result = new ProjectionReplacementService(new ApplicationStateStore(stateDirectory), gateway(google)).resume(id)

        then:
        result.status == 'verified'
        provider.findEventByUid(source.uid).calendarName == 'Family'
        server.verify(1, google ? postRequestedFor(urlEqualTo(createPath)) : putRequestedFor(urlEqualTo(createPath)))

        where:
        google << [true, false]
    }

    def 'unrelated duplicate during replacement recovery is never adopted or used as permission to retire the source'() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent source = event(provider, 'Work', 1, 30, '"v1"')
        CalendarEvent target = event(provider, 'Family', 2, 30)
        CalendarEvent liveTarget = event(provider, 'Family', 2, 30, '"v2"')
        relocateFixture(google, source, liveTarget)
        def store = new ApplicationStateStore(stateDirectory)

        when:
        new ProjectionReplacementService(store, provider).replace([source], [target])

        then:
        def failure = thrown(RuntimeException)
        failure.message.contains('503')

        when:
        CalendarEvent foreign = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(source) +
            [id: google ? 'foreign' : server.baseUrl() + '/busy/foreign.ics', calendarName: 'Busy'])
        inventory(google, 'retry', [source, liveTarget, foreign])
        server.stubFor(get(urlEqualTo(path(google, foreign))).willReturn(aResponse().withStatus(200)
            .withHeader('ETag', '"v1"').withBody(resource(google, foreign, '"v1"'))))
        String id = store.loadSyncOperations().keySet().first()
        new ProjectionReplacementService(new ApplicationStateStore(stateDirectory), gateway(google)).resume(id)

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains('unrelated identity collision')
        store.loadSyncOperations()[id].status == 'pending'
        server.verify(1, deleteRequestedFor(urlEqualTo(path(google, source))))

        where:
        google << [true, false]
    }

    def 'in-place projection writes cannot substitute membership or resurrect historical occurrences'() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent expected = event(provider, 'Work', 1, 30, '"v1"')
        Map original = ProjectionMetadata.read(expected).value
        Map member = new LinkedHashMap(original.members[0] as Map)
        if (changeMember) { member.taskId = 'different'; member.occurrenceKey = 'different:0' }
        def changed = new ProjectionMetadata(original + [revision: 2, members: [member], historical: !changeMember])
        CalendarEvent target = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(expected) +
            [etag: null, description: changed.description('Human note', 'group-a', 'sync')])

        when:
        provider.replaceProjection(expected, target)

        then:
        thrown(IllegalArgumentException)
        server.allServeEvents.empty

        where:
        google | changeMember
        true   | true
        false  | true
        true   | false
        false  | false
    }

    def 'managed discovery finds unknown planner identities but empty selection and read-only calendars stay restricted'() {
        given:
        ProjectionCalendarGateway provider = gateway(google)
        CalendarEvent owned = event(provider, 'Work', 1, 30, '"v1"')
        CalendarEvent appointment = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(owned) +
            [id: google ? 'appointment' : server.baseUrl() + '/work/appointment.ics', uid: 'unrelated@example.test',
                description: 'Unrelated appointment'])
        Map state = google ? [:] : [syncSupported: true]
        if (google) {
            server.stubFor(get(urlPathEqualTo('/calendar/v3/calendars/work/events')).willReturn(okJson(JsonOutput.toJson([
                nextSyncToken: 'staged-token', items: [owned, appointment].collect {
                    new JsonSlurper().parseText(resource(true, it, '"v1"'))
                }]))))
        } else {
            String rows = [owned, appointment].collect {
                '<d:response><d:href>' + URI.create(it.id).rawPath + '</d:href><d:propstat><d:prop>' +
                    '<d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>'
            }.join('')
            server.stubFor(request('REPORT', urlEqualTo('/work/')).willReturn(aResponse().withStatus(207)
                .withBody('<d:multistatus xmlns:d="DAV:"><d:sync-token>staged-token</d:sync-token>' + rows + '</d:multistatus>')))
            [owned, appointment].each {
                server.stubFor(get(urlEqualTo(path(false, it))).willReturn(aResponse().withStatus(200)
                    .withHeader('ETag', '"v1"').withBody(resource(false, it, '"v1"'))))
            }
        }
        Instant start = Instant.parse('2026-11-24T00:00:00Z')
        Instant end = start.plusSeconds(86400)

        when:
        Map discovered = (provider as CalendarChangeGateway).readChanges('Work', state, null, start, end)
        Map empty = (provider as CalendarChangeGateway).readChanges('Work', state, [] as Set, start, end)

        then:
        discovered.events*.uid == [owned.uid]
        discovered.state.cursor == 'staged-token'
        empty.events.isEmpty()
        ProjectionMetadata.read(discovered.events[0]).value.members*.taskId == ['a']

        when:
        int before = server.allServeEvents.size()
        (provider as CalendarChangeGateway).readChanges('Busy', [:], null, start, end)

        then:
        thrown(IllegalStateException)
        server.allServeEvents.size() == before

        where:
        google << [true, false]
    }
}
