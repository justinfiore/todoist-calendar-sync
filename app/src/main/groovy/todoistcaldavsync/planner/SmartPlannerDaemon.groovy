package todoistcaldavsync.planner

import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.feedback.RegexFeedbackEngine
import todoistcaldavsync.planner.messaging.*
import todoistcaldavsync.planner.state.ConversationRecord
import todoistcaldavsync.planner.state.ConversationStore

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/** Long-running multi-horizon planning and feedback lifecycle. */
final class SmartPlannerDaemon implements AutoCloseable {
    private final ProductionPlannerOrchestrator orchestrator
    private final ProductionIntegrationConfig config
    private final MessagingSurface surface
    private final ConversationStore conversations
    private final RegexFeedbackEngine regexFeedback
    private final Supplier<Instant> clock
    private final ScheduledExecutorService scheduler
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>()
    private final Map<String, AtomicBoolean> pending = new ConcurrentHashMap<>()
    private final Map<String, Integer> retryAttempts = new ConcurrentHashMap<>()
    private final Map<String, Map> runtimeStatus = new ConcurrentHashMap<>()
    private final AtomicBoolean started = new AtomicBoolean(false)
    private final AtomicBoolean closed = new AtomicBoolean(false)
    private volatile Throwable fatalFailure
    private final Object mutationLock = new Object()
    private final Set<String> allowedActors

    SmartPlannerDaemon(ProductionPlannerOrchestrator orchestrator,
                       MessagingSurface surface,
                       Supplier<Instant> clock = { Instant.now() },
                       ScheduledExecutorService scheduler = null) {
        if (orchestrator == null || surface == null) throw new IllegalArgumentException('orchestrator and messaging surface are required')
        this.orchestrator = orchestrator
        this.config = orchestrator.integrationConfig
        this.surface = surface
        this.clock = clock ?: ({ Instant.now() } as Supplier<Instant>)
        this.scheduler = scheduler ?: Executors.newScheduledThreadPool(
            Math.max(2, config.planningRuns().size())) { Runnable r ->
            Thread t = new Thread(r, 'smartplanner-daemon')
            t.daemon = false
            t
        }
        this.conversations = new ConversationStore(config.deliveriesDir.resolve('conversations'))
        this.regexFeedback = new RegexFeedbackEngine(config.feedbackRules())
        this.allowedActors = Collections.unmodifiableSet(config.feedbackActors().collect { it.toString() } as Set)
        config.planningRuns().each {
            running[it.name.toString()] = new AtomicBoolean(false)
            pending[it.name.toString()] = new AtomicBoolean(false)
        }
    }

    synchronized void start() {
        if (!started.compareAndSet(false, true)) return
        try {
            if (config.daemon.startupConnectivityCheck == true) {
                // Deliberately uncaught: configuration/authentication/provider startup failures fail fast.
                orchestrator.verifyConnectivity(clock.get())
            }
            surface.start(this.&handleEvent)
            config.planningRuns().each { Map run ->
                long initialMillis = run.runOnStartup == true ? 0L : (run.initialDelay as Duration).toMillis()
                long intervalMillis = (run.interval as Duration).toMillis()
                runtimeStatus[run.name.toString()] = [state: 'SCHEDULED',
                    nextRunAt: clock.get().plusMillis(initialMillis).toString()]
                scheduler.scheduleWithFixedDelay({ safeScheduledRun(run) } as Runnable,
                    initialMillis, intervalMillis, TimeUnit.MILLISECONDS)
            }
        } catch (Throwable failure) {
            started.set(false)
            scheduler.shutdownNow()
            try { surface.close() } catch (Throwable ignored) {}
            throw failure
        }
    }

