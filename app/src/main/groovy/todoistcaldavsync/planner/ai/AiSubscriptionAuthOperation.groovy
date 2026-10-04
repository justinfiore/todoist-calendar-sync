package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import todoistcaldavsync.planner.config.PlannerConfig

/** Safe launcher boundary for isolated subscription authentication profiles. */
final class AiSubscriptionAuthOperation {
    static final String GROK_COMPATIBILITY_GATE = 'xAI has not authorized third-party reuse of the Grok Build OAuth client, and its official source routes OAuth sessions to the CLI proxy rather than api.x.ai'
    static final String CODEX_DEVICE_GATE = 'Codex CLI-issued tokens have not passed the owner-authorized public SIWC endpoint capability probe; use the official browser flow locally and securely transfer the protected record to a self-hosted host'

    private final PlannerConfig.AiConfig config

    AiSubscriptionAuthOperation(PlannerConfig.AiConfig config) {
        if (config == null) throw new IllegalArgumentException('planner.ai configuration is required')
        this.config = config
    }

    int execute(String operation, String provider, String flow, boolean remote,
                boolean json, Appendable out, Appendable err) {
        if (!(operation in ['ai-auth-login','ai-auth-status','ai-auth-logout'])) {
            throw new IllegalArgumentException('unsupported AI authentication operation')
        }
        if (!(provider in ['codex', 'grok'])) {
            throw new IllegalArgumentException('--ai-provider must be one of: codex, grok')
        }
        if (!(flow in ['device', 'browser'])) {
            throw new IllegalArgumentException('--auth-flow must be one of: device, browser')
        }
        if (operation != 'ai-auth-status' && remote) {
            throw new IllegalArgumentException('--remote is allowed only with ai-auth-status')
        }
        if (provider == 'grok') return unsupported(provider, operation, remote, json, out, GROK_COMPATIBILITY_GATE)
        if (config.subscription.authRoot == null) {
            return unsupported(provider, operation, remote, json, out,
                'planner.ai.subscription.auth_root is required for subscription authentication')
        }
        if (operation == 'ai-auth-login' && flow == 'device') {
            return unsupported(provider, operation, remote, json, out, CODEX_DEVICE_GATE)
        }
        def store = new SubscriptionCredentialStore(config.subscription.authRoot, 'codex')
        def adapter = new OpenAiSiwcAdapter()
        def service = new SubscriptionCredentialService(store, adapter)
        try {
            Map result
            if (operation == 'ai-auth-login') {
                new OpenAiSiwcLogin(store, new JdkSubscriptionOAuthTransport(), config.subscription.loginTimeout).login(out)
                result = [provider:provider, operation:operation, state:'ready', adapterRevision:adapter.revision()]
            } else if (operation == 'ai-auth-status') {
                result = [provider:provider, operation:operation] + service.status(remote)
            } else {
                result = [provider:provider, operation:operation, state:service.logout(), adapterRevision:adapter.revision()]
            }
            emit(result,json,out); return 0
        } catch (SubscriptionAuthException e) {
            Map result=[provider:provider,operation:operation,state:'failed',error:e.code,
                reauthenticationRequired:e.reauthenticationRequired,adapterRevision:adapter.revision()]
            emit(result,json,out); return 3
        } catch (Exception ignored) {
            Map result=[provider:provider,operation:operation,state:'failed',error:'authentication_failed',
                reauthenticationRequired:false,adapterRevision:adapter.revision()]
            emit(result,json,out); return 3
        }
    }

    private static int unsupported(String provider,String operation,boolean remote,boolean json,Appendable out,String reason) {
        Map result=[provider:provider,operation:operation,state:'unsupported',localCredential:'not_inspected',
            remoteEntitlement:remote?'not_checked':'not_requested',adapterRevision:null,reason:reason]
        emit(result,json,out);3
    }
    private static void emit(Map result,boolean json,Appendable out) {
        if(json) out.append(JsonOutput.toJson(result)).append('\n')
        else out.append("${result.provider}: ${result.state}${result.reason?' ('+result.reason+')':''}\n")
    }
}
