package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.DeliveryReceipt
import todoistcaldavsync.planner.domain.Message

/**
 * Provider-neutral messaging boundary. Isolated from planner core.
 * Implementations must not sleep on retries; callers own scheduling.
 */
interface MessagingGateway {
    /**
     * Send a message. Must record accurate failures (never false delivered).
     * Idempotency is caller/ledger responsibility; adapters should still honor
     * message.idempotencyKey when the provider supports it.
     */
    DeliveryReceipt send(Message message)
}

/**
 * Narrow write marker (mirrors other gateway splits).
 */
interface MessagingWriteGateway extends MessagingGateway {
    // marker
}
