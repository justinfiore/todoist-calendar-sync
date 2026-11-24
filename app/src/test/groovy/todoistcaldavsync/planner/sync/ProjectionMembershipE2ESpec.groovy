package todoistcaldavsync.planner.sync

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir
import todoistcaldavsync.planner.CalendarProviderConfig
import todoistcaldavsync.planner.ProductionIntegrationConfig
import todoistcaldavsync.planner.ProductionPlannerOrchestrator
import todoistcaldavsync.planner.SmartPlannerDaemon
import todoistcaldavsync.planner.messaging.InMemoryMessagingSurface
import todoistcaldavsync.planner.messaging.MessagingEvent
import todoistcaldavsync.TodoistCalDavSync
import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.recurrence.*
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.state.ConversationStore
import todoistcaldavsync.planner.migration.MigrationState
import todoistcaldavsync.planner.migration.LegacyCalendarMigration
import todoistcaldavsync.planner.writeback.CalendarWritebackDecision

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

/** Real provider HTTP, guarded replacement capability, durable state and restart; no live accounts. */
class ProjectionMembershipE2ESpec extends Specification {
    @TempDir Path directory
    WireMockServer server
    Map<String, Map> tasks = [:]
    Map<String, Map> events = [:]
    Map<String, Map> tombstones = [:]
    List<String> mutations = []
    boolean interruptRetirement
    boolean interrupted
    boolean interruptUpgrade
    boolean interruptLegacyDelete
    boolean interruptImport
    String migrationAccount = 'fixture-user'
    int interruptAfterCreate
    boolean allowTaskWrites
    String interruptDurationTask
    List<String> taskWrites = []
    List<Map> feedItems = []
    List<String> itemCursors = []
    String unavailableDestination
    Closure afterCreate
    ProjectionCalendarGateway calendar
    TodoistScheduleGateway todoist
    ApplicationStateStore store
    CalendarEvent source
    List<Task> originals
    final PlannerConfig config = PlannerConfig.fromMap(planner: [timezone: 'UTC',
        routing: [mode: 'legacy_rules', labelsToInclude: ['work'], rules: [
            [rule: 'family', calendarName: 'Family'], [rule: 'work', calendarName: 'Work']]],
        availability: [working_windows: [weekday: ['09:00-17:00']]]])

    def setup() {
        def transformer = [getName: { 'providers' }, transform: { served ->
            def request = served.request
            String path = URI.create(request.url).path
            String method = request.method.value()
            if (path == '/todoist/sync') {
                Map form = request.bodyAsString.split('&').collectEntries {
                    String[] pair = it.split('=', 2)
                    [(pair[0]): URLDecoder.decode(pair[1], 'UTF-8')]
                }
                if (form.sync_token != null) {
                    itemCursors << form.sync_token
                    return okJson(JsonOutput.toJson([sync_token: 'items-' + itemCursors.size(),
                        full_sync_date_utc: '2026-11-24T00:00:00Z', items: feedItems])).build()
                }
                assert allowTaskWrites
                Map command = new JsonSlurper().parseText(form.commands)[0] as Map
                assert command.type == 'item_update'
                assert command.args.keySet() in [['id', 'duration'] as Set, ['id', 'due'] as Set]
                if (command.args.due != null) {
                    assert command.args.due.keySet() == ['date', 'string', 'is_recurring', 'lang', 'timezone'] as Set
                    assert command.args.due.string == tasks[command.args.id].due.string
                    assert command.args.due.is_recurring
                    tasks[command.args.id].due = command.args.due
                } else tasks[command.args.id].duration = command.args.duration
                tasks[command.args.id].updated_at = '2026-11-24T17:00:00Z'
                taskWrites << (command.args.due != null ? 'recurring_due' : 'duration')
                if (command.args.id == interruptDurationTask) {
                    interruptDurationTask = null
                    return aResponse().withStatus(503).build()
                }
                return okJson(JsonOutput.toJson([sync_status: [(command.uuid): 'ok']])).build()
            }
            if (path == '/todoist/user') return okJson(JsonOutput.toJson([id: migrationAccount, email: 'test@example.test',
                token: 'MUST-NOT-EXPORT-USER-TOKEN'])).build()
            if (path == '/todoist/tasks') return okJson(JsonOutput.toJson([results: tasks.values().findAll {
                !(it.checked in [true, 1]) && !(it.is_deleted in [true, 1])
            }, next_cursor: null])).build()
            if (path == '/todoist/projects') return okJson(JsonOutput.toJson([results: [[id: 'p', name: 'Work']], next_cursor: null])).build()
            if (path.startsWith('/todoist/tasks/')) {
                Map task = tasks[path.substring('/todoist/tasks/'.length())]
                if (method == 'POST') {
                    assert allowTaskWrites
                    Map fields = new JsonSlurper().parseText(request.bodyAsString) as Map
                    assert fields.keySet() == ['due_datetime'] as Set
                    task.due = (task.due ?: [:]) + [date: fields.due_datetime]
                    task.updated_at = '2026-11-24T17:00:00Z'
                    taskWrites << 'due'
                } else assert method == 'GET'
                return task == null ? aResponse().withStatus(404).build() : okJson(JsonOutput.toJson(task)).build()
            }
            List<String> parts = path.tokenize('/')
            String destination = parts[3]
            if (method == 'GET' && parts.size() == 5) {
                if (destination == unavailableDestination) return aResponse().withStatus(503).build()
                def parameter = request.queryParameter('privateExtendedProperty')
                String uid = parameter.isPresent() ? parameter.firstValue() : null
                String iCalUid = request.queryParameter('iCalUID').isPresent() ? request.queryParameter('iCalUID').firstValue() : null
                List<Map> matches = events.values().findAll {
                    it.destination == destination && (iCalUid == null || it.iCalUID == iCalUid) &&
                        (uid == null || 'plannerUid=' + it.extendedProperties?.private?.plannerUid == uid)
                }.toList()
                return okJson(JsonOutput.toJson([items: matches, nextSyncToken: 'calendar-token'])).build()
            }
            String key = destination + ':' + (parts.size() > 5 ? parts[5] : '')
            if (method == 'GET') {
                Map event = events[key] ?: tombstones[key]
                return event == null ? aResponse().withStatus(404).build() : okJson(JsonOutput.toJson(event)).build()
            }
            if (method == 'POST') {
                Map body = new JsonSlurper().parseText(request.bodyAsString) as Map
                if (parts.last() == 'import') {
                    assert !body.containsKey('id') && body.iCalUID
                    assert request.queryParameter('conferenceDataVersion').firstValue() == '1'
                    body.id = 'restored-' + body.iCalUID
                    mutations << 'import:' + destination + ':' + body.id
                    events[destination + ':' + body.id] = body + [destination: destination, etag: '"restored"', updated: '2026-11-24T13:00:00Z']
                    if (interruptImport) { interruptImport = false; return aResponse().withStatus(503).build() }
                    return okJson(JsonOutput.toJson(events[destination + ':' + body.id])).withStatus(201).build()
                }
                key = destination + ':' + body.id
                if (events.containsKey(key) || tombstones.containsKey(key)) return aResponse().withStatus(409).build()
                mutations << 'create:' + key
                events[key] = body + [destination: destination, etag: '"created"', updated: '2026-11-24T13:00:00Z']
                afterCreate?.call()
                if (interruptAfterCreate == mutations.count { it.startsWith('create:') }) {
                    return aResponse().withStatus(503).build()
                }
                return okJson(JsonOutput.toJson(events[key])).withStatus(201).build()
            }
            if (method == 'PATCH') {
                if (request.getHeader('If-Match') != events[key]?.etag) return aResponse().withStatus(412).build()
                Map body = new JsonSlurper().parseText(request.bodyAsString) as Map
                for (String field : ['start', 'end']) {
                    if (!(body[field] instanceof Map)) continue
                    Map merged = (events[key][field] ?: [:]) + body[field]
                    merged = merged.findAll { name, value -> value != null }
                    if (merged.date && merged.dateTime) return aResponse().withStatus(400).build()
                    body[field] = merged
                }
                events[key] += body + [etag: '"updated"', updated: '2026-11-24T18:00:00Z']
                mutations << 'update:' + key
                if (interruptUpgrade) {
                    interruptUpgrade = false
                    return aResponse().withStatus(503).build()
                }
                return okJson(JsonOutput.toJson(events[key])).build()
            }
            assert method == 'DELETE'
            if (interruptRetirement && !interrupted) {
                interrupted = true
                return aResponse().withStatus(503).build()
            }
            if (request.getHeader('If-Match') != events[key]?.etag) return aResponse().withStatus(412).build()
            mutations << 'delete:' + key
            tombstones[key] = events.remove(key) + [status: 'cancelled']
            if (interruptLegacyDelete && key.contains('legacy-')) {
                interruptLegacyDelete = false
                return aResponse().withStatus(503).build()
            }
            aResponse().withStatus(204).build()
        }] as ResponseDefinitionTransformerV2
        server = new WireMockServer(options().dynamicPort().extensions(transformer))
        server.start()
        server.stubFor(any(anyUrl()).willReturn(aResponse().withTransformers('providers')))
        def providerConfig = new CalendarProviderConfig.GoogleCalendarApiConfig(Path.of('unused'), Path.of('unused'), null,
            'test@example.test', 8787, ['Work', 'Family'].collect {
                [name: it, id: it.toLowerCase(), role: 'informational', managed_destination: true]
            })
        calendar = new GoogleCalendarApiGateway(config: providerConfig, managedCalendarNames: ['Work', 'Family'],
            baseUrl: server.baseUrl() + '/calendar/v3', allowInsecureHttp: true,
            accessTokenSupplier: { 'fixture-only' }, timezone: ZoneId.of('UTC'))
        todoist = new TodoistRestGateway(baseUrl: server.baseUrl() + '/todoist', tokenOverride: 'fixture-only', allowInsecureHttp: true)
        store = new ApplicationStateStore(directory)
        int elapsed = 0
        [30, 60, 15].eachWithIndex { minutes, index ->
            String id = ['a', 'b', 'c'][index]
            tasks[id] = [id: id, content: 'Task ' + id, labels: ['work'], project_id: 'p', priority: 3,
                description: 'Human note', deadline: [date: '2026-12-01'], completed_count: 0,
                updated_at: '2026-11-24T10:00:00Z', duration: [amount: minutes, unit: 'minute'],
                due: [date: Instant.parse('2026-11-24T09:00:00Z').plusSeconds(elapsed * 60L).toString()]]
            elapsed += minutes
        }
        initializeSource()
    }

    def cleanup() { server?.stop() }

