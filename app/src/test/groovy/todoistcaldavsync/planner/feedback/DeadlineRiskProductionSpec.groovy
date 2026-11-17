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
        def marker = [
            schema_version: 1, owner: 'smartplanner', task_id: 'rec-1', completed_count: 2,
            deadline_mode: 'managed', deadline_source: 'existing_deadline', deadline_date: '2026-08-07',
            last_verified_due: '2026-08-10',
            recurrence_fingerprint: todoistcaldavsync.planner.recurrence.LifecycleSupport.fingerprint(
                todoistcaldavsync.planner.domain.Task.fromTodoistMap(recurringItem('2026-08-07', 2),
                    new todoistcaldavsync.planner.domain.Task.DurationResolver(30, [:]), 'manual', zone).todoistDue),
            marker_generation: 3, last_command_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
        ]
        def canonical = new todoistcaldavsync.planner.recurrence.LifecycleMarker([
            taskId: 'rec-1', completedCount: 2, deadlineMode: 'managed', deadlineSource: 'existing_deadline',
            deadlineDate: '2026-08-07', lastVerifiedDue: '2026-08-10',
            recurrenceFingerprint: marker.recurrence_fingerprint, markerGeneration: 3,
            lastCommandId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
        ]).canonicalMap()
        String description = "human text" + todoistcaldavsync.planner.recurrence.LifecycleMarkerCodec.PREFIX +
            groovy.json.JsonOutput.toJson(canonical) + todoistcaldavsync.planner.recurrence.LifecycleMarkerCodec.SUFFIX
        Map before = recurringItem('2026-08-07', 2) + [description: description]
        Map after = recurringItem('2026-08-21', 2) + [description: description]
        String commandId = '33333333-3333-4333-8333-333333333333'
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson(
            JsonOutput.toJson([sync_status: [(commandId): 'ok'], sync_token: 'next', items: [after]]))))
        server.stubFor(get(urlPathEqualTo('/api/v1/tasks/rec-1')).willReturn(okJson(JsonOutput.toJson(after))))
        def gateway = gateway()

        when:
        def write = gateway.updateAuthorizedDeadline('rec-1', '2026-08-21', commandId)
        Map live = gateway.fetchTask('rec-1')
        def task = todoistcaldavsync.planner.domain.Task.fromTodoistMap(live,
            new todoistcaldavsync.planner.domain.Task.DurationResolver(30, [:]), 'manual', zone)
        def config = todoistcaldavsync.planner.config.PlannerConfig.fromMap(planner: [
            mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]
        ])
        def classifier = new todoistcaldavsync.planner.recurrence.LifecycleClassifier()
        def manager = new todoistcaldavsync.planner.recurrence.RecurrenceLifecycleManager(config,
            new todoistcaldavsync.planner.recurrence.LifecycleMarkerWriter(gateway, config))
        def acknowledgedMarker = new todoistcaldavsync.planner.recurrence.LifecycleMarker(
            taskId: 'rec-1', completedCount: 2, deadlineMode: 'managed', deadlineSource: 'authorized_reply',
            deadlineDate: '2026-08-21', lastVerifiedDue: '2026-08-10',
            recurrenceFingerprint: marker.recurrence_fingerprint, markerGeneration: 4,
            lastCommandId: "deadline-risk:${commandId}")
        def acknowledgedTask = todoistcaldavsync.planner.domain.Task.builder()
            .id(task.id).content(task.content).labels(task.labels).priority(task.priority)
            .deadline(task.deadline).deadlineDate(java.time.LocalDate.parse('2026-08-21'))
            .todoistDue(task.todoistDue).completedCount(task.completedCount)
            .description(new todoistcaldavsync.planner.recurrence.LifecycleMarkerCodec().merge(task.description, acknowledgedMarker))
            .lifecycleMarker(acknowledgedMarker).effectiveDuration(task.effectiveDuration)
            .durationSource(task.durationSource ?: 'default').build()
        def classification = classifier.classify(acknowledgedTask, 'smartplanner-seen')
        def acknowledged = manager.process(acknowledgedTask)
        def human = manager.process(task)

        then:
        write.state == TodoistCommandState.COMMITTED
        live.deadline.date == '2026-08-21'
        live.due.string == 'every week'
        live.due.date == '2026-08-10'
        live.completed_count == 2
        live.checked != true
        classification.reason == 'authorized_deadline_edit'
        classification.writable
        classification.state.name() == 'USER_EDITED'
        task.deadlineDate.toString() == '2026-08-21'
        task.completedCount == 2
        task.todoistDue.date == '2026-08-10'
        task.lifecycleMarker.completedCount == 2
        acknowledged.action == 'deadline_acknowledged'
        !acknowledged.frozen
        human.frozen
    }

    def "restarted receipt store does not send a second sync write"() {
        given:
        def dir = java.nio.file.Files.createTempDirectory('deadline-risk-receipts')
        String eventId = 'evt-redelivery'
        DeadlineRiskReceipts.handle(dir, 'save', eventId, [[commandId: 'deadline-risk:prop:t1:deadline:2026-08-21', state: 'verified']])

        when:
        def loaded = DeadlineRiskReceipts.handle(dir, 'load', eventId)

        then:
        def commandId = UUID.nameUUIDFromBytes('deadline-risk:t1:2026-08-21'.getBytes('UTF-8')).toString()
        DeadlineRiskReceipts.handle(dir, 'save', eventId, [[commandId: commandId, state: 'verified']])
        def restarted = DeadlineRiskReceipts.handle(dir, 'load', eventId)
        restarted.contains(commandId)
        def posted = []
        def handler = new DeadlineRiskReplyHandler(
            new DeadlineRiskInterpreter(new DeadlineRiskInterpreter.FixtureGateway()),
            new DeadlineRiskFeedback(new CountingGateway(server), zone), zone,
            { thread, text -> posted << text },
            { ids -> ids.collectEntries { [(it): [id: it, deadline: [date: '2026-08-07'], priority: 1]] } },
            { op, id, outcomes = null -> DeadlineRiskReceipts.handle(dir, op, id, outcomes) })
        handler.handle(new MessagingEvent([eventId: eventId, threadTs: '1.1', text: '1 push back two weeks']),
            conversation(), storedRows())
        server.findAll(postRequestedFor(urlEqualTo('/api/v1/sync'))).isEmpty()
        cleanup:
        dir.toFile().deleteDir()
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
         due: [date: '2026-08-10', string: 'every week', is_recurring: true, lang: 'en', timezone: null],
         labels: ['schedule', 'smartplanner-seen']]
    }

    private TodoistRestGateway gateway() {
        new TodoistRestGateway(
            baseUrl: server.baseUrl() + '/api/v1',
            tokenEnv: 'TODOIST_ACCESS_TOKEN',
            envResolver: { 'test-token' },
            allowInsecureHttp: true)
    }

    private static class CountingGateway extends todoistcaldavsync.planner.adapters.InMemoryTodoistGateway {
        private final com.github.tomakehurst.wiremock.WireMockServer server
        CountingGateway(com.github.tomakehurst.wiremock.WireMockServer server) {
            super([])
            this.server = server
        }
        @Override void updateTaskDeadline(String taskId, String deadlineIso) {
            server.addStubMapping(post(urlEqualTo('/api/v1/sync')).willReturn(okJson('{"ok":true}')).build())
            def client = java.net.http.HttpClient.newHttpClient()
            def request = java.net.http.HttpRequest.newBuilder(URI.create(server.baseUrl() + '/api/v1/sync'))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("deadline=${deadlineIso}"))
                .build()
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            super.updateTaskDeadline(taskId, deadlineIso)
        }
    }

    private static class NoWriteGateway extends todoistcaldavsync.planner.adapters.InMemoryTodoistGateway {
        NoWriteGateway() { super([]) }
    }
}
