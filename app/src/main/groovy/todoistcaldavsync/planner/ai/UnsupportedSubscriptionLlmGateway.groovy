package todoistcaldavsync.planner.ai

/** Fail-closed adapter retained for the xAI client-registration/live compatibility gate. */
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
            "${provider} is unsupported: ${provider == 'grok_build_subscription' ? AiSubscriptionAuthOperation.GROK_COMPATIBILITY_GATE : 'subscription adapter is unavailable'}"))
    }
}
