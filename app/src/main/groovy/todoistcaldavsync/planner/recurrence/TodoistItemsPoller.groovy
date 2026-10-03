package todoistcaldavsync.planner.recurrence

import todoistcaldavsync.planner.adapters.TodoistLifecycleGateway
import todoistcaldavsync.planner.adapters.TodoistRestGateway
import todoistcaldavsync.planner.adapters.TodoistSyncPage

import java.time.Duration

/** Five-minute crash-safe incremental delivery loop; lifecycle processing is injected. */
final class TodoistItemsPoller {
    static final Duration POLL_INTERVAL = Duration.ofMinutes(5)

    private final TodoistLifecycleGateway gateway
    private final ItemsSyncStateStore store
    private final Closure<Void> processor

    TodoistItemsPoller(TodoistLifecycleGateway gateway, ItemsSyncStateStore store,
                       Closure<Void> processor) {
        if (gateway == null || store == null || processor == null) {
            throw new IllegalArgumentException('gateway, store, and processor are required')
        }
        this.gateway = gateway; this.store = store; this.processor = processor
    }

    PollResult poll() {
        ItemsSyncState current
        try { current = store.load() }
        catch (IllegalStateException ignored) { store.reset(); current = ItemsSyncState.bootstrap() }
        if (current.pendingItems) return drain(current)

        TodoistSyncPage page
        try {
            page = gateway.syncItems(current.syncToken)
        } catch (TodoistRestGateway.TodoistGatewayException e) {
            if (e.classification == 'HTTP_STATUS' && e.statusCode == 400 && current.syncToken != '*') {
                store.reset()
                return new PollResult(false, true, 0, 'cursor_reset')
            }
            if (e.classification == 'AUTHENTICATION' || e.statusCode in [401, 403]) throw e
            return new PollResult(false, false, 0, 'transient_backoff')
        }
        store.checkpoint(page)
        PollResult drained = drain(store.load())
        if (page.fullSync) {
            TodoistSyncPage followup = fetch(page.syncToken)
            if (followup == null) return drained
            store.checkpoint(followup)
            PollResult incremental = drain(store.load())
            return new PollResult(incremental.success, false,
                drained.processed + incremental.processed,
                incremental.processed == 0 ? 'bootstrap_caught_up' : 'bootstrap_and_incremental')
        }
        drained
    }

    private TodoistSyncPage fetch(String syncToken) {
        try {
            return gateway.syncItems(syncToken)
        } catch (TodoistRestGateway.TodoistGatewayException e) {
            if (e.classification == 'AUTHENTICATION' || e.statusCode in [401, 403]) throw e
            return null
        }
    }

    private PollResult drain(ItemsSyncState state) {
        int processed = 0
        for (Map item : state.pendingItems) {
            processor.call(Collections.unmodifiableMap(new LinkedHashMap(item)))
            String id = (item.id ?: item.task_id)?.toString()
            if (!id) throw new IllegalStateException('Todoist item delta has no identity')
            store.acknowledge(id)
            processed++
        }
        new PollResult(true, state.syncToken == '*', processed, processed == 0 ? 'empty' : 'processed')
    }
}

final class PollResult {
    final boolean success
    final boolean bootstrapRequired
    final int processed
    final String status
    PollResult(boolean success, boolean bootstrapRequired, int processed, String status) {
        this.success = success; this.bootstrapRequired = bootstrapRequired
        this.processed = processed; this.status = status
    }
}
