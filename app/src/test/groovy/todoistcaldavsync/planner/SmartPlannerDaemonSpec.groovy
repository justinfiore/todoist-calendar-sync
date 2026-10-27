package todoistcaldavsync.planner

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.InMemoryCalendarGateway
import todoistcaldavsync.planner.adapters.InMemoryTodoistGateway
import todoistcaldavsync.planner.adapters.CalendarChangeGateway
import todoistcaldavsync.planner.adapters.LifecycleMutation
import todoistcaldavsync.planner.adapters.SlackMessagingGateway
import todoistcaldavsync.planner.adapters.TodoistCommandResult
import todoistcaldavsync.planner.adapters.TodoistCommandState
import todoistcaldavsync.planner.adapters.TodoistLifecycleGateway
import todoistcaldavsync.planner.adapters.TodoistReadGateway
import todoistcaldavsync.planner.adapters.TodoistWriteGateway
import todoistcaldavsync.planner.adapters.TodoistRestGateway
import todoistcaldavsync.planner.adapters.TodoistSyncPage
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Message
import todoistcaldavsync.planner.domain.AppliedMapping
import todoistcaldavsync.planner.domain.ApplyItemStatus
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.EventRole
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.ai.LlmErrorClass
import todoistcaldavsync.planner.ai.*
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.messaging.InMemoryMessagingSurface
import todoistcaldavsync.planner.messaging.MessagingEvent
import todoistcaldavsync.planner.messaging.MessagingSurface
import todoistcaldavsync.planner.messaging.PublishedMessage
import todoistcaldavsync.planner.messaging.SlackSocketModeMessagingSurface
import todoistcaldavsync.planner.recurrence.TodoistDue
import todoistcaldavsync.planner.state.ConversationStore
import todoistcaldavsync.planner.state.ApplicationStateStore

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SmartPlannerDaemonSpec extends Specification {
    Instant initial = Instant.parse('2026-08-16T13:00:00Z')

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        final List<Runnable> fixed = []
        final List<Long> fixedDelayMillis = []
        final List<Map> oneShot = []

        ManualScheduler() { super(1) }

        @Override
        ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            fixed << command
            fixedDelayMillis << TimeUnit.MILLISECONDS.convert(delay, unit)
            super.schedule({ } as Runnable, 1L, TimeUnit.DAYS)
        }

        @Override
        ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            oneShot << [command: command, delayMillis: TimeUnit.MILLISECONDS.convert(delay, unit)]
            super.schedule({ } as Runnable, 1L, TimeUnit.DAYS)
        }
    }

    private static final class LifecycleGateway extends InMemoryTodoistGateway implements TodoistLifecycleGateway {
        final List<String> syncTokens = []

        LifecycleGateway(List<Map> tasks = []) { super(tasks) }

        @Override Map fetchTask(String taskId) { getTask(taskId) }
        @Override TodoistCommandResult updateRecurringDue(String taskId, TodoistDue liveDue,
                                                           String replacementDate, String commandId) {
            new TodoistCommandResult(TodoistCommandState.COMMITTED, commandId)
        }
        @Override TodoistCommandResult updateLifecycleFields(LifecycleMutation mutation) {
            new TodoistCommandResult(TodoistCommandState.COMMITTED, mutation.commandId)
        }
        @Override TodoistSyncPage syncItems(String syncToken) {
            syncTokens << syncToken
            syncToken == '*'
                ? new TodoistSyncPage('bootstrap-token', true, '2026-08-16T13:00:00Z', [])
                : new TodoistSyncPage('caught-up-token', false, null, [])
        }
    }

    private static final class ChangeCalendar extends InMemoryCalendarGateway implements CalendarChangeGateway {
        final CalendarEvent changed
        int changeReads

        ChangeCalendar(CalendarEvent changed) {
            super('Planned', true, [changed])
            this.changed = changed
        }

        @Override Map readChanges(String calendarName, Map state, Set<String> candidateUids,
                                  Instant rangeStart, Instant rangeEnd) {
            changeReads++
            [events: candidateUids.contains(changed.uid) ? [changed] : [], deleted: [],
             state: [cursor: "cursor-${changeReads}"]]
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
                calendar: [provider: 'caldav'],
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
                       ScheduledThreadPoolExecutor scheduler = null, ConversationStore conversationStore = null,
                       Map configExtra = [:], InMemoryCalendarGateway calendarGateway = null) {
        Map root = config(stateRoot, configExtra)
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
        def calendar = calendarGateway ?: new InMemoryCalendarGateway('Planned', true, [])
        def orchestrator = new ProductionPlannerOrchestrator(planner, integration, readGateway, writeGateway, calendar, calendar,
            { now.get() }, null, aiProvider)
        def daemon = new SmartPlannerDaemon(orchestrator, surface, { now.get() },
            scheduler ?: Executors.newSingleThreadScheduledExecutor(), conversationStore)
        [daemon, orchestrator, writeGateway, calendar, surface, integration]
    }

    def 'daemon adopts a newer owned move before planning and never previews the old slot back'() {
        given:
        File state = Files.createTempDirectory('smartplanner-writeback-').toFile()
        def now = new AtomicReference<>(initial)
        Instant oldDue = Instant.parse('2026-08-17T11:00:00Z')
        Instant moved = Instant.parse('2026-08-17T10:00:00Z')
        String uid = ManagedEventIds.uidForBlock('old-block')
        CalendarEvent event = CalendarEvent.builder()
            .id(uid).uid(uid).title('Owned task')
            .description(ManagedEventIds.buildDescription('old-block', 'old-plan'))
            .calendarName('Planned').start(moved).end(moved.plusSeconds(1800))
            .lastModified(Instant.parse('2026-08-16T12:00:00Z'))
            .role(EventRole.MANAGED_OUTPUT).build()
        def calendar = new ChangeCalendar(event)
        def todoist = new InMemoryTodoistGateway([[id: 't1', content: 'Write report', labels: ['schedule'], priority: 2,
            duration: [amount: 30, unit: 'minute'], due: [date: oldDue.toString()],
            updated_at: '2026-08-16T11:00:00Z']])
        def parts = build(state, now, new InMemoryMessagingSurface(), null, todoist, todoist,
            null, null, [calendarWriteback: [enabled: true]], calendar)
        new ApplicationStateStore(parts[5].applicationsDir).putMapping(AppliedMapping.builder()
            .taskId('t1').blockId('old-block').eventUid(uid).slotStart(oldDue).slotEnd(oldDue.plusSeconds(1800))
            .planId('old-plan').planVersion(1).planHash('old-hash').appliedAt(initial.minusSeconds(3600))
            .calendarStatus(ApplyItemStatus.APPLIED).todoistStatus(ApplyItemStatus.APPLIED).build())
        SmartPlannerDaemon daemon = parts[0]
        daemon.start()

        when:
        def conversation = daemon.runNow('daily')
        def planned = parts[1].planStore.load(conversation.planId)

        then:
        calendar.changeReads == 1
        todoist.dueUpdates*.dueDateTimeIso == [moved.toString()]
        planned.tasks.find { it.id == 't1' }.dueTime == moved
        !planned.scheduledBlocks.any { it.start == oldDue }
        calendar.upserts.isEmpty()
        todoist.deadlineUpdates.isEmpty()

        cleanup:
        daemon?.close()
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

    def 'Socket Mode SDK payloads deliver mentions and thread replies with stable event identities'() {
        given:
        def received = new java.util.concurrent.LinkedBlockingQueue<MessagingEvent>()
        def surface = new SlackSocketModeMessagingSurface([
            channel: 'C123', botTokenEnv: 'BOT', appTokenEnv: 'APP'
        ], { 'synthetic-token' })
        surface.@handler = { received.add(it) } as java.util.function.Consumer<MessagingEvent>
        def app = surface.createSlackApp()
        def context = new com.slack.api.bolt.context.builtin.EventContext()
        def eventRequest = new com.slack.api.bolt.request.builtin.EventRequest(
            groovy.json.JsonOutput.toJson([type: 'event_callback', event_id: eventId,
                event: [type: type, user: 'U1', channel: 'C123', ts: '5000.2',
                    thread_ts: '5000.1', text: text]]), new com.slack.api.bolt.request.RequestHeaders([:]))
        def payload = com.slack.api.bolt.util.EventsApiPayloadParser.buildEventPayload(eventRequest)
        def handlers = app.@eventHandlers[type]

        when:
        def response = handlers.first().apply(payload, context)
        def delivered = received.poll(2, TimeUnit.SECONDS)

        then:
        response.statusCode == 200
        delivered != null
        delivered.eventId == eventId
        delivered.type == expectedType
        delivered.text == expectedText
        delivered.actorId == 'U1'
        delivered.channelId == 'C123'
        delivered.messageTs == '5000.2'
        delivered.threadTs == '5000.1'

        cleanup:
        surface?.close()

        where:
        type          | eventId       | text                 | expectedType   | expectedText
        'message'     | 'Ev-reply'    | 'replan mornings'    | 'thread_reply' | 'replan mornings'
        'app_mention' | 'Ev-mention'  | '<@UBOT> plan daily' | 'app_mention'  | 'plan daily'
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

    private Closure validatedOverrides(AtomicReference<Instant> now, List<String> requestedTypes = [],
                                       Closure adjust = { List rows -> rows }) {
        { plan, String type, String correlationId, String feedback ->
            requestedTypes << type
            def request = new LlmRequest(correlationId: correlationId, suggestionType: type,
                planId: plan.id, planVersion: plan.version, planHash: PlanHash.compute(plan),
                planningInputHash: PlanningInputHash.compute(plan), allowedTaskIds: plan.tasks*.id,
                expectedProposalId: Proposal.fromPlan(plan).id, allowedFeedbackActions: ['REQUEST_CHANGES'],
                planningRangeStart: plan.slots*.start.min(), planningRangeEnd: plan.slots*.end.max())
            Map row = type == 'conversational_feedback_interpretation'
                ? [suggestionId: 'feedback-1', proposalId: request.expectedProposalId, planId: plan.id,
                   planVersion: plan.version, planHash: request.planHash, action: 'REQUEST_CHANGES', reason: 'adjust estimate']
                : [suggestionId: 'duration-1', planId: plan.id, planVersion: plan.version,
                   planHash: request.planHash, planningInputHash: request.planningInputHash,
                   taskIds: ['t1'], overrideType: 'duration_minutes', value: 45,
                   rangeStart: request.planningRangeStart.toString(), rangeEnd: request.planningRangeEnd.toString(),
                   expiresAt: request.planningRangeEnd.toString(), confidence: 0.95, rationale: 'adjust estimate']
            List rows = [row]
            if (type == 'temporary_planning_overrides') rows << (row + [suggestionId: 'context-1',
                overrideType: 'context_label', value: 'deep-focus'])
            if (type == 'temporary_planning_overrides') rows = adjust.call(rows)
            new LlmSchemaValidator().validate(request, JsonOutput.prettyPrint(JsonOutput.toJson([schemaVersion: 1,
                suggestionType: type, correlationId: correlationId, suggestions: rows])), now.get())
        }
    }

    def 'LLM feedback requires deterministic confirmation before a replan and never writes providers'() {
        given:
        File state = Files.createTempDirectory('smartplanner-ai-confirm-').toFile()
        def now = new AtomicReference<>(initial)
        List<String> requestedTypes = []
        Closure provider = validatedOverrides(now, requestedTypes)
        def source = new InMemoryTodoistGateway([
            [id: 't1', content: 'Target report', labels: ['schedule'], priority: 2, duration: [amount: 30, unit: 'minute']],
            [id: 't2', content: 'Control report', labels: ['schedule'], priority: 1, duration: [amount: 30, unit: 'minute']]])
        Map contextConfig = [tasks: [scheduling_eligible_labels: ['schedule'], default_duration_minutes: 30,
            contexts: [focus: [match_labels: ['deep-focus'], preferred_windows: ['weekday 11:00-12:00'],
                preferred_bonus: 10000, avoid_penalty: 10000]]]]
        def parts = build(state, now, new InMemoryMessagingSurface(), provider, source, source,
            null, null, contextConfig)
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
        surface.replies.last().text.contains('45')
        parts[1].planStore.load(pending.planId).tasks*.effectiveDuration*.toMinutes() == [30L, 30L]
        surface.replies.last().text.contains('Confirm with a configured `replan` phrase')
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        when: 'a process restart restores the exact canonical suggestions without another LLM call'
        daemon.close()
        parts = build(state, now, new InMemoryMessagingSurface(), provider, source, source,
            null, null, contextConfig)
        daemon = parts[0]
        surface = parts[4]
        daemon.start()

        then:
        requestedTypes.size() == 2

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
        def revised = parts[1].planStore.load(confirmed.planId)
        revised.tasks.find { it.id == 't1' }.effectiveDuration.toMinutes() == 45
        revised.tasks.find { it.id == 't2' }.effectiveDuration.toMinutes() == 30
        revised.tasks.find { it.id == 't1' }.labels.contains('deep-focus')
        def intervals = revised.scheduledBlocks.collectMany { it.memberIntervals }
        java.time.Duration.between(intervals.find { it.taskId == 't1' }.start, intervals.find { it.taskId == 't1' }.end).toMinutes() == 45
        java.time.Duration.between(intervals.find { it.taskId == 't2' }.start, intervals.find { it.taskId == 't2' }.end).toMinutes() == 30
        intervals.find { it.taskId == 't1' }.start == Instant.parse('2026-08-17T11:00:00Z')
        revised.slots*.end.max() <= Instant.parse(pending.pendingConfirmation.rangeEnd)
        revised.explanations.count { it.code == 'AI_CONFIRMED_TEMPORARY_OVERRIDE' } == 2
        source.fetchTasks()*.duration*.amount == [30, 30]
        source.fetchTasks()*.labels == [['schedule'], ['schedule']]
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        when: 'the exact confirmation envelope is redelivered after another restart'
        daemon.close()
        parts = build(state, now, new InMemoryMessagingSurface(), provider, source, source,
            null, null, contextConfig)
        daemon = parts[0]
        surface = parts[4]
        daemon.start()
        surface.emit(new MessagingEvent(eventId: 'ai-confirm', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2500.2', threadTs: proposal.threadTs, text: 'try again'))

        then:
        parts[1].planStore.listPlanIds().size() == 2
        surface.replies.empty

        cleanup:
        daemon?.close()
    }

    def 'concurrent exact AI confirmations cannot publish two successor proposals'() {
        given:
        File state = Files.createTempDirectory('smartplanner-ai-concurrent-').toFile()
        def now = new AtomicReference<>(initial)
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        boolean holdReads = false
        def source = new InMemoryTodoistGateway([[id: 't1', content: 'Target', labels: ['schedule'],
            priority: 2, duration: [amount: 30, unit: 'minute']]])
        TodoistReadGateway reader = { ->
            if (holdReads) { entered.countDown(); assert release.await(5, TimeUnit.SECONDS) }
            source.fetchTasks()
        } as TodoistReadGateway
        def parts = build(state, now, new InMemoryMessagingSurface(), validatedOverrides(now), reader, source)
        SmartPlannerDaemon daemon = parts[0]
        daemon.start()
        def proposal = daemon.runNow('daily')
        parts[4].emit(new MessagingEvent(eventId: 'suggest', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2850.1', threadTs: proposal.threadTs, text: 'adjust estimate'))
        holdReads = true
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>()
        Closure confirm = { String id ->
            try { parts[4].emit(new MessagingEvent(eventId: id, type: 'thread_reply', actorId: 'U1',
                channelId: 'C123', messageTs: '2850.' + id, threadTs: proposal.threadTs, text: 'try again')) }
            catch (Throwable failure) { failures.add(failure) }
        }

        when:
        Thread first = Thread.start { confirm('first') }
        assert entered.await(5, TimeUnit.SECONDS)
        Thread second = Thread.start { confirm('second') }
        new spock.util.concurrent.PollingConditions(timeout: 3).eventually {
            assert second.state == Thread.State.BLOCKED
        }
        release.countDown()
        first.join(5000)
        second.join(5000)

        then:
        !first.alive && !second.alive
        failures.empty
        new ConversationStore(parts[5].deliveriesDir.resolve('conversations'))
            .find('C123', proposal.threadTs).iteration == 2
        parts[1].planStore.listPlanIds().size() == 2
        parts[4].replies.count { it.text.contains('Revised proposal') } == 1
        parts[4].replies.any { it.text.contains('already resolved') }
        source.dueUpdates.empty
        parts[3].upserts.empty

        cleanup:
        release.countDown()
        daemon?.close()
    }

    def 'interrupted AI generation reuses its durable intent and exact saved successor after restart'() {
        given:
        File state = Files.createTempDirectory('smartplanner-ai-interrupt-').toFile()
        def now = new AtomicReference<>(initial)
        boolean failPublicationBarrier = true
        def failingStore = new ConversationStore(state.toPath().resolve('deliveries/conversations'), { Map root ->
            if (failPublicationBarrier && root.conversations.values().any { it.status == 'PUBLISHING_REVISION' }) {
                failPublicationBarrier = false
                throw new IOException('injected interruption before publishing barrier')
            }
        })
        def provider = validatedOverrides(now)
        def parts = build(state, now, new InMemoryMessagingSurface(), provider, null, null, null, failingStore)
        SmartPlannerDaemon daemon = parts[0]
        daemon.start()
        def proposal = daemon.runNow('daily')
        parts[4].emit(new MessagingEvent(eventId: 'suggest', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2900.1', threadTs: proposal.threadTs, text: 'adjust estimate'))

        when:
        parts[4].emit(new MessagingEvent(eventId: 'confirm-interrupted', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2900.2', threadTs: proposal.threadTs, text: 'try again'))

        then:
        thrown(Exception)
        parts[1].planStore.listPlanIds().size() == 2

        when:
        def intent = failingStore.find('C123', proposal.threadTs).pendingConfirmation
        def successor = parts[1].planStore.load(intent.successorPlanId)
        parts[4].emit(new MessagingEvent(eventId: 'unrelated-feedback', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2900.3', threadTs: proposal.threadTs, text: 'yes'))

        then: 'another action cannot erase the in-progress confirmation or approve its old plan'
        failingStore.find('C123', proposal.threadTs).pendingConfirmation == intent
        parts[4].replies.last().text.contains('already in progress')
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        when:
        daemon.close()
        now.set(initial.plusSeconds(120))
        parts = build(state, now, new InMemoryMessagingSurface(), provider)
        daemon = parts[0]
        daemon.start() // recovers the interrupted confirmation event
        def resolved = new ConversationStore(parts[5].deliveriesDir.resolve('conversations')).find('C123', proposal.threadTs)

        then:
        resolved.iteration == 2
        resolved.planId == successor.id
        resolved.resolvedAiConfirmation.successorPlanId == successor.id
        parts[1].planStore.listPlanIds().size() == 2
        parts[1].planStore.load(successor.id).createdAt == successor.createdAt
        parts[4].replies.count { it.text.contains('Revised proposal') } == 1
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        cleanup:
        daemon?.close()
    }

    def 'AI confirmation refuses #fault after restart without a successor or provider write'() {
        given:
        File state = Files.createTempDirectory('smartplanner-ai-refusal-').toFile()
        def now = new AtomicReference<>(initial)
        def source = new InMemoryTodoistGateway([[id: 't1', content: 'Target', labels: ['schedule'],
            priority: 2, duration: [amount: 30, unit: 'minute']]])
        def provider = validatedOverrides(now)
        def parts = build(state, now, new InMemoryMessagingSurface(), provider, source, source)
        SmartPlannerDaemon daemon = parts[0]
        daemon.start()
        def proposal = daemon.runNow('daily')
        parts[4].emit(new MessagingEvent(eventId: 'suggest', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2700.1', threadTs: proposal.threadTs, text: 'adjust estimate please'))
        daemon.close()
        def store = new ConversationStore(parts[5].deliveriesDir.resolve('conversations'))
        def record = store.find('C123', proposal.threadTs)
        Map pending = new LinkedHashMap(record.pendingConfirmation)
        switch (fault) {
            case 'tampered content': pending.canonicalJson = pending.canonicalJson.replace('45', '90'); break
            case 'unknown suggestion': pending.suggestionIds = ['unknown']; break
            case 'legacy pending map': pending = [action: 'replan', overrides: [exclude_task_ids: ['t1']]]; break
            case 'wrong thread binding': pending.threadTs = 'different-thread'; break
            case 'stale plan': pending.planHash = 'f' * 64; break
            case 'source duration drift': source.getTask('t1').duration.amount = 60; break
            case 'source description drift': source.getTask('t1').description = 'changed planning notes'; break
            case 'calendar event drift':
                parts[3].seed(CalendarEvent.builder().id('new-event').uid('new-event').title('New meeting')
                    .calendarName('Planned').start(Instant.parse('2026-08-17T10:00:00Z'))
                    .end(Instant.parse('2026-08-17T11:00:00Z')).build())
                break
            case 'expired confirmation': now.set(initial.plusSeconds(901)); break
            case 'missing signing key': Files.delete(parts[5].decisionsDir.resolve('.ai-suggestion-signing-key')); break
            case 'corrupt signing key': Files.write(parts[5].decisionsDir.resolve('.ai-suggestion-signing-key'), new byte[32]); break
        }
        store.save(record.withPendingConfirmation(pending, now.get()))
        parts = build(state, now, new InMemoryMessagingSurface(), provider, source, source,
            null, null, [:], parts[3])
        daemon = parts[0]
        daemon.start()

        when:
        parts[4].emit(new MessagingEvent(eventId: 'confirm', type: 'thread_reply',
            actorId: fault == 'unauthorized actor' ? 'U2' : 'U1', channelId: 'C123',
            messageTs: '2700.2', threadTs: proposal.threadTs, text: 'try again'))

        then:
        store.find('C123', proposal.threadTs).iteration == 1
        parts[1].planStore.listPlanIds().size() == 1
        parts[4].replies.last().text =~ /(?i)(failed safely|not authorized)/
        source.dueUpdates.empty
        source.deadlineUpdates.empty
        parts[3].upserts.empty
        !Files.exists(parts[5].decisionsDir.resolve('ai-suggestion-decisions.json'))

        cleanup:
        daemon?.close()

        where:
        fault << ['tampered content', 'unknown suggestion', 'legacy pending map', 'wrong thread binding',
            'stale plan', 'source duration drift', 'source description drift', 'expired confirmation',
            'missing signing key', 'corrupt signing key', 'unauthorized actor', 'calendar event drift']
    }

    def 'AI #result overrides never become an actionable pending confirmation'() {
        given:
        File state = Files.createTempDirectory('smartplanner-ai-empty-').toFile()
        def now = new AtomicReference<>(initial)
        def provider = validatedOverrides(now, [], { List rows ->
            result == 'empty' ? [] : rows.collect { row -> row + [rangeEnd:
                Instant.parse(row.rangeEnd).minusSeconds(3600).toString()] }
        })
        def parts = build(state, now, new InMemoryMessagingSurface(), provider)
        SmartPlannerDaemon daemon = parts[0]
        daemon.start()
        def proposal = daemon.runNow('daily')

        when:
        parts[4].emit(new MessagingEvent(eventId: 'suggest', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '2800.1', threadTs: proposal.threadTs, text: 'adjust the estimate'))

        then:
        new ConversationStore(parts[5].deliveriesDir.resolve('conversations'))
            .find('C123', proposal.threadTs).pendingConfirmation.isEmpty()
        parts[1].planStore.listPlanIds().size() == 1
        parts[4].replies.last().text =~ /(?i)(no validated task override|exact reviewed planning range)/
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        cleanup:
        daemon?.close()

        where:
        result << ['empty', 'narrower-range']
    }

    def 'AI authentication failure becomes daemon state and clears after a fresh successful result'() {
        given:
        File state = Files.createTempDirectory('smartplanner-ai-reauth-').toFile()
        def now = new AtomicReference<>(initial)
        int calls=0
        Closure provider = { plan, String type, String correlationId, String feedback ->
            calls++
            if(calls==1)return [accepted:false,error:[errorClass:LlmErrorClass.AUTHENTICATION]]
            [accepted:true,bundle:[suggestions:[[action:'ACKNOWLEDGE',rationale:'safe']]]]
        }
        def parts=build(state,now,new InMemoryMessagingSurface(),provider)
        SmartPlannerDaemon daemon=parts[0];InMemoryMessagingSurface surface=parts[4]
        daemon.start();def proposal=daemon.runNow('daily')

        when:
        surface.emit(new MessagingEvent(eventId:'auth-failed',type:'thread_reply',actorId:'U1',
            channelId:'C123',messageTs:'2600.1',threadTs:proposal.threadTs,text:'interpret this'))

        then:
        daemon.statusSnapshot().ai.state=='REAUTHENTICATION_REQUIRED'
        surface.replies.last().text.contains('ai-auth-login')
        parts[2].dueUpdates.empty && parts[3].upserts.empty

        when:
        surface.emit(new MessagingEvent(eventId:'auth-recovered',type:'thread_reply',actorId:'U1',
            channelId:'C123',messageTs:'2600.2',threadTs:proposal.threadTs,text:'interpret again'))

        then:
        !daemon.statusSnapshot().containsKey('ai')
        parts[2].dueUpdates.empty && parts[3].upserts.empty

        cleanup:
        daemon?.close()
    }

    def 'daemon shutdown interrupts active AI and rejects its late result'() {
        given:
        File state=Files.createTempDirectory('smartplanner-ai-shutdown-').toFile()
        def now=new AtomicReference<>(initial)
        def entered=new CountDownLatch(1)
        Closure provider={plan,String type,String correlationId,String feedback->
            entered.countDown()
            try { new CountDownLatch(1).await(30,TimeUnit.SECONDS) }
            catch(InterruptedException ignored) { }
            [accepted:true,bundle:[suggestions:[[action:'ACKNOWLEDGE',rationale:'too late']]]]
        }
        def parts=build(state,now,new InMemoryMessagingSurface(),provider)
        SmartPlannerDaemon daemon=parts[0];InMemoryMessagingSurface surface=parts[4]
        daemon.start();def proposal=daemon.runNow('daily')
        Thread request=Thread.start {
            try {
                surface.emit(new MessagingEvent(eventId:'slow-ai',type:'thread_reply',actorId:'U1',
                    channelId:'C123',messageTs:'2700.1',threadTs:proposal.threadTs,text:'interpret slowly'))
            } catch(Throwable ignored) { }
        }
        assert entered.await(5,TimeUnit.SECONDS)

        when:
        daemon.close();request.join(5000)
        def conversation=new ConversationStore(parts[5].deliveriesDir.resolve('conversations'))
            .find('C123',proposal.threadTs)

        then:
        !request.alive
        conversation.pendingConfirmation.isEmpty()
        parts[2].dueUpdates.empty && parts[3].upserts.empty

        cleanup:
        try { daemon?.close() } catch(Exception ignored) {}
    }

    def 'legacy untyped AI pending overrides are retained for inspection but confer no authority'() {
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
        reloaded.pendingConfirmation == pending.pendingConfirmation
        reloaded.iteration == 1
        surface.replies.last().text.contains('pending AI authority is invalid')
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

    def 'recurrence-enabled daemon schedules five-minute item sync and catches up after bootstrap'() {
        given:
        File state = Files.createTempDirectory('smartplanner-recurrence-schedule-').toFile()
        def now = new AtomicReference<>(initial)
        def scheduler = new ManualScheduler()
        def gateway = new LifecycleGateway()
        Map tasks = [scheduling_eligible_labels: ['schedule'], default_duration_minutes: 30,
            recurrence: [enabled: true, rollout_cutoff: '2026-01-01T00:00:00Z']]
        SmartPlannerDaemon daemon = build(state, now, new InMemoryMessagingSurface(), null,
            gateway, gateway, scheduler, null, [tasks: tasks])[0]

        when:
        daemon.start()

        then: 'two planning loops plus the independent item-delta lifecycle loop are registered'
        scheduler.fixed.size() == 3
        scheduler.fixedDelayMillis == [10_000L, 86_400_000L, 300_000L]
        gateway.syncTokens.empty

        when: 'the recurrence loop runs without completion or activity feeds'
        scheduler.fixed[2].run()

        then: 'bootstrap is immediately followed by an incremental empty-state catch-up'
        gateway.syncTokens == ['*', 'bootstrap-token']

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

    def 'interrupted event claims are reclaimable after restart and completed events remain deduplicated'() {
        given:
        Path directory = Files.createTempDirectory('smartplanner-event-claims-')
        def firstProcess = new ConversationStore(directory, null, 'process-1')
        def restartedProcess = new ConversationStore(directory, null, 'process-2')
        Map payload = [eventId: 'Ev-restart', type: 'command', actorId: 'U1', channelId: 'C123',
            messageTs: '1.1', threadTs: null, text: 'status', bot: false]

        expect: 'the first process owns an in-flight claim and suppresses concurrent duplicates'
        firstProcess.claimEvent('Ev-restart', 'C123', '1.1', initial, payload)
        !firstProcess.claimEvent('Ev-restart', 'C123', '1.1', initial)
        restartedProcess.recoverableEventPayloads() == [payload]

        when: 'the daemon restarts before the event action completes'
        boolean reclaimed = restartedProcess.claimEvent('Ev-restart', 'C123', '1.1', initial.plusSeconds(1))
        restartedProcess.completeEvent('Ev-restart', initial.plusSeconds(2))

        then: 'the interrupted event is retried once and its completed result becomes terminal'
        reclaimed
        !new ConversationStore(directory, null, 'process-3')
            .claimEvent('Ev-restart', 'C123', '1.1', initial.plusSeconds(3))
    }

    def 'failed revised-conversation persistence leaves the old plan blocked from approval (AI: #aiMode)'() {
        given:
        File state = Files.createTempDirectory('smartplanner-revision-barrier-').toFile()
        def now = new AtomicReference<>(initial)
        Path conversationDir = state.toPath().resolve('deliveries/conversations')
        def failingStore = new ConversationStore(conversationDir, { Map root ->
            boolean finalRevision = (root.conversations instanceof Map) &&
                root.conversations.values().any { it instanceof Map && it.iteration == 2 && it.status == 'ACTIVE' }
            if (finalRevision) throw new IOException('injected final conversation persistence failure')
        }, 'daemon-process')
        def provider = aiMode ? validatedOverrides(now) : null
        def parts = build(state, now, new InMemoryMessagingSurface(), provider, null, null, null, failingStore)
        SmartPlannerDaemon daemon = parts[0]
        InMemoryMessagingSurface surface = parts[4]
        daemon.start()
        def original = daemon.runNow('daily')
        if (aiMode) surface.emit(new MessagingEvent(eventId: 'suggest-barrier', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '10.1', threadTs: original.threadTs, text: 'adjust estimate'))

        when: 'the revised Slack message succeeds but the new identity cannot be persisted'
        Throwable failure = null
        try {
            surface.emit(new MessagingEvent(eventId: 'Ev-revision-fail', type: 'thread_reply', actorId: 'U1',
                channelId: 'C123', messageTs: '10.2', threadTs: original.threadTs, text: 'try again'))
        } catch (Throwable t) { failure = t }
        def blocked = new ConversationStore(conversationDir).find('C123', original.threadTs)
        surface.emit(new MessagingEvent(eventId: 'Ev-approve-blocked', type: 'thread_reply', actorId: 'U1',
            channelId: 'C123', messageTs: '10.3', threadTs: original.threadTs, text: 'yes'))

        then:
        failure != null
        surface.replies.any { it.text.contains('Revised proposal') }
        surface.replies.any { it.text.contains('Feedback failed safely') }
        blocked.status == 'PUBLISHING_REVISION'
        parts[2].dueUpdates.empty
        parts[3].upserts.empty

        when: 'restart must not blindly republish an uncertain revision'
        daemon.close()
        def restarted = build(state, now, new InMemoryMessagingSurface(), provider)
        daemon = restarted[0]
        daemon.start()

        then:
        restarted[4].replies.empty
        restarted[1].planStore.listPlanIds().size() == 2
        new ConversationStore(conversationDir).find('C123', original.threadTs).status == 'PUBLISHING_REVISION'

        cleanup:
        daemon?.close()

        where:
        aiMode << [false, true]
    }

    def 'bounded Slack callback admission rejects overload and connection status follows the live client'() {
        given:
        def release = new CountDownLatch(1)
        def entered = new CountDownLatch(1)
        def live = new java.util.concurrent.atomic.AtomicBoolean(true)
        def callbacks = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(1))
        def surface = new SlackSocketModeMessagingSurface([channel: 'C123', maxEventTextChars: 4000],
            null, null, callbacks, null, { live.get() })
        surface.@handler = { MessagingEvent ignored ->
            entered.countDown()
            release.await()
        } as java.util.function.Consumer<MessagingEvent>
        surface.@connected = true

        expect: 'one active callback and one queued callback fill the bounded executor'
        surface.enqueue(new MessagingEvent(eventId: 'Ev-1', text: 'one'))
        entered.await(2, TimeUnit.SECONDS)
        surface.enqueue(new MessagingEvent(eventId: 'Ev-2', text: 'two'))
        !surface.enqueue(new MessagingEvent(eventId: 'Ev-3', text: 'three'))

        and: 'the connection probe initially observes readiness'
        surface.connected

        when: 'the live connection disconnects'
        live.set(false)

        then:
        !surface.connected

        when: 'the live connection reconnects'
        live.set(true)

        then:
        surface.connected

        cleanup:
        release.countDown()
        surface?.close()
    }

    def 'accepted Slack callback retries a released handler failure internally'() {
        given:
        def attempts = new java.util.concurrent.atomic.AtomicInteger()
        def recovered = new CountDownLatch(1)
        def callbacks = Executors.newSingleThreadExecutor()
        def surface = new SlackSocketModeMessagingSurface([channel: 'C123', maxEventTextChars: 4000],
            null, null, callbacks)
        surface.@handler = { MessagingEvent ignored ->
            if (attempts.incrementAndGet() == 1) throw new IOException('injected callback failure')
            recovered.countDown()
        } as java.util.function.Consumer<MessagingEvent>

        expect:
        surface.enqueue(new MessagingEvent(eventId: 'Ev-retry', text: 'status'))
        recovered.await(2, TimeUnit.SECONDS)
        attempts.get() == 2

        cleanup:
        surface?.close()
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
