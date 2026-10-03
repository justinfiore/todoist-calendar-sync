package todoistcaldavsync.planner.recurrence

import spock.lang.Specification
import spock.lang.Unroll
import todoistcaldavsync.planner.adapters.TodoistCommandResult
import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.adapters.TodoistLifecycleGateway
import todoistcaldavsync.planner.adapters.TodoistRestGateway
import todoistcaldavsync.planner.adapters.TodoistSyncPage
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task

import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class RecurrenceLifecycleSpec extends Specification {
    def resolver = new Task.DurationResolver(30, [:])

    def "rich Due projection is verbatim and occurrence identity is monotonic"() {
        when:
        Task task = task([date: '2026-10-03T09:00:00', string: 'every! 2 weekdays at 9am',
                          is_recurring: true, lang: 'en', timezone: 'America/New_York'], 7)

        then:
        task.todoistDue.string == 'every! 2 weekdays at 9am'
        task.todoistDue.timezone == 'America/New_York'
        task.todoistDue.instant.toString() == '2026-10-03T13:00:00Z'
        task.occurrenceIdentity().occurrenceKey() == 't1:7'
        new OccurrenceIdentity('t1', 7).advancementFrom(task.occurrenceIdentity()) == 0
        new OccurrenceIdentity('t1', 8).advancementFrom(task.occurrenceIdentity()) == 1
        new OccurrenceIdentity('t1', 9).advancementFrom(task.occurrenceIdentity()) == 2

        when:
        task.occurrenceIdentity().advancementFrom(new OccurrenceIdentity('t1', 8))

        then:
        thrown(IllegalStateException)
    }

    @Unroll
    def "recurring Due refuses incomplete tuple #missing"() {
        given:
        Map due = [date: '2026-10-03', string: 'every day', is_recurring: true,
                   lang: 'en', timezone: 'America/New_York']
        due.remove(missing)

        when:
        task(due, 0)

        then:
        thrown(IllegalArgumentException)

        where:
        missing << ['date', 'string', 'lang', 'timezone']
    }

    def "recurring Due preserves Todoist floating timezone as an explicit null field"() {
        given:
        Map due = [date: '2026-10-03T09:00:00', string: 'every day @ 09:00',
            is_recurring: true, lang: 'en', timezone: null]

        when:
        Task task = task(due, 0)

        then:
        task.todoistDue.completeRecurrenceTuple()
        task.todoistDue.timezone == null
        task.todoistDue.timezonePresent
        task.todoistDue.syncTuple('2026-10-04T09:00:00').timezone == null
        task.todoistDue.instant.toString() == '2026-10-03T13:00:00Z'
    }

    def "provider-normalized date and timezone alias retain fingerprint while zone drift does not"() {
        given:
        TodoistDue original = task([date: '2026-10-03T09:00:00', string: 'every day @ 09:00',
            is_recurring: true, lang: 'en', timezone: null], 0).todoistDue
        TodoistDue normalized = task([date: '2026-10-04T10:30:00', string: 'every day @ 09:00',
            is_recurring: true, lang: 'en', time_zone: null], 1).todoistDue
        TodoistDue drifted = task([date: '2026-10-04T10:30:00', string: 'every day @ 09:00',
            is_recurring: true, lang: 'en', timezone: 'America/New_York'], 1).todoistDue

        expect:
        original.timezonePresent && normalized.timezonePresent
        LifecycleSupport.fingerprint(normalized) == LifecycleSupport.fingerprint(original)
        LifecycleSupport.fingerprint(drifted) != LifecycleSupport.fingerprint(original)
    }

    def "marker codec preserves exact human prefix and canonical suffix without duplication"() {
        given:
        def codec = new LifecycleMarkerCodec()
        def initialMarker = marker(1, 3)
        String human = 'Human text with "quotes" and emoji ✓'

        when:
        String encoded = codec.merge(human, initialMarker)
        def read = codec.read(encoded)
        String replaced = codec.merge(encoded, marker(1, 4))

        then:
        encoded.startsWith(human + '\n\n---\n\n**SmartPlanner metadata — do not edit**\n\n```json\n{')
        encoded.endsWith('\n```')
        read.status == MarkerStatus.VALID
        read.humanPrefix == human
        read.marker.completedCount == 1
        codec.read(codec.merge('', initialMarker)).status == MarkerStatus.VALID
        codec.read(encoded).marker.canonicalMap().keySet().toList() == [
            'schema_version', 'owner', 'task_id', 'completed_count', 'deadline_mode',
            'deadline_source', 'deadline_date', 'pending_legacy_source_date',
            'last_verified_due', 'recurrence_fingerprint', 'last_planner_due',
            'marker_generation', 'last_command_id']
        replaced.count(LifecycleMarkerCodec.WARNING) == 1
        codec.read(encoded.replace('\n```', '\n```\ntrailing')).status == MarkerStatus.UNSAFE
        codec.read(encoded + encoded).status == MarkerStatus.UNSAFE
    }

    def "classification distinguishes initialized advancement edits and sentinel drift"() {
        given:
        def codec = new LifecycleMarkerCodec()
        def classifier = new LifecycleClassifier()
        def due = [date: '2026-10-03T09:00:00', string: 'every day', is_recurring: true,
                   lang: 'en', timezone: 'America/New_York']
        Task base = task(due, 2)
        def marker = marker(2, 1, LifecycleSupport.fingerprint(base.todoistDue), due.date)

        expect:
        classifier.classify(task(due, count, labels, codec.merge('', marker), deadline), 'smartplanner-seen').state == expected

        where:
        count | labels                  | deadline     || expected
        2     | ['smartplanner-seen']   | '2026-10-03' || LifecycleState.INITIALIZED
        4     | ['smartplanner-seen']   | '2026-10-03' || LifecycleState.ADVANCED
        1     | ['smartplanner-seen']   | '2026-10-03' || LifecycleState.COUNT_REGRESSION
        2     | []                      | '2026-10-03' || LifecycleState.SENTINEL_MISSING
        2     | ['smartplanner-seen']   | '2026-10-04' || LifecycleState.USER_EDITED
    }

    def "classification fails closed for absent malformed and removed lifecycle authority"() {
        given:
        def classifier = new LifecycleClassifier()
        def codec = new LifecycleMarkerCodec()
        def due = [date: '2026-10-03T09:00:00', string: 'every day', is_recurring: true,
                   lang: 'en', timezone: 'America/New_York']
        Task base = task(due, 2)
        def valid = marker(2, 1, LifecycleSupport.fingerprint(base.todoistDue), due.date)

        expect:
        classifier.classify(task(due, 2, [], ''), 'smartplanner-seen').state == LifecycleState.UNMARKED
        classifier.classify(task(due, 2, ['smartplanner-seen'], ''), 'smartplanner-seen').state == LifecycleState.MARKER_MISSING
        classifier.classify(task(due, 2, ['smartplanner-seen'], codec.merge('', valid) + 'moved'),
            'smartplanner-seen').state == LifecycleState.UNSUPPORTED
        classifier.classify(Task.fromTodoistMap(rawTask([date: '2026-10-03'], 2,
            ['smartplanner-seen'], codec.merge('', valid)), resolver, 'manual', ZoneId.of('America/New_York')),
            'smartplanner-seen').state == LifecycleState.RECURRENCE_REMOVED
        classifier.classify(task(due, 4, ['smartplanner-seen'], codec.merge('', valid)),
            'smartplanner-seen').skippedOccurrences == 1
    }

    def "classification accepts a same-occurrence Due written from recorded planner provenance"() {
        given:
        def codec = new LifecycleMarkerCodec()
        def due = [date: '2026-10-03T14:00:00Z', string: 'every day', is_recurring: true,
                   lang: 'en', timezone: 'America/New_York']
        Task live = task(due, 2)
        def provenance = new LifecycleMarker(taskId: 't1', completedCount: 2,
            deadlineMode: 'managed', deadlineSource: 'initial_user_due',
            deadlineDate: LocalDate.parse('2026-10-03'), pendingLegacySourceDate: null,
            lastVerifiedDue: '2026-10-03T09:00:00',
            recurrenceFingerprint: LifecycleSupport.fingerprint(live.todoistDue),
            lastPlannerDue: due.date, markerGeneration: 2,
            lastCommandId: '11111111-1111-1111-1111-111111111111')
        Task withMarker = task(due, 2, ['smartplanner-seen'], codec.merge('', provenance))

        expect:
        new LifecycleClassifier().classify(withMarker, 'smartplanner-seen').state == LifecycleState.INITIALIZED
    }

    def "marker writer rebases a winning human prefix with one stable command identity"() {
        given:
        def codec = new LifecycleMarkerCodec()
        Map raw = rawTask([date: '2026-10-03T09:00:00', string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York'], 2, ['smartplanner-seen'], 'Original')
        Task expected = Task.fromTodoistMap(raw, resolver, 'manual', ZoneId.of('America/New_York'))
        LifecycleMarker intended = marker(2, 1,
            LifecycleSupport.fingerprint(expected.todoistDue), expected.todoistDue.date)
        List<String> commandIds = []
        int writes = 0
        TodoistLifecycleGateway gateway = [
            updateLifecycleFields: { mutation ->
                writes++
                commandIds << mutation.commandId
                def parsed = codec.read(mutation.description)
                raw.description = writes == 1 ? codec.merge('Human edit', parsed.marker) : mutation.description
                new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
            },
            fetchTask: { String ignored -> new LinkedHashMap(raw) }
        ] as TodoistLifecycleGateway
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]]])

        when:
        Task verified = new LifecycleMarkerWriter(gateway, config).write(expected, intended)

        then:
        writes == 2
        commandIds.toSet() == [intended.lastCommandId] as Set
        codec.read(verified.description).humanPrefix == 'Human edit'
        codec.read(verified.description).marker.markerGeneration == 1
    }

    def "marker writer accepts an ambiguous response only after exact re-read verification"() {
        given:
        def codec = new LifecycleMarkerCodec()
        Map raw = rawTask([date: '2026-10-03', string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York'], 2, ['smartplanner-seen'], 'Human')
        Task expected = Task.fromTodoistMap(raw, resolver, 'manual', ZoneId.of('America/New_York'))
        LifecycleMarker intended = marker(2, 1, LifecycleSupport.fingerprint(expected.todoistDue), expected.todoistDue.date)
        TodoistLifecycleGateway gateway = [updateLifecycleFields: { mutation ->
            raw.description = mutation.description
            new TodoistCommandResult(TodoistCommandState.AMBIGUOUS, mutation.commandId)
        }, fetchTask: { String ignored -> new LinkedHashMap(raw) }] as TodoistLifecycleGateway
        def config = PlannerConfig.fromMap(planner: [availability: [working_windows: [weekday: ['09:00-12:00']]]])

        expect:
        codec.read(new LifecycleMarkerWriter(gateway, config).write(expected, intended).description).marker.lastCommandId ==
            intended.lastCommandId
    }

    def "marker writer preserves latest human text when bounded rebases exhaust"() {
        given:
        def codec = new LifecycleMarkerCodec()
        Map raw = rawTask([date: '2026-10-03', string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York'], 2, ['smartplanner-seen'], 'Human 0')
        Task expected = Task.fromTodoistMap(raw, resolver, 'manual', ZoneId.of('America/New_York'))
        LifecycleMarker intended = marker(2, 1, LifecycleSupport.fingerprint(expected.todoistDue), expected.todoistDue.date)
        int attempts = 0
        TodoistLifecycleGateway gateway = [updateLifecycleFields: { mutation ->
            attempts++
            raw.description = codec.merge("Human ${attempts}", codec.read(mutation.description).marker)
            new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
        }, fetchTask: { String ignored -> new LinkedHashMap(raw) }] as TodoistLifecycleGateway
        def config = PlannerConfig.fromMap(planner: [availability: [working_windows: [weekday: ['09:00-12:00']]]])

        when:
        new LifecycleMarkerWriter(gateway, config).write(expected, intended)

        then:
        thrown(LifecycleConflictException)
        attempts == LifecycleMarkerWriter.MAX_ATTEMPTS
        codec.read(raw.description).humanPrefix == 'Human 3'
    }

    @Unroll
    def "marker writer fails closed when #drift drifts during write"() {
        given:
        Map raw = rawTask([date: '2026-10-03', string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York'], 2, ['smartplanner-seen'], 'Human')
        Task expected = Task.fromTodoistMap(raw, resolver, 'manual', ZoneId.of('America/New_York'))
        LifecycleMarker intended = marker(2, 1, LifecycleSupport.fingerprint(expected.todoistDue), expected.todoistDue.date)
        TodoistLifecycleGateway gateway = [updateLifecycleFields: { mutation ->
            raw.description = mutation.description
            mutateDuringWrite.call(raw)
            new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
        }, fetchTask: { String ignored -> new LinkedHashMap(raw) }] as TodoistLifecycleGateway
        def config = PlannerConfig.fromMap(planner: [availability: [working_windows: [weekday: ['09:00-12:00']]]])

        when:
        new LifecycleMarkerWriter(gateway, config).write(expected, intended)

        then:
        thrown(LifecycleConflictException)

        where:
        drift        | mutateDuringWrite
        'Due'        | { Map state -> state.due.date = '2026-10-04' }
        'Deadline'   | { Map state -> state.deadline = [date: '2026-10-04'] }
        'labels'     | { Map state -> state.labels = ['smartplanner-seen', 'human-label'] }
        'count'      | { Map state -> state.completed_count = 3 }
        'generation' | { Map state -> state.description = state.description.replace('"marker_generation":1', '"marker_generation":99') }
    }

    def "legacy candidate preserves original source and removes onboarding request last"() {
        given:
        Map raw = rawTask([date: '2026-10-03T09:00:00', string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York'], 0, [], '', null)
        List<String> logs = []
        TodoistLifecycleGateway gateway = [
            updateLifecycleFields: { mutation ->
                if (mutation.deadlineDate != null) raw.deadline = [date: mutation.deadlineDate]
                if (mutation.labels != null) raw.labels = new ArrayList(mutation.labels)
                if (mutation.description != null) raw.description = mutation.description
                new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
            },
            fetchTask: { String ignored -> new LinkedHashMap(raw) }
        ] as TodoistLifecycleGateway
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-10-03T00:00:00Z']]])
        def manager = new RecurrenceLifecycleManager(config,
            new LifecycleMarkerWriter(gateway, config), { String message -> logs << message })

        when: 'the legacy task is discovered and later moved by SmartPlanner'
        manager.process(Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))
        raw.due = new LinkedHashMap(raw.due as Map)
        raw.due.date = '2026-10-08T14:00:00Z'
        raw.labels = ['smartplanner-onboard']
        def result = manager.process(Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))

        then:
        result.action == 'onboarded'
        raw.deadline.date == '2026-10-03'
        raw.labels == ['smartplanner-seen']
        new LifecycleMarkerCodec().read(raw.description).marker.pendingLegacySourceDate == null
        logs.size() == 1
        logs[0].contains('name=Recurring task id=t1 url=https://app.todoist.com/app/task/t1')
        !logs[0].contains(raw.description)
    }

    def "first observation with onboarding request commits marker then removes request label"() {
        given:
        Map raw = rawTask([date: '2026-10-03T09:00:00', string: 'every day', is_recurring: true,
            lang: 'en', timezone: null], 0, ['smartplanner-onboard'], '', null)
        List<List<String>> labelWrites = []
        TodoistLifecycleGateway gateway = [
            updateLifecycleFields: { mutation ->
                if (mutation.deadlineDate != null) raw.deadline = [date: mutation.deadlineDate]
                if (mutation.labels != null) {
                    raw.labels = new ArrayList(mutation.labels)
                    labelWrites << new ArrayList(mutation.labels)
                }
                if (mutation.description != null) raw.description = mutation.description
                new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
            },
            fetchTask: { String ignored -> new LinkedHashMap(raw) }
        ] as TodoistLifecycleGateway
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-10-03T00:00:00Z']]])

        when:
        def result = new RecurrenceLifecycleManager(config,
            new LifecycleMarkerWriter(gateway, config)).process(
            Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))

        then:
        result.action == 'initialized'
        labelWrites == [
            ['smartplanner-onboard', 'smartplanner-seen'],
            ['smartplanner-seen']
        ]
        raw.deadline.date == '2026-10-03'
        def marker = new LifecycleMarkerCodec().read(raw.description).marker
        marker.markerGeneration == 2
        marker.deadlineMode == 'managed'
        marker.deadlineSource == 'legacy_user_due'
    }

    def "Todoist Sync added_at drives automatic post-cutoff initialization"() {
        given:
        Map raw = rawTask([date: '2026-10-03T09:00:00', string: 'every day', is_recurring: true,
            lang: 'en', timezone: null], 0, [], '', null)
        raw.added_at = raw.remove('created_at')
        TodoistLifecycleGateway gateway = [
            updateLifecycleFields: { mutation ->
                if (mutation.deadlineDate != null) raw.deadline = [date: mutation.deadlineDate]
                if (mutation.labels != null) raw.labels = new ArrayList(mutation.labels)
                if (mutation.description != null) raw.description = mutation.description
                new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
            },
            fetchTask: { String ignored -> new LinkedHashMap(raw) }
        ] as TodoistLifecycleGateway
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-10-02T00:00:00Z']]])

        when:
        def result = new RecurrenceLifecycleManager(config,
            new LifecycleMarkerWriter(gateway, config)).process(
            Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))

        then:
        result.action == 'initialized'
        raw.deadline.date == '2026-10-03'
        raw.labels == ['smartplanner-seen']
        new LifecycleMarkerCodec().read(raw.description).marker.deadlineSource == 'initial_user_due'
    }

    def "restart after staged first-observation onboarding removes request label idempotently"() {
        given:
        Map raw = rawTask([date: '2026-10-03T09:00:00', string: 'every day', is_recurring: true,
            lang: 'en', timezone: null], 0, ['smartplanner-onboard'], '', null)
        int writes = 0
        boolean interrupt = true
        TodoistLifecycleGateway gateway = [
            updateLifecycleFields: { mutation ->
                writes++
                if (interrupt && writes == 2) throw new IllegalStateException('simulated process crash')
                if (mutation.deadlineDate != null) raw.deadline = [date: mutation.deadlineDate]
                if (mutation.labels != null) raw.labels = new ArrayList(mutation.labels)
                if (mutation.description != null) raw.description = mutation.description
                new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
            },
            fetchTask: { String ignored -> new LinkedHashMap(raw) }
        ] as TodoistLifecycleGateway
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-10-03T00:00:00Z']]])
        def manager = new RecurrenceLifecycleManager(config, new LifecycleMarkerWriter(gateway, config))

        when:
        manager.process(Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))

        then:
        thrown(IllegalStateException)
        raw.labels.toSet() == ['smartplanner-onboard', 'smartplanner-seen'] as Set
        new LifecycleMarkerCodec().read(raw.description).marker.markerGeneration == 1

        when:
        interrupt = false
        def replay = manager.process(Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))

        then:
        replay.action == 'onboarding_finalized'
        raw.labels == ['smartplanner-seen']
        new LifecycleMarkerCodec().read(raw.description).marker.markerGeneration == 2
    }

    def "partial onboarding keeps the request label after verified Deadline marker and sentinel staging"() {
        given:
        def codec = new LifecycleMarkerCodec()
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-10-03T00:00:00Z']]])
        Map due = [date: '2026-10-08T14:00:00Z', string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York']
        Task live = task(due, 0, ['smartplanner-onboard'], '', null)
        def pending = new LifecycleMarker(taskId: 't1', completedCount: 0,
            deadlineMode: 'legacy', deadlineSource: 'legacy_pending', deadlineDate: null,
            pendingLegacySourceDate: LocalDate.parse('2026-10-03'), lastVerifiedDue: due.date,
            recurrenceFingerprint: LifecycleSupport.fingerprint(live.todoistDue), lastPlannerDue: due.date,
            markerGeneration: 1, lastCommandId: '11111111-1111-1111-1111-111111111111')
        Map raw = rawTask(due, 0, ['smartplanner-onboard'], codec.merge('', pending), null)
        int writes = 0
        TodoistLifecycleGateway gateway = [updateLifecycleFields: { mutation ->
            writes++
            if (writes == 2) return new TodoistCommandResult(TodoistCommandState.REJECTED, mutation.commandId)
            raw.deadline = [date: mutation.deadlineDate]
            raw.labels = new ArrayList(mutation.labels)
            raw.description = mutation.description
            new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
        }, fetchTask: { String ignored -> new LinkedHashMap(raw) }] as TodoistLifecycleGateway

        when:
        new RecurrenceLifecycleManager(config, new LifecycleMarkerWriter(gateway, config)).process(
            Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))

        then:
        thrown(LifecycleConflictException)
        writes == 2
        raw.deadline.date == '2026-10-03'
        raw.labels.toSet() == ['smartplanner-onboard', 'smartplanner-seen'] as Set
        codec.read(raw.description).marker.deadlineMode == 'managed'
    }

    def "existing Deadline is preserved and a same-count user Due edit freezes lifecycle writes"() {
        given:
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]])
        Map raw = rawTask([date: '2026-10-08', string: 'every week', is_recurring: true,
            lang: 'en', timezone: 'America/New_York'], 0, [], '', '2026-10-10')
        int writes = 0
        TodoistLifecycleGateway gateway = [updateLifecycleFields: { mutation ->
            writes++
            raw.deadline = [date: mutation.deadlineDate]
            raw.labels = new ArrayList(mutation.labels)
            raw.description = mutation.description
            new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
        }, fetchTask: { String ignored -> new LinkedHashMap(raw) }] as TodoistLifecycleGateway
        def manager = new RecurrenceLifecycleManager(config, new LifecycleMarkerWriter(gateway, config))

        when:
        def initialized = manager.process(Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))
        raw.due = [date: '2026-10-09', string: 'every week', is_recurring: true,
            lang: 'en', timezone: 'America/New_York']
        def edited = manager.process(Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))

        then:
        initialized.action == 'initialized'
        edited.frozen
        writes == 1
        raw.deadline.date == '2026-10-10'
        new LifecycleMarkerCodec().read(raw.description).marker.deadlineSource == 'existing_deadline'
    }

    @Unroll
    def "managed recurrence advances Deadline for two native #kind cycles without interpreting expression"() {
        given:
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]])
        def codec = new LifecycleMarkerCodec()
        Map raw = rawTask([date: firstDate, string: expression, is_recurring: true,
            lang: 'en', timezone: 'America/New_York'], 2, ['smartplanner-seen'], '', firstDeadline)
        Task seed = Task.fromTodoistMap(raw, resolver, 'manual', config.timezone)
        raw.description = codec.merge('', new LifecycleMarker(taskId: 't1', completedCount: 2,
            deadlineMode: 'managed', deadlineSource: 'initial_user_due',
            deadlineDate: LocalDate.parse(firstDeadline), pendingLegacySourceDate: null,
            lastVerifiedDue: firstDate, recurrenceFingerprint: LifecycleSupport.fingerprint(seed.todoistDue),
            lastPlannerDue: null, markerGeneration: 1,
            lastCommandId: '11111111-1111-1111-1111-111111111111'))
        TodoistLifecycleGateway gateway = [
            updateLifecycleFields: { mutation ->
                if (mutation.deadlineDate != null) raw.deadline = [date: mutation.deadlineDate]
                if (mutation.labels != null) raw.labels = new ArrayList(mutation.labels)
                if (mutation.description != null) raw.description = mutation.description
                new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
            }, fetchTask: { String ignored -> new LinkedHashMap(raw) }
        ] as TodoistLifecycleGateway
        List<String> logs = []
        def manager = new RecurrenceLifecycleManager(config,
            new LifecycleMarkerWriter(gateway, config), { String message -> logs << message })

        when:
        [secondDate, thirdDate].eachWithIndex { String advancedDate, int index ->
            raw.completed_count = 3 + index
            raw.due = [date: advancedDate, string: expression, is_recurring: true,
                lang: 'en', timezone: 'America/New_York']
            manager.process(Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))
        }

        then:
        raw.deadline.date == thirdDeadline
        codec.read(raw.description).marker.completedCount == 4
        codec.read(raw.description).marker.recurrenceFingerprint == LifecycleSupport.fingerprint(
            Task.fromTodoistMap(raw, resolver, 'manual', config.timezone).todoistDue)

        where:
        kind              | expression                    | firstDate                   | firstDeadline | secondDate                  | thirdDate                   | thirdDeadline
        'daily'           | 'every day'                   | '2026-10-03'                | '2026-10-03'  | '2026-10-04'                | '2026-10-05'                | '2026-10-05'
        'weekly'          | 'every monday'                | '2026-10-05'                | '2026-10-05'  | '2026-10-12'                | '2026-10-19'                | '2026-10-19'
        'monthly'         | 'every 1st'                   | '2026-10-01'                | '2026-10-01'  | '2026-11-01'                | '2026-12-01'                | '2026-12-01'
        'yearly'          | 'every Oct 3'                 | '2026-10-03'                | '2026-10-03'  | '2027-10-03'                | '2028-10-03'                | '2028-10-03'
        'strict-relative' | 'every! 2 weekdays'           | '2026-10-05'                | '2026-10-05'  | '2026-10-07'                | '2026-10-09'                | '2026-10-09'
        'explicit-time'   | 'every day at 9am'            | '2026-10-03T09:00:00-04:00' | '2026-10-03'  | '2026-10-04T09:00:00-04:00' | '2026-10-05T09:00:00-04:00' | '2026-10-05'
    }

    def "count jump reports missing occurrences and advances only the active item"() {
        given:
        def codec = new LifecycleMarkerCodec()
        def config = PlannerConfig.fromMap(planner: [mode: 'preview', timezone: 'America/New_York',
            availability: [working_windows: [weekday: ['09:00-12:00']]],
            tasks: [recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]])
        Map due = [date: '2026-10-08', string: 'every day', is_recurring: true,
            lang: 'en', timezone: 'America/New_York']
        Task live = task(due, 5, ['smartplanner-seen'], '', '2026-10-03')
        def previous = new LifecycleMarker(taskId: 't1', completedCount: 2,
            deadlineMode: 'managed', deadlineSource: 'initial_user_due',
            deadlineDate: LocalDate.parse('2026-10-03'), pendingLegacySourceDate: null,
            lastVerifiedDue: '2026-10-03', recurrenceFingerprint: LifecycleSupport.fingerprint(live.todoistDue),
            lastPlannerDue: null, markerGeneration: 1,
            lastCommandId: '11111111-1111-1111-1111-111111111111')
        Map raw = rawTask(due, 5, ['smartplanner-seen'], codec.merge('', previous), '2026-10-03')
        TodoistLifecycleGateway gateway = [updateLifecycleFields: { mutation ->
            if (mutation.deadlineDate != null) raw.deadline = [date: mutation.deadlineDate]
            raw.description = mutation.description
            new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
        }, fetchTask: { String ignored -> new LinkedHashMap(raw) }] as TodoistLifecycleGateway
        List<String> logs = []

        when:
        def result = new RecurrenceLifecycleManager(config, new LifecycleMarkerWriter(gateway, config),
            { String message -> logs << message }).process(
                Task.fromTodoistMap(raw, resolver, 'manual', config.timezone))

        then:
        result.action == 'advanced'
        result.skippedOccurrences == 2
        raw.deadline.date == '2026-10-08'
        codec.read(raw.description).marker.completedCount == 5
        logs == ['recurrence count jump id=t1 skipped=2 active=5 url=https://app.todoist.com/app/task/t1']
    }

    def "dedicated items cursor checkpoints response and replays inbox before acknowledgement"() {
        given:
        def dir = Files.createTempDirectory('items-sync')
        def store = new ItemsSyncStateStore(dir)

        expect:
        store.load().syncToken == '*'

        when:
        store.checkpoint(new TodoistSyncPage('token-2', true, '2026-10-02T12:00:00Z',
            [[id: 'a', completed_count: 1], [id: 'b', is_deleted: true]]))
        def restarted = new ItemsSyncStateStore(dir)

        then:
        restarted.load().syncToken == 'token-2'
        restarted.load().pendingItems*.id == ['a', 'b']

        when:
        restarted.acknowledge('a')

        then:
        restarted.load().pendingItems*.id == ['b']
    }

    def "items poller replays a staged delta after processor failure without refetching"() {
        given:
        def dir = Files.createTempDirectory('items-poller-replay')
        def store = new ItemsSyncStateStore(dir)
        store.checkpoint(new TodoistSyncPage('token-2', false, null, [[id: 'a', completed_count: 1]]))
        int syncCalls = 0
        int processCalls = 0
        boolean fail = true
        TodoistLifecycleGateway gateway = [syncItems: { String ignored ->
            syncCalls++
            new TodoistSyncPage('token-3', false, null, [])
        }] as TodoistLifecycleGateway
        def poller = new TodoistItemsPoller(gateway, store, { Map item ->
            processCalls++
            if (fail) throw new IllegalStateException('simulated crash')
        })

        when:
        poller.poll()

        then:
        thrown(IllegalStateException)
        store.load().pendingItems*.id == ['a']
        syncCalls == 0

        when: 'a newly constructed process reopens the durable inbox'
        fail = false
        def restartedStore = new ItemsSyncStateStore(dir)
        def restartedPoller = new TodoistItemsPoller(gateway, restartedStore, { Map item ->
            processCalls++
            assert item.id == 'a'
        })
        def replay = restartedPoller.poll()

        then:
        replay.status == 'processed'
        processCalls == 2
        syncCalls == 0
        restartedStore.load().syncToken == 'token-2'
        restartedStore.load().pendingItems.isEmpty()
    }

    def "items poller immediately follows a full snapshot token and checkpoints its delta"() {
        given:
        def dir = Files.createTempDirectory('items-poller-bootstrap')
        def store = new ItemsSyncStateStore(dir)
        List<String> tokens = []
        List<String> processed = []
        TodoistLifecycleGateway gateway = [syncItems: { String token ->
            tokens << token
            token == '*' ? new TodoistSyncPage('token-full', true, '2026-10-02T12:00:00Z', [[id: 'a']]) :
                new TodoistSyncPage('token-incremental', false, null, [[id: 'b']])
        }] as TodoistLifecycleGateway

        when:
        def result = new TodoistItemsPoller(gateway, store,
            { Map item -> processed << item.id.toString() }).poll()

        then:
        tokens == ['*', 'token-full']
        processed == ['a', 'b']
        result.status == 'bootstrap_and_incremental'
        result.processed == 2
        store.load().syncToken == 'token-incremental'
        store.load().pendingItems.isEmpty()
    }

    def "items poller propagates provider authentication failure without resetting cursor"() {
        given:
        def dir = Files.createTempDirectory('items-poller-auth')
        def store = new ItemsSyncStateStore(dir)
        store.checkpoint(new TodoistSyncPage('token-2', false, null, []))
        TodoistLifecycleGateway gateway = [syncItems: { String ignored ->
            throw new TodoistRestGateway.TodoistGatewayException(
                'HTTP_STATUS', 'Todoist Sync failed with HTTP 401', null, 401)
        }] as TodoistLifecycleGateway

        when:
        new TodoistItemsPoller(gateway, store, { Map ignored -> }).poll()

        then:
        def error = thrown(TodoistRestGateway.TodoistGatewayException)
        error.statusCode == 401
        store.load().syncToken == 'token-2'
    }

    def "items poller resets an unusable incremental cursor for provider bootstrap"() {
        given:
        def dir = Files.createTempDirectory('items-poller-reset')
        def store = new ItemsSyncStateStore(dir)
        store.checkpoint(new TodoistSyncPage('expired-token', false, null, []))
        TodoistLifecycleGateway gateway = [syncItems: { String token ->
            assert token == 'expired-token'
            throw new TodoistRestGateway.TodoistGatewayException(
                'HTTP_STATUS', 'Todoist Sync failed with HTTP 400', null, 400)
        }] as TodoistLifecycleGateway

        when:
        def result = new TodoistItemsPoller(gateway, store, { Map ignored -> }).poll()

        then:
        result.status == 'cursor_reset'
        result.bootstrapRequired
        store.load().syncToken == '*'
        store.load().pendingItems.empty
    }

    private Task task(Map due, long count, List labels = ['smartplanner-seen'],
                      String description = '', String deadline = '2026-10-03') {
        Task.fromTodoistMap(rawTask(due, count, labels, description, deadline),
            resolver, 'manual', ZoneId.of('America/New_York'))
    }

    private static Map rawTask(Map due, long count, List labels = ['smartplanner-seen'],
                               String description = '', String deadline = '2026-10-03') {
        [id: 't1', content: 'Recurring task', labels: labels, priority: 1,
            due: due, deadline: deadline == null ? null : [date: deadline], description: description,
            created_at: '2026-10-02T12:00:00Z', updated_at: '2026-10-02T13:00:00Z',
            completed_count: count]
    }

    private LifecycleMarker marker(long count, long generation, String fingerprint = 'sha256:test',
                                   String due = '2026-10-03T09:00:00') {
        new LifecycleMarker(taskId: 't1', completedCount: count, deadlineMode: 'managed',
            deadlineSource: 'initial_user_due', deadlineDate: LocalDate.parse('2026-10-03'),
            pendingLegacySourceDate: null, lastVerifiedDue: due,
            recurrenceFingerprint: fingerprint, lastPlannerDue: due,
            markerGeneration: generation, lastCommandId: '11111111-1111-1111-1111-111111111111')
    }
}
