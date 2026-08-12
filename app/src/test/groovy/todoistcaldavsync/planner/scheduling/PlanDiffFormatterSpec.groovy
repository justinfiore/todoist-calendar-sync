package todoistcaldavsync.planner.scheduling

import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.PlanningExplanation
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.UnscheduledTask

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class PlanDiffFormatterSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')

    def "Moved section renders approval-required status and reason from metadata"() {
        given:
        def prev = Instant.parse('2026-08-06T20:00:00Z')
        def neu = Instant.parse('2026-08-06T13:00:00Z')
        def plan = Plan.builder()
            .id('diff-approval')
            .createdAt(Instant.parse('2026-08-06T12:00:00Z'))
            .mode('preview')
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('b1')
                    .start(neu)
                    .end(neu.plusSeconds(1800))
                    .taskIds(['t-move'])
                    .title('Moved task')
                    .reason('score')
                    .build()
            ])
            .changes([
                PlanChange.builder()
                    .id('chg-1')
                    .type('move')
                    .taskId('t-move')
                    .previousStart(prev)
                    .previousEnd(prev.plusSeconds(1800))
                    .newStart(neu)
                    .newEnd(neu.plusSeconds(1800))
                    .reason('Best feasible slot')
                    .metadata([
                        approvalRequired: true,
                        approvalReason  : 'move_within_require_approval_horizon'
                    ])
                    .build()
            ])
            .build()

        when:
        def md = PlanDiffFormatter.toMarkdown(plan, zone)

        then:
        md.contains('## Moved')
        md.contains('t-move')
        md.contains('Approval required:')
        md.toLowerCase().contains('require-approval horizon') ||
            md.toLowerCase().contains('require approval')
    }

    def "weather-infeasible unscheduled-only renders rule evaluation and AM/PM timestamps without replacement"() {
        given:
        Instant issued = Instant.parse('2026-08-07T12:00:00Z')
        Instant retrieved = Instant.parse('2026-08-07T12:05:00Z')
        String humanIssued = DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a')
            .withLocale(Locale.US).format(issued.atZone(zone))
        String humanRetrieved = DateTimeFormatter.ofPattern('EEE MMM d, yyyy h:mm a')
            .withLocale(Locale.US).format(retrieved.atZone(zone))
        // 12:00Z / 12:05Z on Aug 7 are 8:00 / 8:05 AM Eastern (EDT)
        assert humanIssued.contains('AM')
        assert humanRetrieved.contains('AM')

        Map weather = [
            result              : 'INFEASIBLE',
            ruleId              : 'deck-paint',
            ruleName            : 'deck-paint',
            provider            : 'fixture',
            forecastIssuedAt    : issued.toString(),
            forecastRetrievedAt : retrieved.toString(),
            latitude            : 40.71d,
            longitude           : -74.01d,
            observedField       : 'precipitation_probability',
            observedValue       : 75d,
            threshold           : 15d
        ]
        def deck = Task.builder()
            .id('deck')
            .content('Paint the Deck')
            .labels(['outdoor', 'deck'])
            .priority(3)
            .effectiveDuration(Duration.ofMinutes(60))
            .durationSource('test')
            .build()
        def plan = Plan.builder()
            .id('diff-unscheduled-weather')
            .createdAt(Instant.parse('2026-08-07T14:00:00Z'))
            .mode('preview')
            .tasks([deck])
            .unscheduled([
                new UnscheduledTask(
                    deck,
                    'Precipitation probability (75%) exceeded the task rule maximum (15%).',
                    'weather_infeasible',
                    [weather: weather]
                )
            ])
            .explanations([
                PlanningExplanation.of(
                    'weather_unscheduled',
                    'Precipitation probability (75%) exceeded the task rule maximum (15%).',
                    'task', 'deck', weather
                )
            ])
            .build()

        when:
        def md = PlanDiffFormatter.toMarkdown(plan, zone)
        def jsonText = PlanDiffFormatter.toJson(plan)
        def json = new JsonSlurper().parseText(jsonText)

        then: 'Markdown: exact rule + evaluation + both 12-hour timestamps + provider/location'
        md.contains('## Unscheduled')
        md.contains('Paint the Deck')
        md.contains('Code: weather_infeasible')
        md.contains('Weather rule: deck-paint')
        md.contains('Weather evaluation: INFEASIBLE')
        md.contains("Forecast issued: ${humanIssued}")
        md.contains("Forecast retrieved: ${humanRetrieved}")
        md.contains('Weather provider: fixture')
        md.contains('Weather location: 40.71, -74.01')
        !md.contains('Indoor replacement')
        !md.contains('Replaced by indoor')

        and: 'no duplicate noisy weather block under explanations for same data'
        md.readLines().count { it.contains('Weather rule: deck-paint') } == 1
        md.readLines().count { it.contains("Forecast issued: ${humanIssued}") } == 1
        md.readLines().count { it.contains("Forecast retrieved: ${humanRetrieved}") } == 1

        and: 'JSON exact ISO details on unscheduled metadata'
        def u = json.unscheduled.find { it.task.id == 'deck' }
        u != null
        u.code == 'weather_infeasible'
        u.metadata.weather.ruleId == 'deck-paint'
        u.metadata.weather.ruleName == 'deck-paint'
        u.metadata.weather.result == 'INFEASIBLE'
        u.metadata.weather.forecastIssuedAt == issued.toString()
        u.metadata.weather.forecastRetrievedAt == retrieved.toString()
        u.metadata.weather.provider == 'fixture'
        u.metadata.weather.latitude == 40.71d
        u.metadata.weather.longitude == -74.01d
        jsonText.contains(issued.toString())
        jsonText.contains(retrieved.toString())
    }
}
