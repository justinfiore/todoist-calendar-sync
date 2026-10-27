package todoistcaldavsync.planner

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.ai.AiAssistanceService
import todoistcaldavsync.planner.ai.LlmGateway
import todoistcaldavsync.planner.ai.OpenAiCompatibleLlmGateway
import todoistcaldavsync.planner.ai.OpenAiSiwcAdapter
import todoistcaldavsync.planner.ai.SubscriptionCredentialService
import todoistcaldavsync.planner.ai.SubscriptionCredentialStore
import todoistcaldavsync.planner.ai.SubscriptionResponsesLlmGateway
import todoistcaldavsync.planner.ai.SubscriptionProviderAdapter
import todoistcaldavsync.planner.ai.XaiDeviceOAuthAdapter
import todoistcaldavsync.planner.ai.*
import todoistcaldavsync.planner.apply.PlanApplier
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.feedback.FeedbackParser
import todoistcaldavsync.planner.messaging.MessagingService
import todoistcaldavsync.planner.policy.EventClassifier
import todoistcaldavsync.planner.report.CapacityReportFormatter
import todoistcaldavsync.planner.report.CapacityReportService
import todoistcaldavsync.planner.recurrence.ItemsSyncStateStore
import todoistcaldavsync.planner.recurrence.LifecycleMarkerWriter
import todoistcaldavsync.planner.recurrence.PollResult
import todoistcaldavsync.planner.recurrence.RecurrenceLifecycleManager
import todoistcaldavsync.planner.recurrence.TodoistItemsPoller
import todoistcaldavsync.planner.scheduling.AvailabilityCalculator
import todoistcaldavsync.planner.scheduling.DeterministicScheduler
import todoistcaldavsync.planner.state.*
import todoistcaldavsync.planner.writeback.CalendarWritebackStore
import todoistcaldavsync.planner.writeback.PlannerCalendarWritebackService

import java.time.Instant
import java.util.function.Supplier

/**
 * Phase 7 composition root. It is the only production class that assembles
 * endpoints, state stores, read/write adapters and Phase 1-6 services.
 * Deterministic planning remains separate from all mutation authority.
 */
final class ProductionPlannerOrchestrator implements AutoCloseable {
    final PlannerConfig plannerConfig
    final ProductionIntegrationConfig integrationConfig
    final PlanStore planStore

    private final TodoistReadGateway todoistRead
    private final TodoistWriteGateway todoistWrite
    private final CalendarReadGateway calendarRead
    private final CalendarWriteGateway calendarWrite
    private final ApplicationStateStore applicationState
    private final DecisionStore decisionStore
    private final DeliveryLedger deliveryLedger
    private final Supplier<Instant> clock
    private final Closure<WeatherReadGateway> weatherGatewayFactory
    private final Closure aiSuggestionProvider
    private final Closure<LlmGateway> aiGatewayFactory
    private final TodoistItemsPoller itemsPoller
    private final PlannerCalendarWritebackService calendarWriteback

    ProductionPlannerOrchestrator(File configFile, Supplier<Instant> clock = { Instant.now() }) {
        this(configFile, clock,
            { Map options -> new CalDavHttpGateway(options) },
            { Map options -> new GoogleCalendarApiGateway(options) })
    }

