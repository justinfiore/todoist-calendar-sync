package todoistcaldavsync

import spock.lang.Specification
import spock.lang.Unroll
import groovy.yaml.YamlSlurper
import todoistcaldavsync.planner.writeback.CalendarWritebackStore

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

    def "renders the Todoist task deeplink in the event description"() {
        expect:
        syncer.renderDescription([project_name: 'Home', label_names: ['focus'], priority: 4, id: 'task-1'])
            .contains('Todoist: https://app.todoist.com/app/task/task-1')
    }

    @Unroll
    def "legacy calendar writeback enabled is #expected when section is #label"() {
        given:
        File configFile = File.createTempFile('todoist-calendar-writeback-config-', '.yaml')
        File stateFile = File.createTempFile('todoist-calendar-writeback-config-', '.state')
        stateFile.delete()
        configFile.text = """
            dryRun: true
            ${fragment}
            todoist:
              labelsToInclude: [focus]
            caldav:
              calendars: []
              rules: []
        """.stripIndent()

        when:
        def configured = new TodoistCalDavSync(configFile, stateFile)

        then:
        configured.calendarWritebackEnabled == expected

        cleanup:
        configured?.destroyHttpClients()

        where:
        label     | fragment                                      || expected
        'omitted' | ''                                            || false
        'false'   | 'calendarWriteback:\n              enabled: false' || false
        'true'    | 'calendarWriteback:\n              enabled: true'  || true
    }

    def "legacy calendar writeback rejects a non-boolean before client setup"() {
        given:
        File configFile = File.createTempFile('todoist-calendar-writeback-invalid-', '.yaml')
        File stateFile = File.createTempFile('todoist-calendar-writeback-invalid-', '.state')
        configFile.text = '''
            calendarWriteback:
              enabled: "true"
            todoist:
              labelsToInclude: [focus]
            caldav:
              calendars: []
              rules: []
        '''.stripIndent()

        when:
        new TodoistCalDavSync(configFile, stateFile)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == 'calendarWriteback.enabled must be a boolean'
    }

    def "legacy example is default-off and documentation names the separate cursor store"() {
        given:
        File root = new File('..').canonicalFile
        if (!new File(root, 'README.md').exists()) root = new File('.').canonicalFile
        Map example = new YamlSlurper().parse(new File(root, 'conf/todoist-calendar-sync.conf.example.yaml')) as Map
        String readme = new File(root, 'README.md').getText('UTF-8')

        expect:
        example.calendarWriteback.enabled == false
        readme.contains("separate `${CalendarWritebackStore.FILENAME}` file")
        CalendarWritebackStore.FILENAME == 'calendar-writeback.json'
    }
}
