package todoistcaldavsync.planner.qa

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.CalendarProviderConfig

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class QaCalendarProvisioningServiceWireMockSpec extends Specification {
    WireMockServer server
    Path qaRoot

    def setup() {
        server = new WireMockServer(options().dynamicPort())
        server.start()
        qaRoot = Files.createTempDirectory('qa-calendar-state-').resolve('.qa')
    }

    def cleanup() { server.stop() }

    def "explicit QA provision reuses exact names, creates missing calendars, and persists only returned IDs"() {
        given:
        stubCalendarList([
            [id: 'owner@example.test', summary: 'owner@example.test', primary: true],
            [id: 'existing-output-id', summary: 'SmartPlanner QA Output']
        ])
        server.stubFor(post(urlPathEqualTo('/calendar/v3/calendars'))
            .withHeader('Authorization', equalTo('Bearer qa-access-token'))
            .withHeader('Content-Type', containing('application/json'))
            .withRequestBody(equalToJson(JsonOutput.toJson([
                summary: 'SmartPlanner QA Blockers', timeZone: 'America/New_York'
            ])))
            .willReturn(okJson(JsonOutput.toJson([id: 'created-blocker-id', summary: 'SmartPlanner QA Blockers']))))
        Path stateFile = qaRoot.resolve('state/calendar-ids.json')

        when:
        def result = service(stateFile).provision([
            new QaCalendarSpec('output', 'SmartPlanner QA Output', 'managed_output'),
            new QaCalendarSpec('blockers', 'SmartPlanner QA Blockers', 'hard_blocker')
        ])

        then:
        result*.alias == ['output', 'blockers']
        result*.id == ['existing-output-id', 'created-blocker-id']
        server.verify(1, getRequestedFor(urlPathEqualTo('/calendar/v3/users/me/calendarList'))
            .withQueryParam('maxResults', equalTo('250'))
            .withHeader('Authorization', equalTo('Bearer qa-access-token')))
        server.verify(1, postRequestedFor(urlPathEqualTo('/calendar/v3/calendars')))
        def state = new JsonSlurper().parse(stateFile.toFile()) as Map
        state.keySet() == ['calendars'] as Set
        state.calendars == [
            output: [id: 'existing-output-id'],
            blockers: [id: 'created-blocker-id']
        ]
        !Files.exists(qaRoot.resolve('credential.json'))
    }

    def "list performs dedicated-account preflight but never creates or persists"() {
        given:
        stubCalendarList([
            [id: 'owner@example.test', summary: 'owner@example.test', primary: true],
            [id: 'one', summary: 'SmartPlanner QA Output']
        ])
        Path stateFile = qaRoot.resolve('state/calendar-ids.json')

        when:
        def listed = service(stateFile).list()

        then:
        listed*.id == ['owner@example.test', 'one']
        server.verify(0, postRequestedFor(anyUrl()))
        !Files.exists(stateFile)
    }

    def "preflight refuses a nonmatching primary account before create or state write"() {
        given:
        stubCalendarList([[id: 'personal@example.test', summary: 'Personal', primary: true]])
        Path stateFile = qaRoot.resolve('state/calendar-ids.json')

        when:
        service(stateFile).provision([new QaCalendarSpec('output', 'SmartPlanner QA Output', 'managed_output')])

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('dedicated QA account')
        server.verify(0, postRequestedFor(anyUrl()))
        !Files.exists(stateFile)
    }

    def "service requires a distinct QA token store and state beneath ignored QA root"() {
        given:
        Path base = Files.createTempDirectory('qa-calendar-config-')
        Path normal = base.resolve('normal-token')

        when:
        new QaCalendarProvisioningService([
            config: googleConfig(normal, null), qaRoot: qaRoot,
            stateFile: qaRoot.resolve('state.json'), baseUrl: server.baseUrl() + '/calendar/v3',
            allowInsecureHttp: true, accessTokenSupplier: { 'qa-access-token' }
        ])

        then:
        def missing = thrown(IllegalArgumentException)
        missing.message.contains('QA token store')

        when:
        new QaCalendarProvisioningService([
            config: googleConfig(normal, normal), qaRoot: qaRoot,
            stateFile: qaRoot.resolve('state.json'), baseUrl: server.baseUrl() + '/calendar/v3',
            allowInsecureHttp: true, accessTokenSupplier: { 'qa-access-token' }
        ])

        then:
        def shared = thrown(IllegalArgumentException)
        shared.message.contains('distinct')

        when:
        service(qaRoot.parent.resolve('escaped.json'))

        then:
        def escaped = thrown(IllegalArgumentException)
        escaped.message.contains('.qa')
    }

    private QaCalendarProvisioningService service(Path stateFile) {
        Path secretRoot = qaRoot.parent.resolve('secrets')
        new QaCalendarProvisioningService([
            config: googleConfig(secretRoot.resolve('normal'), secretRoot.resolve('qa')),
            qaRoot: qaRoot, stateFile: stateFile,
            baseUrl: server.baseUrl() + '/calendar/v3', allowInsecureHttp: true,
            accessTokenSupplier: { 'qa-access-token' }, timezone: ZoneId.of('America/New_York')
        ])
    }

    private static CalendarProviderConfig.GoogleCalendarApiConfig googleConfig(Path normal, Path qa) {
        new CalendarProviderConfig.GoogleCalendarApiConfig(
            normal.parent.resolve('client.json'), normal, qa, 'owner@example.test', 8787, [])
    }

    private void stubCalendarList(List<Map> items) {
        server.stubFor(get(urlPathEqualTo('/calendar/v3/users/me/calendarList'))
            .willReturn(okJson(JsonOutput.toJson([items: items]))))
    }
}
