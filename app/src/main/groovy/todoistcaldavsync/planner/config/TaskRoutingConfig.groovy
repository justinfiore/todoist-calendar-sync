package todoistcaldavsync.planner.config

import groovy.json.JsonOutput
import todoistcaldavsync.TaskCalendarRouting
import todoistcaldavsync.planner.domain.Task

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Explicit opt-in routing scope, independent of optimizer and lifecycle eligibility. */
final class TaskRoutingConfig {
    final List<String> labelsToInclude
    final List<String> projectsToInclude
    final List<Map> rules
    final String fingerprint

    private TaskRoutingConfig(List<String> labels, List<String> projects, List<Map> rules) {
        this.labelsToInclude = Collections.unmodifiableList(labels)
        this.projectsToInclude = Collections.unmodifiableList(projects)
        this.rules = Collections.unmodifiableList(rules.collect { Collections.unmodifiableMap(it) })
        String canonical = JsonOutput.toJson([mode: 'legacy_rules', labelsToInclude: labels,
            projectsToInclude: projects, rules: rules])
        this.fingerprint = MessageDigest.getInstance('SHA-256')
            .digest(canonical.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    static TaskRoutingConfig fromMap(Object raw) {
        if (raw == null) return null
        if (!(raw instanceof Map) || raw.mode != 'legacy_rules') {
            throw new IllegalArgumentException('planner.routing.mode must be legacy_rules')
        }
        if (raw.keySet().any { !(it in ['mode', 'labelsToInclude', 'projectsToInclude', 'rules']) }) {
            throw new IllegalArgumentException('planner.routing contains unsupported fields')
        }
        List<String> labels = names(raw.labelsToInclude, 'labelsToInclude')
        List<String> projects = names(raw.projectsToInclude, 'projectsToInclude')
        if (!labels && !projects) throw new IllegalArgumentException('planner.routing requires explicit inclusion labels or projects')
        if (!(raw.rules instanceof Collection) || !raw.rules) {
            throw new IllegalArgumentException('planner.routing.rules must be a non-empty list')
        }
        List<Map> rules = raw.rules.collect { Object row ->
            if (!(row instanceof Map) || row.keySet().any { !(it in ['rule', 'calendarName']) } ||
                !(row.calendarName instanceof String) || !row.calendarName.trim()) {
                throw new IllegalArgumentException('planner.routing.rules requires rule and calendarName')
            }
            if (!(row.rule instanceof String)) throw new IllegalArgumentException('routing rule must be a string')
            TaskCalendarRouting.validateExpression(row.rule)
            [rule: row.rule, calendarName: row.calendarName]
        }
        new TaskRoutingConfig(labels, projects, rules)
    }

    private static List<String> names(Object raw, String field) {
        if (raw == null) return []
        if (!(raw instanceof Collection) || raw.any { !(it instanceof String) || !it.trim() }) {
            throw new IllegalArgumentException("planner.routing.${field} must be a list of nonblank names")
        }
        new ArrayList<String>(raw as Collection)
    }

    Set<String> getDestinations() {
        Collections.unmodifiableSet(new LinkedHashSet<String>(rules*.calendarName))
    }

    boolean includes(Task task) {
        TaskCalendarRouting.includes(task.labels, task.projectName, labelsToInclude, projectsToInclude)
    }

    String destination(Task task) {
        includes(task) ? TaskCalendarRouting.destination(task.labels, task.projectName, rules) : null
    }
}