    /** Provider-factory seam for hermetic composition tests; factories must not perform provider calls. */
    ProductionPlannerOrchestrator(File configFile, Supplier<Instant> clock,
                                  Closure calDavGatewayFactory, Closure googleGatewayFactory) {
        if (configFile == null || !configFile.isFile()) throw new IllegalArgumentException("Config file not found: ${configFile}")
        Map root = new YamlSlurper().parse(configFile) as Map
        this.plannerConfig = PlannerConfig.fromMap(root)
        this.integrationConfig = ProductionIntegrationConfig.fromMap(root, configFile.absoluteFile.parentFile.toPath())
        this.clock = clock ?: ({ Instant.now() } as Supplier<Instant>)
        this.weatherGatewayFactory = null
        this.aiSuggestionProvider = null
        this.aiGatewayFactory = null
        this.planStore = new PlanStore(integrationConfig.plansDir)
        this.applicationState = new ApplicationStateStore(integrationConfig.applicationsDir)
        this.decisionStore = new DecisionStore(integrationConfig.decisionsDir)
        this.deliveryLedger = new DeliveryLedger(integrationConfig.deliveriesDir)
        def todoistGateway = new TodoistRestGateway(integrationConfig.todoist)
        if (calDavGatewayFactory == null || googleGatewayFactory == null) {
            throw new IllegalArgumentException('both calendar provider factories are required')
        }
        def calendarGateway
        if (integrationConfig.calendarProvider.isCalDav()) {
            calendarGateway = calDavGatewayFactory.call([
                calendars          : integrationConfig.calendars,
                managedCalendarName: plannerConfig.outputCalendar,
                timezone           : plannerConfig.timezone,
                timeout            : integrationConfig.caldav.timeout,
                maxResponseBytes   : integrationConfig.caldav.maxResponseBytes
            ])
        } else {
            calendarGateway = googleGatewayFactory.call([
                config             : integrationConfig.calendarProvider.googleCalendarApi,
                managedCalendarName: plannerConfig.outputCalendar,
                timezone           : plannerConfig.timezone
            ])
        }
        if (!(calendarGateway instanceof CalendarReadGateway) || !(calendarGateway instanceof CalendarWriteGateway)) {
            throw new IllegalArgumentException('selected calendar provider must implement CalendarReadGateway and CalendarWriteGateway')
        }
        this.todoistRead = todoistGateway
        this.todoistWrite = todoistGateway
        this.calendarRead = calendarGateway
        this.calendarWrite = calendarGateway
        this.itemsPoller = createItemsPoller(todoistGateway)
        this.calendarWriteback = createCalendarWriteback()
    }

    /** Dependency-injected composition seam used by hermetic end-to-end tests/embedders. */
    ProductionPlannerOrchestrator(PlannerConfig plannerConfig,
                                  ProductionIntegrationConfig integrationConfig,
                                  TodoistReadGateway todoistRead,
                                  TodoistWriteGateway todoistWrite,
                                  CalendarReadGateway calendarRead,
                                  CalendarWriteGateway calendarWrite,
                                  Supplier<Instant> clock = { Instant.now() },
                                  Closure<WeatherReadGateway> weatherGatewayFactory = null,
                                  Closure aiSuggestionProvider = null,
                                  Closure<LlmGateway> aiGatewayFactory = null) {
        if ([plannerConfig, integrationConfig, todoistRead, todoistWrite, calendarRead, calendarWrite].any { it == null }) {
            throw new IllegalArgumentException('planner/integration config and all read/write gateways are required')
        }
        this.plannerConfig = plannerConfig
        this.integrationConfig = integrationConfig
        this.clock = clock ?: ({ Instant.now() } as Supplier<Instant>)
        this.weatherGatewayFactory = weatherGatewayFactory
        this.aiSuggestionProvider = aiSuggestionProvider
        this.aiGatewayFactory = aiGatewayFactory
        this.planStore = new PlanStore(integrationConfig.plansDir)
        this.applicationState = new ApplicationStateStore(integrationConfig.applicationsDir)
        this.decisionStore = new DecisionStore(integrationConfig.decisionsDir)
        this.deliveryLedger = new DeliveryLedger(integrationConfig.deliveriesDir)
        this.todoistRead = todoistRead
        this.todoistWrite = todoistWrite
        this.calendarRead = calendarRead
        this.calendarWrite = calendarWrite
        this.itemsPoller = createItemsPoller(todoistRead)
        this.calendarWriteback = createCalendarWriteback()
    }

    private PlannerCalendarWritebackService createCalendarWriteback() {
        new PlannerCalendarWritebackService(plannerConfig, calendarRead, todoistRead, todoistWrite,
            applicationState, new CalendarWritebackStore(integrationConfig.applicationsDir), clock)
    }