    /** Test/operator seam for a named run. Overlap is coalesced to one pending trigger. */
    ConversationRecord runNow(String runName, Map overrides = [:]) {
        Map run = findRun(runName)
        AtomicBoolean guard = running.computeIfAbsent(run.name.toString()) { new AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) {
            pending.computeIfAbsent(run.name.toString()) { new AtomicBoolean(false) }.set(true)
            runtimeStatus[run.name.toString()] = [state: 'COALESCED', at: clock.get().toString()]
            return null
        }
        try {
            synchronized (mutationLock) {
                return planAndPublish(run, latestForRun(run.name.toString()), overrides ?: [:], false)
            }
        } finally {
            guard.set(false)
            schedulePending(run)
        }
    }

    Map statusSnapshot() {
        Map copy = [:]
        config.planningRuns().each { run ->
            String name = run.name.toString()
            copy[name] = new LinkedHashMap(runtimeStatus[name] ?: [state: 'WAITING'])
        }
        Collections.unmodifiableMap(copy)
    }

    void handleEvent(MessagingEvent event) {
        if (!started.get() || event == null || event.bot || !event.channelId || event.channelId != config.slack.channel) return
        if (!conversations.claimEvent(event.eventId, event.channelId, event.messageTs, clock.get())) return
        if (!allowedActors.contains(event.actorId)) {
            if (event.rootThreadTs()) safeReply(event.channelId, event.rootThreadTs(), 'You are not authorized to control SmartPlanner.', "unauthorized:${event.eventId}")
            return
        }
        if (event.isCommand()) {
            handleCommand(event)
            return
        }
        if (!event.threadTs) return // feedback is thread-only
        ConversationRecord conversation = conversations.find(event.channelId, event.threadTs)
        if (conversation == null || conversation.status != 'ACTIVE') return
        handleFeedback(event, conversation)
    }

    private void handleCommand(MessagingEvent event) {
        List<String> tokens = (event.text ?: '').trim().split(/\s+/).findAll { it } as List<String>
        String verb = tokens ? tokens[0].toLowerCase(Locale.ROOT) : 'help'
        String requestThread = event.rootThreadTs()
        if (!requestThread) {
            PublishedMessage receipt = safeReply(event.channelId, null,
                "${config.slack.appName} is handling your request.", "command-root:${event.eventId}")
            requestThread = receipt?.threadTs
        }
        if (requestThread) safeStatus(event.channelId, requestThread)
        try {
            switch (verb) {
                case 'plan':
                    List<String> requested = tokens.size() > 1
                        ? [tokens[1]] : config.planningRuns()*.name.collect { it.toString() }
                    List<ConversationRecord> results = requested.collect { runNow(it) }.findAll { it != null }
                    safeReply(event.channelId, requestThread,
                        results ? "Published ${results.size()} proposal(s): ${results.collect { "`${it.runName}` `${it.proposalId}`" }.join(', ')}."
                            : 'Requested planning work was already active; duplicate trigger coalesced.',
                        "command-result:${event.eventId}")
                    break
                case 'replan':
                    if (tokens.size() < 2) throw new IllegalArgumentException('Usage: replan RUN_NAME [feedback]')
                    String replanName = tokens[1]
                    ConversationRecord active = latestForRun(replanName)
                    if (active == null || active.status != 'ACTIVE') {
                        throw new IllegalArgumentException("No active proposal for planning run '${replanName}'")
                    }
                    String feedback = tokens.size() > 2 ? tokens.subList(2, tokens.size()).join(' ') : ''
                    Map commandOverrides = feedback ? [criteria: feedback] : [:]
                    MessagingEvent synthetic = new MessagingEvent(eventId: "${event.eventId}:replan", type: 'thread_reply',
                        actorId: event.actorId, channelId: active.channelId, messageTs: event.messageTs,
                        threadTs: active.threadTs, text: feedback ?: 'replan')
                    Plan activePlan = orchestrator.planStore.load(active.planId)
                    synchronized (mutationLock) { replanInThread(synthetic, active, validateOverrides(activePlan, commandOverrides)) }
                    safeReply(event.channelId, requestThread, "Replanned `${replanName}` in its proposal thread.", "command-replan:${event.eventId}")
                    break
                case 'status':
                    safeReply(event.channelId, requestThread, formatStatus(), "command-status:${event.eventId}")
                    break
                case 'help':
                    safeReply(event.channelId, requestThread,
                        "Commands: `plan [run-name]`, `replan run-name [feedback]`, `status`, `help`. Reply in a proposal thread to approve, reject, acknowledge, apply safe changes, or request replanning.",
                        "command-help:${event.eventId}")
                    break
                default:
                    safeReply(event.channelId, requestThread, "Unknown command `${verb}`. Try `help`.", "command-unknown:${event.eventId}")
            }
        } catch (Throwable t) {
            safeReply(event.channelId, requestThread, "Request failed safely: ${safeError(t)}", "command-error:${event.eventId}")
        } finally {
            if (requestThread) safeClearStatus(event.channelId, requestThread)
        }
    }

