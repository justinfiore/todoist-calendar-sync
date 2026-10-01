package todoistcaldavsync.planner.policy

import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.EventRole

import java.time.Instant

class EventClassifierSpec extends Specification {

    PlannerConfig config = PlannerConfig.fromMap(planner: [
        mode        : 'preview',
        availability: [
            working_windows: [weekday: ['09:00-17:00']],
            calendars      : [
                [calendar: 'Work', default_role: 'hard_blocker'],
                [calendar: 'Family', default_role: 'soft_blocker'],
                [calendar: 'Bob', default_role: 'informational'],
                [calendar: 'Todoist Planned', default_role: 'managed_output']
            ],
            event_rules    : [
                [
                    name                 : 'Justin drives',
                    calendar_regex       : '^Bob$',
                    title_regex          : '(?i)justin.*drives',
                    role                 : 'hard_blocker',
                    buffer_before_minutes: 15,
                    buffer_after_minutes : 20
                ],
                [
                    name          : 'Family pickup text',
                    calendar_regex: '^Family$',
                    text_regex    : '(?is)pickup',
                    role          : 'soft_blocker'
                ],
                [
                    name          : 'Bob default informational rule',
                    calendar_regex: '^Bob$',
                    role          : 'informational'
                ]
            ],
            unknown_calendar_fallback: 'informational'
        ]
    ])

    EventClassifier classifier = new EventClassifier(config)

    private CalendarEvent ev(Map args) {
        CalendarEvent.builder()
            .id(args.id ?: 'e')
            .title(args.title ?: '')
            .description(args.description ?: '')
            .calendarName(args.calendar)
            .start(Instant.parse(args.start ?: '2026-08-06T10:00:00Z'))
            .end(Instant.parse(args.end ?: '2026-08-06T11:00:00Z'))
            .build()
    }

    def "first ordered explicit event rule wins over later rules and calendar default"() {
        when:
        def result = classifier.classify(ev(id: '1', calendar: 'Bob', title: 'Justin drives Bob to practice'))

        then:
        result.role == EventRole.HARD_BLOCKER
        result.matchedRuleName == 'Justin drives'
        result.bufferBeforeMinutes == 15
        result.bufferAfterMinutes == 20
        result.classificationReason.contains('Justin drives')
    }

    def "calendar default applies when no event rule matches"() {
        when:
        def work = classifier.classify(ev(id: '2', calendar: 'Work', title: 'Standup'))
        def planned = classifier.classify(ev(id: '3', calendar: 'Todoist Planned', title: 'Block'))

        then:
        work.role == EventRole.HARD_BLOCKER
        work.matchedRuleName == 'calendar_default:Work'
        planned.role == EventRole.MANAGED_OUTPUT
        planned.matchedRuleName == 'calendar_default:Todoist Planned'
    }

    def "title regex and description/text regex match"() {
        when:
        def byTitle = classifier.classify(ev(id: '4', calendar: 'Bob', title: 'JUSTIN DRIVES kids'))
        def byText = classifier.classify(ev(
            id: '5', calendar: 'Family', title: 'School', description: 'Please handle pickup at 3pm'
        ))

        then:
        byTitle.role == EventRole.HARD_BLOCKER
        byText.role == EventRole.SOFT_BLOCKER
        byText.matchedRuleName == 'Family pickup text'
    }

    def "unknown calendar is diagnostic fallback not silent free"() {
        when:
        def result = classifier.classify(ev(id: '6', calendar: 'Random External', title: 'Meetup'))
        def explanations = classifier.explanationsFor([result])

        then:
        result.role == EventRole.INFORMATIONAL
        result.unknownCalendar
        result.matchedRuleName == 'unknown_calendar_fallback'
        result.classificationReason.toLowerCase().contains('unknown')
        explanations.any { it.code == 'unknown_calendar' }
    }

    def "Bob event without Justin title uses later informational rule not Work default"() {
        when:
        def result = classifier.classify(ev(id: '7', calendar: 'Bob', title: 'Soccer game'))

        then:
        result.role == EventRole.INFORMATIONAL
        result.matchedRuleName == 'Bob default informational rule'
    }

    def "buffers are zero for calendar-default classification"() {
        when:
        def result = classifier.classify(ev(id: '8', calendar: 'Family', title: 'Dinner', description: 'no match keywords'))

        then:
        result.role == EventRole.SOFT_BLOCKER
        result.bufferBeforeMinutes == 0
        result.bufferAfterMinutes == 0
    }
}
