package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import todoistcaldavsync.planner.config.PlannerConfig

/** Safe launcher boundary for isolated subscription authentication profiles. */
final class AiSubscriptionAuthOperation {
    static final String CODEX_DEVICE_GATE = 'Codex CLI-issued tokens have not passed the owner-authorized public SIWC endpoint capability probe; use the official browser flow locally and securely transfer the protected record to a self-hosted host'
    static final String XAI_BROWSER_GATE = 'xAI subscription authentication uses the RFC 8628 device flow; browser authorization-code login is not implemented'

    private final PlannerConfig.AiConfig config
    private final SubscriptionOAuthTransport transport
    private final Closure openAiLoginFactory
    private final Closure xaiLoginFactory

    AiSubscriptionAuthOperation(PlannerConfig.AiConfig config,
                                SubscriptionOAuthTransport transport = new JdkSubscriptionOAuthTransport(),
                                Closure openAiLoginFactory = null,
                                Closure xaiLoginFactory = null) {
        if (config == null || transport == null) throw new IllegalArgumentException('planner.ai configuration is required')
        this.config = config
        this.transport = transport
        this.openAiLoginFactory = openAiLoginFactory ?: { store ->
            new OpenAiSiwcLogin(store, transport, config.subscription.loginTimeout)
        }
        this.xaiLoginFactory = xaiLoginFactory ?: { store -> new XaiDeviceLogin(store, transport) }
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
        if (config.subscription.authRoot == null) {
            return unsupported(provider, operation, remote, json, out,
                'planner.ai.subscription.auth_root is required for subscription authentication')
        }
        if (operation == 'ai-auth-login' && provider == 'codex' && flow == 'device') {
            return unsupported(provider, operation, remote, json, out, CODEX_DEVICE_GATE)
        }
        if (operation == 'ai-auth-login' && provider == 'grok' && flow == 'browser') {
            return unsupported(provider, operation, remote, json, out, XAI_BROWSER_GATE)
        }
        def store = new SubscriptionCredentialStore(config.subscription.authRoot, provider)
        SubscriptionProviderAdapter adapter = provider == 'codex' ? new OpenAiSiwcAdapter() : new XaiDeviceOAuthAdapter()
        def service = new SubscriptionCredentialService(store, adapter, transport)
        try {
            Map result
            if (operation == 'ai-auth-login') {
                (provider == 'codex' ? openAiLoginFactory.call(store) : xaiLoginFactory.call(store)).login(out)
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
