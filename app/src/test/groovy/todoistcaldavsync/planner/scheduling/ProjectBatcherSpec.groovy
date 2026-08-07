package todoistcaldavsync.planner.scheduling

import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProjectBatcherSpec extends Specification {

    ZoneId zone = ZoneId.of('America/New_York')
    Instant now = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()

    PlannerConfig config = PlannerConfig.fromMap(planner: [
        mode        : 'preview',
        timezone    : 'America/New_York',
        availability: [working_windows: [weekday: ['09:00-17:00']]],
        batching    : [
            enabled                   : true,
            project_batch_bonus       : 25,
            max_focus_block_minutes   : 90,
            minimum_focus_block_minutes: 30,
            context_switch_penalty    : 15
        ]
    ])

    ProjectBatcher batcher = new ProjectBatcher(config)

    private Task task(String id, String projectId, int minutes, int priority = 2, Instant deadline = null,
                      List<String> labels = ['schedule']) {
        Task.builder()
            .id(id)
            .content("Task ${id}")
            .projectId(projectId)
            .projectName(projectId)
            .labels(labels)
            .priority(priority)
            .deadline(deadline)
            .effectiveDuration(Duration.ofMinutes(minutes))
            .durationSource('test')
            .build()
    }

    PlannerConfig configWithContexts = PlannerConfig.fromMap(planner: [
        mode        : 'preview',
        timezone    : 'America/New_York',
        availability: [working_windows: [weekday: ['09:00-17:00']]],
        tasks       : [
            contexts: [
                phone: [match_labels: ['phone'], preferred_windows: ['weekday 12:00-13:00']],
                home : [match_labels: ['home'], preferred_windows: ['weekday 16:00-17:00']]
            ]
        ],
        batching    : [
            enabled                    : true,
            project_batch_bonus        : 25,
            max_focus_block_minutes    : 90,
            minimum_focus_block_minutes: 30,
            context_switch_penalty     : 15
        ]
    ])

    def "same-project small tasks form an explicit focus unit"() {
        given:
        def tasks = [
            task('s1', 'Scouts', 15, 2),
            task('s2', 'Scouts', 10, 2),
            task('s3', 'Scouts', 20, 3),
            task('other', 'AI', 30, 2)
        ]

        when:
        def units = batcher.buildUnits(tasks, now)
        def scouts = units.find { it.projectId() == 'Scouts' && it.focusBlock }

        then:
        scouts != null
        scouts.tasks*.id as Set == ['s1', 's2', 's3'] as Set
        scouts.totalMinutes == 45
        scouts.title().contains('focus block')
        units.any { it.primaryId() == 'other' && !it.focusBlock }
    }

    def "does not batch unrelated projects together"() {
        given:
        def tasks = [
            task('a', 'A', 15),
            task('b', 'B', 15)
        ]

        when:
        def units = batcher.buildUnits(tasks, now)

        then:
        units.size() == 2
        units.every { !it.focusBlock || it.tasks.size() == 1 }
    }

    def "respects max focus block duration by splitting"() {
        given:
        def tasks = [
            task('a', 'P', 40),
            task('b', 'P', 40),
            task('c', 'P', 40)
        ]

        when:
        def units = batcher.buildUnits(tasks, now)
        def focusOrSingle = units.findAll { it.projectId() == 'P' }

        then:
        focusOrSingle.every { it.totalMinutes <= 90 }
        focusOrSingle.size() >= 2
        focusOrSingle.sum { it.tasks.size() } == 3
    }

    def "batching disabled yields only singles"() {
        given:
        def off = PlannerConfig.fromMap(planner: [
            mode        : 'preview',
            timezone    : 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            batching    : [enabled: false]
        ])
        def tasks = [task('a', 'P', 15), task('b', 'P', 15)]

        when:
        def units = new ProjectBatcher(off).buildUnits(tasks, now)

        then:
        units.size() == 2
        units.every { !it.focusBlock }
    }

    def "below minimumFocusBlockMinutes emits singles not focus block"() {
        given:
        // min focus 30; two 10m tasks = 20 < 30 → singles
        def tasks = [task('a', 'P', 10), task('b', 'P', 10)]

        when:
        def units = batcher.buildUnits(tasks, now)

        then:
        units.size() == 2
        units.every { !it.focusBlock }
        units.every { it.tasks.size() == 1 }
    }

    def "exactly minimumFocusBlockMinutes forms focus block"() {
        given:
        // min focus 30; 15+15 = 30 → focus
        def tasks = [task('a', 'P', 15), task('b', 'P', 15)]

        when:
        def units = batcher.buildUnits(tasks, now)
        def focus = units.find { it.focusBlock }

        then:
        focus != null
        focus.totalMinutes == 30
        focus.tasks*.id as Set == ['a', 'b'] as Set
    }

    def "incompatible context labels are not batched into one focus block"() {
        given:
        def batcherCtx = new ProjectBatcher(configWithContexts)
        def tasks = [
            task('phone-1', 'Home', 20, 2, null, ['schedule', 'phone']),
            task('home-1', 'Home', 20, 2, null, ['schedule', 'home'])
        ]

        when:
        def units = batcherCtx.buildUnits(tasks, now)

        then:
        units.size() == 2
        units.every { !it.focusBlock }
        units.every { it.tasks.size() == 1 }
        units*.tasks*.id.flatten() as Set == ['phone-1', 'home-1'] as Set
    }

    def "compatible same-context tasks still form a focus block"() {
        given:
        def batcherCtx = new ProjectBatcher(configWithContexts)
        def tasks = [
            task('phone-a', 'Work', 20, 2, null, ['schedule', 'phone']),
            task('phone-b', 'Work', 20, 2, null, ['schedule', 'phone'])
        ]

        when:
        def units = batcherCtx.buildUnits(tasks, now)
        def focus = units.find { it.focusBlock }

        then:
        focus != null
        focus.tasks*.id as Set == ['phone-a', 'phone-b'] as Set
        focus.totalMinutes == 40
    }

    def "contextless tasks batch with other contextless only not with labeled contexts"() {
        given:
        def batcherCtx = new ProjectBatcher(configWithContexts)
        def tasks = [
            task('plain-a', 'Mix', 20, 2, null, ['schedule']),
            task('plain-b', 'Mix', 20, 2, null, ['schedule']),
            task('phone-x', 'Mix', 20, 2, null, ['schedule', 'phone'])
        ]

        when:
        def units = batcherCtx.buildUnits(tasks, now)
        def plainFocus = units.find { it.focusBlock && it.tasks*.id.containsAll(['plain-a', 'plain-b']) }
        def phoneUnit = units.find { it.tasks*.id.contains('phone-x') }

        then:
        plainFocus != null
        plainFocus.tasks*.id as Set == ['plain-a', 'plain-b'] as Set
        !plainFocus.tasks*.id.contains('phone-x')
        phoneUnit != null
        phoneUnit.tasks.size() == 1
        !phoneUnit.focusBlock || phoneUnit.tasks*.id == ['phone-x']
    }
}