    private Task normalize(Map raw) { Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone) }
    private RoutedProjections projections() { new RoutedProjections(config, calendar) }
    private ProjectionMembershipChange service() { new ProjectionMembershipChange(new ApplicationStateStore(directory), calendar, todoist, config) }
    private RoutedScheduleReconciler sync() { new RoutedScheduleReconciler(new ApplicationStateStore(directory), calendar, todoist, config) }

    private void initializeSource() {
        originals = tasks.values().collect { normalize(it) }
        CalendarEvent target = projections().fromMembers(originals.collect { projections().member(it) }, 'Work', 'Focus', 'Calendar note')
        source = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(target) + [etag: '"original"', lastModified: '2026-11-24T11:00:00Z'])
        events.clear()
        events['work:' + source.id] = [id: source.id, summary: source.title, description: source.description,
            etag: source.etag, updated: source.lastModified.toString(), destination: 'work',
            extendedProperties: [private: [plannerUid: source.uid]],
            start: [dateTime: source.start.toString()], end: [dateTime: source.end.toString()]]
        // Reinitialization only occurs before a test starts provider mutation.
    }

    private List<CalendarEvent> liveEvents() {
        events.collect { key, value -> calendar.readResource(value.destination == 'work' ? 'Work' : 'Family', value.id.toString()) }
    }

    private Map runtimeRoot(boolean enabled = true, boolean reverse = false) {
        [planner: [timezone: 'UTC', mode: 'preview',
            routing: [mode: 'legacy_rules', labelsToInclude: ['work'], rules: [
                [rule: 'family', calendarName: 'Family'], [rule: 'work', calendarName: 'Work']]],
            sync: [enabled: enabled, interval: 'PT1M'], calendarWriteback: [enabled: reverse],
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            integration: [todoist: [base_url: 'https://api.todoist.com/api/v1', token_env: 'FIXTURE_ONLY'],
                calendar: [provider: 'google_calendar_api', google_calendar_api: [
                    oauth_client_secret_file: 'unused.json', token_store_dir: 'unused-tokens', account_email: 'test@example.test',
                    calendars: ['Work', 'Family'].collect { [name: it, id: it.toLowerCase(), role: 'informational', managed_destination: true] }]],
                state: [plans_dir: directory.resolve('plans').toString(), applications_dir: directory.toString(),
                    decisions_dir: directory.resolve('decisions').toString(), deliveries_dir: directory.resolve('deliveries').toString()]]]]
    }

    private Map cliSync(boolean preview = false, boolean enabled = true, boolean reverse = false) {
        Map root = runtimeRoot(enabled, reverse)
        File file = directory.resolve('fixture.json').toFile()
        file.text = JsonOutput.toJson(root)
        File logging = directory.resolve('logging.groovy').toFile()
        logging.text = 'log4j.rootLogger="OFF"\n'
        def app = new ProductionPlannerOrchestrator(PlannerConfig.fromMap(root),
            ProductionIntegrationConfig.fromMap(root, directory), todoist, todoist, calendar, calendar)
        StringBuilder output = new StringBuilder(), error = new StringBuilder()
        int code = TodoistCalDavSync.run(['-f', file.toString(), '-l', logging.toString(), '--operation',
            preview ? 'planner-sync-preview' : 'planner-sync', '--range-start', '2026-11-24T00:00:00Z',
            '--range-end', '2026-11-25T00:00:00Z'] as String[], output, error,
            { throw new AssertionError('no OAuth bootstrap') }, { throw new AssertionError('no credential import') },
            { a, b, c -> throw new AssertionError('no provisioning') }, { throw new AssertionError('no AI') }, { app })
        [code: code, error: error.toString(), result: output.toString() ? new JsonSlurper().parseText(output.toString()) : null]
    }

    private static class ManualSyncScheduler extends ScheduledThreadPoolExecutor {
        List<Runnable> polls = []
        List<Long> delays = []
        ManualSyncScheduler() { super(1) }
        @Override ScheduledFuture<?> scheduleWithFixedDelay(Runnable action, long initial, long delay, TimeUnit unit) {
            polls << action
            delays << unit.toMillis(delay)
            super.schedule({ } as Runnable, 1L, TimeUnit.DAYS)
        }
    }

    def 'daemon E2E sync cadence works without planning schedules and exposes outage backoff and separate authorities'() {
        given:
        Map root = runtimeRoot()
        root.planner.daemon = [enabled: true, planning_runs: [], startup_connectivity_check: false, shutdown_timeout: 'PT1S',
            retry: [initial_delay: 'PT5S', max_delay: 'PT1M']]
        root.planner.messaging = [enabled: true, provider: 'slack', slack_mode: 'socket_mode', destination: 'C1',
            bot_token_env: 'FIXTURE_BOT', app_token_env: 'FIXTURE_APP']
        def now = new AtomicReference(Instant.parse('2026-11-24T00:00:00Z'))
        def app = new ProductionPlannerOrchestrator(PlannerConfig.fromMap(root),
            ProductionIntegrationConfig.fromMap(root, directory), todoist, todoist, calendar, calendar, { now.get() })
        def scheduler = new ManualSyncScheduler()
        def surface = new InMemoryMessagingSurface()
        def daemon = new SmartPlannerDaemon(app, surface, { now.get() }, scheduler)

        when:
        daemon.start()
        scheduler.polls[0].run()

        then:
        scheduler.delays == [60_000L]
        daemon.statusSnapshot().sync.state == 'SYNCHRONIZED'
        daemon.statusSnapshot().authorities == [forwardSync: true, reverseSync: false, lifecycle: false, planningRuns: 0, planMode: 'preview']
        mutations.isEmpty()
        surface.proposals.isEmpty()

        when:
        now.set(now.get().plusSeconds(60))
        unavailableDestination = 'family'
        scheduler.polls[0].run()

        then:
        daemon.statusSnapshot().sync.state == 'RETRYABLE_FAILURE'
        daemon.statusSnapshot().sync.retryAttempt == 1
        daemon.statusSnapshot().sync.lastSuccessAt == '2026-11-24T00:00:00Z'
        daemon.statusSnapshot().sync.nextRunAt == '2026-11-24T00:01:05Z'

        when:
        unavailableDestination = null
        scheduler.polls[0].run()

        then:
        daemon.statusSnapshot().sync.state == 'RETRYABLE_FAILURE'

        when:
        now.set(now.get().plusSeconds(60))
        tasks.b.due = [date: '2026-11-24T23:20:00Z']
        tasks.b.updated_at = '2026-11-24T12:00:00Z'
        scheduler.polls[0].run()

        then:
        daemon.statusSnapshot().sync.state == 'SYNCHRONIZED'
        events.size() == 3
        taskWrites.isEmpty()
        surface.proposals.isEmpty()
        !directory.resolve('plans').toFile().exists()

        cleanup:
        daemon?.close()
    }

    def 'CLI E2E mirrors included manual and all-day schedules outside work hours while preview and disabled sync never write'() {
        given:
        events.clear()
        tasks.a.labels = ['work', 'manual']
        tasks.a.due = [date: '2026-11-24T23:10:00Z']
        tasks.b.due = [date: '2026-11-24']
        tasks.c.due = null
        String originalTasks = JsonOutput.toJson(tasks)

        when:
        Map preview = cliSync(true)
        Map disabled = cliSync(false, false)

        then:
        preview.code == 0
        preview.result.results.count { it.status == 'preview_create' } == 2
        preview.result.authorities == [forward: false, reverse: false, lifecycle: false, planning: false]
        disabled.code == 2
        disabled.error.contains('planner.sync.enabled')
        events.isEmpty()
        mutations.isEmpty()
        taskWrites.isEmpty()
        !directory.resolve('sync/todoist-items-sync.json').toFile().exists()

        when:
        Map synced = cliSync()
        List<CalendarEvent> actual = liveEvents()

        then:
        synced.code == 0
        synced.result.status == 'synchronized'
        actual.size() == 2
        actual.find { ProjectionMetadata.read(it).value.members[0].taskId == 'a' }.start == Instant.parse('2026-11-24T23:10:00Z')
        CalendarEvent allDay = actual.find { it.allDay }
        allDay.start == Instant.parse('2026-11-24T00:00:00Z')
        allDay.end == Instant.parse('2026-11-25T00:00:00Z')
        JsonOutput.toJson(tasks) == originalTasks
        taskWrites.isEmpty()
        !directory.resolve('recurrence/todoist-items-sync.json').toFile().exists()
        !directory.resolve('plans').toFile().exists()
    }

    def 'CLI E2E discovers a lost-state group before initial creates then splits and relocates without planning'() {
        expect:
        cliSync().code == 0
        mutations.isEmpty()
        store.loadProjections().values()*.status == ['current']

        when:
        tasks.b.due = [date: '2026-11-24T23:20:00Z']
        tasks.b.updated_at = '2026-11-24T12:00:00Z'
        tasks.b.labels = ['work', 'family']
        Map result = cliSync()
        int writes = mutations.size()
        Map echo = cliSync()

        then:
        result.code == 0
        echo.code == 0
        mutations.size() == writes
        events.size() == 3
        CalendarEvent relocated = liveEvents().find { it.calendarName == 'Family' }
        ProjectionMetadata.read(relocated).value.members*.taskId == ['b']
        relocated.start == Instant.parse('2026-11-24T23:20:00Z')
        liveEvents().findAll { it.calendarName == 'Work' }*.start.toSet() == [originals[0].dueTime, originals[2].dueTime] as Set
        taskWrites.isEmpty()
        itemCursors == ['*', 'items-1', 'items-2']
    }

    def 'CLI E2E durably stages positive departure evidence and resumes failed retirement after feed evidence disappears'() {
        given:
        assert cliSync().code == 0
        tasks.remove('b')
        feedItems = [[id: 'b', checked: 1]]
        interruptRetirement = true

        when:
        Map failed = cliSync()

        then:
        failed.code == 1
        failed.error.contains('HTTP 503')
        store.loadSyncOperations().values().any { it.status == 'pending' }
        new ItemsSyncStateStore(directory.resolve('sync')).load().pendingItems*.id == ['b']

        when:
        feedItems = []
        Map resumed = cliSync()
        List<CalendarEvent> actual = liveEvents()

        then:
        resumed.code == 0
        store.loadSyncOperations().values().every { it.status == 'verified' && store.syncProjectionsCommitted(it.id.toString()) }
        new ItemsSyncStateStore(directory.resolve('sync')).load().pendingItems.isEmpty()
        actual.size() == 3
        CalendarEvent historical = actual.find { ProjectionMetadata.read(it).value.historical }
        ProjectionMetadata.read(historical).value.members*.taskId == ['b']
        historical.start == originals[1].dueTime
        historical.end == originals[1].dueTime.plusSeconds(3600)
        actual.findAll { !ProjectionMetadata.read(it).value.historical }*.start.toSet() == [originals[0].dueTime, originals[2].dueTime] as Set
        taskWrites.isEmpty()
    }

    def 'CLI E2E pending relocation refuses routing drift before any further mutation'() {
        given:
        assert cliSync().code == 0
        tasks.b.labels = ['work', 'family']
        tasks.b.updated_at = '2026-11-24T20:00:00Z'
        interruptRetirement = true
        assert cliSync().code == 1
        int writes = mutations.size()
        Map root = runtimeRoot()
        root.planner.routing.rules = root.planner.routing.rules.reverse()

        when:
        application(root).syncSchedules(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('routing changed')
        mutations.size() == writes
        events.containsKey('work:' + source.id)
        liveEvents().any { it.calendarName == 'Family' }
        taskWrites.empty
    }

    def 'CLI E2E withholds all initial creation for damaged membership and all writes during a destination outage'() {
        given:
        String original = events['work:' + source.id].description
        events['work:' + source.id].description = original.readLines().findAll { !it.startsWith(ProjectionMetadata.PREFIX) }.join('\n')

        when:
        Map damaged = cliSync()

        then:
        damaged.code == 1
        damaged.result.status == 'conflict'
        damaged.result.results.any { it.reason?.contains('membership') }
        mutations.isEmpty()

        when:
        events['work:' + source.id].description = original
        unavailableDestination = 'family'
        String cursor = new ItemsSyncStateStore(directory.resolve('sync')).load().syncToken
        Map unavailable = cliSync()

        then:
        unavailable.code == 1
        unavailable.error.contains('HTTP 503')
        new ItemsSyncStateStore(directory.resolve('sync')).load().syncToken == cursor
        mutations.isEmpty()
        taskWrites.isEmpty()

        when:
        unavailableDestination = null
        Map recovered = cliSync()

        then:
        recovered.code == 0
        events.size() == 1
        mutations.isEmpty()
    }

    def 'CLI E2E group end-only resize requires separate reverse authority and echoes do not resize twice'() {
        given:
        assert cliSync().code == 0
        events['work:' + source.id].end.dateTime = '2026-11-24T10:10:00Z'
        events['work:' + source.id].updated = '2026-11-24T16:00:00Z'
        events['work:' + source.id].etag = '"human"'
        allowTaskWrites = true

        when:
        Map withheld = cliSync()

        then:
        withheld.code == 1
        taskWrites.isEmpty()
        mutations.isEmpty()

        when:
        Map resized = cliSync(false, true, true)
        int writes = taskWrites.size()
        Map echo = cliSync(false, true, true)

        then:
        resized.code == 0
        echo.code == 0
        tasks.values()*.duration*.amount == [20, 40, 10]
        tasks.values()*.due*.date == ['2026-11-24T09:00:00Z', '2026-11-24T09:20:00Z', '2026-11-24T10:00:00Z']
        taskWrites.size() == writes
        events.size() == 1
        liveEvents()[0].end == Instant.parse('2026-11-24T10:10:00Z')
        tasks.values().every { it.description == 'Human note' && it.deadline == [date: '2026-12-01'] && it.completed_count == 0 }
    }

    def 'CLI E2E partial transform recovery stops when reverse authority is revoked and never repeats committed member writes'() {
        given:
        assert cliSync().code == 0
        events['work:' + source.id].end.dateTime = '2026-11-24T10:10:00Z'
        events['work:' + source.id].updated = '2026-11-24T16:00:00Z'
        events['work:' + source.id].etag = '"human"'
        allowTaskWrites = true
        interruptDurationTask = 'b'

        when:
        Map partial = cliSync(false, true, true)
        List<String> committedWrites = new ArrayList<>(taskWrites)
        Map disabled = cliSync()

        then:
        partial.code == 1
        partial.error.contains('HTTP 503')
        disabled.code == 1
        disabled.result.status == 'pending'
        taskWrites == committedWrites
        tasks.a.duration.amount == 20
        tasks.b.duration.amount == 40
        tasks.c.duration.amount == 15

        when:
        Map recovered = cliSync(false, true, true)

        then:
        recovered.code == 0
        taskWrites.size() == committedWrites.size() + 2
        tasks.values()*.duration*.amount == [20, 40, 10]
        tasks.values()*.due*.date == ['2026-11-24T09:00:00Z', '2026-11-24T09:20:00Z', '2026-11-24T10:00:00Z']
        store.loadSyncOperations().values().every { it.status == 'verified' }
        events.size() == 1
    }

    def 'planning E2E preserves reconciled group and outside-hours placements rather than an old proposal'() {
        given:
        Map root = runtimeRoot()
        def app = new ProductionPlannerOrchestrator(PlannerConfig.fromMap(root),
            ProductionIntegrationConfig.fromMap(root, directory), todoist, todoist, calendar, calendar,
            { Instant.parse('2026-11-24T00:00:00Z') })
        String unchanged = JsonOutput.toJson(tasks)
        Instant start = Instant.parse('2026-11-24T00:00:00Z'), end = start.plusSeconds(86400)

        when:
        Plan first = app.preview(start, end)

        then:
        first.scheduledBlocks.size() == 1
        first.scheduledBlocks[0].memberIntervals*.start == originals*.dueTime
        first.metrics.scheduledTaskCount == 3
        first.changes*.type == ['keep', 'keep', 'keep']
        mutations.isEmpty()
        taskWrites.isEmpty()
        !store.projectionsPath().toFile().exists()

        when:
        assert cliSync().code == 0
        tasks.b.due.date = '2026-11-24T23:10:00Z'
        tasks.b.updated_at = '2026-11-24T17:00:00Z'
        Map synced = cliSync()
        int writes = mutations.size()
        Plan second = app.preview(start, end, first.id)
        Plan loaded = app.planStore.load(second.id)

        then:
        synced.code == 0
        second.scheduledBlocks.find { it.taskIds == ['b'] }.start == Instant.parse('2026-11-24T23:10:00Z')
        second.scheduledBlocks.find { it.taskIds == ['a'] }.start == originals[0].dueTime
        second.scheduledBlocks.find { it.taskIds == ['c'] }.start == originals[2].dueTime
        second.metrics.scheduledTaskCount == 3
        second.humanDiff.contains('Task b')
        PlanHash.compute(loaded) == PlanHash.compute(second)
        loaded.metrics.routedSource.tasks.find { it.id == 'b' }.todoistDue.date == '2026-11-24T23:10:00Z'
        loaded.metrics.routedSource.projections.size() == 3
        mutations.size() == writes
        taskWrites.isEmpty()
        JsonOutput.toJson(tasks) != unchanged
    }

    def 'planning E2E replaces only eligible projections and keeps appointments plus manual tasks occupied'() {
        given:
        events.clear()
        tasks.a.labels = ['work', 'family']
        tasks.b.labels = ['work', 'manual']
        tasks.b.due.date = '2026-11-24T09:00:00Z'
        tasks.c.due.date = '2026-11-24T16:00:00Z'
        assert cliSync().code == 0
        events['work:appointment'] = [id: 'appointment', iCalUID: 'appointment@example.test', summary: 'Real appointment', destination: 'work',
            etag: '"appointment"', updated: '2026-11-24T08:00:00Z',
            start: [dateTime: '2026-11-24T10:00:00Z'], end: [dateTime: '2026-11-24T11:00:00Z']]
        Map root = runtimeRoot()
        root.planner.stability = [keep_manual_moves: false, freeze_within: 'PT0S']
        root.planner.availability.event_rules = [[name: 'appointment', title_regex: 'Real appointment', role: 'hard_blocker']]
        def app = new ProductionPlannerOrchestrator(PlannerConfig.fromMap(root),
            ProductionIntegrationConfig.fromMap(root, directory), todoist, todoist, calendar, calendar,
            { Instant.parse('2026-11-24T00:00:00Z') })
        int writes = mutations.size()

        when:
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        plan.scheduledBlocks.collectMany { it.taskIds }.toSet() == ['a', 'c'] as Set
        plan.scheduledBlocks.every { it.taskIds.size() == 1 }
        plan.scheduledBlocks.every { !it.start.isBefore(Instant.parse('2026-11-24T11:00:00Z')) }
        plan.slots.any { it.start == Instant.parse('2026-11-24T11:00:00Z') && it.end == Instant.parse('2026-11-24T17:00:00Z') }
        mutations.size() == writes
        taskWrites.isEmpty()

        when:
        tasks.c.duration.amount = 46
        app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        def drift = thrown(IllegalStateException)
        drift.message.contains('reconcile before planning')
        mutations.size() == writes

        when:
        unavailableDestination = 'family'
        app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        thrown(GoogleCalendarApiGateway.GoogleCalendarGatewayException)
        mutations.size() == writes
        taskWrites.isEmpty()
    }

    def 'planning E2E never partially moves a verified group containing a newly manual member'() {
        given:
        tasks.b.labels = ['work', 'manual']
        Map root = runtimeRoot()
        root.planner.stability = [keep_manual_moves: false, freeze_within: 'PT0S']
        def app = new ProductionPlannerOrchestrator(PlannerConfig.fromMap(root),
            ProductionIntegrationConfig.fromMap(root, directory), todoist, todoist, calendar, calendar,
            { Instant.parse('2026-11-24T00:00:00Z') })

        when:
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        plan.scheduledBlocks.size() == 1
        plan.scheduledBlocks[0].taskIds == ['a', 'b', 'c']
        plan.scheduledBlocks[0].frozen
        plan.scheduledBlocks[0].memberIntervals*.start == originals*.dueTime
        plan.tasks.find { it.id == 'b' }.manual
        mutations.isEmpty()
        taskWrites.isEmpty()
    }

    def 'approved routed plan E2E supersedes current placement and reapplies only from verified own evidence'() {
        given:
        Map root = runtimeRoot()
        root.planner.mode = 'approval_required'
        root.planner.stability = [keep_manual_moves: false, freeze_within: 'PT0S']
        root.planner.availability.working_windows.weekday = ['12:00-17:00']
        def app = application(root)
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))
        Approval approval = approval(plan)
        String protectedFields = JsonOutput.toJson(tasks.values().collect { it.subMap(['content', 'description', 'labels', 'deadline', 'completed_count', 'duration']) })

        expect:
        mutations.isEmpty()
        taskWrites.isEmpty()
        app.apply(plan.id).overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED

        when:
        allowTaskWrites = true
        ApplicationReceipt applied = app.apply(plan.id, approval)

        then:
        applied.errors.isEmpty()
        applied.overallStatus == ApplyItemStatus.APPLIED
        applied.items.size() == 3
        !events.containsKey('work:' + source.id)
        plan.scheduledBlocks.every { block -> block.taskIds.every { id ->
            normalize(tasks[id]).dueTime == (block.intervalFor(id)?.start ?: block.start)
        } }
        liveEvents().collectMany { ProjectionMetadata.read(it).value.members*.taskId }.toSet() == ['a', 'b', 'c'] as Set
        JsonOutput.toJson(tasks.values().collect { it.subMap(['content', 'description', 'labels', 'deadline', 'completed_count', 'duration']) }) == protectedFields

        when:
        int writes = mutations.size() + taskWrites.size()
        ApplicationReceipt repeated = application(root).apply(plan.id, approval)

        then:
        repeated.errors.isEmpty()
        repeated.overallStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
        mutations.size() + taskWrites.size() == writes

        when:
        tasks.b.due.date = '2026-11-24T23:00:00Z'
        tasks.b.updated_at = '2026-11-24T23:00:00Z'
        ApplicationReceipt stale = app.apply(plan.id, approval)

        then:
        stale.overallStatus == ApplyItemStatus.FAILED
        !stale.errors.isEmpty()
        mutations.size() + taskWrites.size() == writes
    }

    def 'approved routed apply E2E recovers an ambiguous create without replaying tasks or adopting unrelated resources'() {
        given:
        Map root = runtimeRoot()
        root.planner.mode = 'approval_required'
        root.planner.stability = [keep_manual_moves: false, freeze_within: 'PT0S']
        root.planner.availability.working_windows.weekday = ['12:00-17:00']
        def app = application(root)
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))
        allowTaskWrites = true
        interruptAfterCreate = 1

        when:
        ApplicationReceipt partial = app.apply(plan.id, approval(plan))
        int dueWrites = taskWrites.size()

        then:
        !partial.errors.isEmpty()
        events.containsKey('work:' + source.id)
        store.loadSyncOperations().values().any { it.status == 'pending' && it.intent.type == 'routed-plan' }

        when:
        ApplicationReceipt recovered = application(root).apply(plan.id, approval(plan))

        then:
        recovered.errors.isEmpty()
        recovered.overallStatus == ApplyItemStatus.APPLIED
        taskWrites.size() == dueWrites
        mutations.count { it.startsWith('create:') } == plan.scheduledBlocks.size()
        !events.containsKey('work:' + source.id)
        store.loadSyncOperations().values().every { it.status == 'verified' }
    }

    def 'manual sync between routed proposal and approval E2E makes the proposal stale without overwriting the edit'() {
        given:
        Map root = runtimeRoot()
        root.planner.mode = 'approval_required'
        def app = application(root)
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))
        Approval approval = approval(plan)
        assert cliSync().code == 0
        tasks.b.due.date = '2026-11-24T23:10:00Z'
        tasks.b.updated_at = '2026-11-24T17:00:00Z'
        assert cliSync().code == 0
        int writes = mutations.size()

        when:
        ApplicationReceipt stale = app.apply(plan.id, approval)

        then:
        stale.overallStatus == ApplyItemStatus.FAILED
        !stale.errors.isEmpty()
        mutations.size() == writes
        taskWrites.isEmpty()
        tasks.b.due.date == '2026-11-24T23:10:00Z'
    }

    private ProductionPlannerOrchestrator application(Map root) {
        new ProductionPlannerOrchestrator(PlannerConfig.fromMap(root), ProductionIntegrationConfig.fromMap(root, directory),
            todoist, todoist, calendar, calendar, { Instant.parse('2026-11-24T00:00:00Z') })
    }

    def 'routed approval E2E rejects a later #edit before any dependent provider mutation'() {
        given:
        Map root = runtimeRoot()
        root.planner.mode = 'approval_required'
        def app = application(root)
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))
        Approval approved = approval(plan)
        switch (edit) {
            case 'native duration': tasks.b.duration.amount = 61; break
            case 'route': tasks.b.labels = ['work', 'family']; break
            case 'group interval': events['work:' + source.id].end.dateTime = '2026-11-24T11:00:00Z'; break
            case 'appointment':
                events['work:appointment'] = [id: 'appointment', iCalUID: 'appointment@example.test', summary: 'New appointment',
                    destination: 'work', etag: '"appointment"', start: [dateTime: '2026-11-24T09:00:00Z'],
                    end: [dateTime: '2026-11-24T10:00:00Z']]
                break
        }

        when:
        ApplicationReceipt result = app.apply(plan.id, approved)

        then:
        result.overallStatus == ApplyItemStatus.FAILED
        !result.errors.isEmpty()
        mutations.isEmpty()
        taskWrites.isEmpty()
        store.loadSyncOperations().isEmpty()

        where:
        edit << ['native duration', 'route', 'group interval', 'appointment']
    }

    def 'routed safe apply E2E preserves frozen groups and fully automated refusal'() {
        given:
        Map root = runtimeRoot()
        root.planner.mode = 'apply_safe_changes'
        root.planner.stability = [keep_manual_moves: false, freeze_within: 'PT24H']
        def app = application(root)
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        expect:
        plan.scheduledBlocks.every { it.frozen }
        app.applySafe(plan.id).overallStatus == ApplyItemStatus.SKIPPED_PROTECTED
        mutations.isEmpty()
        taskWrites.isEmpty()

        when:
        root.planner.mode = 'fully_automated'
        def automated = application(root)
        Plan refused = automated.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        automated.apply(refused.id, approval(refused)).overallStatus == ApplyItemStatus.SKIPPED_UNAPPROVED
        mutations.isEmpty()
        taskWrites.isEmpty()
    }

    private static Approval approval(Plan plan) {
        Approval.builder().id('fixture-approval').planId(plan.id).planVersion(plan.version).planHash(PlanHash.compute(plan))
            .approvedAt(Instant.parse('2026-11-24T00:01:00Z')).approvedBy('fixture-owner').build()
    }

    def 'fake Slack surface E2E reports a stale routed approval instead of claiming application'() {
        given:
        Map root = runtimeRoot()
        root.planner.mode = 'approval_required'
        root.planner.daemon = [enabled: true, startup_connectivity_check: false, shutdown_timeout: 'PT1S',
            planning_runs: [[name: 'daily', horizon: 'P1D', interval: 'PT10M', run_on_startup: false]]]
        root.planner.messaging = [enabled: true, provider: 'slack', slack_mode: 'socket_mode', destination: 'C1',
            bot_token_env: 'FIXTURE_BOT', app_token_env: 'FIXTURE_APP']
        root.planner.integration.feedback = [allowed_actors: ['U1'], rules: [[name: 'approve', pattern: '^yes$', action: 'approve']]]
        def app = application(root)
        def surface = new InMemoryMessagingSurface()
        def daemon = new SmartPlannerDaemon(app, surface, { Instant.parse('2026-11-24T00:00:00Z') }, new ManualSyncScheduler())
        daemon.start()
        def conversation = daemon.runNow('daily')
        assert cliSync().code == 0
        tasks.b.due.date = '2026-11-24T23:10:00Z'
        tasks.b.updated_at = '2026-11-24T17:00:00Z'
        assert cliSync().code == 0
        int writes = mutations.size()

        when:
        surface.emit(new MessagingEvent(eventId: 'approve-stale', type: 'thread_reply', actorId: 'U1',
            channelId: 'C1', threadTs: conversation.threadTs, messageTs: '1000.2', text: 'yes'))

        then:
        surface.replies*.text.any { it.contains('Approval did not complete: `ERROR`') && it.contains('fresh proposal') }
        !surface.replies*.text.any { it.contains('Approved plan applied') }
        new ConversationStore(app.integrationConfig.deliveriesDir.resolve('conversations')).find('C1', conversation.threadTs).status == 'APPLY_ERROR'
        mutations.size() == writes
        taskWrites.isEmpty()
        tasks.b.due.date == '2026-11-24T23:10:00Z'

        cleanup:
        daemon?.close()
    }

    def 'approved routed plan E2E schedules a previously undated task without sync inventing a schedule'() {
        given:
        events.clear()
        tasks.keySet().retainAll(['a'])
        tasks.a.due = null
        Map root = runtimeRoot()
        root.planner.mode = 'approval_required'
        def app = application(root)
        assert cliSync().code == 0

        when:
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        events.isEmpty()
        tasks.a.due == null
        taskWrites.isEmpty()

        when:
        allowTaskWrites = true
        ApplicationReceipt result = app.apply(plan.id, approval(plan))

        then:
        result.errors.isEmpty()
        result.overallStatus == ApplyItemStatus.APPLIED
        tasks.a.due.date == '2026-11-24T09:00:00Z'
        taskWrites == ['due']
        liveEvents().size() == 1
        liveEvents()[0].start == Instant.parse('2026-11-24T09:00:00Z')
    }

    def 'approved routed grouping E2E preserves native recurrence and replaces individual projections only after exact approval'() {
        given:
        events.clear()
        tasks.b.due += [string: 'every day', is_recurring: true, lang: 'en', timezone: null]
        assert cliSync().code == 0
        Set<String> individualIds = events.keySet().toSet()
        Map root = runtimeRoot()
        root.planner.mode = 'approval_required'
        root.planner.stability = [keep_manual_moves: false, freeze_within: 'PT0S']
        root.planner.batching = [enabled: true, max_focus_block_minutes: 180, minimum_focus_block_minutes: 30]
        root.planner.availability.working_windows.weekday = ['12:00-17:00']
        def app = application(root)
        Plan plan = app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))
        int writes = mutations.size()

        expect:
        plan.scheduledBlocks.size() == 1
        plan.scheduledBlocks[0].taskIds.toSet() == ['a', 'b', 'c'] as Set
        events.size() == 3
        taskWrites.isEmpty()

        when:
        allowTaskWrites = true
        ApplicationReceipt result = app.apply(plan.id, approval(plan))

        then:
        result.errors.isEmpty()
        result.overallStatus == ApplyItemStatus.APPLIED
        events.size() == 1
        !events.keySet().intersect(individualIds)
        taskWrites.count { it == 'recurring_due' } == 1
        tasks.b.due.subMap(['string', 'is_recurring', 'lang', 'timezone']) ==
            [string: 'every day', is_recurring: true, lang: 'en', timezone: null]
        tasks.b.completed_count == 0
        CalendarEvent group = liveEvents()[0]
        group.start == Instant.parse('2026-11-24T12:00:00Z')
        group.end == Instant.parse('2026-11-24T13:45:00Z')
        ProjectionMetadata.read(group).value.members*.effectiveMinutes.sum() == 105
        mutations.drop(writes)[0].startsWith('create:')
        mutations.drop(writes).drop(1).every { it.startsWith('delete:') }
        store.loadProjections().values().count { it.status == 'current' } == 1
        store.loadProjections().values().count { it.status == 'retired' } == 3

        when:
        writes = mutations.size()
        Map echo = cliSync()

        then:
        echo.code == 0
        events.size() == 1
        mutations.size() == writes
    }

    def 'project-included manual tasks mirror but remain excluded from optimizer and lifecycle scope'() {
        given:
        events.clear()
        tasks.a.labels = ['manual']
        Map root = runtimeRoot()
        root.planner.routing = [mode: 'legacy_rules', projectsToInclude: ['Work'], rules: [
            [rule: 'family', calendarName: 'Family'], [rule: 'p:Work', calendarName: 'Work']]]
        def app = new ProductionPlannerOrchestrator(PlannerConfig.fromMap(root),
            ProductionIntegrationConfig.fromMap(root, directory), todoist, todoist, calendar, calendar)
        String original = JsonOutput.toJson(tasks)

        when:
        Map result = app.syncSchedules(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        result.status == 'synchronized'
        events.size() == 3
        Task manual = Task.fromTodoistMap(tasks.a + [project_name: 'Work'], app.plannerConfig.durationResolver,
            app.plannerConfig.manualLabel, app.plannerConfig.timezone)
        !app.plannerConfig.planningEligible(manual)
        app.plannerConfig.projectionDestination(manual) == 'Work'
        JsonOutput.toJson(tasks) == original
        taskWrites.isEmpty()
        !directory.resolve('recurrence').toFile().exists()

        cleanup:
        app?.close()
    }

    def 'provider E2E splits edited members into individual routes and retains maximal unchanged runs'() {
        given:
        edited.each { String id ->
            tasks[id].updated_at = '2026-11-24T12:00:00Z'
            tasks[id].due = [date: '2026-11-24T22:10:00Z']
            if (relocate) tasks[id].labels = ['work', 'family']
        }
        String untouched = JsonOutput.toJson(tasks)

        when:
        Map result = service().apply(source, originals)
        List<CalendarEvent> actual = liveEvents()

        then:
        result.status == 'verified'
        JsonOutput.toJson(tasks) == untouched
        !events.containsKey('work:' + source.id)
        actual.collect { ProjectionMetadata.read(it).value.members*.taskId }.toSet() == expectedRuns.toSet()
        actual.findAll { ProjectionMetadata.read(it).value.members.any { !edited.contains(it.taskId) } }.every { event ->
            ProjectionMetadata.read(event).value.members.every { member ->
                Task task = originals.find { it.id == member.taskId }
                member.start == task.dueTime.toString() && member.effectiveMinutes == task.effectiveDuration.toMinutes()
            }
        }
        actual.findAll { ProjectionMetadata.read(it).value.members.any { edited.contains(it.taskId) } }.every {
            it.start == Instant.parse('2026-11-24T22:10:00Z') && it.calendarName == (relocate ? 'Family' : 'Work')
        }
        store.loadProjections().values().count { it.status == 'current' } == expectedRuns.size()

        where:
        edited          | relocate | expectedRuns
        ['b']           | false    | [['a'], ['b'], ['c']]
        ['c']           | true     | [['a', 'b'], ['c']]
        ['a']           | false    | [['a'], ['b', 'c']]
        ['a', 'c']      | true     | [['a'], ['b'], ['c']]
        ['a', 'b', 'c'] | false    | [['a'], ['b'], ['c']]
    }

    def 'provider E2E departure creates only completed member history and restarts after retirement failure'() {
        given:
        interruptRetirement = true
        Map items = [:]
        if (kind == 'completed') tasks.b.checked = 1
        if (kind == 'completed_absent') { tasks.remove('b'); items.b = [id: 'b', checked: 1] }
        if (kind == 'deleted') { tasks.remove('b'); items.b = [id: 'b', is_deleted: 1] }
        if (kind == 'due_removed') tasks.b.due = null
        if (kind == 'ineligible') tasks.b.labels = []
        if (tasks.b != null) tasks.b.updated_at = '2026-11-24T12:00:00Z'
        boolean completed = kind.startsWith('completed')
        String untouched = JsonOutput.toJson(tasks)

        when:
        service().apply(source, originals, items)

        then:
        thrown(RuntimeException)
        store.loadProjections().isEmpty()
        store.loadSyncOperations().values().first().status == 'pending'

        when:
        Map result = service().resume(store.loadSyncOperations().keySet().first())
        List<CalendarEvent> actual = liveEvents()

        then:
        result.status == 'verified'
        JsonOutput.toJson(tasks) == untouched
        actual.collect { ProjectionMetadata.read(it).value.members*.taskId }.toSet() ==
            (completed ? [['a'], ['b'], ['c']] : [['a'], ['c']]).toSet()
        actual.count { ProjectionMetadata.read(it).value.historical } == (completed ? 1 : 0)
        actual.findAll { ProjectionMetadata.read(it).value.historical }.every {
            it.start == Instant.parse('2026-11-24T09:30:00Z') && it.end == Instant.parse('2026-11-24T10:30:00Z')
        }
        mutations.count { it.startsWith('create:') } == actual.size()
        mutations.count { it.startsWith('delete:') } == 1

        where:
        kind << ['completed', 'completed_absent', 'deleted', 'due_removed', 'ineligible']
    }

    def 'provider E2E native advancement projects fresh successor and old interval without lifecycle writes'() {
        given:
        tasks.a.due += [is_recurring: true, string: 'every day', lang: 'en', timezone: null]
        Task before = normalize(tasks.a)
        def marker = new LifecycleMarker([taskId: 'a', completedCount: 0, deadlineMode: 'date',
            deadlineSource: 'user', deadlineDate: '2026-12-01', lastVerifiedDue: before.todoistDue.date,
            recurrenceFingerprint: LifecycleSupport.fingerprint(before.todoistDue), markerGeneration: 1, lastCommandId: 'fixture'])
        tasks.a.description = new LifecycleMarkerCodec().merge('Human note', marker)
        tasks.a.labels = ['work', 'smartplanner-seen']
        initializeSource()
        tasks.a.completed_count = 1
        tasks.a.due.date = '2026-11-25T23:17:00Z'
        if (successorMode == 'undated') tasks.a.due = null
        if (successorMode == 'ineligible') tasks.a.labels = ['smartplanner-seen']
        tasks.a.updated_at = '2026-11-24T12:00:00Z'
        String untouched = JsonOutput.toJson(tasks)

        when:
        Map result = service().apply(source, originals)
        List<CalendarEvent> actual = liveEvents()

        then:
        JsonOutput.toJson(tasks) == untouched
        actual.size() == (successorMode == 'dated' ? 3 : 2)
        CalendarEvent history = actual.find { ProjectionMetadata.read(it).value.historical }
        ProjectionMetadata.read(history).value.members*.occurrenceKey == ['a:0']
        history.start == Instant.parse('2026-11-24T09:00:00Z') && history.end == Instant.parse('2026-11-24T09:30:00Z')
        CalendarEvent successor = actual.find { ProjectionMetadata.read(it).value.members*.occurrenceKey == ['a:1'] }
        successorMode == 'dated' ? successor.start == Instant.parse('2026-11-25T23:17:00Z') &&
            successor.end == Instant.parse('2026-11-25T23:47:00Z') : successor == null
        result.intent.departureEvidence.reports.a == 'advanced_' + (successorMode == 'dated' ? 'projected' : successorMode)
        CalendarEvent residual = actual.find { ProjectionMetadata.read(it).value.members*.taskId == ['b', 'c'] }
        residual.start == Instant.parse('2026-11-24T09:30:00Z') && residual.end == Instant.parse('2026-11-24T10:45:00Z')

        where:
        successorMode << ['dated', 'undated', 'ineligible']
    }

    def 'absence and unexplained occurrence changes cannot authorize retirement'() {
        given:
        if (absent) tasks.remove('b') else tasks.b.completed_count = 1

        when:
        service().apply(source, originals)

        then:
        thrown(IllegalStateException)
        mutations.isEmpty()
        store.loadSyncOperations().isEmpty()

        where:
        absent << [true, false]
    }

    def 'task edit after target creation preserves source and blocks recovery rather than retiring new intent'() {
        given:
        tasks.b.due = null
        tasks.b.updated_at = '2026-11-24T12:00:00Z'
        afterCreate = { tasks.c.due = [date: '2026-11-24T20:00:00Z'] }

        when:
        service().apply(source, originals)

        then:
        thrown(IllegalStateException)
        events.containsKey('work:' + source.id)
        mutations.every { it.startsWith('create:') }

        when:
        service().resume(store.loadSyncOperations().keySet().first())

        then:
        thrown(IllegalStateException)
        events.containsKey('work:' + source.id)
        store.loadProjections().isEmpty()
    }

    def 'restart after each ambiguous completed-member replacement creates no duplicate history or residuals'() {
        given:
        tasks.b.checked = 1
        interruptAfterCreate = checkpoint

        when:
        service().apply(source, originals)

        then:
        thrown(RuntimeException)
        events.containsKey('work:' + source.id)
        mutations.count { it.startsWith('create:') } == checkpoint
        store.loadProjections().isEmpty()

        when:
        Map result = service().resume(store.loadSyncOperations().keySet().first())

        then:
        result.status == 'verified'
        liveEvents().size() == 3
        liveEvents().count { ProjectionMetadata.read(it).value.historical } == 1
        mutations.count { it.startsWith('create:') } == 3
        mutations.count { it.startsWith('delete:') } == 1

        where:
        checkpoint << [1, 2, 3]
    }

    def 'duration-only member edit does not compact unaffected tasks and all departures leave no active coverage'() {
        given:
        if (allDepart) {
            tasks.values().each { it.due = null; it.updated_at = '2026-11-24T12:00:00Z' }
        } else {
            tasks.c.duration = [amount: 30, unit: 'minute']
            tasks.c.updated_at = '2026-11-24T12:00:00Z'
        }
        String untouched = JsonOutput.toJson(tasks)

        when:
        service().apply(source, originals)
        List<CalendarEvent> actual = liveEvents()

        then:
        JsonOutput.toJson(tasks) == untouched
        actual.size() == (allDepart ? 0 : 2)
        allDepart || actual.find { ProjectionMetadata.read(it).value.members*.taskId == ['a', 'b'] }.end == Instant.parse('2026-11-24T10:30:00Z')
        allDepart || actual.find { ProjectionMetadata.read(it).value.members*.taskId == ['c'] }.end == Instant.parse('2026-11-24T11:00:00Z')
        !events.containsKey('work:' + source.id)

        where:
        allDepart << [false, true]
    }

    def 'missing equal or older task clocks withhold member edits without remote mutation'() {
        given:
        tasks.b.due = [date: '2026-11-24T22:00:00Z']
        tasks.b.updated_at = updated

        when:
        service().apply(source, originals)

        then:
        thrown(IllegalStateException)
        mutations.isEmpty()
        store.loadSyncOperations().isEmpty()

        where:
        updated << [null, '2026-11-24T10:00:00Z', '2026-11-24T11:00:00Z']
    }

    def 'provider E2E creates current outside-hours or all-day schedules and mirrors fresh Todoist edits without planning'() {
        given:
        events.clear()
        tasks.a.due = [date: date]
        Task task = normalize(tasks.a)
        String untouched = JsonOutput.toJson(tasks)

        when:
        Map preview = sync().project(task, true)

        then:
        preview.status == 'preview_create'
        mutations.isEmpty()

        when:
        Map created = sync().project(task)
        CalendarEvent initial = liveEvents().first()

        then:
        created.status == 'verified'
        initial.start == task.dueTime
        initial.allDay == task.allDayDue
        JsonOutput.toJson(tasks) == untouched

        when: 'a later Todoist edit is mirrored, not an optimizer-selected time'
        tasks.a.due = [date: '2026-11-25T21:17:00Z']
        tasks.a.duration = [amount: 47, unit: 'minute']
        tasks.a.updated_at = '2026-11-24T14:00:00Z'
        Map result = sync().reconcile(initial, [normalize(tasks.a)], true)
        CalendarEvent actual = liveEvents().first()

        then:
        result.status == 'verified'
        actual.start == Instant.parse('2026-11-25T21:17:00Z')
        actual.end == Instant.parse('2026-11-25T22:04:00Z')
        !actual.allDay
        taskWrites.isEmpty()
        sync().reconcile(actual, [normalize(tasks.a)], true).status == 'unchanged'
        mutations.size() == 2

        when: 'the user explicitly converts the timed task back to date-only'
        tasks.a.due = [date: '2026-11-27']
        tasks.a.updated_at = '2026-11-24T20:00:00Z'
        Map reverted = sync().reconcile(actual, [normalize(tasks.a)], true)
        CalendarEvent allDay = liveEvents().first()

        then:
        reverted.status == 'verified'
        allDay.allDay
        allDay.start == Instant.parse('2026-11-27T00:00:00Z')
        allDay.end == Instant.parse('2026-11-28T00:00:00Z')
        events.values().first().start == [date: '2026-11-27']
        taskWrites.empty

        where:
        date << ['2026-11-24T23:41:00Z', '2026-11-24']
    }

    def 'provider E2E end-only resize or drag writes only authorized schedule fields and suppresses echoes'() {
        given:
        events.clear()
        sync().project(normalize(tasks.a))
        CalendarEvent initial = liveEvents().first()
        Map raw = events.values().first()
        raw.start.dateTime = start
        raw.end.dateTime = end
        raw.etag = '"human"'; raw.updated = '2026-11-24T16:00:00Z'
        CalendarEvent edited = liveEvents().first()
        Map protectedFields = new JsonSlurper().parseText(JsonOutput.toJson(tasks.a.findAll { !(it.key in ['due', 'duration', 'updated_at']) })) as Map
        allowTaskWrites = true

        when:
        Map preview = sync().reconcile(edited, [normalize(tasks.a)], true, true)

        then:
        preview.status == 'preview_reverse'
        taskWrites.isEmpty()
        sync().reconcile(edited, [normalize(tasks.a)], false).status == 'reverse_disabled'

        when:
        Map result = sync().reconcile(edited, [normalize(tasks.a)], true)
        CalendarEvent actual = liveEvents().first()

        then:
        result.status == 'verified'
        tasks.a.due.date == start
        tasks.a.duration.amount == minutes
        tasks.a.findAll { !(it.key in ['due', 'duration', 'updated_at']) } == protectedFields
        taskWrites == expectedWrites
        sync().reconcile(actual, [normalize(tasks.a)], true).status == 'unchanged'
        mutations.size() == 2

        where:
        start                  | end                    | minutes | expectedWrites
        '2026-11-24T09:00:00Z' | '2026-11-24T09:45:00Z' | 45      | ['duration']
        '2026-11-24T20:10:00Z' | '2026-11-24T20:40:00Z' | 30      | ['due']
    }

    def 'provider E2E restores a retired projection when #removed returns without replaying stale creates'() {
        given:
        events.clear()
        Map created = sync().project(normalize(tasks.a))
        CalendarEvent initial = liveEvents().first()
        if (removed == 'Due') tasks.a.due = null
        else tasks.a.labels = []
        tasks.a.updated_at = '2026-11-24T20:00:00Z'

        when:
        sync().reconcile(initial, [normalize(tasks.a)], true)

        then:
        events.isEmpty()
        store.loadProjections().values().every { it.status == 'retired' }

        when:
        tasks.a.labels = ['work']
        tasks.a.due = [date: '2026-11-25T22:13:00Z']
        tasks.a.updated_at = '2026-11-24T21:00:00Z'
        Map restored = sync().project(normalize(tasks.a))

        then:
        restored.status == 'verified'
        liveEvents().size() == 1
        liveEvents().first().start == Instant.parse('2026-11-25T22:13:00Z')
        liveEvents().first().id != initial.id
        tombstones['work:' + initial.id].status == 'cancelled'
        store.loadProjections().values().count { it.status == 'current' } == 1
        taskWrites.empty

        when:
        store.commitSyncProjections(created.id.toString())

        then:
        thrown(IllegalStateException)
        liveEvents().first().start == Instant.parse('2026-11-25T22:13:00Z')

        where:
        removed << ['Due', 'inclusion']
    }

    def 'provider E2E return relocation allocates a fresh Google lifetime and resumes its unknown create exactly once'() {
        given:
        events.clear()
        sync().project(normalize(tasks.a))
        CalendarEvent initial = liveEvents().first()
        tasks.a.labels = ['work', 'family']; tasks.a.updated_at = '2026-11-24T20:00:00Z'
        sync().reconcile(initial, [normalize(tasks.a)], true)
        CalendarEvent family = liveEvents().first()
        tasks.a.labels = ['work']; tasks.a.updated_at = '2026-11-24T21:00:00Z'
        interruptAfterCreate = 3

        when:
        sync().reconcile(family, [normalize(tasks.a)], true)

        then:
        thrown(RuntimeException)
        mutations.count { it.startsWith('create:') } == 3

        when:
        Map pending = store.loadSyncOperations().values().find { it.status == 'pending' }
        String journaledId = pending.intent.resources.values().find { it.action == 'create' }.event.id
        service().resume(pending.id.toString())
        CalendarEvent restored = liveEvents().first()

        then:
        liveEvents().size() == 1
        restored.uid == initial.uid
        restored.id == journaledId
        restored.id != initial.id
        restored.calendarName == 'Work'
        restored.start == initial.start && restored.end == initial.end
        tombstones['work:' + initial.id].status == 'cancelled'
        tombstones['family:' + family.id].status == 'cancelled'
        mutations.count { it.startsWith('create:') } == 3
        store.loadProjections().values().count { it.status == 'current' } == 1
        taskWrites.empty
    }

    def 'provider E2E competing edits cancellation and lost-state disagreements never choose a winner'() {
        given:
        events.clear()
        sync().project(normalize(tasks.a))
        CalendarEvent initial = liveEvents().first()

        when:
        events.values().first().end.dateTime = '2026-11-24T09:50:00Z'
        events.values().first().updated = '2026-11-24T16:00:00Z'
        events.values().first().etag = '"human"'
        tasks.a.due = [date: '2026-11-24T20:00:00Z']
        tasks.a.updated_at = '2026-11-24T17:00:00Z'
        Map result = sync().reconcile(liveEvents().first(), [normalize(tasks.a)], true)

        then:
        result.status == 'conflict'
        mutations.size() == 1
        taskWrites.isEmpty()

        when:
        def recovered = new RoutedScheduleReconciler(new ApplicationStateStore(directory.resolve('lost-state')), calendar, todoist, config)
        Map lost = recovered.reconcile(liveEvents().first(), [normalize(tasks.a)], true)

        then:
        lost.status == 'conflict'
        mutations.size() == 1

        when:
        events.clear()
        sync().cancel(initial)
        Map projected = sync().project(normalize(tasks.a))

        then:
        projected.status == 'existing_or_cancelled'
        mutations.size() == 1
        tasks.a.due.date == '2026-11-24T20:00:00Z'
    }

    def 'calendar conversion and historical edits cannot change an active task'() {
        given:
        if (historical) {
            tasks.a.checked = 1
            service().apply(source, originals)
        } else {
            events.clear()
            tasks.a.due = [date: '2026-11-24']
            sync().project(normalize(tasks.a))
        }
        CalendarEvent target = liveEvents().find { ProjectionMetadata.read(it).value.historical == historical }
        Map raw = events.values().find { it.id == target.id }
        raw.start = [dateTime: '2026-11-25T11:00:00Z']
        raw.end = [dateTime: '2026-11-25T11:30:00Z']
        raw.updated = '2026-11-24T16:00:00Z'; raw.etag = '"manual"'
        String untouched = JsonOutput.toJson(tasks)
        int writes = mutations.size()

        when:
        Map result = sync().reconcile(calendar.readResource(target.calendarName, target.id), tasks.values().collect { normalize(it) }, true)

        then:
        result.status == (historical ? 'historical' : 'conflict')
        JsonOutput.toJson(tasks) == untouched
        mutations.size() == writes
        taskWrites.isEmpty()

        where:
        historical << [false, true]
    }

    def 'CLI E2E upgrades a verified old single-task projection without moving tasks and survives restart'() {
        given:
        interruptUpgrade = ambiguous
        tasks.keySet().retainAll(['a'])
        initializeSource()
        String blockId = 'old-single'
        String uid = ManagedEventIds.uidForBlock(blockId)
        events['work:' + source.id].extendedProperties.private.plannerUid = uid
        events['work:' + source.id].description = ManagedEventIds.buildOccurrenceDescription(blockId, 'old-plan', originals, 'My note')
        def mapping = AppliedMapping.builder().taskId('a').occurrenceKey('a:0').blockId(blockId).eventUid(uid)
            .planId('old-plan').slotStart(source.start).slotEnd(source.end).appliedAt(Instant.parse('2026-11-24T10:00:00Z'))
            .calendarStatus(ApplyItemStatus.APPLIED).todoistStatus(ApplyItemStatus.APPLIED).build()
        store.putMapping(mapping)
        String originalTasks = JsonOutput.toJson(tasks)

        when:
        Map preview = cliSync(true)

        then:
        preview.code == 0
        preview.result.results.any { it.status == 'preview_upgrade' }
        mutations.isEmpty()
        store.loadProjections().isEmpty()

        when:
        Map upgraded = cliSync()
        if (ambiguous) {
            assert upgraded.code == 1
            assert store.loadSyncOperations().values().first().outcomes.values().first().state == 'unknown'
            upgraded = cliSync()
        }
        CalendarEvent live = liveEvents().first()
        Map restarted = cliSync()

        then:
        upgraded.code == 0
        restarted.code == 0
        mutations == ['update:work:' + source.id]
        events.size() == 1
        live.uid == uid
        live.start == source.start
        live.end == source.end
        live.description.contains('My note')
        ProjectionMetadata.read(live).value.members*.occurrenceKey == ['a:0']
        store.loadMappings().a.toMap() == mapping.toMap()
        store.loadHistoricalMappings()['a:0'].toMap() == mapping.toMap()
        store.loadSyncOperations().values().every { it.status == 'verified' }
        JsonOutput.toJson(tasks) == originalTasks
        taskWrites.isEmpty()

        where:
        ambiguous << [false, true]
    }

    def 'CLI E2E refuses unsafe old #damage with actionable repair and zero writes'() {
        given:
        tasks.keySet().retainAll(['a'])
        initializeSource()
        String blockId = 'old-single'
        String uid = ManagedEventIds.uidForBlock(blockId)
        events['work:' + source.id].extendedProperties.private.plannerUid = uid
        events['work:' + source.id].description = ManagedEventIds.buildOccurrenceDescription(blockId, 'old-plan', originals)
        if (damage != 'lost-state') store.putMapping(AppliedMapping.builder().taskId('a').occurrenceKey('a:0')
            .blockId(blockId).eventUid(uid).planId('old-plan').slotStart(source.start).slotEnd(source.end)
            .appliedAt(Instant.parse('2026-11-24T10:00:00Z')).calendarStatus(ApplyItemStatus.APPLIED)
            .todoistStatus(ApplyItemStatus.APPLIED).build())
        if (damage == 'group') events['work:' + source.id].description += 'todoist-series-id:b\ntodoist-occurrence-id:b:0\n'
        if (damage == 'moved') events['work:' + source.id].end.dateTime = '2026-11-24T10:45:00Z'
        if (damage == 'malformed') events['work:' + source.id].description += 'todoist-projection:not-base64\n'

        when:
        Map result = cliSync()

        then:
        result.code == 1
        result.result.results.any { it.status == 'conflict' && (it.reason.contains('repair') || it.reason.contains('restore state')) }
        mutations.isEmpty()
        taskWrites.isEmpty()
        store.loadProjections().isEmpty()

        where:
        damage << ['lost-state', 'group', 'moved', 'malformed']
    }

    def 'local-state loss recovers only agreeing portable schedules without provider mutation'() {
        given:
        events.clear()
        sync().project(normalize(tasks.a))
        CalendarEvent live = liveEvents().first()
        def recoveredStore = new ApplicationStateStore(directory.resolve('recovered'))
        def recovered = new RoutedScheduleReconciler(recoveredStore, calendar, todoist, config)

        when:
        Map result = recovered.reconcile(live, [normalize(tasks.a)], true)

        then:
        result.status == 'recovered'
        recoveredStore.loadProjections().values().first().status == 'current'
        mutations.size() == 1
        taskWrites.isEmpty()
    }

    def 'concurrent human calendar edit during departure is preserved with a pending conflict'() {
        given:
        tasks.b.checked = 1
        afterCreate = {
            events['work:' + source.id].etag = '"manual"'
            events['work:' + source.id].end = [dateTime: '2026-11-24T12:15:00Z']
        }

        when:
        service().apply(source, originals)

        then:
        thrown(IllegalStateException)
        events['work:' + source.id].end.dateTime == '2026-11-24T12:15:00Z'
        mutations.every { it.startsWith('create:') }
        store.loadProjections().isEmpty()

        when:
        service().resume(store.loadSyncOperations().keySet().first())

        then:
        thrown(IllegalStateException)
        store.loadSyncOperations().values().first().outcomes.values().any { it.state == 'conflict' }
        mutations.every { it.startsWith('create:') }
    }

    private Map migrationRoot() {
        Map root = runtimeRoot(false)
        root.planner.integration.state.applications_dir = directory.resolve('applications').toString()
        root.planner.integration.feedback = [allowed_actors: ['fixture-owner']]
        root
    }

    private void legacyEvents() {
        events.clear()
        tasks.each { String id, Map raw ->
            CalendarEvent taskEvent = projections().fromTask(normalize(raw))
            if (taskEvent == null) return
            events['work:legacy-' + id] = [id: 'legacy-' + id, destination: 'work',
                iCalUID: CalendarWritebackDecision.legacyUid('fixture-user', id), etag: '"legacy-' + id + '"',
                updated: '2026-11-24T11:00:00Z', summary: raw.content, description: 'Legacy human note',
                location: 'Keep location', reminders: [useDefault: false, overrides: [[method: 'popup', minutes: 7]]],
                start: [dateTime: taskEvent.start.toString()], end: [dateTime: taskEvent.end.toString()]]
        }
        events['work:appointment'] = [id: 'appointment', destination: 'work', iCalUID: 'not-a-task', etag: '"appointment"',
            summary: 'Unrelated appointment', start: [dateTime: '2026-11-24T16:00:00Z'], end: [dateTime: '2026-11-24T16:45:00Z']]
    }

    private Map migrationApproval(LegacyCalendarMigration migration, String action = 'delete') {
        Map current = migration.state.read()
        [action: action, manifestHash: current.manifestHash,
            reviewHash: action == 'rollback' ? current.rollbackReviewHash : current.manifestHash,
            actor: 'fixture-owner', approvedAt: '2026-11-24T00:00:00Z', expiresAt: '2026-11-24T02:00:00Z',
            declinedCalendarEdits: current.manifest.candidates.findAll { it.unsyncedCalendarEdit }*.resource*.id,
            handoff: [legacyWritersStopped: true, otherMutationLoopsHeld: true,
                scopeHash: MigrationState.hash(current.manifest.scope), checkedAt: '2026-11-24T00:00:00Z',
                evidence: 'isolated fixture: no legacy process, serialized planner state']]
    }

    def 'migration CLI E2E inventories privately and reconstructs fresh individual schedules before separate releases'() {
        given:
        legacyEvents()
        Map root = migrationRoot()
        root.planner.routing.labelsToInclude << 'unrouted'
        def app = application(root)
        def migration = app.migration()
        File input = directory.resolve('migration-input.json').toFile()
        input.text = JsonOutput.toJson([:])
        File logging = directory.resolve('migration-logging.groovy').toFile()
        logging.text = 'log4j.rootLogger="OFF"\n'
        StringBuilder out = new StringBuilder(), err = new StringBuilder()

        when:
        int result = TodoistCalDavSync.run(['-f', input.toString(), '-l', logging.toString(), '--operation', 'planner-migrate'] as String[],
            out, err, null, null, null, null, { app })

        then:
        err.toString().empty
        result == 0
        migration.status().stage == 'INVENTORIED'
        migration.status().candidates == 3
        migration.status().exclusions*.id == ['appointment']
        mutations.empty
        !java.nio.file.Files.readString(Path.of(migration.status().exportPath)).contains('MUST-NOT-EXPORT-USER-TOKEN')
        MigrationState.readJson(Path.of(migration.status().exportPath)).manifest.candidates[0].resource.payload.reminders.overrides[0].minutes == 7

        when:
        app.preview(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-25T00:00:00Z'))

        then:
        thrown(IllegalStateException)
        mutations.empty

        when:
        migration.approveDeletion(migrationApproval(migration))
        migration.deleteApproved()
        tasks.a.due.date = '2026-11-24T23:20:00Z'
        tasks.a.updated_at = '2026-11-24T12:00:00Z'
        tasks.b.due = [date: '2026-11-25']
        tasks.c.due = null
        tasks.d = new JsonSlurper().parseText(JsonOutput.toJson(tasks.a)) as Map
        tasks.d.id = 'd'; tasks.d.labels = ['unrouted']
        tasks.e = new JsonSlurper().parseText(JsonOutput.toJson(tasks.a)) as Map
        tasks.e.id = 'e'; tasks.e.labels = ['excluded']
        Map before = new JsonSlurper().parseText(JsonOutput.toJson(tasks)) as Map
        migration.reconstruct()
        migration.verify()

        then:
        migration.status().stage == 'VERIFIED'
        events.size() == 3
        mutations.take(3).every { it.startsWith('delete:work:legacy-') }
        mutations.drop(3).every { it.startsWith('create:') }
        liveEvents().find { it.start == Instant.parse('2026-11-24T23:20:00Z') }.end == Instant.parse('2026-11-24T23:50:00Z')
        liveEvents().find { it.allDay }.start == Instant.parse('2026-11-25T00:00:00Z')
        liveEvents().find { it.allDay }.end == Instant.parse('2026-11-26T00:00:00Z')
        migration.status().skipped == [[taskId: 'c', reason: 'unscheduled'],
            [taskId: 'd', reason: 'unroutable'], [taskId: 'e', reason: 'ineligible']]
        tasks == before
        taskWrites.empty

        when:
        migration.release('planning', migrationApproval(migration, 'release-planning'))

        then:
        thrown(IllegalStateException)

        when:
        migration.release('sync', migrationApproval(migration, 'release-sync'))

        then:
        migration.status().syncReleased

        when:
        tasks.a.due.date = '2026-11-25T22:07:00Z'
        tasks.a.updated_at = '2026-11-25T10:00:00Z'
        root.planner.sync.enabled = true
        app = application(root)
        Map synced = app.syncSchedules(Instant.parse('2026-11-24T00:00:00Z'), Instant.parse('2026-11-26T00:00:00Z'))
        migration = app.migration()
        migration.verify()
        migration.release('planning', migrationApproval(migration, 'release-planning'))

        then:
        synced.status == 'synchronized'
        migration.status().stage == 'PLANNING_RELEASED'
        new ApplicationStateStore(directory.resolve('applications')).loadProjections().size() == 2
        liveEvents().find { !it.allDay && it.id != 'appointment' }.start == Instant.parse('2026-11-25T22:07:00Z')
        events.containsKey('work:appointment')
        tasks.b == before.b && tasks.c == before.c
        taskWrites.empty
    }

    def 'migration E2E reconciles unknown deletions and creates on restart without duplicate replacements'() {
        given:
        legacyEvents()
        Map root = migrationRoot()
        def migration = application(root).migration()
        migration.inventory()
        migration.approveDeletion(migrationApproval(migration))
        interruptLegacyDelete = true

        when:
        migration.deleteApproved()

        then:
        thrown(RuntimeException)
        migration.status().stage == 'DELETION_APPROVED'
        mutations.size() == 1

        when:
        migration.reconstruct()

        then:
        thrown(IllegalStateException)
        mutations.size() == 1

        when:
        migration = application(root).migration()
        migration.deleteApproved()
        interruptAfterCreate = 1
        migration.reconstruct()

        then:
        thrown(RuntimeException)
        migration.status().stage == 'RECONSTRUCTING'

        when:
        migration = application(root).migration()
        migration.reconstruct()
        migration.verify()

        then:
        migration.status().stage == 'VERIFIED'
        mutations.count { it.startsWith('create:') } == 3
        mutations.count { it.startsWith('delete:') } == 3
        taskWrites.empty
    }

    def 'migration rollback E2E restores complete private copies after unknown import without rewriting Todoist or cursors'() {
        given:
        legacyEvents()
        Map root = migrationRoot()
        def migration = application(root).migration()
        migration.inventory()
        migration.approveDeletion(migrationApproval(migration))
        migration.deleteApproved()
        migration.reconstruct()
        migration.reviewRollback()
        Map permission = migrationApproval(migration, 'rollback')
        tasks.a.due.date = '2026-11-27T07:15:00Z'
        interruptImport = true

        when:
        migration.rollback(permission)

        then:
        thrown(RuntimeException)
        migration.status().stage == 'ROLLING_BACK'

        when:
        migration = application(root).migration()
        migration.rollback(permission)

        then:
        migration.status().stage == 'ROLLED_BACK'
        mutations.count { it.startsWith('import:') } == 3
        events.values().findAll { it.id.startsWith('restored-') }.every {
            it.location == 'Keep location' && it.description == 'Legacy human note' && it.reminders.overrides[0].minutes == 7
        }
        events.values().count { it.id.startsWith('restored-') } == 3
        tasks.a.due.date == '2026-11-27T07:15:00Z'
        taskWrites.empty
        events.containsKey('work:appointment')
    }

    def 'migration rollback E2E refuses post-review legacy drift before removing replacements'() {
        given:
        legacyEvents()
        def migration = application(migrationRoot()).migration()
        migration.inventory()
        Map original = new JsonSlurper().parseText(JsonOutput.toJson(events['work:legacy-a'])) as Map
        migration.approveDeletion(migrationApproval(migration))
        migration.deleteApproved()
        migration.reconstruct()
        migration.reviewRollback()
        Map permission = migrationApproval(migration, 'rollback')
        original.etag = '"human-recreated"'
        original.description = 'Human recreated this event after rollback review'
        events['work:legacy-a'] = original
        int count = mutations.size()

        when:
        migration.rollback(permission)

        then:
        thrown(IllegalStateException)
        mutations.size() == count
        liveEvents().count { ManagedEventIds.isPlannerUid(it.uid) } == 3
        taskWrites.empty
    }

    def 'combined routed lifecycle E2E migrates plans drags resizes splits and recovers native departures without planning'() {
        given:
        tasks.a.due += [is_recurring: true, string: 'every day', lang: 'en', timezone: null]
        Task recurring = normalize(tasks.a)
        def marker = new LifecycleMarker([taskId: 'a', completedCount: 0, deadlineMode: 'date',
            deadlineSource: 'user', deadlineDate: '2026-12-01', lastVerifiedDue: recurring.todoistDue.date,
            recurrenceFingerprint: LifecycleSupport.fingerprint(recurring.todoistDue), markerGeneration: 1, lastCommandId: 'fixture'])
        tasks.a.description = new LifecycleMarkerCodec().merge('Human note', marker)
        tasks.a.labels = ['work', 'smartplanner-seen']
        legacyEvents()
        Map root = migrationRoot()
        def migration = application(root).migration()
        migration.inventory()
        migration.approveDeletion(migrationApproval(migration))
        migration.deleteApproved()
        migration.reconstruct()
        migration.verify()
        migration.release('sync', migrationApproval(migration, 'release-sync'))
        migration.release('planning', migrationApproval(migration, 'release-planning'))
        root.planner.mode = 'approval_required'
        root.planner.sync.enabled = true
        root.planner.calendarWriteback.enabled = true
        root.planner.stability = [keep_manual_moves: false, freeze_within: 'PT0S']
        root.planner.batching = [enabled: true, max_focus_block_minutes: 180, minimum_focus_block_minutes: 30]
        root.planner.availability.working_windows.weekday = ['12:00-17:00']
        def app = application(root)
        Instant start = Instant.parse('2026-11-24T00:00:00Z'), end = start.plusSeconds(86400)
        Plan plan = app.preview(start, end)
        allowTaskWrites = true

        when:
        ApplicationReceipt applied = app.apply(plan.id, approval(plan))

        then:
        applied.errors.empty
        applied.overallStatus == ApplyItemStatus.APPLIED
        plan.scheduledBlocks.size() == 1
        liveEvents().findAll { it.id != 'appointment' }.size() == 1

        when:
        Map group = events.values().find { it.id != 'appointment' }
        group.start.dateTime = '2026-11-24T14:00:00Z'
        group.end.dateTime = '2026-11-24T15:45:00Z'
        group.etag = '"human-drag"'; group.updated = '2026-11-24T18:00:00Z'
        Map dragged = app.syncSchedules(start, end)

        then:
        dragged.status == 'synchronized'
        tasks.a.due.date == '2026-11-24T14:00:00'
        tasks.b.due.date == '2026-11-24T14:30:00Z'
        tasks.c.due.date == '2026-11-24T15:30:00Z'

        when:
        group = events.values().find { it.id != 'appointment' }
        group.end.dateTime = '2026-11-24T15:10:00Z'
        group.etag = '"human-resize"'; group.updated = '2026-11-24T19:00:00Z'
        Map resized = app.syncSchedules(start, end)

        then:
        resized.status == 'synchronized'
        tasks.values()*.duration*.amount == [20, 40, 10]
        tasks.b.due.date == '2026-11-24T14:20:00Z'
        tasks.c.due.date == '2026-11-24T15:00:00Z'

        when:
        tasks.c.due.date = '2026-11-24T16:13:00Z'
        tasks.c.labels = ['work', 'family']
        tasks.c.updated_at = '2026-11-24T20:00:00Z'
        Map split = app.syncSchedules(start, end)

        then:
        split.status == 'synchronized'
        liveEvents().find { it.calendarName == 'Family' }.start == Instant.parse('2026-11-24T16:13:00Z')
        liveEvents().find { it.calendarName == 'Work' && it.id != 'appointment' }.end == Instant.parse('2026-11-24T15:00:00Z')

        when:
        tasks.a.completed_count = 1
        tasks.a.due.date = '2026-11-25T23:17:00'
        tasks.a.updated_at = '2026-11-24T21:00:00Z'
        feedItems = [new LinkedHashMap(tasks.a)]
        int scheduleWrites = taskWrites.size()
        interruptRetirement = true
        app.syncSchedules(start, end)

        then:
        thrown(RuntimeException)

        when:
        app = application(root)
        Map recovered = app.syncSchedules(start, end)
        List<CalendarEvent> projected = liveEvents().findAll { it.id != 'appointment' }

        then:
        recovered.status == 'synchronized'
        projected.size() == 4
        CalendarEvent history = projected.find { ProjectionMetadata.read(it).value.historical }
        history.start == Instant.parse('2026-11-24T14:00:00Z')
        history.end == Instant.parse('2026-11-24T14:20:00Z')
        ProjectionMetadata.read(history).value.members*.occurrenceKey == ['a:0']
        projected.find { ProjectionMetadata.read(it).value.members*.occurrenceKey == ['a:1'] }.start == Instant.parse('2026-11-25T23:17:00Z')
        projected.find { ProjectionMetadata.read(it).value.members*.taskId == ['b'] }.start == Instant.parse('2026-11-24T14:20:00Z')
        taskWrites.size() == scheduleWrites

        when:
        tasks.b.due = null; tasks.b.updated_at = '2026-11-24T22:00:00Z'
        tasks.a.labels = ['smartplanner-seen']; tasks.a.updated_at = '2026-11-24T22:00:00Z'
        tasks.remove('c')
        feedItems = [[id: 'c', is_deleted: 1]]
        Map departed = app.syncSchedules(start, end)

        then:
        departed.status == 'synchronized'
        liveEvents().findAll { it.id != 'appointment' }*.id == [history.id]
        taskWrites.size() == scheduleWrites
        new ApplicationStateStore(directory.resolve('applications')).loadProjections().values().count { it.status == 'current' } == 0
        tasks.values().every { it.deadline == [date: '2026-12-01'] && it.priority == 3 && it.content.startsWith('Task ') }
    }

    def 'migration approval E2E refuses #damage with zero dependent mutations'() {
        given:
        legacyEvents()
        if (damage == 'undeclined manual edit') events['work:legacy-a'].start.dateTime = '2026-11-24T08:45:00Z'
        Map root = migrationRoot()
        def migration = application(root).migration()
        migration.inventory()
        Map permission = migrationApproval(migration)
        switch (damage) {
            case 'manifest drift': permission.manifestHash = '0' * 64; break
            case 'unauthorized actor': permission.actor = 'stranger'; break
            case 'expired approval': permission.expiresAt = '2026-11-24T00:00:00Z'; break
            case 'missing backup': java.nio.file.Files.delete(Path.of(migration.status().exportPath)); break
            case 'resource version drift': events['work:legacy-b'].etag = '"human"'; break
            case 'account drift': migrationAccount = 'other-account'; break
            case 'routing drift':
                root.planner.routing.rules.reverse(true)
                migration = application(root).migration(); break
            case 'active sync':
                root.planner.sync.enabled = true
                migration = application(root).migration(); break
            case 'writer stop missing': permission.handoff.legacyWritersStopped = false; break
            case 'undeclined manual edit': permission.declinedCalendarEdits = []; break
        }

        when:
        migration.approveDeletion(permission)

        then:
        thrown(Exception)
        mutations.empty
        taskWrites.empty
        events.size() == 4

        where:
        damage << ['manifest drift', 'unauthorized actor', 'expired approval', 'missing backup', 'resource version drift',
            'account drift', 'routing drift', 'active sync', 'writer stop missing', 'undeclined manual edit']
    }

    def 'migration E2E withholds parity and rollback over newer human #edit'() {
        given:
        legacyEvents()
        def migration = application(migrationRoot()).migration()
        migration.inventory()
        migration.approveDeletion(migrationApproval(migration))
        migration.deleteApproved()
        migration.reconstruct()
        int writes = mutations.size()
        if (edit == 'replacement') {
            Map event = events.values().find { it.id != 'appointment' }
            event.etag = '"human"'
            event.end.dateTime = '2026-11-24T12:00:00Z'
        } else tasks.b.due.date = '2026-11-24T18:30:00Z'

        when:
        migration.verify()

        then:
        thrown(IllegalStateException)
        migration.status().stage == 'RECONSTRUCTING'
        mutations.size() == writes

        when:
        IllegalStateException refused = null
        try {
            migration.reviewRollback()
            migration.rollback(migrationApproval(migration, 'rollback'))
        } catch (IllegalStateException failure) { refused = failure }

        then:
        (refused != null) == (edit == 'replacement')
        if (edit == 'replacement') {
            assert mutations.size() == writes
        } else {
            assert tasks.b.due.date == '2026-11-24T18:30:00Z'
            assert migration.status().stage == 'ROLLED_BACK'
        }
        taskWrites.empty

        where:
        edit << ['replacement', 'Todoist']
    }

    def 'stale portable membership cannot supersede a newer durable baseline before replacement writes'() {
        given:
        def metadata = new ProjectionMetadata(ProjectionMetadata.read(source).value + [revision: 2])
        CalendarEvent newer = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(source) +
            [etag: '"newer"', description: metadata.description('Calendar note', source.uid, 'sync')])
        store.commitProjections([newer])
        tasks.b.checked = 1

        when:
        service().apply(source, originals)

        then:
        thrown(IllegalStateException)
        mutations.isEmpty()
        store.loadSyncOperations().isEmpty()
        ProjectionMetadata.read(ProjectionMetadata.eventFromMap(store.loadProjections().values().first().event as Map)).value.revision == 2
    }
}
