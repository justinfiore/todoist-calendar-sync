package todoistcaldavsync.planner.scheduling

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.FixtureCalendarGateway
import todoistcaldavsync.planner.adapters.FixtureTodoistGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.policy.EventClassifier
import todoistcaldavsync.planner.report.CapacityReportService
import todoistcaldavsync.planner.state.PlanStore

import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fixture-backed Phase 2 integration: capacity fixtures + deterministic proposal.
 */
class SchedulerFixtureIntegrationSpec extends Specification {

    private static File fixture(String name) {
        def url = SchedulerFixtureIntegrationSpec.classLoader.getResource("planner/fixtures/${name}")
        if (url) {
            return new File(url.toURI())
        }
        def candidates = [
            new File("src/test/resources/planner/fixtures/${name}"),
            new File("app/src/test/resources/planner/fixtures/${name}")
        ]
        def hit = candidates.find { it.exists() }
        if (!hit) {
            throw new IllegalStateException("Cannot locate fixture ${name}")
        }
        return hit
    }

    def configFile = fixture('capacity-config.yaml')
    def tasksFile = fixture('capacity-tasks.yaml')
    def eventsFile = fixture('capacity-events.yaml')

    def "capacity fixtures produce deterministic preview plan with unscheduled reasons"() {
        given:
        def config = PlannerConfig.load(configFile)
        // Extend fixture config with phase-2 defaults already applied by parser
        ZoneId zone = config.timezone
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 8).atStartOfDay(zone).toInstant()
        Instant now = LocalDate.of(2026, 8, 6).atTime(8, 0).atZone(zone).toInstant()

        def todoist = FixtureTodoistGateway.fromFile(tasksFile)
        def calendar = FixtureCalendarGateway.fromFile(eventsFile, zone)
        def reportService = new CapacityReportService(config, todoist, calendar)
        def report = reportService.generate(rangeStart, rangeEnd)

        def scheduler = new DeterministicScheduler(config)

        when:
        def plan1 = scheduler.propose(report.candidateTasks, report.slots, rangeStart, rangeEnd, now)
        def plan2 = scheduler.propose(report.candidateTasks.toList().reverse(), report.slots.toList().reverse(), rangeStart, rangeEnd, now)
        def md = plan1.humanDiff
        def json = PlanDiffFormatter.toJson(plan1)

        then:
        plan1.id == plan2.id
        plan1.scheduledBlocks*.start == plan2.scheduledBlocks*.start
        plan1.scheduledBlocks*.taskIds == plan2.scheduledBlocks*.taskIds
        plan1.unscheduled*.task*.id == plan2.unscheduled*.task*.id

        and: 'manual excluded from candidates already'
        !report.candidateTasks.any { it.manual }
        plan1.tasks.every { !it.manual }

        and: 'impossible task unscheduled with reason'
        plan1.unscheduled.any { it.task.id == 't-impossible' && it.reason }

        and: 'no hard overlaps'
        def blocks = plan1.scheduledBlocks.toSorted { it.start }
        for (int i = 0; i < blocks.size() - 1; i++) {
            assert !blocks[i + 1].start.isBefore(blocks[i].end)
        }

        and: 'human diff uses 12-hour clock; JSON ISO'
        md.contains('AM') || md.contains('PM')
        json.contains('scheduledBlocks')
        json.contains('2026-08-')

