package todoistcaldavsync.planner.apply

import spock.lang.Specification
import spock.lang.Unroll
import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.recurrence.*
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.state.PlanStore

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrencePlanApplierSpec extends Specification {
    static final String CALENDAR = 'Todoist Planned'

    def "two native occurrences use distinct UIDs and retain the historical event"() {
        given:
        def dir = Files.createTempDirectory('recurrence-apply')
        def plansDir = Files.createTempDirectory('recurrence-plan-store')
        def state = new ApplicationStateStore(dir)
        def planStore = new PlanStore(plansDir)
        def calendar = new InMemoryCalendarGateway(CALENDAR, true)
        def config = PlannerConfig.fromMap(planner: [mode: 'approval_required', timezone: 'America/New_York',
            output_calendar: CALENDAR,
            availability: [working_windows: [weekday: ['09:00-17:00']],
                calendars: [[calendar: CALENDAR, default_role: 'managed_output']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]])
        def codec = new LifecycleMarkerCodec()
        Map raw = recurringRaw(0, '2026-10-05T09:00:00', '2026-10-05', marker(0,
            '2026-10-05T09:00:00', '2026-10-05', 1))
        TodoistLifecycleGateway todoist = lifecycleGateway(raw)
        def applier = new PlanApplier(config, new ManagedCalendarWriteGateway(calendar, CALENDAR),
            calendar, todoist, todoist, state, { Instant.parse('2026-10-02T12:00:00Z') })
        def firstPreview = plan(Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone),
            'plan-1', Instant.parse('2026-10-05T14:00:00Z'))
        planStore.save(firstPreview)
        def first = planStore.load(firstPreview.id)

        when:
        def firstReceipt = applier.apply(first, approval(first))
        String firstUid = firstReceipt.items[0].eventUid
        String firstDue = raw.due.date

        and: 'Todoist natively advances and lifecycle polling has verified the next occurrence'
        raw.completed_count = 1
        raw.due = [date: '2026-10-06T09:00:00', string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York']
        raw.deadline = [date: '2026-10-06']
        raw.description = codec.merge('Human description', marker(1,
            '2026-10-06T09:00:00', '2026-10-06', 3))
        def secondPreview = plan(Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone),
            'plan-2', Instant.parse('2026-10-06T15:00:00Z'))
        planStore.save(secondPreview)
        def second = planStore.load(secondPreview.id)
        def secondReceipt = applier.apply(second, approval(second))
        String secondUid = secondReceipt.items[0].eventUid
        def events = calendar.fetchEvents(Instant.parse('2026-10-01T00:00:00Z'),
            Instant.parse('2026-10-10T00:00:00Z'))

        then:
        firstReceipt.success()
        firstDue == '2026-10-05T14:00:00Z'
        raw.due.timezone == 'America/New_York'
        secondReceipt.success()
        firstUid != secondUid
        events*.uid.toSet() == [firstUid, secondUid] as Set
        events.every { it.description.contains('https://app.todoist.com/app/task/series-1') }
        state.loadHistoricalMappings().keySet() == ['series-1:0', 'series-1:1'] as Set

        cleanup:
        dir?.toFile()?.deleteDir()
        plansDir?.toFile()?.deleteDir()
    }

    def "floating recurrence receives planner-zone civil time and preserves explicit null timezone"() {
        expect:
        PlanApplier.formatRecurringDueIso(
            Instant.parse('2026-10-05T14:30:00Z'),
            TodoistDue.from([date: '2026-10-05T09:00:00', string: 'every day',
                is_recurring: true, lang: 'en', timezone: null], ZoneId.of('America/New_York'),
                { String value, ZoneId zone -> LocalDateTime.parse(value).atZone(zone).toInstant() }),
            ZoneId.of('America/New_York')) == '2026-10-05T10:30:00'
    }

    def "fixed-zone recurrence receives UTC instant and preserves IANA timezone authority"() {
        expect:
        PlanApplier.formatRecurringDueIso(
            Instant.parse('2027-03-14T07:30:00Z'),
            TodoistDue.from([date: '2027-03-13T08:30:00Z',
                string: 'every day at 3:30am starting March 13 2027',
                is_recurring: true, lang: 'en', timezone: 'America/New_York'],
                ZoneId.of('America/New_York'),
                { String value, ZoneId ignored -> Instant.parse(value) }),
            ZoneId.of('America/New_York')) == '2027-03-14T07:30:00Z'
    }

    def "retry after marker success and rate-limited Due write completes without duplicate event"() {
        given:
        def dir = Files.createTempDirectory('recurrence-rate-limit-retry')
        def calendar = new InMemoryCalendarGateway(CALENDAR, true)
        def config = PlannerConfig.fromMap(planner: [mode: 'approval_required', timezone: 'America/New_York',
            output_calendar: CALENDAR,
            availability: [working_windows: [weekday: ['09:00-17:00']],
                calendars: [[calendar: CALENDAR, default_role: 'managed_output']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]])
        Map raw = recurringRaw(0, '2026-10-05T09:00:00', '2026-10-05',
            marker(0, '2026-10-05T09:00:00', '2026-10-05', 1))
        int dueAttempts = 0
        int markerWrites = 0
        TodoistLifecycleGateway todoist = [
            fetchTasks: { -> [new LinkedHashMap(raw)] },
            fetchTask: { String ignored -> new LinkedHashMap(raw) },
            updateLifecycleFields: { mutation ->
                markerWrites++
                raw.description = mutation.description
                new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
            },
            updateRecurringDue: { String ignored, TodoistDue due, String replacement, String commandId ->
                dueAttempts++
                if (dueAttempts == 1) {
                    throw new TodoistRestGateway.TodoistGatewayException(
                        'HTTP_STATUS', 'Todoist Sync failed with HTTP 429', null, 429)
                }
                raw.due = new LinkedHashMap(due.syncTuple(replacement))
                new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
            },
            updateTaskDue: { String ignored, String value -> throw new AssertionError('REST Due write used') },
            updateTaskDeadline: { String ignored, String value -> throw new AssertionError('Deadline write used') },
            syncItems: { String ignored -> throw new AssertionError('poll not expected') }
        ] as TodoistLifecycleGateway
        def proposed = plan(Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone),
            'rate-limit-plan', Instant.parse('2026-10-05T14:00:00Z'))
        def applier = new PlanApplier(config, new ManagedCalendarWriteGateway(calendar, CALENDAR),
            calendar, todoist, todoist, new ApplicationStateStore(dir),
            { Instant.parse('2026-10-02T12:00:00Z') })

        when:
        def first = applier.apply(proposed, approval(proposed))
        def second = applier.apply(proposed, approval(proposed))

        then:
        first.overallStatus == ApplyItemStatus.FAILED
        second.success()
        dueAttempts == 2
        markerWrites == 1
        raw.due.date == '2026-10-05T14:00:00Z'
        calendar.fetchEvents(Instant.parse('2026-10-01T00:00:00Z'),
            Instant.parse('2026-10-10T00:00:00Z')).size() == 1

        cleanup:
        dir?.toFile()?.deleteDir()
    }

    def "approval hash binds occurrence Deadline hard label recurrence and lifecycle provenance"() {
        given:
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-17:00']]]])
        Map baseline = recurringRaw(0, '2026-10-05T09:00:00', '2026-10-05',
            marker(0, '2026-10-05T09:00:00', '2026-10-05', 1))
        Task original = Task.fromTodoistMap(baseline, config.durationResolver, config.manualLabel, config.timezone)
        String originalHash = PlanHash.compute(plan(original, 'hash-plan', Instant.parse('2026-10-05T14:00:00Z')))

        expect:
        [
            mutate(baseline) { it.labels = ['smartplanner-seen', 'hard'] },
            mutate(baseline) { it.deadline = [date: '2026-10-06'] },
            mutate(baseline) { it.completed_count = 1 },
            mutate(baseline) { it.due.string = 'every! day' },
            mutate(baseline) { it.description = new LifecycleMarkerCodec().merge('Human description',
                marker(0, '2026-10-05T09:00:00', '2026-10-05', 2, 'later_user_due')) }
        ].every { Map changed ->
            Task task = Task.fromTodoistMap(changed, config.durationResolver, config.manualLabel, config.timezone)
            PlanHash.compute(plan(task, 'hash-plan', Instant.parse('2026-10-05T14:00:00Z'))) != originalHash
        }

        and: 'hard is scheduling state, not recurrence corruption'
        Map hard = mutate(baseline) { it.labels = ['smartplanner-seen', 'hard'] }
        new LifecycleClassifier().classify(
            Task.fromTodoistMap(hard, config.durationResolver, config.manualLabel, config.timezone),
            'smartplanner-seen').state == LifecycleState.INITIALIZED
    }

    def "empty local state reconstructs the active occurrence without a duplicate event"() {
        given:
        def firstDir = Files.createTempDirectory('recurrence-recovery-first')
        def recoveredDir = Files.createTempDirectory('recurrence-recovery-empty')
        def calendar = new InMemoryCalendarGateway(CALENDAR, true)
        def config = PlannerConfig.fromMap(planner: [mode: 'approval_required', timezone: 'America/New_York',
            output_calendar: CALENDAR,
            availability: [working_windows: [weekday: ['09:00-17:00']],
                calendars: [[calendar: CALENDAR, default_role: 'managed_output']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]])
        Map raw = recurringRaw(0, '2026-10-05T09:00:00', '2026-10-05',
            marker(0, '2026-10-05T09:00:00', '2026-10-05', 1))
        TodoistLifecycleGateway todoist = lifecycleGateway(raw)
        def task = Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone)
        def initial = plan(task, 'recovery-plan', Instant.parse('2026-10-05T14:00:00Z'))
        def firstApplier = new PlanApplier(config, new ManagedCalendarWriteGateway(calendar, CALENDAR),
            calendar, todoist, todoist, new ApplicationStateStore(firstDir),
            { Instant.parse('2026-10-02T12:00:00Z') })
        firstApplier.apply(initial, approval(initial))
        def fresh = plan(Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone),
            'recovery-plan', Instant.parse('2026-10-05T14:00:00Z'))
        def recoveredState = new ApplicationStateStore(recoveredDir)
        def recovered = new PlanApplier(config, new ManagedCalendarWriteGateway(calendar, CALENDAR),
            calendar, todoist, todoist, recoveredState, { Instant.parse('2026-10-02T13:00:00Z') })

        when:
        def receipt = recovered.apply(fresh, approval(fresh))
        def events = calendar.fetchEvents(Instant.parse('2026-10-01T00:00:00Z'),
            Instant.parse('2026-10-10T00:00:00Z'))

        then:
        receipt.success()
        events.size() == 1
        recoveredState.loadMapping('series-1').occurrenceKey == 'series-1:0'

        cleanup:
        firstDir?.toFile()?.deleteDir()
        recoveredDir?.toFile()?.deleteDir()
    }

    def "managed descriptions retain ownership and list every task by ID-only link"() {
        given:
        Task first = Task.builder().id('a').content('Renamed A').priority(1).completedCount(2)
            .effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
        Task second = Task.builder().id('b').content('Renamed B').priority(1).completedCount(7)
            .effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()

        when:
        String description = ManagedEventIds.buildOccurrenceDescription('block', 'plan', [second, first], 'focus')

        then:
        ManagedEventIds.hasOwnershipMarker(description)
        description.contains('todoist-occurrence-id:a:2')
        description.contains('todoist-occurrence-id:b:7')
        description.count('https://app.todoist.com/app/task/') == 2
        description.contains('https://app.todoist.com/app/task/a')
        description.contains('https://app.todoist.com/app/task/b')
    }

    @Unroll
    def "#race between planning and apply fails before Todoist or Calendar mutation"() {
        given:
        def dir = Files.createTempDirectory('recurrence-race')
        def calendar = new InMemoryCalendarGateway(CALENDAR, true)
        def config = PlannerConfig.fromMap(planner: [mode: 'approval_required', timezone: 'America/New_York',
            output_calendar: CALENDAR,
            availability: [working_windows: [weekday: ['09:00-17:00']],
                calendars: [[calendar: CALENDAR, default_role: 'managed_output']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]])
        Map plannedRaw = recurringRaw(0, '2026-10-05T09:00:00', '2026-10-05',
            marker(0, '2026-10-05T09:00:00', '2026-10-05', 1))
        Task plannedTask = Task.fromTodoistMap(plannedRaw, config.durationResolver,
            config.manualLabel, config.timezone)
        def proposed = plan(plannedTask, 'race-plan', Instant.parse('2026-10-05T14:00:00Z'))
        Map liveRaw = mutate(plannedRaw, mutateLive)
        int recurringWrites = 0
        TodoistLifecycleGateway todoist = [
            fetchTasks: { -> [new LinkedHashMap(liveRaw)] },
            fetchTask: { String ignored -> new LinkedHashMap(liveRaw) },
            updateRecurringDue: { String ignored, TodoistDue due, String replacement, String commandId ->
                recurringWrites++
                new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
            },
            updateLifecycleFields: { mutation -> throw new AssertionError('marker write must not run') },
            updateTaskDue: { String ignored, String value -> throw new AssertionError('REST Due write used') },
            updateTaskDeadline: { String ignored, String value -> throw new AssertionError('Deadline write used') },
            syncItems: { String ignored -> throw new AssertionError('poll not expected') }
        ] as TodoistLifecycleGateway
        def applier = new PlanApplier(config, new ManagedCalendarWriteGateway(calendar, CALENDAR),
            calendar, todoist, todoist, new ApplicationStateStore(dir),
            { Instant.parse('2026-10-02T12:00:00Z') })

        when:
        def receipt = applier.apply(proposed, approval(proposed))

        then:
        receipt.errors.any { it.contains('stale recurring lifecycle preflight') }
        recurringWrites == 0
        calendar.fetchEvents(Instant.parse('2026-10-01T00:00:00Z'),
            Instant.parse('2026-10-10T00:00:00Z')).empty

        cleanup:
        dir?.toFile()?.deleteDir()

        where:
        race                         | mutateLive
        'native completion'          | { Map raw -> raw.completed_count = 1 }
        'concurrent human text edit' | { Map raw -> raw.description = raw.description.replace('Human description', 'Edited by human') }
    }

    private static TodoistLifecycleGateway lifecycleGateway(Map raw) {
        [
            fetchTasks: { -> [new LinkedHashMap(raw)] },
            fetchTask: { String ignored -> new LinkedHashMap(raw) },
            updateRecurringDue: { String ignored, TodoistDue due, String replacement, String commandId ->
                raw.due = new LinkedHashMap(due.syncTuple(replacement))
                new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
            },
            updateLifecycleFields: { mutation ->
                if (mutation.deadlineDate != null) raw.deadline = [date: mutation.deadlineDate]
                if (mutation.labels != null) raw.labels = new ArrayList(mutation.labels)
                if (mutation.description != null) raw.description = mutation.description
                new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
            },
            updateTaskDue: { String ignored, String value -> throw new AssertionError('REST Due write used') },
            updateTaskDeadline: { String ignored, String value -> throw new AssertionError('ordinary Deadline write used') },
            syncItems: { String ignored -> throw new AssertionError('poll not expected') }
        ] as TodoistLifecycleGateway
    }

    private static Map recurringRaw(long count, String dueDate, String deadline, LifecycleMarker marker) {
        [id: 'series-1', content: 'Daily review', labels: ['smartplanner-seen'], priority: 2,
            due: [date: dueDate, string: 'every day', is_recurring: true,
                lang: 'en', timezone: 'America/New_York'], deadline: [date: deadline],
            description: new LifecycleMarkerCodec().merge('Human description', marker),
            created_at: '2026-10-01T12:00:00Z', updated_at: '2026-10-02T12:00:00Z',
            completed_count: count]
    }

    private static LifecycleMarker marker(long count, String due, String deadline, long generation,
                                          String source = 'initial_user_due') {
        def tuple = TodoistDue.from([date: due, string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York'], java.time.ZoneId.of('America/New_York')) {
            String value, java.time.ZoneId zone -> Task.parseFlexibleInstant(value, false, zone)
        }
        new LifecycleMarker(taskId: 'series-1', completedCount: count, deadlineMode: 'managed',
            deadlineSource: source, deadlineDate: java.time.LocalDate.parse(deadline),
            pendingLegacySourceDate: null, lastVerifiedDue: due,
            recurrenceFingerprint: LifecycleSupport.fingerprint(tuple), lastPlannerDue: null,
            markerGeneration: generation, lastCommandId: '11111111-1111-1111-1111-111111111111')
    }

    private static Plan plan(Task task, String id, Instant start) {
        def block = ScheduledBlock.builder().id("block-${id}").start(start)
            .end(start + Duration.ofMinutes(30)).taskIds([task.id]).title(task.content)
            .reason('recurrence test').build()
        Plan.builder().id(id).version(1).createdAt(Instant.parse('2026-10-02T10:00:00Z'))
            .mode('approval_required').tasks([task]).scheduledBlocks([block]).build()
    }

    private static Approval approval(Plan plan) {
        Approval.builder().id("approval-${plan.id}").planId(plan.id).planVersion(plan.version)
            .planHash(PlanHash.compute(plan)).approvedAt(Instant.parse('2026-10-02T11:00:00Z'))
            .approvedBy('test').build()
    }

    private static Map mutate(Map source, Closure change) {
        Map copy = new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(source)) as Map
        change.call(copy)
        copy
    }
}
