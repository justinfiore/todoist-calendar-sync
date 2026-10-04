package todoistcaldavsync.planner.ai

import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig

class AiSubscriptionFailClosedSpec extends Specification {
    def "subscription provider profiles parse while disabled and retain no secret"() {
        when:
        def config = PlannerConfig.fromMap(planner: [
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            ai: [enabled: false, provider: provider, subscription: [
                auth_root: '/var/lib/smartplanner/ai-auth',
                experimental_protocol_acknowledged: false,
                login_timeout: 'PT5M',
                codex: [executable: '/usr/local/bin/codex', min_version: '0.160.0', max_version: '0.160.0'],
                grok: [executable: '/usr/local/bin/grok', min_version: '1.0.46', max_version: '1.0.46']
            ]]
        ])

        then:
        config.ai.provider == provider
        config.ai.subscription.authRoot.toString() == '/var/lib/smartplanner/ai-auth'
        config.ai.subscription.codex.executable.toString() == '/usr/local/bin/codex'
        config.ai.subscription.grok.executable.toString() == '/usr/local/bin/grok'
        config.ai.secretEnv == null

        where:
        provider << ['codex_subscription', 'grok_build_subscription']
    }

    def "OpenAI subscription enables only on the official public inference host"() {
        when:
        def config=PlannerConfig.fromMap(planner: [
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            ai: [enabled: true, provider: 'codex_subscription', model: 'vendor-model', subscription: [
                auth_root: '/var/lib/smartplanner/ai-auth', experimental_protocol_acknowledged: true,
                codex:[allowed_hosts:['api.openai.com']]
            ]]
        ])

        then:
        config.ai.enabled
        config.ai.provider=='codex_subscription'
    }

    def "xAI subscription remains at its exact registration and endpoint gate"() {
        when:
        PlannerConfig.fromMap(planner: [availability:[working_windows:[weekday:['09:00-12:00']]],
            ai:[enabled:true,provider:'grok_build_subscription',model:'vendor-model',subscription:[
                auth_root:'/var/lib/smartplanner/ai-auth',experimental_protocol_acknowledged:true,
                grok:[allowed_hosts:['api.x.ai']]]]])

        then:
        def error=thrown(IllegalArgumentException)
        error.message.contains('third-party reuse of the Grok Build OAuth client')
    }

    def "subscription config rejects relative paths unknown fields and API key crossover"() {
        when:
        PlannerConfig.fromMap(planner: [
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            ai: [enabled: false, provider: 'codex_subscription', secret_env: 'OPENAI_API_KEY', subscription: [
                auth_root: '../personal', unexpected: true,
                codex: [executable: 'codex', unknown: true]
            ]]
        ])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('auth_root must be absolute')
        error.message.contains('subscription contains an unknown field')
        error.message.contains('codex.executable must be absolute')
        error.message.contains('codex contains an unknown field')
        error.message.contains('secret_env is not allowed')
    }

    def "unsupported xAI gateway never calls another provider and returns its concrete compatibility gate"() {
        given:
        def request = new LlmRequest(correlationId: 'c1', suggestionType: 'task_suggestions',
            provider: provider, model: 'm', planId: 'p1', planVersion: 1,
            planHash: 'a' * 64, planningInputHash: 'b' * 64, context: [:],
            allowedTaskIds: [], allowedEventIds: [], maxTokens: 64)

        when:
        def result = new UnsupportedSubscriptionLlmGateway(provider).complete(request)

        then:
        !result.success
        result.error.errorClass == LlmErrorClass.COMPATIBILITY
        result.error.detail.contains('third-party reuse')

        where:
        provider << ['grok_build_subscription']
    }
}