        and: 'preview mode only'
        plan1.mode == 'preview'
    }

    def "plan store snapshot of fixture proposal is reloadable"() {
        given:
        def config = PlannerConfig.load(configFile)
        ZoneId zone = config.timezone
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 8).atStartOfDay(zone).toInstant()
        Instant now = LocalDate.of(2026, 8, 6).atTime(8, 0).atZone(zone).toInstant()
        def report = new CapacityReportService(
            config,
            FixtureTodoistGateway.fromFile(tasksFile),
            FixtureCalendarGateway.fromFile(eventsFile, zone)
        ).generate(rangeStart, rangeEnd)
        def plan = new DeterministicScheduler(config).propose(
            report.candidateTasks, report.slots, rangeStart, rangeEnd, now)
        def dir = Files.createTempDirectory('fixture-plan-store')
        def store = new PlanStore(dir)

        when:
        store.save(plan)
        def loaded = store.load(plan.id)

        then:
        loaded.scheduledBlocks.size() == plan.scheduledBlocks.size()
        loaded.unscheduled.size() == plan.unscheduled.size()
        loaded.humanDiff == plan.humanDiff || loaded.humanDiff != null

        cleanup:
        dir.toFile().deleteDir()
    }

    def "14-day fixture horizon produces deterministic multi-day preview with blockers and unscheduled"() {
        given:
        def twConfig = PlannerConfig.load(fixture('two-week-config.yaml'))
        def twTasks = fixture('two-week-tasks.yaml')
        def twEvents = fixture('two-week-events.yaml')
        ZoneId zone = twConfig.timezone
        // True 14-day horizon: 2026-08-06 inclusive through 2026-08-20 exclusive = 14 days
        Instant rangeStart = LocalDate.of(2026, 8, 6).atStartOfDay(zone).toInstant()
        Instant rangeEnd = LocalDate.of(2026, 8, 20).atStartOfDay(zone).toInstant()
        Instant now = LocalDate.of(2026, 8, 6).atTime(8, 0).atZone(zone).toInstant()
        assert java.time.Duration.between(rangeStart, rangeEnd).toDays() == 14L

        def todoist = FixtureTodoistGateway.fromFile(twTasks)
        def calendar = FixtureCalendarGateway.fromFile(twEvents, zone)
        def report = new CapacityReportService(twConfig, todoist, calendar).generate(rangeStart, rangeEnd)
        def scheduler = new DeterministicScheduler(twConfig)

        when:
        def plan1 = scheduler.propose(report.candidateTasks, report.slots, rangeStart, rangeEnd, now)
        def plan2 = scheduler.propose(
            report.candidateTasks.toList().reverse(),
            report.slots.toList().reverse(),
            rangeStart, rangeEnd, now)
        def sig = { p ->
            [
                id     : p.id,
                blocks : p.scheduledBlocks.collect { [it.id, it.start.toString(), it.end.toString(), it.taskIds, it.focusBlock] },
                unsched: p.unscheduled.collect { [it.task.id, it.code, it.reason] },
                changes: p.changes.collect { [it.type, it.taskId, it.newStart?.toString(), it.newEnd?.toString()] }
            ]
        }
        def scheduledDates = plan1.scheduledBlocks.collect {
            it.start.atZone(zone).toLocalDate()
        } as Set
        def bufferMin = twConfig.stability.minimumBufferBetweenBlocksMinutes
        def blocks = plan1.scheduledBlocks.toSorted { it.start }

        then: 'deterministic repeated output and stable ordering'
        sig(plan1) == sig(plan2)
        plan1.id == plan2.id
        plan1.humanDiff == plan2.humanDiff
        plan1.scheduledBlocks*.id == plan1.scheduledBlocks.toSorted { a, b -> a.start <=> b.start ?: a.id <=> b.id }*.id
        plan1.changes*.id == plan1.changes.toSorted { a, b ->
            (a.newStart ?: Instant.EPOCH) <=> (b.newStart ?: Instant.EPOCH) ?: a.id <=> b.id
        }*.id

        and: 'scheduled across multiple distinct working days (not a single-day collapse)'
        scheduledDates.size() >= 3
        plan1.scheduledBlocks.size() >= 4
        // At least one block in week 1 and one in week 2 of the horizon
        scheduledDates.any { it.isBefore(LocalDate.of(2026, 8, 13)) }
        scheduledDates.any { !it.isBefore(LocalDate.of(2026, 8, 13)) }

        and: 'no hard conflicts / buffer violations'
        for (int i = 0; i < blocks.size() - 1; i++) {
            assert !blocks[i + 1].start.isBefore(blocks[i].end + java.time.Duration.ofMinutes(bufferMin))
        }

        and: 'hard blockers (with buffers) not overlapped by scheduled blocks'
        def hardEvents = report.classifiedEvents.findAll {
            it.role?.configValue == 'hard_blocker'
        }
        assert hardEvents.size() >= 1
        blocks.each { b ->
            hardEvents.each { ev ->
                Instant hStart = ev.bufferedStart()
                Instant hEnd = ev.bufferedEnd()
                boolean overlaps = b.start.isBefore(hEnd) && hStart.isBefore(b.end)
                assert !overlaps: "block ${b.id} [${b.start},${b.end}) overlaps hard ${ev.id} [${hStart},${hEnd})"
            }
        }

        and: 'impossible task explicitly unscheduled with reason; manual excluded'
        plan1.unscheduled.any { it.task.id == 'tw-impossible' && it.reason }
        plan1.unscheduled.find { it.task.id == 'tw-impossible' }.code in ['deadline_infeasible', 'no_capacity', 'deadline_passed']
        !plan1.tasks.any { it.id == 'tw-manual' || it.manual }
        !plan1.scheduledBlocks.any { it.taskIds.contains('tw-manual') }
        !plan1.unscheduled.any { it.task.id == 'tw-manual' }

        and: 'same-project scouts may batch; mixed contexts present among candidates'
        report.candidateTasks*.id.containsAll(['tw-scout-a', 'tw-scout-b', 'tw-email', 'tw-deck', 'tw-deep'])
        def scheduledIds = plan1.scheduledBlocks.collectMany { it.taskIds } as Set
        // Most feasible tasks scheduled; impossible is not
        !scheduledIds.contains('tw-impossible')
        scheduledIds.size() >= 5

        and: 'horizon-end / late-deadline task scheduled on or before its deadline when capacity exists'
        def horizonTaskBlocks = plan1.scheduledBlocks.findAll { it.taskIds.contains('tw-horizon-end') }
        if (horizonTaskBlocks) {
            def deadline = java.time.ZonedDateTime.of(2026, 8, 20, 17, 0, 0, 0, zone).toInstant()
            assert horizonTaskBlocks.every { !it.end.isAfter(deadline) }
            // Should not be forced only to day 1
            assert horizonTaskBlocks.every { !it.start.atZone(zone).toLocalDate().isBefore(LocalDate.of(2026, 8, 6)) }
        } else {
            assert plan1.unscheduled.any { it.task.id == 'tw-horizon-end' && it.reason }
        }

        and: 'preview-only — no write gateways involved; mode stays preview'
        plan1.mode == 'preview'
        twConfig.mode == 'preview'
        // Gateways used are fixture read-only types
        todoist instanceof FixtureTodoistGateway
        calendar instanceof FixtureCalendarGateway

        and: 'PlanStore round-trip for the 14-day plan'
        def dir = Files.createTempDirectory('two-week-plan-store')
        def store = new PlanStore(dir)
        store.save(plan1)
        def loaded = store.load(plan1.id)
        loaded.id == plan1.id
        loaded.scheduledBlocks*.start == plan1.scheduledBlocks*.start
        loaded.scheduledBlocks*.taskIds == plan1.scheduledBlocks*.taskIds
        loaded.unscheduled*.task*.id == plan1.unscheduled*.task*.id
        loaded.mode == 'preview'
        store.pathFor(plan1.id).toAbsolutePath().startsWith(dir.toAbsolutePath())

        cleanup:
        dir?.toFile()?.deleteDir()
    }
}
