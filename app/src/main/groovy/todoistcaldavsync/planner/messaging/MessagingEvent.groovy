package todoistcaldavsync.planner.messaging

/** Normalized inbound event. Provider callbacks acknowledge before enqueuing this value. */
final class MessagingEvent {
    final String eventId
    final String type
    final String actorId
    final String channelId
    final String messageTs
    final String threadTs
    final String text
    final boolean bot

    MessagingEvent(Map values = [:]) {
        this.eventId = values.eventId?.toString()
        this.type = values.type?.toString()
        this.actorId = values.actorId?.toString()
        this.channelId = values.channelId?.toString()
        this.messageTs = values.messageTs?.toString()
        this.threadTs = values.threadTs?.toString()
        this.text = values.text?.toString() ?: ''
        this.bot = values.bot == true
    }

    String rootThreadTs() { threadTs ?: messageTs }
    boolean isCommand() { type == 'command' || type == 'app_mention' }
}

final class PublishedMessage {
    final String channelId
    final String messageTs
    final String threadTs
    final String status
    final String error

    PublishedMessage(Map values = [:]) {
        this.channelId = values.channelId?.toString()
        this.messageTs = values.messageTs?.toString()
        this.threadTs = (values.threadTs ?: values.messageTs)?.toString()
        this.status = values.status?.toString() ?: 'DELIVERED'
        this.error = values.error?.toString()
    }

    boolean delivered() { status == 'DELIVERED' && channelId && messageTs }
}
