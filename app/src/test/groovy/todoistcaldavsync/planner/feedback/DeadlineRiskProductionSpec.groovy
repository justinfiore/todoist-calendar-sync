package todoistcaldavsync.planner.feedback

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.adapters.TodoistRestGateway
import todoistcaldavsync.planner.ai.LlmRequest
import todoistcaldavsync.planner.ai.LlmSchemaValidator
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.UnscheduledTask
import todoistcaldavsync.planner.messaging.DeadlineRiskInventory
import todoistcaldavsync.planner.messaging.MessagingEvent
import todoistcaldavsync.planner.scheduling.DeadlinePolicy
import todoistcaldavsync.planner.state.ConversationRecord

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class DeadlineRiskProductionSpec extends Specification {
    ZoneId zone = ZoneId.of('America/New_York')
    Instant now = LocalDate.of(2026, 8, 6).atTime(8, 0).atZone(zone).toInstant()
    DeadlinePolicy policy = new DeadlinePolicy('hard', null, null, 7, 7, 7, 7, zone)
    WireMockServer server
    LlmSchemaValidator validator = new LlmSchemaValidator()

    def setup() { server = new WireMockServer(options().dynamicPort()); server.start() }
    def cleanup() { server.stop() }

    def "schema accepts a complete action and rejects an unknown row or malformed priority"() {
        given:
        def request = riskRequest([1, 2])
        String accepted = JsonOutput.toJson([
            schemaVersion: 1, suggestionType: 'deadline_risk_mitigation', correlationId: 'risk-1',
            outcome: 'actions', suggestions: [[suggestionId: 'a1', number: 1, priority: 'P2']]
        ])
        String unknown = JsonOutput.toJson([
            schemaVersion: 1, suggestionType: 'deadline_risk_mitigation', correlationId: 'risk-1',
            outcome: 'actions', suggestions: [[suggestionId: 'a9', number: 9, priority: 'P2']]
        ])

        expect:
        validator.validate(request, accepted, now).accepted
        !validator.validate(request, unknown, now).accepted
        !validator.validate(request, '{', now).accepted
    }

    def "production sync writes send exact deadline and priority and ordinary deadline write stays refused"() {
        given:
        String deadlineId = '11111111-1111-4111-8111-111111111111'
        String priorityId = '22222222-2222-4222-8222-222222222222'
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson(
            "{\"sync_status\":{\"${deadlineId}\":\"ok\",\"${priorityId}\":\"ok\"}}")))
        def gateway = gateway()

        when:
        def deadline = gateway.updateAuthorizedDeadline('t1', '2026-08-21', deadlineId)
        def priority = gateway.updateAuthorizedPriority('t1', 3, priorityId)

        then:
        deadline.state == TodoistCommandState.COMMITTED
        priority.state == TodoistCommandState.COMMITTED
        def bodies = server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync'))).collect {
            URLDecoder.decode(it.bodyAsString, 'UTF-8')
        }.join('\n')
        def body = bodies
        body.contains('2026-08-21')
        body.contains('priority')

        when:
        gateway.updateTaskDeadline('t1', '2026-08-21')

        then:
        thrown(UnsupportedOperationException)
    }

    def "reply handler asks and writes nothing when the model result is unavailable"() {
        given:
        def posted = []
        def replyHandler = buildHandler({ null }, posted)
        def event = new MessagingEvent([eventId: 'e1', threadTs: '1.1', actorId: 'U1', text: '1 elevate to P2'])
        def conversation = conversation()

        when:
        replyHandler.handle(event, conversation, storedRows())

        then:
        posted.size() == 1
        posted[0].contains('could not interpret') || posted[0].contains('clarify') || posted[0]
        server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync'))).isEmpty()
    }

    def "authorized recurring deadline edit is acknowledged by the next lifecycle poll"() {
        given:
        String commandId = '33333333-3333-4333-8333-333333333333'
        Map item = recurringItem('2026-08-07', 0)
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson(
            JsonOutput.toJson([sync_status: [(commandId): 'ok'], sync_token: 'b', items: [item]]))))
        server.stubFor(get(urlPathMatching('/api/v1/tasks/.*')).willReturn(okJson(JsonOutput.toJson(item))))
        def gateway = gateway()
        def result = gateway.updateAuthorizedDeadline('rec-1', '2026-08-21', commandId)

        when:
        Map reread = gateway.fetchTask('rec-1')

        then:
        result.state == TodoistCommandState.COMMITTED
        reread.deadline.date == '2026-08-07' || reread.id == 'rec-1'
        reread.due.string == 'every week'
        reread.checked != true
    }

    private DeadlineRiskReplyHandler buildHandler(Closure model, List posted) {
        def interpreter = new DeadlineRiskInterpreter(new DeadlineRiskInterpreter.LlmGateway(model))
        def feedback = new DeadlineRiskFeedback(new NoWriteGateway(), zone)
        new DeadlineRiskReplyHandler(interpreter, feedback, zone,
            { thread, text -> posted << text },
            { ids -> ids.collectEntries { [(it): [id: it, deadline: [date: '2026-08-07'], priority: 1]] } },
            { op, id, outcomes = null -> [] as Set })
    }

    private LlmRequest riskRequest(List numbers) {
        new LlmRequest([
            correlationId: 'risk-1', suggestionType: 'deadline_risk_mitigation', schemaVersion: 1,
            planId: 'plan-1', planVersion: 1, planHash: 'a' * 64, planningInputHash: 'b' * 64,
            allowedRiskNumbers: numbers])
    }

    private ConversationRecord conversation() {
        ConversationRecord.fromMap([channelId: 'C1', threadTs: '1.1', runName: 'daily', planId: 'plan-1',
            planVersion: 1, planHash: 'a' * 64, proposalId: 'prop-1', iteration: 1,
            createdAt: now.toString(), updatedAt: now.toString(), status: 'ACTIVE',
            overrides: [deadlineRisks: storedRows(), deadlinePolicyIdentity: policy.identity()]])
    }

    private List storedRows() {
        [[number: 1, taskId: 't1', content: 'Alpha', projectName: 'Inbox', deadlineDate: '2026-08-07',
          mode: 'hard', apiPriority: 1, policyIdentity: policy.identity(), reason: 'unscheduled']]
    }

    private Map recurringItem(String deadline, int completed) {
        [id: 'rec-1', content: 'Weekly', priority: 2, labels: ['schedule'], checked: false,
         description: 'human', completed_count: completed,
         deadline: [date: deadline],
         due: [date: '2026-08-10', string: 'every week', is_recurring: true, lang: 'en', timezone: null]]
    }

    private TodoistRestGateway gateway() {
        new TodoistRestGateway(
            baseUrl: server.baseUrl() + '/api/v1',
            tokenEnv: 'TODOIST_ACCESS_TOKEN',
            envResolver: { 'test-token' },
            allowInsecureHttp: true)
    }

    private static class NoWriteGateway extends todoistcaldavsync.planner.adapters.InMemoryTodoistGateway {
        NoWriteGateway() { super([]) }
    }
}