    private TodoistItemsPoller createItemsPoller(Object gateway) {
        if (!plannerConfig.recurrence.enabled) return null
        if (!(gateway instanceof TodoistLifecycleGateway)) {
            throw new IllegalArgumentException(
                'recurrence lifecycle requires a TodoistLifecycleGateway with Sync support')
        }
        TodoistLifecycleGateway lifecycle = gateway as TodoistLifecycleGateway
        def manager = new RecurrenceLifecycleManager(plannerConfig,
            new LifecycleMarkerWriter(lifecycle, plannerConfig),
            { String message -> System.err.println("SmartPlanner recurrence: ${message}") })
        def state = new ItemsSyncStateStore(integrationConfig.applicationsDir.resolve('recurrence'))
        new TodoistItemsPoller(lifecycle, state, { Map item ->
            if (item.is_deleted == true || item.isDeleted == true) return
            Task task = Task.fromTodoistMap(item, plannerConfig.durationResolver,
                plannerConfig.manualLabel, plannerConfig.timezone)
            manager.process(task)
        })
    }

    /** Execute one crash-safe incremental Todoist items lifecycle poll. */
    PollResult pollTodoistItems() {
        if (itemsPoller == null) throw new IllegalStateException('recurrence lifecycle is disabled')
        itemsPoller.poll()
    }

    /** Mutating daemon-only pre-plan writeback. Standalone preview/capacity never call this method. */
    List<Map> pollCalendarWriteback(Instant rangeStart, Instant rangeEnd) {
        calendarWriteback.pollAndApply(rangeStart, rangeEnd)
    }

    /** Read-only live capacity operation. */
    String capacity(Instant rangeStart, Instant rangeEnd, String format = 'markdown') {
        def report = new CapacityReportService(plannerConfig, todoistRead, calendarRead).generate(rangeStart, rangeEnd)
        format?.toLowerCase(Locale.ROOT) == 'json'
            ? CapacityReportFormatter.toJson(report)
            : CapacityReportFormatter.toMarkdown(report)
    }

    /**
     * Crawl live tasks/events, optionally fetch weather, reuse the previous plan
     * for stability, deterministically propose, and atomically persist the snapshot.
     */
    Plan preview(Instant rangeStart, Instant rangeEnd, String previousPlanId = null) {
        return preview(rangeStart, rangeEnd, previousPlanId, [:])
    }

    /** Deterministic preview with conversation-scoped, non-persisted planning overrides. */
    Plan preview(Instant rangeStart, Instant rangeEnd, String previousPlanId, Map overrides) {
        List<Task> tasks = applyTemporaryOverrides(normalizedEligibleTasks(), overrides ?: [:])
        List<CalendarEvent> events = calendarRead.fetchEvents(rangeStart, rangeEnd)
        propose(tasks, events, rangeStart, rangeEnd, previousPlanId, overrides ?: [:], clock.get(), [])
    }

    private Plan propose(List<Task> tasks, List<CalendarEvent> events, Instant rangeStart, Instant rangeEnd,
                         String previousPlanId, Map overrides, Instant now, List<PlanningExplanation> explanations,
                         String confirmedPlanId = null) {
        List<CalendarEvent> classified = new EventClassifier(plannerConfig).classifyAll(events)
        def availability = new AvailabilityCalculator(plannerConfig).calculate(rangeStart, rangeEnd, classified)
        Plan previous = loadPrevious(previousPlanId ?: integrationConfig.previousPlanId)
        WeatherForecast forecast = null
        if (plannerConfig.weather.enabled) {
            try {
                forecast = weatherGateway().fetchForecast(rangeStart, rangeEnd)
            } catch (OpenMeteoWeatherGateway.WeatherGatewayException ignored) {
                // Represent provider unavailability as missing forecast data so the
                // deterministic evaluator applies the configured fallback policy.
                forecast = null
            }
        }
        Set<String> frozen = overrides.freeze_task_ids instanceof Collection
            ? (overrides.freeze_task_ids as Collection).collect { it.toString() } as Set : [] as Set
        Plan plan = new DeterministicScheduler(plannerConfig).propose(
            tasks, availability.slots, rangeStart, rangeEnd, now, previous, frozen, forecast)
        if (explanations) {
            plan = Plan.builder().id(confirmedPlanId ?: plan.id).version(plan.version).createdAt(plan.createdAt).mode(plan.mode)
                .tasks(plan.tasks).slots(plan.slots).scheduledBlocks(plan.scheduledBlocks).unscheduled(plan.unscheduled)
                .changes(plan.changes).explanations(plan.explanations + explanations).metrics(plan.metrics)
                .humanDiff(plan.humanDiff).build()
        }
        planStore.save(plan)
        return plan
    }

