package todoistcaldavsync

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir
import todoistcaldavsync.planner.ai.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.Task

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class Phase7MainCliSpec extends Specification {
    @TempDir
    Path directory

    def "existing main entry point exposes Phase 7 operations and help needs no config"() {
        given:
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['--help'] as String[], out, err)

        then:
        code == 0
        err.toString().empty
        out.toString().contains('legacy-sync')
        out.toString().contains('preview')
        out.toString().contains('apply-safe')
        out.toString().contains('apply-decision')
        out.toString().contains('ai-suggest')
    }

    def "unknown production operation fails before any network"() {
        given:
        File cfg = File.createTempFile('phase7-main-', '.yaml')
        File log = File.createTempFile('phase7-log-', '.groovy')
        cfg.text = 'planner: {}\n'
        log.text = 'log4j.rootLogger="OFF"\n'
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', cfg.path, '-l', log.path,
            '--operation', 'not-an-operation'] as String[], new StringBuilder(), err)

        then:
        code == 2
        err.toString().contains('Unsupported operation: not-an-operation')
    }

    def "ai-suggest exits according to validation acceptance including empty bundles"() {
        given:
        def gateway = { LlmRequest request ->
            String text = JsonOutput.toJson([schemaVersion: 1, suggestionType: request.suggestionType,
                correlationId: request.correlationId, suggestions: rows])
            LlmGatewayResult.success(new LlmResponse(request.correlationId, request.suggestionType, 1,
                text, text.getBytes('UTF-8').length))
        } as LlmGateway

        when:
        def execution = runSuggestion(assistance(gateway))

        then:
        execution.code == expectedExit
        execution.err.empty
        execution.closed
        execution.arguments == ['plan-cli', 'task_suggestions', 'cli-test', null]
        execution.json.accepted == accepted
        execution.json.keySet() == ['accepted', 'error', 'audit', 'bundle'] as Set
        execution.json.audit.outcome == (accepted ? 'ACCEPTED_SUGGESTIONS' : 'REJECTED')
        if (accepted) {
            assert execution.json.error == null
            assert execution.json.bundle.type == 'task_suggestions'
            assert execution.json.bundle.contentHash ==~ /[0-9a-f]{64}/
            assert execution.json.bundle.suggestions.size() == rows.size()
            if (rows) assert execution.json.bundle.suggestions == [[id: 's-cli', type: 'TaskSuggestion']]
        } else {
            assert execution.json.bundle == null
            assert execution.json.error.class == 'SCHEMA_REJECTED'
            assert execution.json.error.reasonCode
            assert execution.json.error.reasonCode == execution.json.audit.reasonCode
            assert execution.json.error.containsKey('statusCode')
            assert execution.json.error.containsKey('responseBytes')
            assert execution.json.error.containsKey('structuredTextBytes')
            assert execution.json.error.containsKey('responseTruncated')
        }

        where:
        rows                          | accepted | expectedExit
        []                            | true     | 0
        [suggestion('task-cli')]       | true     | 0
        [suggestion('unknown-task')]   | false    | 1
    }

    def "ai-suggest serializes only the safe error metadata and preserves compatibility fields"() {
        given:
        Map safe = [reasonCode: 'unknown_task', statusCode: 200, responseBytes: 1234,
            structuredTextBytes: 321, responseTruncated: true]
        def result = [bundle: null,
            error: safe + [errorClass: LlmErrorClass.SCHEMA_REJECTED, detail: 'LLM response rejected',
                rawResponse: 'private-model-canary'],
            audit: new LlmAuditReceipt(safe + [outcome: 'REJECTED', errorClass: LlmErrorClass.SCHEMA_REJECTED])]

        when:
        def execution = runSuggestion(result)

        then:
        execution.code == 1
        execution.err.empty
        execution.closed
        execution.json.error == safe + [class: 'SCHEMA_REJECTED', detail: 'LLM response rejected']
        safe.every { key, value -> execution.json.audit[key] == value }
        !execution.output.contains('private-model-canary')
    }

    def "ai-suggest unknown gateway exception is rejected without leaking sensitive exception text"() {
        given:
        def gateway = { LlmRequest ignored ->
            throw new IOException('opaque-private-canary owner@example.test Bearer synthetic-secret')
        } as LlmGateway

        when:
        def execution = runSuggestion(assistance(gateway))

        then:
        execution.code == 1
        execution.err.empty
        execution.json.accepted == false
        execution.json.bundle == null
        execution.json.error.class == 'TRANSPORT'
        execution.json.error.responseBytes == null
        execution.json.error.structuredTextBytes == null
        !execution.output.contains('opaque-private-canary')
        !execution.output.contains('owner@example.test')
        !execution.output.contains('synthetic-secret')
    }

    def "ai-suggest invalid configuration retains exit two without a result"() {
        given:
        File config = directory.resolve('invalid.yaml').toFile()
        config.text = 'planner: {}\n'
        def out = new StringBuilder()
        def err = new StringBuilder()

        when:
        int code = TodoistCalDavSync.run(['-f', config.path, '-l', logging().path,
            '--operation', 'ai-suggest', '--plan-id', 'plan-cli', '--ai-type', 'task_suggestions',
            '--correlation-id', 'cli-test'] as String[], out, err)

        then:
        code == 2
        out.empty
        err.toString().startsWith('Error: ')
    }

    private static Map suggestion(String taskId) {
        [suggestionId: 's-cli', taskId: taskId, kind: 'duration', proposedValue: 45,
            confidence: 0.8, rationale: 'Synthetic estimate', evidenceIds: []]
    }

    private static AssistanceResult assistance(LlmGateway gateway) {
        Instant now = Instant.parse('2026-10-26T12:00:00Z')
        def config = PlannerConfig.fromMap(planner: [availability: [working_windows: [weekday: ['09:00-12:00']]],
            ai: [enabled: true, provider: 'fixture', model: 'fixture-v1']])
        Plan plan = Plan.builder().id('plan-cli').version(1).createdAt(now).mode('preview')
            .tasks([Task.builder().id('task-cli').content('Synthetic task')
                .effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()]).build()
        new AiAssistanceService(config.ai, gateway, null, new LlmSchemaValidator(), { now })
            .suggest('task_suggestions', 'cli-test', plan)
    }

    private Map runSuggestion(def result) {
        File config = directory.resolve('planner.yaml').toFile()
        config.text = 'planner: {}\n'
        def out = new StringBuilder()
        def err = new StringBuilder()
        def execution = [closed: false, arguments: null]
        def planner = [aiSuggestions: { Object... args -> execution.arguments = args.toList(); result },
            close: { execution.closed = true }]
        execution.code = TodoistCalDavSync.run(['-f', config.path, '-l', logging().path,
            '--operation', 'ai-suggest', '--plan-id', 'plan-cli', '--ai-type', 'task_suggestions',
            '--correlation-id', 'cli-test'] as String[], out, err,
            { throw new AssertionError('OAuth must not run') },
            { throw new AssertionError('Legacy import must not run') },
            { a, b, c -> throw new AssertionError('QA provisioning must not run') },
            { throw new AssertionError('AI authentication must not run') },
            { File supplied -> assert supplied == config; planner })
        execution.output = out.toString()
        execution.err = err.toString()
        execution.json = new JsonSlurper().parseText(execution.output)
        execution
    }

    private File logging() {
        File file = directory.resolve('log.groovy').toFile()
        file.text = 'log4j.rootLogger="OFF"\n'
        file
    }
}