    private void handleFeedback(MessagingEvent event, ConversationRecord conversation) {
        safeStatus(event.channelId, event.threadTs)
        try {
            RegexFeedbackEngine.FeedbackMatch match = regexFeedback.match(event.text)
            String action = match?.action
            Map overrides = new LinkedHashMap(conversation.overrides ?: [:])
            if (match?.overrides) overrides.putAll(match.overrides)
            String structuredCommand = null

            if (action != null && conversation.pendingConfirmation) {
                Map pending = conversation.pendingConfirmation
                Instant expiresAt = Instant.parse(pending.expiresAt.toString())
                if (clock.get().isAfter(expiresAt) || pending.planId != conversation.planId ||
                    pending.planHash != conversation.planHash || pending.action != action) {
                    conversations.save(conversation.withPendingConfirmation([:], clock.get()))
                    safeReply(event.channelId, event.threadTs,
                        'The pending AI suggestion is stale, expired, or does not match this confirmation; no action was taken.',
                        "feedback-confirm-stale:${event.eventId}")
                    return
                }
                if (pending.overrides instanceof Map) overrides.putAll(pending.overrides as Map)
                conversation = conversation.withPendingConfirmation([:], clock.get())
                conversations.save(conversation)
            }

            if (action == null && orchestrator.plannerConfig.ai.enabled) {
                def interpreted = orchestrator.aiSuggestions(conversation.planId,
                    'conversational_feedback_interpretation', event.eventId ?: "feedback-${event.messageTs}", event.text)
                if (interpreted?.accepted && interpreted.bundle?.suggestions) {
                    def suggestion = interpreted.bundle.suggestions[0]
                    String proposedAction = normalizeAiAction(suggestion.action?.toString())
                    Map proposedOverrides = [:]
                    if (proposedAction == 'replan') {
                        def suggested = orchestrator.aiSuggestions(conversation.planId,
                            'temporary_planning_overrides', "override-${event.messageTs?.replace('.', '-')}", event.text)
                        if (suggested?.accepted) mergeAiOverrides(proposedOverrides, suggested.bundle?.suggestions ?: [])
                    }
                    if (proposedAction != null) {
                        Map pending = [action: proposedAction, overrides: proposedOverrides,
                            sourceEventId: event.eventId, planId: conversation.planId, planHash: conversation.planHash,
                            expiresAt: clock.get().plus(Duration.ofMinutes(15)).toString()]
                        conversations.save(conversation.withPendingConfirmation(pending, clock.get()))
                        safeReply(event.channelId, event.threadTs,
                            "AI suggests `${proposedAction}`${proposedOverrides ? " with temporary overrides `${proposedOverrides}`" : ''}. Confirm with a configured `${proposedAction}` phrase within 15 minutes. No plan or provider changes have occurred.",
                            "feedback-ai-confirm:${event.eventId}")
                        return
                    }
                }
            }
            if (action == null) {
                safeReply(event.channelId, event.threadTs,
                    'I could not classify that feedback. Use a configured confirmation/rejection/replan phrase, or enable LLM feedback interpretation.',
                    "feedback-unmatched:${event.eventId}")
                return
            }

            Plan plan = orchestrator.planStore.load(conversation.planId)
            if (plan == null || PlanHash.compute(plan) != conversation.planHash || plan.version != conversation.planVersion) {
                safeReply(event.channelId, event.threadTs, 'This proposal is stale or its stored identity no longer matches; no changes were applied.',
                    "feedback-stale:${event.eventId}")
                return
            }
            switch (action) {
                case 'acknowledge':
                    conversations.save(conversation.withStatus('ACKNOWLEDGED', clock.get()))
                    safeReply(event.channelId, event.threadTs, 'Acknowledged. No Todoist or calendar changes were made.', "feedback-ack:${event.eventId}")
                    break
                case 'reject':
                    String command = structuredCommand ?: "reject ${conversation.proposalId} ${conversation.planHash} ${match?.captures?.reason ?: event.text}"
                    orchestrator.feedback(conversation.planId, command, event.actorId, event.eventId, event.messageTs)
                    conversations.save(conversation.withStatus('REJECTED', clock.get()))
                    safeReply(event.channelId, event.threadTs, 'Proposal rejected. No Todoist or calendar changes were made.', "feedback-reject:${event.eventId}")
                    break
                case 'approve':
                    String command = structuredCommand ?: "approve ${conversation.proposalId} ${conversation.planHash}"
                    def parsed = orchestrator.feedback(conversation.planId, command, event.actorId, event.eventId, event.messageTs)
                    if (!parsed.accepted || parsed.approval == null) throw new IllegalStateException(parsed.message ?: 'approval rejected')
                    def applied = orchestrator.applyDecision(conversation.planId, parsed.decision.id)
                    conversations.save(conversation.withStatus('APPLIED', clock.get()))
                    safeReply(event.channelId, event.threadTs, "Approved plan applied with status `${applied.status}`.", "feedback-approve:${event.eventId}")
                    break
                case 'apply_safe':
                    def receipt = orchestrator.applySafe(conversation.planId)
                    conversations.save(conversation.withStatus('SAFE_CHANGES_APPLIED', clock.get()))
                    safeReply(event.channelId, event.threadTs, "Safe changes processed with status `${receipt.overallStatus.wire}`; protected changes remain withheld.", "feedback-safe:${event.eventId}")
                    break
                case 'replan':
                    synchronized (mutationLock) { replanInThread(event, conversation, validateOverrides(plan, overrides)) }
                    break
                case 'status':
                    safeReply(event.channelId, event.threadTs,
                        "Proposal `${conversation.proposalId}` iteration ${conversation.iteration} is `${conversation.status}`.",
                        "feedback-status:${event.eventId}")
                    break
                case 'help':
                    safeReply(event.channelId, event.threadTs,
                        'Reply with a configured approve, reject, acknowledge, apply-safe, or replan phrase.',
                        "feedback-help:${event.eventId}")
                    break
                default:
                    throw new IllegalArgumentException("Unsupported feedback action: ${action}")
            }
        } catch (Throwable t) {
            safeReply(event.channelId, event.threadTs, "Feedback failed safely: ${safeError(t)}", "feedback-error:${event.eventId}")
        } finally {
            safeClearStatus(event.channelId, event.threadTs)
        }
    }

