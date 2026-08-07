package todoistcaldavsync.planner.domain

import spock.lang.Specification

import java.time.Instant

class CalendarEventAndPlanSpec extends Specification {

    def "CalendarEvent validates end after start and is immutable via withClassification"() {
        given:
        def ev = CalendarEvent.builder()
            .id('e1')
            .title('Meeting')
            .calendarName('Work')
            .start(Instant.parse('2026-08-06T10:00:00Z'))
            .end(Instant.parse('2026-08-06T11:00:00Z'))
            .build()

        when:
        def classified = ev.withClassification(EventRole.HARD_BLOCKER, 'rule-a', 'because', 10, 5)

        then:
        classified.role == EventRole.HARD_BLOCKER
        classified.matchedRuleName == 'rule-a'
        classified.bufferBeforeMinutes == 10
        classified.bufferedStart() == Instant.parse('2026-08-06T09:50:00Z')
        classified.bufferedEnd() == Instant.parse('2026-08-06T11:05:00Z')
        ev.role == null
    }

    def "CalendarEvent rejects end not after start"() {
        when:
        CalendarEvent.builder()
            .id('e1')
            .title('X')
            .calendarName('Work')
            .start(Instant.parse('2026-08-06T10:00:00Z'))
            .end(Instant.parse('2026-08-06T10:00:00Z'))
            .build()

        then:
        thrown(IllegalArgumentException)
    }

    def "TimeSlot rejects non-positive duration"() {
        when:
        TimeSlot.builder()
            .start(Instant.parse('2026-08-06T10:00:00Z'))
            .end(Instant.parse('2026-08-06T09:00:00Z'))
            .build()

        then:
        thrown(IllegalArgumentException)
    }

    def "Plan, PlanChange, PlanningExplanation validate required fields and are immutable"() {
        given:
        def explanation = PlanningExplanation.of('code', 'msg', 'task', 't1', [k: 'v'])
        def change = PlanChange.builder().id('c1').type('add').reason('fit').taskId('t1').build()
        def plan = Plan.builder()
            .id('p1')
            .mode('preview')
            .explanations([explanation])
            .changes([change])
            .metrics([usable: 60])
            .build()

        expect:
        plan.explanations.size() == 1
        plan.changes[0].type == 'add'
        plan.metrics.usable == 60

        when:
        plan.explanations.add(explanation)

        then:
        thrown(UnsupportedOperationException)

        when:
        PlanningExplanation.builder().message('no code').build()

        then:
        thrown(IllegalArgumentException)
    }

    def "EventRole parses config values"() {
        expect:
        EventRole.fromConfig('hard_blocker') == EventRole.HARD_BLOCKER
        EventRole.fromConfig('SOFT_BLOCKER') == EventRole.SOFT_BLOCKER

        when:
        EventRole.fromConfig('nope')

        then:
        thrown(IllegalArgumentException)
    }
}
