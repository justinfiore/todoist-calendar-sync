package todoistcaldavsync.planner.ai

/** Fail-closed adapter retained until a provider passes every compatibility/live gate. */
final class UnsupportedSubscriptionLlmGateway implements LlmGateway {
    private final String provider

    UnsupportedSubscriptionLlmGateway(String provider) {
        if (!(provider in ['codex_subscription', 'grok_build_subscription'])) {
            throw new IllegalArgumentException('unsupported subscription provider')
        }
        this.provider = provider
    }

    @Override
    LlmGatewayResult complete(LlmRequest request) {
        LlmGatewayResult.failure(new LlmError(LlmErrorClass.COMPATIBILITY,
            "${provider} is unsupported: ${AiSubscriptionAuthOperation.COMPATIBILITY_GATE}"))
    }
}