    private void replanInThread(MessagingEvent event, ConversationRecord prior, Map overrides) {
        Map run = findRun(prior.runName)
        Instant now = clock.get()
        Duration horizon = overrideHorizon(run.horizon as Duration, overrides)
        Plan plan = orchestrator.preview(now, now.plus(horizon), prior.planId, overrides)
        Proposal proposal = Proposal.fromPlan(plan)
        Message message = renderer().renderProposal(plan, now)
        PublishedMessage reply = safeReply(event.channelId, event.threadTs,
            "*Revised proposal — iteration ${prior.iteration + 1}*\n${message.body}",
            "replan:${event.eventId}:${proposal.id}")
        if (reply == null || !reply.delivered()) throw new IllegalStateException('revised proposal delivery failed')
        ConversationRecord next = prior.next(planId: plan.id, planVersion: plan.version,
            planHash: proposal.planHash, proposalId: proposal.id, updatedAt: now,
            overrides: overrides, status: 'ACTIVE')
        conversations.save(next)
    }

    private ConversationRecord planAndPublish(Map run, ConversationRecord previous, Map overrides, boolean threaded) {
        Instant startedAt = clock.get()
        String name = run.name.toString()
        runtimeStatus[name] = [state: 'RUNNING', startedAt: startedAt.toString()]
        Duration horizon = overrideHorizon(run.horizon as Duration, overrides)
        Plan plan = orchestrator.preview(startedAt, startedAt.plus(horizon), previous?.planId, overrides)
        Proposal proposal = Proposal.fromPlan(plan)
        Message rendered = renderer().renderProposal(plan, startedAt)
            .withDeliveryKey("daemon:${name}:${proposal.id}", [planningRun: name, horizon: horizon.toString()])
        PublishedMessage published = surface.publishProposal(rendered)
        if (!published.delivered()) throw new IllegalStateException(published.error ?: 'proposal delivery failed')
        ConversationRecord record = new ConversationRecord(channelId: published.channelId,
            threadTs: published.threadTs, runName: name, planId: plan.id, planVersion: plan.version,
            planHash: proposal.planHash, proposalId: proposal.id, iteration: 1,
            previousPlanId: previous?.planId, previousProposalId: previous?.proposalId,
            createdAt: startedAt, updatedAt: clock.get(), status: 'ACTIVE', overrides: overrides)
        if (previous != null && (previous.channelId != record.channelId || previous.threadTs != record.threadTs)) {
            conversations.save(previous.withStatus('SUPERSEDED', clock.get()))
        }
        conversations.save(record)
        runtimeStatus[name] = [state: 'WAITING_FOR_FEEDBACK', completedAt: clock.get().toString(),
            proposalId: proposal.id, threadTs: published.threadTs,
            nextRunAt: clock.get().plus(run.interval as Duration).toString()]
        record
    }

