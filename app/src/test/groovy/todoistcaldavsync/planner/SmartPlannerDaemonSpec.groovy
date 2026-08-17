package todoistcaldavsync.planner

import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.adapters.InMemoryCalendarGateway
import todoistcaldavsync.planner.adapters.InMemoryTodoistGateway
import todoistcaldavsync.planner.adapters.SlackMessagingGateway
import todoistcaldavsync.planner.adapters.TodoistReadGateway
import todoistcaldavsync.planner.adapters.TodoistWriteGateway
import todoistcaldavsync.planner.adapters.TodoistRestGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Message
import todoistcaldavsync.planner.messaging.InMemoryMessagingSurface
import todoistcaldavsync.planner.messaging.MessagingEvent
import todoistcaldavsync.planner.messaging.MessagingSurface
import todoistcaldavsync.planner.messaging.PublishedMessage
import todoistcaldavsync.planner.messaging.SlackSocketModeMessagingSurface
import todoistcaldavsync.planner.state.ConversationStore

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SmartPlannerDaemonSpec extends Specification {
    Instant initial = Instant.parse('2026-08-16T13:00:00Z')

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        final List<Runnable> fixed = []
        final List<Map> oneShot = []

        ManualScheduler() { super(1) }

        @Override
        ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            fixed << command
            super.schedule({ } as Runnable, 1L, TimeUnit.DAYS)
        }

        @Override
        ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            oneShot << [command: command, delayMillis: TimeUnit.MILLISECONDS.convert(delay, unit)]
            super.schedule({ } as Runnable, 1L, TimeUnit.DAYS)
        }
    }

    private static final class FlakySurface implements MessagingSurface {
        final InMemoryMessagingSurface delegate = new InMemoryMessagingSurface()
        int remainingProposalFailures
        Throwable proposalFailure

        @Override void start(java.util.function.Consumer<MessagingEvent> handler) { delegate.start(handler) }
        @Override PublishedMessage publishProposal(Message message) {
            if (remainingProposalFailures-- > 0) {
                if (proposalFailure != null) throw proposalFailure
                return new PublishedMessage(channelId: message.destination, status: 'FAILED', error: 'injected failure')
            }
            delegate.publishProposal(message)
        }
        @Override PublishedMessage reply(String channel, String thread, String text, String key) {
            delegate.reply(channel, thread, text, key)
        }
        @Override void setWorkingStatus(String channel, String thread, String status, List<String> loading) {
            delegate.setWorkingStatus(channel, thread, status, loading)
        }
        @Override void clearWorkingStatus(String channel, String thread) { delegate.clearWorkingStatus(channel, thread) }
        @Override boolean isConnected() { delegate.connected }
        @Override void close() { delegate.close() }
    }

    private Map config(File stateRoot, Map extra = [:]) {
        Map root = [planner: [mode: 'approval_required', timezone: 'UTC', output_calendar: 'Planned',
            availability: [working_windows: [weekday: ['09:00-17:00']],
                calendars: [[calendar: 'Planned', default_role: 'managed_output']]],
            tasks: [scheduling_eligible_labels: ['schedule'], default_duration_minutes: 30],
            stability: [freeze_within: 'PT2H', require_approval_for_move_within: 'P1D'],
            daemon: [enabled: true, startup_connectivity_check: true, shutdown_timeout: 'PT1S',
                retry: [initial_delay: 'PT5S', max_delay: 'PT1M', multiplier: 2],
                planning_runs: [
                    [name: 'daily', horizon: 'P1D', interval: 'PT10S', initial_delay: 'P30D', run_on_startup: false],
                    [name: 'weekly', horizon: 'P7D', interval: 'P1D', initial_delay: 'P30D', run_on_startup: false]
                ]],
            messaging: [enabled: true, provider: 'slack', slack_mode: 'socket_mode', destination: 'C123',
                bot_token_env: 'SLACK_BOT_TOKEN', app_token_env: 'SLACK_APP_TOKEN', app_name: 'SmartPlanner',
                command: '/smartplanner', loading_messages: ['is planning…']],
            ai: [enabled: false],
            integration: [
                todoist: [base_url: 'https://api.todoist.com/api/v1', token_env: 'TEST_TOKEN'],
                caldav: [calendars: [[name: 'Planned', url: 'https://calendar.example.test/planned', auth: [type: 'none']]]],
                feedback: [allowed_actors: ['U1'], rules: [
                    [name: 'approve', pattern: '(?i)^\\s*(yes|approve)\\s*$', action: 'approve'],
                    [name: 'reject', pattern: '(?i)^\\s*(no|reject)(?:\\s+(?<reason>.*))?$', action: 'reject'],
                    [name: 'replan', pattern: '(?i)^\\s*try again\\s*$', action: 'replan',
                        overrides: [priority_overrides: [t1: 4]]],
                    [name: 'ack', pattern: '(?i)^\\s*(ok|ack)\\s*$', action: 'acknowledge']
                ]],
                state: [plans_dir: new File(stateRoot, 'plans').path,
                    applications_dir: new File(stateRoot, 'applications').path,
                    decisions_dir: new File(stateRoot, 'decisions').path,
                    deliveries_dir: new File(stateRoot, 'deliveries').path]
            ]]]
        if (extra) root.planner.putAll(extra)
        root
    }

    private List build(File stateRoot, AtomicReference<Instant> now,
                       MessagingSurface surface = new InMemoryMessagingSurface(), Closure aiProvider = null,
                       TodoistReadGateway todoistRead = null, TodoistWriteGateway todoistWrite = null,
                       ScheduledThreadPoolExecutor scheduler = null) {
        Map root = config(stateRoot)
        if (aiProvider != null) {
            root.planner.ai = [enabled: true, provider: 'fixture', model: 'fixture-model',
                allowed_suggestion_types: ['conversational_feedback_interpretation', 'temporary_planning_overrides']]
        }
        PlannerConfig planner = PlannerConfig.fromMap(root)
        ProductionIntegrationConfig integration = ProductionIntegrationConfig.fromMap(root, Path.of('.').toAbsolutePath())
        def todoist = new InMemoryTodoistGateway([[id: 't1', content: 'Write report', labels: ['schedule'], priority: 2,
            duration: [amount: 30, unit: 'minute']]])
        TodoistReadGateway readGateway = todoistRead ?: todoist
        TodoistWriteGateway writeGateway = todoistWrite ?: todoist
        def calendar = new InMemoryCalendarGateway('Planned', true, [])
        def orchestrator = new ProductionPlannerOrchestrator(planner, integration, readGateway, writeGateway, calendar, calendar,
            { now.get() }, null, aiProvider)
        def daemon = new SmartPlannerDaemon(orchestrator, surface, { now.get() },
            scheduler ?: Executors.newSingleThreadScheduledExecutor())
        [daemon, orchestrator, writeGateway, calendar, surface, integration]
    }

    def 'publishes configured horizons, replans in the same thread, and applies exact approval after restart'() {
        given:
        File state = Files.createTempDirectory('smartplanner-daemon-').toFile()
        def now = new AtomicReference<>(initial)
        def parts = build(state, now)
        SmartPlannerDaemon daemon = parts[0]
        def todoist = parts[2]
        def calendar = parts[3]
        InMemoryMessagingSurface surface = parts[4]
        daemon.start()

        when: 'each named horizon can be initiated without exiting'
        def daily = daemon.runNow('daily')
        now.set(initial.plusSeconds(60))
        def weekly = daemon.runNow('weekly')

        then:
        daily.runName == 'daily'
        weekly.runName == 'weekly'
        surface.proposals.size() == 2
        surface.proposals[0].metadata.horizon == 'PT24H'
        surface.proposals[1].metadata.horizon == 'PT168H'
        todoist.dueUpdates.empty
        calendar.upserts.empty

        when: 'thread feedback requests a deterministic revised proposal with configured overrides'
        now.set(initial.plusSeconds(120))
        surface.emit(new MessagingEvent(eventId: 'Ev-replan', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '1000.900001', threadTs: daily.threadTs, text: 'try again'))
        def revised = new ConversationStore(parts[5].deliveriesDir.resolve('conversations')).find('C123', daily.threadTs)

        then:
        revised.iteration == 2
        revised.previousProposalId == daily.proposalId
        revised.overrides.priority_overrides.t1 == 4
        surface.replies.any { it.threadTs == daily.threadTs && it.text.contains('Revised proposal') }
        todoist.dueUpdates.empty
        calendar.upserts.empty

        when: 'the process restarts and an authorized exact thread confirmation arrives'
        daemon.close()
        def restartedParts = build(state, now, new InMemoryMessagingSurface())
        SmartPlannerDaemon restarted = restartedParts[0]
        InMemoryMessagingSurface restartedSurface = restartedParts[4]
        restarted.start()
        restartedSurface.emit(new MessagingEvent(eventId: 'Ev-approve', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '1000.900002', threadTs: daily.threadTs, text: 'yes'))
        def finalConversation = new ConversationStore(restartedParts[5].deliveriesDir.resolve('conversations'))
            .find('C123', daily.threadTs)

        then:
        restartedSurface.replies*.text.find { it.contains('Approved plan applied') }
        finalConversation.status == 'APPLIED'
        restartedParts[2].dueUpdates*.taskId == ['t1']
        restartedParts[3].upserts.size() == 1

        cleanup:
        try { daemon?.close() } catch (Exception ignored) {}
        try { restarted?.close() } catch (Exception ignored) {}
    }

    def 'ignores bots unrelated channels root feedback and duplicate or unauthorized events'() {
        given:
        File state = Files.createTempDirectory('smartplanner-events-').toFile()
        def now = new AtomicReference<>(initial)
        def parts = build(state, now)
        SmartPlannerDaemon daemon = parts[0]
        InMemoryMessagingSurface surface = parts[4]
        daemon.start()
        def proposal = daemon.runNow('daily')
        int baselineReplies = surface.replies.size()

        when:
        surface.emit(new MessagingEvent(eventId: 'bot', type: 'thread_reply', actorId: 'U1', channelId: 'C123',
            messageTs: '1', threadTs: proposal.threadTs, text: 'yes', bot: true))
        surface.emit(new MessagingEvent(eventId: 'other', type: 'thread_reply', actorId: 'U1', channelId: 'C999',
            messageTs: '2', threadTs: proposal.threadTs, text: 'yes'))
        surface.emit(new MessagingEvent(eventId: 'root', type: 'thread_reply', actorId: 'U1', channelId: 'C123',
            messageTs: '3', text: 'yes'))
        surface.emit(new MessagingEvent(eventId: 'deny', type: 'thread_reply', actorId: 'U2', channelId: 'C123',
            messageTs: '4', threadTs: proposal.threadTs, text: 'yes'))
        surface.emit(new MessagingEvent(eventId: 'deny', type: 'thread_reply', actorId: 'U2', channelId: 'C123',
            messageTs: '4', threadTs: proposal.threadTs, text: 'yes'))

        then:
        parts[2].dueUpdates.empty
        parts[3].upserts.empty
        surface.replies.size() == baselineReplies + 1
        surface.replies.last().text.contains('not authorized')

        cleanup:
        daemon?.close()
    }

    def 'status API sends channel thread status and loading messages without exposing token'() {
        given:
        List calls = []
        def surface = new SlackSocketModeMessagingSurface([
            channel: 'C123', botTokenEnv: 'BOT', appTokenEnv: 'APP', appName: 'SmartPlanner'
        ], { 'secret' }, { String method, Map payload, String token ->
            calls << [method: method, payload: payload, token: token]
            [ok: true]
        })
        surface.@botToken = 'secret'

        when:
        surface.setWorkingStatus('C123', '123.456', 'is planning…', ['is checking capacity…'])
        surface.clearWorkingStatus('C123', '123.456')

        then:
        calls*.method == ['assistant.threads.setStatus', 'assistant.threads.setStatus']
        calls[0].payload == [channel_id: 'C123', thread_ts: '123.456', status: 'is planning…',
            loading_messages: ['is checking capacity…']]
        calls[1].payload.status == ''
        !calls.toString().contains('xoxb-')

        cleanup:
        surface.close()
    }

    def 'Socket Mode surface publishes channel parents and replies with the original thread identity'() {
        given:
        List<Map> payloads = []
        def gateway = new SlackMessagingGateway(mode: 'chat_api', botTokenOverride: 'test-token', destination: 'C123',
            transport: { SlackMessagingGateway.HttpCall call ->
                Map payload = new JsonSlurper().parseText(call.body) as Map
                payloads << payload
                String ts = payload.thread_ts ? '5000.2' : '5000.1'
                new SlackMessagingGateway.HttpResult(200, "{\"ok\":true,\"ts\":\"${ts}\",\"channel\":\"C123\"}")
            }, clock: { initial })
        def surface = new SlackSocketModeMessagingSurface([channel: 'C123'])
        surface.@outbound = gateway
        surface.@connected = true
        Message proposal = Message.builder().kind('proposal_summary').subject('Plan').body('Proposal')
            .destination('C123').planId('p1').planVersion(1).planHash('a' * 64).proposalId('prop1')
            .idempotencyKey('proposal-key').createdAt(initial).build()

        when:
        def parent = surface.publishProposal(proposal)
        def reply = surface.reply('C123', parent.threadTs, 'Iteration 2', 'reply-key')

        then:
        parent.threadTs == '5000.1'
        reply.messageTs == '5000.2'
        !payloads[0].containsKey('thread_ts')
        payloads[1].thread_ts == '5000.1'
        payloads*.channel == ['C123', 'C123']

        cleanup:
        surface.close()
    }

    def 'Slack commands plan every horizon by default and replan a named run in its proposal thread'() {
        given:
        File state = Files.createTempDirectory('smartplanner-commands-').toFile()
        def now = new AtomicReference<>(initial)
        def parts = build(state, now)
        SmartPlannerDaemon daemon = parts[0]
        InMemoryMessagingSurface surface = parts[4]
        daemon.start()

        when: 'plan without a run name requests every configured horizon'
        surface.emit(new MessagingEvent(eventId: 'cmd-plan-all', type: 'command', actorId: 'U1',
            channelId: 'C123', messageTs: '2000.1', text: 'plan'))

        then:
        surface.proposals*.metadata*.planningRun as Set == ['daily', 'weekly'] as Set
        surface.replies.any { it.text.contains('Published 2 proposal(s)') }

        when: 'status reports readiness, Socket Mode state, queue activity, and next-run information'
        surface.emit(new MessagingEvent(eventId: 'cmd-status', type: 'command', actorId: 'U1',
            channelId: 'C123', messageTs: '2000.15', text: 'status'))
        String statusText = surface.replies.last().text

        then:
        statusText.contains('Readiness: `ready`')
        statusText.contains('Socket Mode: `connected`')
        statusText.contains('active `false`; pending `false`')
        statusText.contains('next `')

        when: 'a named command creates a linked iteration in the existing proposal thread'
        now.set(initial.plusSeconds(60))
        surface.emit(new MessagingEvent(eventId: 'cmd-replan', type: 'command', actorId: 'U1',
            channelId: 'C123', messageTs: '2000.2', text: 'replan daily prefer mornings'))
        def daily = new ConversationStore(parts[5].deliveriesDir.resolve('conversations')).list()
            .findAll { it.runName == 'daily' }.max { a, b -> a.updatedAt <=> b.updatedAt }

        then:
        daily.iteration == 2
        daily.overrides.criteria == 'prefer mornings'
        surface.replies.any { it.threadTs == daily.threadTs && it.text.contains('Revised proposal') }
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        when: 'a later run supersedes the older proposal thread'
        String supersededThread = daily.threadTs
        now.set(initial.plusSeconds(120))
        def replacement = daemon.runNow('daily')
        surface.emit(new MessagingEvent(eventId: 'stale-old-thread', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2000.3', threadTs: supersededThread, text: 'yes'))
        def old = new ConversationStore(parts[5].deliveriesDir.resolve('conversations')).find('C123', supersededThread)

        then:
        replacement.threadTs != supersededThread
        old.status == 'SUPERSEDED'
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        cleanup:
        daemon?.close()
    }

    def 'LLM feedback requires deterministic confirmation before a replan and never writes providers'() {
        given:
        File state = Files.createTempDirectory('smartplanner-ai-confirm-').toFile()
        def now = new AtomicReference<>(initial)
        List<String> requestedTypes = []
        Closure provider = { plan, String type, String correlationId, String feedback ->
            requestedTypes << type
            if (type == 'conversational_feedback_interpretation') {
                return [accepted: true, bundle: [suggestions: [[action: 'REQUEST_CHANGES', rationale: 'reduce load']]]]
            }
            [accepted: true, bundle: [suggestions: [[overrideType: 'exclude', taskIds: ['t1'], value: true]]]]
        }
        def parts = build(state, now, new InMemoryMessagingSurface(), provider)
        SmartPlannerDaemon daemon = parts[0]
        InMemoryMessagingSurface surface = parts[4]
        daemon.start()
        def proposal = daemon.runNow('daily')

        when: 'unmatched natural-language feedback produces only a pending suggestion'
        surface.emit(new MessagingEvent(eventId: 'ai-feedback', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2500.1', threadTs: proposal.threadTs,
            text: 'Please make tomorrow less busy'))
        def pending = new ConversationStore(parts[5].deliveriesDir.resolve('conversations'))
            .find('C123', proposal.threadTs)

        then:
        pending.iteration == 1
        pending.pendingConfirmation.action == 'replan'
        pending.pendingConfirmation.planId == pending.planId
        pending.pendingConfirmation.overrides.exclude_task_ids == ['t1']
        surface.replies.last().text.contains('Confirm with a configured `replan` phrase')
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        when: 'the authorized deterministic phrase confirms the exact pending suggestion'
        now.set(initial.plusSeconds(60))
        surface.emit(new MessagingEvent(eventId: 'ai-confirm', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2500.2', threadTs: proposal.threadTs, text: 'try again'))
        def confirmed = new ConversationStore(parts[5].deliveriesDir.resolve('conversations'))
            .find('C123', proposal.threadTs)

        then:
        requestedTypes == ['conversational_feedback_interpretation', 'temporary_planning_overrides']
        confirmed.iteration == 2
        confirmed.pendingConfirmation.isEmpty()
        confirmed.overrides.exclude_task_ids == ['t1']
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        cleanup:
        daemon?.close()
    }

    def 'temporary overrides reject unknown task ids and conversation pending confirmations survive restart'() {
        given:
        File state = Files.createTempDirectory('smartplanner-overrides-').toFile()
        def now = new AtomicReference<>(initial)
        def parts = build(state, now)
        SmartPlannerDaemon daemon = parts[0]
        InMemoryMessagingSurface surface = parts[4]
        daemon.start()
        def proposal = daemon.runNow('daily')
        def store = new ConversationStore(parts[5].deliveriesDir.resolve('conversations'))
        def pending = proposal.withPendingConfirmation([action: 'replan', planId: proposal.planId,
            planHash: proposal.planHash, expiresAt: initial.plusSeconds(60).toString(),
            overrides: [exclude_task_ids: ['missing-task']]], initial)
        store.save(pending)

        when:
        surface.emit(new MessagingEvent(eventId: 'confirm-invalid', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '3000.1', threadTs: proposal.threadTs, text: 'try again'))
        def reloaded = new ConversationStore(parts[5].deliveriesDir.resolve('conversations'))
            .find('C123', proposal.threadTs)

        then:
        reloaded.pendingConfirmation.isEmpty()
        reloaded.iteration == 1
        surface.replies.last().text.contains('unknown task')
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        cleanup:
        daemon?.close()
    }

    def 'scheduled horizons expose next-run state and duplicate active triggers coalesce exactly once'() {
        given:
        File state = Files.createTempDirectory('smartplanner-schedule-').toFile()
        def now = new AtomicReference<>(initial)
        def scheduler = new ManualScheduler()
        def surface = new InMemoryMessagingSurface()
        def parts = build(state, now, surface, null, null, null, scheduler)
        SmartPlannerDaemon daemon = parts[0]

        when:
        daemon.start()

        then:
        scheduler.fixed.size() == 2
        daemon.statusSnapshot().daily.state == 'SCHEDULED'
        daemon.statusSnapshot().daily.nextRunAt == initial.plusSeconds(30L * 86400L).toString()

        when: 'two triggers arrive while daily is active'
        daemon.@running.daily.set(true)
        daemon.runNow('daily')
        daemon.runNow('daily')

        then:
        daemon.statusSnapshot().daily.state == 'COALESCED'
        scheduler.oneShot.empty

        when: 'the active cycle completes'
        daemon.@running.daily.set(false)
        scheduler.fixed[0].run()

        then: 'one and only one pending cycle is scheduled'
        surface.proposals.size() == 1
        scheduler.oneShot.size() == 1
        scheduler.oneShot[0].delayMillis == 0L

        when:
        scheduler.oneShot[0].command.run()

        then:
        surface.proposals.size() == 2
        daemon.statusSnapshot().daily.state == 'WAITING_FOR_FEEDBACK'
        daemon.statusSnapshot().daily.nextRunAt == initial.plusSeconds(10).toString()

        cleanup:
        daemon?.close()
    }

    def 'transient scheduled failure records bounded retry and later succeeds without stopping daemon'() {
        given:
        File state = Files.createTempDirectory('smartplanner-retry-').toFile()
        def now = new AtomicReference<>(initial)
        def scheduler = new ManualScheduler()
        def surface = new FlakySurface(remainingProposalFailures: 1)
        def parts = build(state, now, surface, null, null, null, scheduler)
        SmartPlannerDaemon daemon = parts[0]
        daemon.start()

        when:
        scheduler.fixed[0].run()

        then:
        daemon.statusSnapshot().daily.state == 'RETRYABLE_FAILURE'
        daemon.statusSnapshot().daily.retryAttempt == 1
        daemon.statusSnapshot().daily.retryIn == 'PT5S'
        scheduler.oneShot*.delayMillis == [5000L]
        surface.connected

        when:
        now.set(initial.plusSeconds(5))
        scheduler.oneShot[0].command.run()

        then:
        surface.delegate.proposals.size() == 1
        daemon.statusSnapshot().daily.state == 'WAITING_FOR_FEEDBACK'
        surface.connected

        cleanup:
        daemon?.close()
    }

    def 'startup probe fails before messaging readiness and scheduled fatal auth terminates nonzero path'() {
        given: 'a required provider that fails the startup read probe'
        File startupState = Files.createTempDirectory('smartplanner-startup-fatal-').toFile()
        def now = new AtomicReference<>(initial)
        def startupSurface = new InMemoryMessagingSurface()
        def startupScheduler = new ManualScheduler()
        TodoistReadGateway denied = Stub() {
            fetchTasks() >> { throw new TodoistRestGateway.TodoistGatewayException('HTTP_STATUS', 'Todoist startup failed with HTTP 401') }
        }
        def startupParts = build(startupState, now, startupSurface, null, denied, null, startupScheduler)

        when:
        startupParts[0].start()

        then:
        def startupFailure = thrown(TodoistRestGateway.TodoistGatewayException)
        startupFailure.message.contains('HTTP 401')
        !startupSurface.connected
        startupScheduler.isShutdown()
        startupScheduler.fixed.empty

        when: 'authentication is lost after readiness'
        File runtimeState = Files.createTempDirectory('smartplanner-runtime-fatal-').toFile()
        def runtimeScheduler = new ManualScheduler()
        def runtimeSurface = new FlakySurface(remainingProposalFailures: 1,
            proposalFailure: new TodoistRestGateway.TodoistGatewayException('HTTP_STATUS', 'Todoist runtime failed with HTTP 403'))
        SmartPlannerDaemon runtime = build(runtimeState, now, runtimeSurface, null, null, null, runtimeScheduler)[0]
        runtime.start()
        runtimeScheduler.fixed[0].run()
        runtime.awaitTermination()

        then:
        def fatal = thrown(IllegalStateException)
        fatal.message.contains('fatal required-provider authentication failure')
        runtime.statusSnapshot().daily.state == 'FATAL_REQUIRED_PROVIDER_AUTH'
        runtimeScheduler.isShutdown()
        !runtimeSurface.connected

        cleanup:
        try { startupParts[0]?.close() } catch (Exception ignored) {}
        try { runtime?.close() } catch (Exception ignored) {}
    }

    def 'graceful close stops scheduling and messaging without corrupting persisted conversation state'() {
        given:
        File state = Files.createTempDirectory('smartplanner-shutdown-').toFile()
        def now = new AtomicReference<>(initial)
        def scheduler = new ManualScheduler()
        def surface = new InMemoryMessagingSurface()
        SmartPlannerDaemon daemon = build(state, now, surface, null, null, null, scheduler)[0]
        daemon.start()
        def proposal = daemon.runNow('daily')

        when:
        daemon.close()
        daemon.close()

        then:
        scheduler.isShutdown()
        !surface.connected
        new ConversationStore(daemon.@config.deliveriesDir.resolve('conversations'))
            .find('C123', proposal.threadTs).planId == proposal.planId
    }

    def 'daemon configuration rejects malformed schedules regex and missing Socket Mode references'() {
        given:
        File state = Files.createTempDirectory('smartplanner-config-').toFile()
        Map root = config(state)
        mutate(root)

        when:
        PlannerConfig.fromMap(root)
        ProductionIntegrationConfig.fromMap(root, Path.of('.').toAbsolutePath())

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(expected)

        where:
        mutate << [
            { Map r -> r.planner.daemon.planning_runs[0].interval = 'PT1S' },
            { Map r -> r.planner.daemon.planning_runs[0].initial_delay = 'P31D' },
            { Map r -> r.planner.daemon.retry.max_delay = 'PT1S' },
            { Map r -> r.planner.integration.feedback.rules[0].pattern = '[' },
            { Map r -> r.planner.integration.feedback.rules[0].overrides = [priority_overrides: [t1: 9]] },
            { Map r -> r.planner.messaging.command = '/Bad Command' },
            { Map r -> r.planner.messaging.max_event_text_chars = 0 },
            { Map r -> r.planner.messaging.remove('app_token_env') }
        ]
        expected << ['interval must be between', 'initial_delay must be between', 'retry delays require',
                     'pattern invalid', 'priorities 1..4', 'lowercase Slack command', 'max_event_text_chars', 'app_token_env']
    }
}
