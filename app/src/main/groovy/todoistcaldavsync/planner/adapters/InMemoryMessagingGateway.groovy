package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.DeliveryReceipt
import todoistcaldavsync.planner.domain.Message

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * Test fake messaging gateway. Captures sends; optional fail-on-Nth-call.
 */
class InMemoryMessagingGateway implements MessagingWriteGateway {
    final List<Message> sent = new CopyOnWriteArrayList<>()
    final List<DeliveryReceipt> receipts = new CopyOnWriteArrayList<>()
    private final AtomicInteger callCount = new AtomicInteger(0)
    private Integer failOnCall
    private String failClassification = 'TRANSPORT'
    private String failMessage = 'injected failure'
    private final Supplier<Instant> clock
    private String channelId = 'C_TEST'
    private boolean deliver = true

    InMemoryMessagingGateway(Supplier<Instant> clock = { Instant.now() }) {
        this.clock = clock ?: ({ Instant.now() } as Supplier)
    }

    void failOnCall(int n, String classification = 'TRANSPORT', String message = 'injected failure') {
        this.failOnCall = n
        this.failClassification = classification
        this.failMessage = message
    }

    void clearFailure() {
        this.failOnCall = null
    }

    void setDeliver(boolean v) {
        this.deliver = v
    }

    void setChannelId(String v) {
        this.channelId = v
    }

    int getCallCount() {
        callCount.get()
    }

    @Override
    DeliveryReceipt send(Message message) {
        if (message == null) {
            throw new IllegalArgumentException('message is required')
        }
        int n = callCount.incrementAndGet()
        Instant now = clock.get()
        sent << message
        if (failOnCall != null && n == failOnCall) {
            DeliveryReceipt r = DeliveryReceipt.builder()
                .id("dlv-fail-${n}")
                .idempotencyKey(message.idempotencyKey)
                .kind(message.kind)
                .destination(message.destination)
                .planId(message.planId)
                .planVersion(message.planVersion)
                .planHash(message.planHash)
                .proposalId(message.proposalId)
                .status('FAILED')
                .attemptedAt(now)
                .completedAt(now)
                .errorClassification(failClassification)
                .errorMessage(failMessage)
                .build()
            receipts << r
            return r
        }
        if (!deliver) {
            DeliveryReceipt r = DeliveryReceipt.builder()
                .id("dlv-fail-${n}")
                .idempotencyKey(message.idempotencyKey)
                .kind(message.kind)
                .destination(message.destination)
                .planId(message.planId)
                .planVersion(message.planVersion)
                .planHash(message.planHash)
                .proposalId(message.proposalId)
                .status('FAILED')
                .attemptedAt(now)
                .completedAt(now)
                .errorClassification('TRANSPORT')
                .errorMessage('delivery disabled')
                .build()
            receipts << r
            return r
        }
        DeliveryReceipt r = DeliveryReceipt.builder()
            .id("dlv-ok-${n}")
            .idempotencyKey(message.idempotencyKey)
            .kind(message.kind)
            .destination(message.destination)
            .planId(message.planId)
            .planVersion(message.planVersion)
            .planHash(message.planHash)
            .proposalId(message.proposalId)
            .status('DELIVERED')
            .providerMessageId("ts-${n}.000")
            .threadId("ts-${n}.000")
            .channelId(channelId)
            .attemptedAt(now)
            .completedAt(now)
            .metadata([call: n])
            .build()
        receipts << r
        return r
    }
}
