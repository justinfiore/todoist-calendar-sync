package todoistcaldavsync.planner.messaging

import todoistcaldavsync.planner.domain.Message

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/** Hermetic daemon/test surface. */
final class InMemoryMessagingSurface implements MessagingSurface {
    final List<Message> proposals = new CopyOnWriteArrayList<>()
    final List<Map> replies = new CopyOnWriteArrayList<>()
    final List<Map> statuses = new CopyOnWriteArrayList<>()
    private volatile Consumer<MessagingEvent> handler
    private volatile boolean connected
    private long sequence

    @Override
    void start(Consumer<MessagingEvent> handler) {
        if (handler == null) throw new IllegalArgumentException('handler is required')
        this.handler = handler
        this.connected = true
    }

    void emit(MessagingEvent event) {
        if (!connected || handler == null) throw new IllegalStateException('surface is not started')
        handler.accept(event)
    }

    @Override
    synchronized PublishedMessage publishProposal(Message message) {
        proposals << message
        String ts = "1000.${String.format('%06d', ++sequence)}"
        new PublishedMessage(channelId: message.destination, messageTs: ts, threadTs: ts)
    }

    @Override
    synchronized PublishedMessage reply(String channelId, String threadTs, String text, String idempotencyKey) {
        String ts = "1000.${String.format('%06d', ++sequence)}"
        replies << [channelId: channelId, threadTs: threadTs, text: text, idempotencyKey: idempotencyKey, messageTs: ts]
        new PublishedMessage(channelId: channelId, messageTs: ts, threadTs: threadTs)
    }

    @Override
    void setWorkingStatus(String channelId, String threadTs, String status, List<String> loadingMessages) {
        statuses << [channelId: channelId, threadTs: threadTs, status: status, loadingMessages: loadingMessages ?: []]
    }

    @Override
    void clearWorkingStatus(String channelId, String threadTs) {
        statuses << [channelId: channelId, threadTs: threadTs, status: '', loadingMessages: []]
    }

    @Override boolean isConnected() { connected }
    @Override void close() { connected = false; handler = null }
}