    private void safeScheduledRun(Map run) {
        String name = run.name.toString()
        AtomicBoolean guard = running.computeIfAbsent(name) { new AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) {
            pending.computeIfAbsent(name) { new AtomicBoolean(false) }.set(true)
            runtimeStatus[name] = [state: 'COALESCED', at: clock.get().toString()]
            return
        }
        try {
            synchronized (mutationLock) {
                planAndPublish(run, latestForRun(name), [:], false)
            }
            retryAttempts.remove(name)
        } catch (Throwable t) {
            if (isFatalRequiredProviderAuth(t)) {
                runtimeStatus[name] = [state: 'FATAL_REQUIRED_PROVIDER_AUTH', at: clock.get().toString(), error: safeError(t)]
                requestFatalShutdown(t)
                return
            }
            // Runtime provider/messaging/transient failures never terminate the scheduler.
            int attempt = (retryAttempts[name] ?: 0) + 1
            retryAttempts[name] = attempt
            long initial = (config.daemon.retryInitialDelay as Duration).toMillis()
            long maximum = (config.daemon.retryMaxDelay as Duration).toMillis()
            double multiplier = config.daemon.retryMultiplier as double
            long delay = Math.min(maximum, Math.max(initial,
                (long) (initial * Math.pow(multiplier, Math.min(attempt - 1, 30)))))
            runtimeStatus[name] = [state: 'RETRYABLE_FAILURE', at: clock.get().toString(),
                error: safeError(t), retryAttempt: attempt, retryIn: Duration.ofMillis(delay).toString(),
                nextRunAt: clock.get().plusMillis(delay).toString()]
            System.err.println("SmartPlanner run ${name} failed; retry ${attempt} in ${delay}ms: ${safeError(t)}")
            if (started.get() && !scheduler.isShutdown()) {
                try { scheduler.schedule({ safeScheduledRun(run) } as Runnable, delay, TimeUnit.MILLISECONDS) }
                catch (java.util.concurrent.RejectedExecutionException ignored) {}
            }
        } finally {
            guard.set(false)
            schedulePending(run)
        }
    }