    Map prepareAiPending(AiSuggestionBundle supplied, ConversationRecord conversation, String sourceEvent) {
        if (!plannerConfig.ai.enabled) throw new IllegalArgumentException('AI is disabled')
        Instant now = clock.get()
        def validator = new LlmSchemaValidator()
        def canonical = validator.canonicalize(supplied, now)
        if (!canonical.accepted || canonical.bundle.suggestionType != 'temporary_planning_overrides' ||
            !canonical.bundle.suggestions) throw new IllegalArgumentException('no validated task override is ready')
        AiSuggestionBundle bundle = canonical.bundle
        Plan plan = requirePlan(conversation.planId)
        Instant start = plan.slots*.start.min(), end = plan.slots*.end.max()
        if (!start || !end || !end.isAfter(now) || bundle.planId != plan.id ||
            bundle.planVersion != conversation.planVersion || bundle.planHash != conversation.planHash ||
            bundle.planHash != PlanHash.compute(plan)) throw new IllegalArgumentException('stale suggestion plan or range')
        bundle.suggestions.each { TemporaryPlanningOverride o ->
            if (o.rangeStart != start || o.rangeEnd != end || o.expiresAt.isBefore(end))
                throw new IllegalArgumentException('override must cover the exact reviewed planning range')
        }
        Instant expiry = ([now.plusSeconds(900)] + bundle.suggestions*.expiresAt).min()
        aiDecisionStore().sealPending([schemaVersion: 1, action: 'replan', sourceEventId: sourceEvent,
            channelId: conversation.channelId, threadTs: conversation.threadTs, planId: plan.id,
            planVersion: plan.version, planHash: bundle.planHash, planningInputHash: bundle.planningInputHash,
            contentHash: bundle.contentHash, correlationId: bundle.correlationId,
            canonicalJson: validator.exportCanonical(bundle), suggestionIds: bundle.suggestions*.suggestionId,
            rangeStart: start.toString(), rangeEnd: end.toString(), expiresAt: expiry.toString()])
    }

    /** Exact human confirmation produces a preview, never an apply approval. */
    Plan previewConfirmedAi(ConversationRecord conversation, String actor) {
        Map pending = conversation.pendingConfirmation
        def store = aiDecisionStore()
        Instant now = clock.get()
        if (!plannerConfig.ai.enabled || !integrationConfig.feedback.allowedActors.contains(actor) ||
            pending.schemaVersion != 1 || !store.authenticPending(pending) ||
            pending.channelId != conversation.channelId || pending.threadTs != conversation.threadTs ||
            pending.planId != conversation.planId || pending.planVersion != conversation.planVersion ||
            pending.planHash != conversation.planHash || !Instant.parse(pending.expiresAt.toString()).isAfter(now))
            throw new IllegalArgumentException('pending AI authority is missing, altered, unauthorized or expired; request fresh review')
        Plan plan = requirePlan(conversation.planId)
        Instant start = Instant.parse(pending.rangeStart.toString()), end = Instant.parse(pending.rangeEnd.toString())
        if (!end.isAfter(now) || start != plan.slots*.start.min() || end != plan.slots*.end.max())
            throw new IllegalArgumentException('reviewed planning range is no longer valid')
        List<Task> source = applyTemporaryOverrides(normalizedEligibleTasks(), conversation.overrides)
        if (PlanningInputHash.taskSetHash(source) != PlanningInputHash.taskSetHash(plan.tasks))
            throw new IllegalArgumentException('source tasks changed; request fresh review')
        List<CalendarEvent> events = calendarRead.fetchEvents(start, end)
        def restored = new LlmSchemaValidator().restore(pending.canonicalJson?.toString(),
            pending.correlationId?.toString(), plan, events, now)
        if (!restored.accepted || restored.bundle.contentHash != pending.contentHash ||
            restored.bundle.planningInputHash != pending.planningInputHash ||
            restored.bundle.suggestions*.suggestionId != pending.suggestionIds)
            throw new IllegalArgumentException('pending suggestion or planning input changed; request fresh review')
        AiSuggestionBundle bundle = restored.bundle
        // Validate the complete set before recording any authorizing decision.
        bundle.suggestions.each { TemporaryPlanningOverride o ->
            if (o.rangeStart != start || o.rangeEnd != end || o.expiresAt.isBefore(end))
                throw new IllegalArgumentException('override cannot cover reviewed range')
        }
        if (pending.confirmedActor != actor || !pending.confirmedAt || !pending.successorPlanId)
            throw new IllegalArgumentException('durable exact confirmation intent is required')
        Plan retained = planStore.load(pending.successorPlanId.toString())
        if (retained != null) return retained
        def confirmation = new AiSuggestionConfirmationService(store,
            AiSuggestionConfirmationService.allowlist(integrationConfig.feedback.allowedActors))
        List<String> decisionIds = bundle.suggestions.collect { suggestion ->
            String correlation = 'confirm-' + AiValues.sha256(pending.sourceEventId + '|' + suggestion.suggestionId).substring(0, 32)
            def result = confirmation.decide(bundle, suggestion.suggestionId, plan, events, actor, correlation, 'CONFIRM', now)
            if (result.confirmed) return result.record.decisionId
            if (result.outcome == 'IDEMPOTENT_REPLAY') return result.record.priorDecisionId
            throw new IllegalArgumentException('exact AI decision was refused')
        }
        List<Task> transformed = new ConfirmedOverrideApplier().apply(source, store, decisionIds, bundle, plan, events, start, end, now)
        List<PlanningExplanation> explanations = bundle.suggestions.collect { TemporaryPlanningOverride o ->
            PlanningExplanation.of('AI_CONFIRMED_TEMPORARY_OVERRIDE',
                "Confirmed temporary ${o.overrideType}: ${o.value}", 'task', o.taskIds.join(','),
                [suggestionId: o.suggestionId, rangeStart: start.toString(), rangeEnd: end.toString()])
        }
        propose(transformed, events, start, end, plan.id, conversation.overrides, now, explanations,
            pending.successorPlanId.toString())
    }

