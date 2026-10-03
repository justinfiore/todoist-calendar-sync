package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import todoistcaldavsync.planner.config.PlannerConfig

/**
 * Safe launcher boundary for subscription authentication profiles.
 *
 * Provider login remains vendor-owned, but neither provider currently exposes a
 * stable, terms-cleared, tool-free subscription inference protocol. Until that
 * release gate passes, every operation fails before a process, credential file,
 * keyring, network client, or planner service is constructed.
 */
final class AiSubscriptionAuthOperation {
    static final String COMPATIBILITY_GATE =
        'no stable tool-free subscription inference protocol has been proven'

    private final PlannerConfig.AiConfig config

    AiSubscriptionAuthOperation(PlannerConfig.AiConfig config) {
        if (config == null) throw new IllegalArgumentException('planner.ai configuration is required')
        this.config = config
    }

    int execute(String operation, String provider, String flow, boolean remote,
                boolean json, Appendable out, Appendable err) {
        if (!(provider in ['codex', 'grok'])) {
            throw new IllegalArgumentException('--ai-provider must be one of: codex, grok')
        }
        if (!(flow in ['device', 'browser'])) {
            throw new IllegalArgumentException('--auth-flow must be one of: device, browser')
        }
        if (operation != 'ai-auth-status' && remote) {
            throw new IllegalArgumentException('--remote is allowed only with ai-auth-status')
        }
        Map result = [
            provider: provider,
            operation: operation,
            state: 'unsupported',
            localCredential: 'not_inspected',
            remoteEntitlement: remote ? 'not_checked' : 'not_requested',
            adapterRevision: null,
            reason: COMPATIBILITY_GATE
        ]
        if (json) {
            out.append(JsonOutput.toJson(result)).append('\n')
        } else {
            out.append("${provider}: unsupported (${COMPATIBILITY_GATE})\n")
        }
        return 3
    }
}
