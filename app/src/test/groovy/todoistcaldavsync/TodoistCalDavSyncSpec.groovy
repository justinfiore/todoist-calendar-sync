package todoistcaldavsync

import spock.lang.Specification

class TodoistCalDavSyncSpec extends Specification {

    TodoistCalDavSync syncer

    def setup() {
        File configFile = File.createTempFile('todoist-calendar-sync-', '.yaml')
        File stateFile = File.createTempFile('todoist-calendar-sync-', '.state')
        configFile.text = '''
            dryRun: true
            todoist:
              labelsToInclude: [focus]
            caldav:
              calendars: []
              rules:
                - calendarName: Work
                  rule: "work AND NOT someday"
                - calendarName: Projects
                  rule: "p:Home"
        '''.stripIndent()
        stateFile.delete()
        syncer = new TodoistCalDavSync(configFile, stateFile)
    }

    def "filters tasks when either an included label or project matches"() {
        given:
        def items = [
            [id: 'label-match', labels: ['focus'], project_name: 'Elsewhere'],
            [id: 'project-match', labels: ['other'], project_name: 'Home'],
            [id: 'excluded', labels: ['other'], project_name: 'Elsewhere']
        ]

        when:
        def included = syncer.filterItemsForInclusionInCalendar(items, ['focus'], ['Home'])

        then:
        included*.id == ['label-match', 'project-match']
    }

    def "routes to the first matching rule and supports label and project negation"() {
        expect:
        syncer.identifyCalendarName(item) == expectedCalendar

        where:
        item                                                                  || expectedCalendar
        [content: 'work task', label_names: ['work'], project_name: 'Elsewhere'] || 'Work'
        [content: 'deferred work', label_names: ['work', 'someday'], project_name: 'Elsewhere'] || null
        [content: 'home task', label_names: ['work'], project_name: 'Home']   || 'Work'
        [content: 'home only', label_names: [], project_name: 'Home']         || 'Projects'
    }

    def "normalizes Todoist metadata and removes tasks without due dates"() {
        given:
        def items = [
            [id: 'dated', labels: ['focus'], project_id: 'p1', due: [date: '2026-08-06']],
            [id: 'missing-due', labels: ['focus'], project_id: 'p2'],
            [id: 'null-due-date', labels: ['focus'], project_id: 'p3', due: [date: null]]
        ]

        when:
        def normalized = syncer.resolveProjectName(syncer.resolveLabelNames(items), [p1: 'Home', p2: 'Work', p3: 'Errands'])
        def dated = syncer.removeItemsWithNoDueDates(normalized)

        then:
        normalized[0].label_names == ['focus']
        normalized[0].project_name == 'Home'
        dated*.id == ['dated']
    }

    def "generates stable calendar event IDs"() {
        expect:
        syncer.generateUidFromItem(42, [id: 'abc']) == '6gp2qob2cc'
    }

    def "maps Todoist priority to iCalendar priority"() {
        expect:
        syncer.todoistToICalPriority(todoistPriority) == expectedPriority

        where:
        todoistPriority || expectedPriority
        4               || 1
        3               || 2
        2               || 3
        1               || 4
        0               || 5
    }
}