    Map confirmAiIntent(ConversationRecord conversation, String actor) {
        Map pending = conversation.pendingConfirmation
        def store = aiDecisionStore()
        if (!integrationConfig.feedbackActors().contains(actor) || pending.schemaVersion != 1 ||
            !store.authenticPending(pending) || pending.planId != conversation.planId ||
            pending.planHash != conversation.planHash || pending.planVersion != conversation.planVersion ||
            pending.channelId != conversation.channelId || pending.threadTs != conversation.threadTs ||
            !Instant.parse(pending.expiresAt.toString()).isAfter(clock.get()) ||
            (pending.confirmedActor != null && pending.confirmedActor != actor))
            throw new IllegalArgumentException('pending AI authority is invalid or expired; request fresh review')
        String confirmedAt = pending.confirmedAt ?: clock.get().toString()
        String successorId = 'ai-preview-' + AiValues.sha256(
            [pending.contentHash, pending.planId, pending.channelId, pending.threadTs, confirmedAt].join('|'))
        store.sealPending(pending + [confirmedActor: actor, confirmedAt: confirmedAt, successorPlanId: successorId])
    }

    private AiSuggestionDecisionStore aiDecisionStore() {
        AiSuggestionDecisionStore.serviceOwned(integrationConfig.decisionsDir)
    }

    /** Startup probe: authenticate and perform bounded Todoist and CalDAV reads without writes. */
    void verifyConnectivity(Instant now = clock.get()) {
        if(plannerConfig.ai.enabled && plannerConfig.ai.provider in ['codex_subscription','grok_build_subscription']) {
            try {
                boolean codex=plannerConfig.ai.provider=='codex_subscription'
                String provider=codex?'codex':'grok'
                SubscriptionProviderAdapter adapter=codex?new OpenAiSiwcAdapter():new XaiDeviceOAuthAdapter()
                String token=new SubscriptionCredentialService(
                    new SubscriptionCredentialStore(plannerConfig.ai.subscription.authRoot,provider),adapter).accessToken()
                token=null
            } catch(Exception failure) {
                String loginFlow=plannerConfig.ai.provider=='codex_subscription'?'browser':'device'
                String provider=plannerConfig.ai.provider=='codex_subscription'?'codex':'grok'
                throw new IllegalStateException(
                    "Subscription authentication is not ready; run ai-auth-login --ai-provider ${provider} --auth-flow ${loginFlow}",
                    failure)
            }
        }
        normalizedEligibleTasks()
        calendarRead.fetchEvents(now, now.plusSeconds(300))
    }

