package todoistcaldavsync.planner.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.sync.GuardedScheduleUpdate

import java.time.Duration
import java.time.Instant

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class GuardedScheduleWireMockSpec extends Specification {
    WireMockServer server
    final PlannerConfig config = PlannerConfig.fromMap(planner: [timezone: 'America/New_York',
        availability: [working_windows: [weekday: ['09:00-17:00']]]])
    final String commandId = '11111111-1111-1111-1111-111111111111'
    def setup() { server = new WireMockServer(options().dynamicPort()); server.start() }
    def cleanup() { server.stop() }

    private GuardedScheduleUpdate service() {
        new GuardedScheduleUpdate(new TodoistRestGateway(baseUrl: server.baseUrl() + '/api/v1',
            allowInsecureHttp: true, tokenOverride: 'test-token'), config)
    }

    private Map task() {
        [id: 'a', content: 'Human task', labels: ['work'], project_id: 'p', priority: 3,
            description: 'Human note', deadline: [date: '2027-03-20'], completed_count: 4,
            checked: false, is_deleted: false, updated_at: '2027-03-13T12:00:00Z',
            due: [date: '2027-03-13T03:30:00', timezone: null, string: 'every day at 3:30',
                is_recurring: true, lang: 'en'], duration: [amount: 1, unit: 'hour']]
    }

    private Task normalized(Map raw) {
        Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone)
    }

    private void reads(Map before, Map after) {
        server.stubFor(get(urlEqualTo('/api/v1/tasks/a')).inScenario('write').whenScenarioStateIs('Started')
            .willReturn(okJson(JsonOutput.toJson(before))))
        server.stubFor(get(urlEqualTo('/api/v1/tasks/a')).inScenario('write').whenScenarioStateIs('written')
            .willReturn(okJson(JsonOutput.toJson(after))))
        server.stubFor(post(urlEqualTo('/api/v1/sync')).inScenario('write').whenScenarioStateIs('Started')
            .willReturn(okJson(JsonOutput.toJson([sync_status: [(commandId): 'ok']])))
            .willSetStateTo('written'))
    }

    private List commands() {
        server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync'))).collectMany { request ->
            String encoded = request.bodyAsString.split('&').find { it.startsWith('commands=') }.substring(9)
            new JsonSlurper().parseText(URLDecoder.decode(encoded, 'UTF-8')) as List
        }
    }

    def 'native duration normalizes hours and writes only documented minute payload with protected fields preserved'() {
        given:
        Map before = task()
        Map after = task() + [duration: [amount: 45, unit: 'minute'], updated_at: '2027-03-13T12:01:00Z']
        reads(before, after)

        when:
        Task verified = service().duration(normalized(before), 45, commandId)

        then:
        normalized(before).nativeDuration == Duration.ofMinutes(60)
        verified.nativeDuration == Duration.ofMinutes(45)
        verified.todoistDue.snapshotMap() == normalized(before).todoistDue.snapshotMap()
        verified.deadlineDate.toString() == '2027-03-20'
        verified.content == 'Human task' && verified.priority == 3
        verified.labels == ['work'] && verified.description == 'Human note' && verified.completedCount == 4
        commands() == [[type: 'item_update', uuid: commandId,
            args: [id: 'a', duration: [amount: 45, unit: 'minute']]]]
    }

    def 'duration guard refuses advanced completed or edited tasks before issuing a command'() {
        given:
        Map original = task()
        Map changed = task() + change
        reads(changed, changed)

        when:
        service().duration(normalized(original), 45, commandId)

        then:
        thrown(IllegalStateException)
        commands().empty

        where:
        change << [[completed_count: 5], [checked: true], [is_deleted: true], [priority: 1],
            [description: 'new human note'], [duration: [amount: 35, unit: 'minute']]]
    }

    def 'duration postconditions detect provider damage without replay or forbidden repair'() {
        given:
        Map before = task()
        Map after = task() + [duration: [amount: 45, unit: 'minute']] + damage
        reads(before, after)

        when:
        service().duration(normalized(before), 45, commandId)

        then:
        thrown(IllegalStateException)
        commands().size() == 1

        where:
        damage << [[completed_count: 5], [deadline: [date: '2027-03-21']], [content: 'changed'],
            [due: [date: '2027-03-14T03:30:00', timezone: null, is_recurring: false]]]
    }

    def 'recurring due move across DST uses full floating or fixed tuple rather than datetime-only REST update'() {
        given:
        Map before = task()
        before.due.timezone = timezone
        before.due.date = timezone == null ? '2027-03-13T03:30:00' : '2027-03-13T08:30:00Z'
        Map after = task()
        after.due.timezone = timezone
        after.due.date = replacement
        reads(before, after)

        when:
        Task verified = service().due(normalized(before), Instant.parse('2027-03-14T07:30:00Z'), commandId)

        then:
        verified.dueTime == Instant.parse('2027-03-14T07:30:00Z')
        verified.nativeDuration == Duration.ofHours(1)
        commands() == [[type: 'item_update', uuid: commandId, args: [id: 'a', due: [date: replacement,
            timezone: timezone, is_recurring: true, lang: 'en', string: 'every day at 3:30']]]]
        server.verify(0, postRequestedFor(urlEqualTo('/api/v1/tasks/a')))

        where:
        timezone            | replacement
        null                | '2027-03-14T03:30:00'
        'America/New_York'  | '2027-03-14T07:30:00Z'
    }

    def 'completion between outer preflight and recurring command reread never moves the successor'() {
        given:
        Map before = task()
        Map advanced = task() + [completed_count: 5]
        advanced.due.date = '2027-03-14T03:30:00'
        server.stubFor(get(urlEqualTo('/api/v1/tasks/a')).inScenario('completion').whenScenarioStateIs('Started')
            .willReturn(okJson(JsonOutput.toJson(before))).willSetStateTo('advanced'))
        server.stubFor(get(urlEqualTo('/api/v1/tasks/a')).inScenario('completion').whenScenarioStateIs('advanced')
            .willReturn(okJson(JsonOutput.toJson(advanced))))

        when:
        service().due(normalized(before), Instant.parse('2027-03-14T10:00:00Z'), commandId)

        then:
        thrown(todoistcaldavsync.planner.recurrence.GuardedRecurringDueUpdateException)
        commands().empty
    }

    def 'native completion after the intended due command cannot trigger a repair over the successor'() {
        given:
        Map before = task()
        Map advanced = task() + change
        advanced.due.date = '2027-03-15T03:30:00'
        reads(before, advanced)

        when:
        service().due(normalized(before), Instant.parse('2027-03-14T10:00:00Z'), commandId)

        then:
        def failure = thrown(todoistcaldavsync.planner.recurrence.GuardedRecurringDueUpdateException)
        failure.message.contains('native lifecycle changed')
        commands().size() == 1

        where:
        change << [[completed_count: 5], [checked: true], [is_deleted: true]]
    }
}
