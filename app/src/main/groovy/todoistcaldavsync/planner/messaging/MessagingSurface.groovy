package todoistcaldavsync.planner.messaging

import todoistcaldavsync.planner.domain.Message

import java.util.function.Consumer

/** Bidirectional provider-neutral messaging surface used by the daemon. */
interface MessagingSurface extends AutoCloseable {
    void start(Consumer<MessagingEvent> handler)
    PublishedMessage publishProposal(Message message)
    PublishedMessage reply(String channelId, String threadTs, String text, String idempotencyKey)
    void setWorkingStatus(String channelId, String threadTs, String status, List<String> loadingMessages)
    void clearWorkingStatus(String channelId, String threadTs)
    boolean isConnected()
    @Override void close()
}