    private static List<Task> applyTemporaryOverrides(List<Task> tasks, Map overrides) {
        Set<String> excluded = overrides.exclude_task_ids instanceof Collection
            ? (overrides.exclude_task_ids as Collection).collect { it.toString() } as Set : [] as Set
        Map priorities = overrides.priority_overrides instanceof Map ? overrides.priority_overrides as Map : [:]
        tasks.findAll { !excluded.contains(it.id) }.collect { Task task ->
            if (!priorities.containsKey(task.id)) return task
            int priority = Integer.parseInt(priorities[task.id].toString())
            if (priority < 1 || priority > 4) throw new IllegalArgumentException("priority override for ${task.id} must be 1..4")
            Task.builder().id(task.id).content(task.content).projectId(task.projectId).projectName(task.projectName)
                .labels(task.labels).priority(priority).deadline(task.deadline).dueTime(task.dueTime)
                .nativeDuration(task.nativeDuration).effectiveDuration(task.effectiveDuration)
                .durationSource(task.durationSource).manual(task.manual).allDayDue(task.allDayDue).build()
        }
    }

    private WeatherReadGateway weatherGateway() {
        if (weatherGatewayFactory != null) return weatherGatewayFactory.call()
        def wc = plannerConfig.weather
        new OpenMeteoWeatherGateway(
            wc.latitude, wc.longitude, wc.timezone ?: plannerConfig.timezone,
            wc.forecastHorizonDays ?: 7,
            integrationConfig.weather.baseUrl.toString(),
            integrationConfig.weather.timeout as java.time.Duration,
            null, null,
            integrationConfig.weather.maxResponseBytes as long)
    }

    /** Apply a stored plan using its configured mode and exact optional approval. */
    ApplicationReceipt apply(String planId, Approval approval = null) {
        Plan plan = requirePlan(planId)
        return applier().apply(plan, approval)
    }

    /** Explicit safe-only entry; protected/frozen/manual items remain withheld. */
    ApplicationReceipt applySafe(String planId) {
        return applier().applySafeChanges(requirePlan(planId))
    }

    List<DeliveryReceipt> deliver(String planId, String kind = null, Instant now = clock.get()) {
        MessagingService service = messagingService(true)
        return kind ? service.deliverKind(planId, kind, now) : service.deliverDue(planId, now)
    }

    /** Parse and persist feedback only. Never applies as a side effect. */
    FeedbackParser.FeedbackResult feedback(String planId, String command, String actor,
                                           String correlationId = null, String messageId = null) {
        MessagingService service = messagingService(false)
        def ctx = new FeedbackParser.FeedbackContext([
            actorId: actor, correlationId: correlationId, messageId: messageId,
            destination: plannerConfig.messaging.destination
        ])
        return service.handleFeedback(planId, command, ctx)
    }

    /** Explicit second step after feedback; exact accepted decision binding is rechecked. */
    MessagingService.ApplyDecisionResult applyDecision(String planId, String decisionId) {
        def decision = decisionStore.load(decisionId)
        if (decision == null) throw new IllegalArgumentException("Decision not found: ${decisionId}")
        return messagingService(false).applyDecision(planId, decision)
    }

    /** AI side service: bounded suggestions/audit only, with no persistence or mutation port. */
    def aiSuggestions(String planId, String type,
                      String correlationId, String feedbackText = null) {
        if (!plannerConfig.ai.enabled) throw new IllegalStateException('AI is disabled; set planner.ai.enabled explicitly')
        Plan plan = requirePlan(planId)
        if (aiSuggestionProvider != null) {
            return aiSuggestionProvider.call(plan, type, correlationId, feedbackText)
        }
        Instant start = plan.slots ? plan.slots*.start.min() : plan.createdAt
        Instant end = plan.slots ? plan.slots*.end.max() : plan.createdAt.plusSeconds(86400)
        List<CalendarEvent> events = calendarRead.fetchEvents(start, end)
        def service = AiAssistanceService.create(plannerConfig, {
            if(aiGatewayFactory!=null)return aiGatewayFactory.call(plannerConfig.ai)
            if(plannerConfig.ai.provider in ['codex_subscription','grok_build_subscription']) {
                boolean codex=plannerConfig.ai.provider=='codex_subscription'
                String provider=codex?'codex':'grok'
                SubscriptionProviderAdapter adapter=codex?new OpenAiSiwcAdapter():new XaiDeviceOAuthAdapter()
                return new SubscriptionResponsesLlmGateway(plannerConfig.ai,
                    new SubscriptionCredentialService(
                        new SubscriptionCredentialStore(plannerConfig.ai.subscription.authRoot,provider),adapter))
            }
            new OpenAiCompatibleLlmGateway(plannerConfig.ai)
            } as Supplier,
            clock).orElseThrow()
        service.suggest(type, correlationId, plan, events, feedbackText)
    }

