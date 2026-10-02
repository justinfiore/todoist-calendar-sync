package todoistcaldavsync.planner.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.apply.PlanApplier
import todoistcaldavsync.planner.domain.Task

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import todoistcaldavsync.planner.recurrence.TodoistDue

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class TodoistSyncGatewayWireMockSpec extends Specification {
    WireMockServer server
    def setup() { server = new WireMockServer(options().dynamicPort()); server.start() }
    def cleanup() { server.stop() }

    def "Sync item_update preserves exact opaque recurrence tuple and stable UUID"() {
        given:
        String uuid = '11111111-1111-1111-1111-111111111111'
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson(
            "{\"sync_status\":{\"${uuid}\":\"ok\"}}")))
        def gateway = gateway()
        def due = Task.fromTodoistMap([id: 't1', content: 'x', labels: [], priority: 1,
            due: [date: '2026-10-03T09:00:00', string: 'every! 2 weekdays at 9am',
                  is_recurring: true, lang: 'en', timezone: 'America/New_York']],
            new Task.DurationResolver(30, [:]), 'manual', ZoneId.of('UTC')).todoistDue

        when:
        def result = gateway.updateRecurringDue('t1', due, '2026-10-03T14:30:00Z', uuid)

        then:
        result.state == TodoistCommandState.COMMITTED
        def requests = server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync')))
        requests.size() == 1
        def request = requests[0]
        request.getHeader('Authorization') == 'Bearer test-token'
        Map form = parseForm(request.bodyAsString)
        def commands = new JsonSlurper().parseText(form.commands)
        commands == [[type: 'item_update', uuid: uuid, args: [id: 't1', due: [
            date: '2026-10-03T14:30:00Z', string: 'every! 2 weekdays at 9am',
            is_recurring: true, lang: 'en', timezone: 'America/New_York']]]]
    }

    def "civil-time Sync item_update preserves an explicitly null floating recurrence tuple"() {
        given:
        String uuid = '12111111-1111-1111-1111-111111111111'
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson(
            "{\"sync_status\":{\"${uuid}\":\"ok\"}}")))
        def due = Task.fromTodoistMap([id: 't1', content: 'x', labels: [], priority: 1,
            due: [date: '2026-10-03T09:00:00', string: 'every day @ 09:00',
                is_recurring: true, lang: 'en', timezone: null]],
            new Task.DurationResolver(30, [:]), 'manual', ZoneId.of('America/New_York')).todoistDue
        String replacement = PlanApplier.formatRecurringDueIso(
            Instant.parse('2026-10-04T14:30:00Z'), due, ZoneId.of('America/New_York'))

        when:
        gateway().updateRecurringDue('t1', due, replacement, uuid)

        then:
        replacement == '2026-10-04T10:30:00'
        Map form = parseForm(server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync')))[0].bodyAsString)
        new JsonSlurper().parseText(form.commands)[0].args.due == [
            date: '2026-10-04T10:30:00', string: 'every day @ 09:00',
            is_recurring: true, lang: 'en', timezone: null]
    }

    def "full then incremental items Sync uses isolated token and accepts empty delta"() {
        given:
        server.stubFor(post(urlEqualTo('/api/v1/sync')).inScenario('items')
            .whenScenarioStateIs('Started')
            .willReturn(okJson('{"sync_token":"one","full_sync_date_utc":"2026-10-02T12:00:00Z","items":[{"id":"t1"}]}'))
            .willSetStateTo('incremental'))
        server.stubFor(post(urlEqualTo('/api/v1/sync')).inScenario('items')
            .whenScenarioStateIs('incremental')
            .willReturn(okJson('{"sync_token":"two","items":[]}')))
        def gateway = gateway()

        when:
        def full = gateway.syncItems('*')
        def incremental = gateway.syncItems(full.syncToken)

        then:
        full.fullSync && full.items*.id == ['t1']
        !incremental.fullSync && incremental.items.empty && incremental.syncToken == 'two'
        def forms = server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync')))*.bodyAsString.collect { parseForm(it) }
        forms*.sync_token == ['*', 'one']
        forms*.resource_types == ['["items"]', '["items"]']
    }

    def "items Sync preserves added_at and deletion tombstone shapes"() {
        given:
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson('''{
          "sync_token":"next","items":[
            {"id":"new","added_at":"2026-10-02T12:34:56Z","completed_count":0},
            {"id":"gone","is_deleted":true}
          ]
        }''')))

        when:
        def page = gateway().syncItems('prior')

        then:
        page.items == [
            [id: 'new', added_at: '2026-10-02T12:34:56Z', completed_count: 0],
            [id: 'gone', is_deleted: true]
        ]
        Map form = parseForm(server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync')))[0].bodyAsString)
        form.sync_token == 'prior'
        form.resource_types == '["items"]'
    }

    def "HTTP 429 mutation is surfaced after one request without blind retry"() {
        given:
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(aResponse()
            .withStatus(429).withHeader('Retry-After', '2').withBody('{"error":"rate_limited"}')))

        when:
        gateway().updateRecurringDue('t1', completeDue(), '2026-10-04T09:00:00',
            '13111111-1111-1111-1111-111111111111')

        then:
        def error = thrown(TodoistRestGateway.TodoistGatewayException)
        error.statusCode == 429
        error.classification == 'HTTP_STATUS'
        server.countRequestsMatching(postRequestedFor(urlEqualTo('/api/v1/sync')).build()).count == 1
    }

    def "lifecycle boundary alone can combine Deadline labels and description"() {
        given:
        String uuid = '22222222-2222-2222-2222-222222222222'
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson(
            "{\"sync_status\":{\"${uuid}\":\"ok\"}}")))
        def gateway = gateway()

        when:
        def result = gateway.updateLifecycleFields(LifecycleMutation.validated(
            't1', uuid, '2026-10-03', ['smartplanner-seen'], 'human plus marker'))

        then:
        result.state == TodoistCommandState.COMMITTED
        Map form = parseForm(server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync')))[0].bodyAsString)
        new JsonSlurper().parseText(form.commands) == [[type: 'item_update', uuid: uuid,
            args: [id: 't1', deadline: [date: '2026-10-03'], labels: ['smartplanner-seen'],
                description: 'human plus marker']]]

        when: 'ordinary planner boundary attempts Deadline mutation'
        gateway.updateTaskDeadline('t1', '2026-10-04')

        then:
        thrown(UnsupportedOperationException)
        server.countRequestsMatching(postRequestedFor(urlEqualTo('/api/v1/sync')).build()).count == 1
    }

    def "Sync command classifies rejection and missing status without blind retry"() {
        given:
        String rejectedId = '33333333-3333-3333-3333-333333333333'
        String absentId = '44444444-4444-4444-4444-444444444444'
        server.stubFor(post(urlEqualTo('/api/v1/sync')).inScenario('status')
            .whenScenarioStateIs('Started')
            .willReturn(okJson("{\"sync_status\":{\"${rejectedId}\":{\"error_code\":42}}}"))
            .willSetStateTo('absent'))
        server.stubFor(post(urlEqualTo('/api/v1/sync')).inScenario('status')
            .whenScenarioStateIs('absent').willReturn(okJson('{"sync_status":{}}')))
        def gateway = gateway()
        def due = completeDue()

        expect:
        gateway.updateRecurringDue('t1', due, '2026-10-04', rejectedId).state == TodoistCommandState.REJECTED
        gateway.updateRecurringDue('t1', due, '2026-10-04', absentId).state == TodoistCommandState.AMBIGUOUS
        server.countRequestsMatching(postRequestedFor(urlEqualTo('/api/v1/sync')).build()).count == 2
    }

    def "recurring mutation refuses incomplete tuple before HTTP"() {
        given:
        def gateway = gateway()
        TodoistDue incomplete = TodoistDue.from([date: '2026-10-03'], ZoneId.of('UTC')) {
            String value, ZoneId zone -> Task.parseFlexibleInstant(value, false, zone)
        }

        when:
        gateway.updateRecurringDue('t1', incomplete, '2026-10-04',
            '55555555-5555-5555-5555-555555555555')

        then:
        thrown(IllegalArgumentException)
        server.allServeEvents.empty
    }

    private static TodoistDue completeDue() {
        Task.fromTodoistMap([id: 't1', content: 'x', labels: [], priority: 1,
            due: [date: '2026-10-03', string: 'every day', is_recurring: true,
                lang: 'en', timezone: 'America/New_York']],
            new Task.DurationResolver(30, [:]), 'manual', ZoneId.of('UTC')).todoistDue
    }

    private TodoistRestGateway gateway() {
        new TodoistRestGateway(baseUrl: "http://localhost:${server.port()}/api/v1",
            tokenOverride: 'test-token', allowInsecureHttp: true, includeProjectNames: false)
    }

    private static Map parseForm(String form) {
        form.split('&').collectEntries { part ->
            def pair = part.split('=', 2)
            [(URLDecoder.decode(pair[0], StandardCharsets.UTF_8)):
                 URLDecoder.decode(pair[1], StandardCharsets.UTF_8)]
        }
    }
}
