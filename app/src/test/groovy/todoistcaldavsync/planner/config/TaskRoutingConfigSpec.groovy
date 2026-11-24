package todoistcaldavsync.planner.config

import spock.lang.Specification
import todoistcaldavsync.planner.domain.Task

class TaskRoutingConfigSpec extends Specification {
    private Map routing() {
        [mode: 'legacy_rules', labelsToInclude: ['focus'], projectsToInclude: ['ABC Company'], rules: [
            [rule: 'work AND NOT someday AND NOT p:Home', calendarName: 'Work'],
            [rule: 'p:ABC Company', calendarName: 'Projects'],
            [rule: 'personal', calendarName: 'Projects']
        ]]
    }

    def "projection inclusion and ordered destination preserve exact legacy predicates"() {
        given:
        def config = PlannerConfig.fromMap([routing: routing(),
            availability: [working_windows: [weekday: ['09:00-17:00']]]])
        Task task = Task.fromTodoistMap([id: '1', labels: labels, project_name: project],
            config.durationResolver)

        expect:
        config.routing.includes(task) == included
        config.routing.destination(task) == destination

        where:
        labels                         | project        || included | destination
        ['work']                       | 'ABC Company'  || true     | 'Work'
        ['work', 'someday']             | 'ABC Company'  || true     | 'Projects'
        []                             | 'ABC Company'  || true     | 'Projects'
        ['focus', 'work']               | 'Home'         || true     | null
        ['focus', 'Work']               | 'Elsewhere'    || true     | null
        ['focus', 'personal']           | 'Elsewhere'    || true     | 'Projects'
        ['work']                       | 'abc company'  || false    | null
        ['Focus', 'work']               | 'Elsewhere'    || false    | null
    }

    def "fingerprint binds rule order and inclusion but not map key order"() {
        given:
        Map original = routing()
        Map reordered = [rules: original.rules, projectsToInclude: original.projectsToInclude,
                         labelsToInclude: original.labelsToInclude, mode: original.mode]
        Map changed = routing()
        changed.rules = changed.rules.reverse()

        expect:
        TaskRoutingConfig.fromMap(original).fingerprint == TaskRoutingConfig.fromMap(reordered).fingerprint
        TaskRoutingConfig.fromMap(original).fingerprint != TaskRoutingConfig.fromMap(changed).fingerprint
    }

    def "invalid expressions fail rather than silently invent semantics"() {
        given:
        Map raw = routing()
        raw.rules[0].rule = expression

        when:
        TaskRoutingConfig.fromMap(raw)

        then:
        thrown(IllegalArgumentException)

        where:
        expression << ['', 'work OR home', 'AND work', 'work AND ', 'NOT ', 'p:', '(work)', 'work AND NOT NOT home']
    }

    def "routed and compatible output modes cannot be mixed"() {
        when:
        PlannerConfig.fromMap([routing: routing(), output_calendar: 'Work',
            availability: [working_windows: [weekday: ['09:00-17:00']]]])

        then:
        thrown(IllegalArgumentException)
    }
}