    String renderPlan(Plan plan) { PlanStore.toJson(plan) }

    static Approval loadApproval(File file) {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("Approval file not found: ${file}")
        def parsed = file.name.toLowerCase(Locale.ROOT).endsWith('.json')
            ? new JsonSlurper().parse(file)
            : new YamlSlurper().parse(file)
        if (!(parsed instanceof Map)) throw new IllegalArgumentException('Approval file must contain an object')
        Approval.fromMap(parsed as Map)
    }

    private List<Task> normalizedEligibleTasks() {
        List<Task> tasks = todoistRead.fetchTasks().collect {
            Task.fromTodoistMap(it, plannerConfig.durationResolver, plannerConfig.manualLabel, plannerConfig.timezone)
        }.findAll { !it.manual }
        if (plannerConfig.schedulingEligibleLabels) {
            tasks = tasks.findAll { Task task ->
                task.labels.any { label -> plannerConfig.schedulingEligibleLabels.any { it.equalsIgnoreCase(label) } }
            }
        }
        tasks
    }

    private Plan loadPrevious(String explicitId) {
        if (explicitId) {
            Plan found = planStore.load(explicitId)
            if (found == null) throw new IllegalArgumentException("Previous plan not found: ${explicitId}")
            return found
        }
        List<Plan> all = planStore.listPlanIds().collect { planStore.load(it) }.findAll { it != null }
        all ? all.max { a, b -> a.createdAt <=> b.createdAt ?: a.id <=> b.id } : null
    }

    private Plan requirePlan(String planId) {
        if (!planId) throw new IllegalArgumentException('plan id is required')
        Plan plan = planStore.load(planId)
        if (plan == null) throw new IllegalArgumentException("Plan not found: ${planId}")
        plan
    }

    private PlanApplier applier() {
        CalendarWriteGateway managed = new ManagedCalendarWriteGateway(calendarWrite, calendarRead, plannerConfig.outputCalendar)
        new PlanApplier(plannerConfig, managed, calendarRead, todoistWrite, todoistRead,
            applicationState, clock, calendarWriteback)
    }

    private MessagingService messagingService(boolean requireDeliveryGateway) {
        MessagingGateway gateway = null
        if (plannerConfig.messaging.enabled && requireDeliveryGateway) {
            if (plannerConfig.messaging.provider != 'slack') {
                throw new IllegalStateException("Messaging provider must be explicitly slack, got ${plannerConfig.messaging.provider}")
            }
            Map opts = [
                // Socket Mode is inbound transport; outbound messages still use chat.postMessage.
                mode         : plannerConfig.messaging.slackMode == 'socket_mode' ? 'chat_api' : plannerConfig.messaging.slackMode,
                destination  : plannerConfig.messaging.destination,
                webhookUrlEnv: plannerConfig.messaging.webhookUrlEnv ?: plannerConfig.messaging.secretEnv,
                botTokenEnv  : plannerConfig.messaging.botTokenEnv ?: plannerConfig.messaging.secretEnv
            ]
            gateway = new SlackMessagingGateway(opts)
        } else if (!plannerConfig.messaging.enabled && requireDeliveryGateway) {
            throw new IllegalStateException('Messaging is disabled; refusing delivery')
        }
        def parser = new FeedbackParser(decisionStore,
            FeedbackParser.allowlist(integrationConfig.feedbackActors()), clock)
        new MessagingService(plannerConfig, planStore, gateway, deliveryLedger,
            decisionStore, parser, applier(), clock)
    }

    @Override
    void close() {
        // JDK HttpClient has no close operation; retained for composition lifecycle symmetry.
    }
}
