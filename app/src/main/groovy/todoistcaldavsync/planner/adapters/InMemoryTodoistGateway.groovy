package todoistcaldavsync.planner.adapters

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic in-memory Todoist read/write fake for Phase 3 tests.
 * Captures update request shapes so deadline preservation can be asserted.
 * Never contacts remote systems.
 */
class InMemoryTodoistGateway implements TodoistReadGateway, TodoistWriteGateway {
    private final List<Map> tasks
    final List<Map> dueUpdates = new CopyOnWriteArrayList<>()
    final List<Map> deadlineUpdates = new CopyOnWriteArrayList<>()

    /** Optional: throw on Nth due update (1-based). */
    Integer failDueOnCall
    /** Optional: throw message for due failures. */
    String failDueMessage = 'simulated Todoist due failure'
    private int dueCallCount = 0

    InMemoryTodoistGateway(List<Map> tasks = []) {
        this.tasks = (tasks ?: []).collect { deepCopy(it) }
    }

    @Override
    List<Map> fetchTasks() {
        tasks.collect { deepCopy(it) }
    }

    Map getTask(String taskId) {
        tasks.find { it.id?.toString() == taskId }
    }

    /** Live due value as stored (string or null). */
    def dueOf(String taskId) {
        def t = getTask(taskId)
        if (t == null) {
            return null
        }
        def due = t.due
        if (due instanceof Map) {
            return due.date ?: due.datetime ?: due.string
        }
        return t.due_date ?: t.dueDate ?: t.due
    }

    def deadlineOf(String taskId) {
        def t = getTask(taskId)
        if (t == null) {
            return null
        }
        def dl = t.deadline
        if (dl instanceof Map) {
            return dl.date ?: dl.datetime ?: dl
        }
        return t.deadline
    }

    @Override
    void updateTaskDue(String taskId, String dueDateTimeIso) {
        dueCallCount++
        Map request = [
            taskId         : taskId,
            dueDateTimeIso : dueDateTimeIso,
            // Capture full shape: deadline must be absent from this request
            fields         : [due: dueDateTimeIso],
            hasDeadlineField: false
        ]
        dueUpdates << request
        if (failDueOnCall != null && dueCallCount == failDueOnCall) {
            throw new RuntimeException(failDueMessage)
        }
        def t = tasks.find { it.id?.toString() == taskId }
        if (t == null) {
            t = [id: taskId]
            tasks << t
        }
        // Update due only — never touch deadline
        if (t.due instanceof Map) {
            def due = new LinkedHashMap(t.due as Map)
            due.date = dueDateTimeIso
            due.datetime = dueDateTimeIso
            t.due = due
        } else {
            t.due = [date: dueDateTimeIso, datetime: dueDateTimeIso, string: dueDateTimeIso]
        }
        t.due_date = dueDateTimeIso
    }

    @Override
    void updateTaskDeadline(String taskId, String deadlineIso) {
        Map request = [
            taskId      : taskId,
            deadlineIso : deadlineIso,
            fields      : [deadline: deadlineIso]
        ]
        deadlineUpdates << request
        def t = tasks.find { it.id?.toString() == taskId }
        if (t == null) {
            t = [id: taskId]
            tasks << t
        }
        t.deadline = [date: deadlineIso]
    }

    void resetCounters() {
        dueCallCount = 0
        failDueOnCall = null
    }

    int getDueCallCount() {
        dueCallCount
    }

    private static Map deepCopy(Map m) {
        def copy = new LinkedHashMap()
        m.each { k, v ->
            if (v instanceof Map) {
                copy[k] = deepCopy(v as Map)
            } else if (v instanceof List) {
                copy[k] = (v as List).collect { it instanceof Map ? deepCopy(it as Map) : it }
            } else {
                copy[k] = v
            }
        }
        return copy
    }
}
