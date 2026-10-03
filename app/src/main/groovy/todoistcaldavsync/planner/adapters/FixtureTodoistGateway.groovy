package todoistcaldavsync.planner.adapters

import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper

/**
 * Fixture-backed read-only Todoist gateway. Never contacts remote systems.
 */
class FixtureTodoistGateway implements TodoistReadGateway {
    private final List<Map> tasks

    FixtureTodoistGateway(List<Map> tasks) {
        this.tasks = (tasks ?: []).collect { new LinkedHashMap(it) }
    }

    static FixtureTodoistGateway fromFile(File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Todoist fixture not found: ${file}")
        }
        def name = file.name.toLowerCase()
        def parsed
        if (name.endsWith('.json')) {
            parsed = new JsonSlurper().parse(file)
        } else {
            parsed = new YamlSlurper().parse(file)
        }
        def list
        if (parsed instanceof List) {
            list = parsed
        } else if (parsed instanceof Map && parsed.tasks instanceof List) {
            list = parsed.tasks
        } else if (parsed instanceof Map && parsed.items instanceof List) {
            list = parsed.items
        } else {
            throw new IllegalArgumentException("Todoist fixture must be a list or {tasks|items: [...]}")
        }
        return new FixtureTodoistGateway(list as List)
    }

    @Override
    List<Map> fetchTasks() {
        tasks.collect { new LinkedHashMap(it) }
    }
}
