package todoistcaldavsync.planner.ai

import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig

import java.time.Instant

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

    def "subscription providers enable only on their exact public inference host"() {
        when:
        def config=PlannerConfig.fromMap(planner: [
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            ai: [enabled: true, provider: provider, model: 'vendor-model', subscription: [
                auth_root: '/var/lib/smartplanner/ai-auth', experimental_protocol_acknowledged: true,
                (profile):[allowed_hosts:[host]]
            ]]
        ])

        then:
        config.ai.enabled
        config.ai.provider==provider

        where:
        provider                  | profile | host
        'codex_subscription'      | 'codex' | 'api.openai.com'
        'grok_build_subscription' | 'grok'  | 'api.x.ai'
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

    def "xAI auth operation dispatches only the Hermes-style device flow and owns its credential"() {
        given:
        File root=File.createTempDir('xai-auth-operation-','')
        def config=PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-12:00']]],
            ai:[enabled:false,provider:'grok_build_subscription',subscription:[auth_root:root.absolutePath,
                grok:[allowed_hosts:['api.x.ai']]]]])
        SubscriptionOAuthTransport noNetwork=[
            get:{a,b,c->throw new AssertionError('local operation must not use network')},
            postForm:{a,b,c->throw new AssertionError('local operation must not use network')}] as SubscriptionOAuthTransport
        int logins=0
        Closure loginFactory={SubscriptionCredentialStore store->
            [login:{Appendable ignored->
                logins++
                store.save(new SubscriptionCredential(provider:'grok',adapterRevision:XaiDeviceOAuthAdapter.REVISION,
                    generation:1,issuer:'https://auth.x.ai',clientId:XaiDeviceOAuthAdapter.CLIENT_ID,
                    accessToken:'fixture-access',refreshToken:'fixture-refresh',expiresAt:Instant.now().plusSeconds(3600),
                    scopes:XaiDeviceOAuthAdapter.SCOPES))
            }]
        }
        def operation=new AiSubscriptionAuthOperation(config.ai,noNetwork,null,loginFactory)
        def loginOut=new StringBuilder();def statusOut=new StringBuilder()

        expect:
        operation.execute('ai-auth-login','grok','device',false,true,loginOut,new StringBuilder())==0
        operation.execute('ai-auth-status','grok','device',false,true,statusOut,new StringBuilder())==0
        logins==1
        loginOut.toString().contains('xai-device-responses-hermetic-v1')
        statusOut.toString().contains('"state":"ready"')
        new SubscriptionCredentialStore(root.toPath(),'grok').load().isPresent()

        when:
        def browserOut=new StringBuilder()
        int browserCode=operation.execute('ai-auth-login','grok','browser',false,true,browserOut,new StringBuilder())

        then:
        browserCode==3
        browserOut.toString().contains('RFC 8628 device flow')
        logins==1

        cleanup:
        root?.deleteDir()
    }

}
