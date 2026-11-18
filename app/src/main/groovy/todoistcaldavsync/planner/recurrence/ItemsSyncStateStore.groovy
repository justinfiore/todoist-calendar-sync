package todoistcaldavsync.planner.recurrence

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.adapters.TodoistSyncPage

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Atomic dedicated items cursor plus crash-replay inbox. */
final class ItemsSyncStateStore {
    static final int SCHEMA_VERSION = 1
    private final Path path

    ItemsSyncStateStore(Path directory) {
        if (directory == null) throw new IllegalArgumentException('directory is required')
        this.path = directory.resolve('todoist-items-sync.json')
    }

    ItemsSyncState load() { synchronized (ItemsSyncStateStore) { readState() } }

    private ItemsSyncState readState() {
        if (!Files.exists(path)) return ItemsSyncState.bootstrap()
        try {
            Object parsed = new JsonSlurper().parseText(Files.readString(path, StandardCharsets.UTF_8))
            if (!(parsed instanceof Map) || parsed.schema_version != SCHEMA_VERSION ||
                !parsed.sync_token || !(parsed.pending_items instanceof List)) {
                throw new IllegalStateException('corrupt Todoist items Sync state')
            }
            new ItemsSyncState(parsed.sync_token.toString(), (parsed.pending_items as List).collect {
                if (!(it instanceof Map)) throw new IllegalStateException('corrupt Todoist items Sync inbox')
                new LinkedHashMap(it as Map)
            }, parsed.full_sync_date_utc?.toString())
        } catch (IllegalStateException e) { throw e }
        catch (Exception e) { throw new IllegalStateException('corrupt Todoist items Sync state', e) }
    }

    void checkpoint(TodoistSyncPage page) { synchronized (ItemsSyncStateStore) {
        if (page == null) throw new IllegalArgumentException('Sync page is required')
        ItemsSyncState current = readState()
        write(new ItemsSyncState(page.syncToken, current.pendingItems + page.items, page.fullSyncDateUtc))
    } }

    void enqueue(Map item) { synchronized (ItemsSyncStateStore) {
        if (!(item instanceof Map) || !(item.id ?: item.task_id)) {
            throw new IllegalArgumentException('item id is required')
        }
        ItemsSyncState current = readState()
        List<Map> pending = new ArrayList<>(current.pendingItems)
        pending << new LinkedHashMap(item)
        write(new ItemsSyncState(current.syncToken, pending, current.fullSyncDateUtc))
    } }

    void acknowledge(String taskId) { synchronized (ItemsSyncStateStore) {
        ItemsSyncState current = readState()
        List<Map> remaining = new ArrayList<>(current.pendingItems)
        int index = remaining.findIndexOf { (it.id ?: it.task_id)?.toString() == taskId }
        if (index >= 0) remaining.remove(index)
        write(new ItemsSyncState(current.syncToken, remaining, current.fullSyncDateUtc))
    } }

    void reset() { synchronized (ItemsSyncStateStore) { Files.deleteIfExists(path) } }

    private void write(ItemsSyncState state) {
        Files.createDirectories(path.parent)
        Path temporary = Files.createTempFile(path.parent, '.todoist-items-sync-', '.tmp')
        try {
            Map root = [schema_version: SCHEMA_VERSION, sync_token: state.syncToken,
                        full_sync_date_utc: state.fullSyncDateUtc, pending_items: state.pendingItems]
            Files.writeString(temporary, JsonOutput.prettyPrint(JsonOutput.toJson(root)), StandardCharsets.UTF_8)
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}

final class ItemsSyncState {
    final String syncToken
    final List<Map> pendingItems
    final String fullSyncDateUtc
    ItemsSyncState(String syncToken, List<Map> pendingItems, String fullSyncDateUtc) {
        this.syncToken = syncToken
        this.pendingItems = Collections.unmodifiableList((pendingItems ?: []).collect {
            Collections.unmodifiableMap(new LinkedHashMap(it))
        })
        this.fullSyncDateUtc = fullSyncDateUtc
    }
    static ItemsSyncState bootstrap() { new ItemsSyncState('*', [], null) }
}