    private void schedulePending(Map run) {
        String name = run.name.toString()
        AtomicBoolean flag = pending.computeIfAbsent(name) { new AtomicBoolean(false) }
        if (!flag.compareAndSet(true, false) || !started.get() || scheduler.isShutdown()) return
        try { scheduler.schedule({ safeScheduledRun(run) } as Runnable, 0L, TimeUnit.MILLISECONDS) }
        catch (java.util.concurrent.RejectedExecutionException ignored) { flag.set(true) }
    }

    private Map findRun(String runName) {
        Map run = config.planningRuns().find { it.name == runName }
        if (run == null) throw new IllegalArgumentException("Unknown planning run '${runName}'. Configured: ${config.planningRuns()*.name.join(', ')}")
        run
    }

    private ConversationRecord latestForRun(String runName) {
        conversations.list().findAll { it.runName == runName }
            .max { a, b -> a.updatedAt <=> b.updatedAt }
    }

    private static Map validateOverrides(Plan plan, Map raw) {
        if (plan == null) throw new IllegalArgumentException('current plan is required for override validation')
        Map value = new LinkedHashMap(raw ?: [:])
        Set<String> known = plan.tasks*.id.collect { it.toString() } as Set
        ['exclude_task_ids', 'freeze_task_ids'].each { key ->
            Collection ids = value[key] instanceof Collection ? value[key] as Collection : []
            if (ids.size() > 100) throw new IllegalArgumentException("${key} supports at most 100 task ids")
            ids.each { if (!known.contains(it.toString())) throw new IllegalArgumentException("${key} references unknown task ${it}") }
        }
        if (value.priority_overrides instanceof Map) {
            if ((value.priority_overrides as Map).size() > 100) throw new IllegalArgumentException('priority_overrides supports at most 100 tasks')
            (value.priority_overrides as Map).each { id, priority ->
                if (!known.contains(id.toString())) throw new IllegalArgumentException("priority_overrides references unknown task ${id}")
                int p = Integer.parseInt(priority.toString())
                if (p < 1 || p > 4) throw new IllegalArgumentException("priority override for ${id} must be 1..4")
            }
        }
        if (value.criteria != null && value.criteria.toString().length() > 1000) {
            throw new IllegalArgumentException('criteria must be at most 1000 characters')
        }
        value
    }

    private static boolean isFatalRequiredProviderAuth(Throwable failure) {
        Throwable current = failure
        while (current != null) {
            String classification = current.metaClass.hasProperty(current, 'classification') ? current.classification?.toString() : null
            String message = current.message ?: ''
            boolean requiredGateway = current.class.name.contains('Todoist') || current.class.name.contains('CalDav')
            if (classification == 'AUTHENTICATION' || (requiredGateway && message ==~ /(?s).*HTTP (401|403).*/)) return true
            current = current.cause
        }
        false
    }

    private void requestFatalShutdown(Throwable failure) {
        System.err.println("SmartPlanner fatal required-provider authentication failure: ${safeError(failure)}")
        fatalFailure = failure
        started.set(false)
        scheduler.shutdown()
        try { surface.close() } catch (Throwable ignored) {}
    }

    private MessageRenderer renderer() {
        new MessageRenderer(orchestrator.plannerConfig.messaging.timezone ?: orchestrator.plannerConfig.timezone,
            config.slack.channel, orchestrator.plannerConfig.messaging.riskDeadlineDays)
    }

    private static Duration overrideHorizon(Duration configured, Map overrides) {
        def raw = overrides.horizon
        if (raw == null) return configured
        Duration value = raw instanceof Duration ? raw as Duration : Duration.parse(raw.toString())
        if (value < Duration.ofMinutes(5) || value > Duration.ofDays(90)) throw new IllegalArgumentException('feedback horizon override must be PT5M..P90D')
        value
    }

    private static void mergeAiOverrides(Map target, Collection suggestions) {
        suggestions.each { s ->
            String type = s.overrideType?.toString()?.toLowerCase(Locale.ROOT)
            List<String> ids = (s.taskIds ?: []).collect { it.toString() }
            if (type in ['priority', 'priority_override']) {
                Map priorities = target.priority_overrides instanceof Map ? new LinkedHashMap(target.priority_overrides as Map) : [:]
                ids.each { priorities[it] = Integer.parseInt(s.value.toString()) }
                target.priority_overrides = priorities
            } else if (type in ['exclude', 'excluded'] && s.value.toString().equalsIgnoreCase('true')) {
                target.exclude_task_ids = (((target.exclude_task_ids ?: []) as Collection) + ids).toSet() as List
            } else if (type == 'freeze' && s.value.toString().equalsIgnoreCase('true')) {
                target.freeze_task_ids = (((target.freeze_task_ids ?: []) as Collection) + ids).toSet() as List
            }
        }
    }

    private static String normalizeAiAction(String action) {
        switch (action?.toUpperCase(Locale.ROOT)) {
            case 'APPROVE': return 'approve'
            case 'APPLY_SAFE': return 'apply_safe'
            case 'REJECT': return 'reject'
            case 'REQUEST_CHANGES': return 'replan'
            default: return null
        }
    }

    private PublishedMessage safeReply(String channel, String thread, String text, String key) {
        try { surface.reply(channel, thread, text, key) }
        catch (Throwable t) { System.err.println("SmartPlanner reply failed: ${safeError(t)}"); null }
    }

    private void safeStatus(String channel, String thread) {
        try { surface.setWorkingStatus(channel, thread, config.slack.workingStatus.toString(), config.slack.loadingMessages as List<String>) }
        catch (Throwable t) { System.err.println("SmartPlanner status update failed non-fatally: ${safeError(t)}") }
    }

    private void safeClearStatus(String channel, String thread) {
        try { surface.clearWorkingStatus(channel, thread) }
        catch (Throwable t) { System.err.println("SmartPlanner status clear failed non-fatally: ${safeError(t)}") }
    }

    private String formatStatus() {
        String header = "Readiness: `${started.get() ? 'ready' : 'stopped'}`; Socket Mode: `${surface.isConnected() ? 'connected' : 'disconnected'}`."
        List<String> runs = config.planningRuns().collect { Map run ->
            String name = run.name.toString()
            Map status = runtimeStatus[name] ?: [state: 'WAITING']
            String last = status.completedAt ?: status.at ?: 'never'
            String next = status.nextRunAt ?: 'not scheduled'
            "• `${name}`: `${status.state}`; active `${running[name]?.get() == true}`; pending `${pending[name]?.get() == true}`; last `${last}`; next `${next}`"
        }
        ([header] + (runs ?: ['No planning runs configured.'])).join('\n')
    }

    private static String safeError(Throwable t) {
        String message = t?.message?.replaceAll(/[\r\n]+/, ' ') ?: t?.class?.simpleName ?: 'unknown error'
        message.length() > 240 ? message.substring(0, 240) + '…' : message
    }

    void awaitTermination() {
        while (started.get() && !Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(1000) } catch (InterruptedException e) { Thread.currentThread().interrupt(); break }
        }
        if (fatalFailure != null) {
            throw new IllegalStateException("SmartPlanner terminated after fatal required-provider authentication failure: ${safeError(fatalFailure)}",
                fatalFailure)
        }
    }

    @Override
    synchronized void close() {
        if (!closed.compareAndSet(false, true)) return
        started.set(false)
        scheduler.shutdown()
        try { scheduler.awaitTermination((config.daemon.shutdownTimeout as Duration).toMillis(), TimeUnit.MILLISECONDS) }
        catch (InterruptedException e) { Thread.currentThread().interrupt() }
        if (!scheduler.isTerminated()) scheduler.shutdownNow()
        try { surface.close() } finally { orchestrator.close() }
    }
}
