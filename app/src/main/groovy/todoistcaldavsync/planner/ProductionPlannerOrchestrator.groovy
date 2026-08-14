package todoistcaldavsync.planner

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.ai.AiAssistanceService
import todoistcaldavsync.planner.ai.OpenAiCompatibleLlmGateway
import todoistcaldavsync.planner.apply.PlanApplier
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.feedback.FeedbackParser
import todoistcaldavsync.planner.messaging.MessagingService
import todoistcaldavsync.planner.policy.EventClassifier
import todoistcaldavsync.planner.report.CapacityReportFormatter
import todoistcaldavsync.planner.report.CapacityReportService
import todoistcaldavsync.planner.scheduling.AvailabilityCalculator
import todoistcaldavsync.planner.scheduling.DeterministicScheduler
import todoistcaldavsync.planner.state.*

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

    ProductionPlannerOrchestrator(File configFile, Supplier<Instant> clock = { Instant.now() }) {
        if (configFile == null || !configFile.isFile()) throw new IllegalArgumentException("Config file not found: ${configFile}")
        Map root = new YamlSlurper().parse(configFile) as Map
        this.plannerConfig = PlannerConfig.fromMap(root)
        this.integrationConfig = ProductionIntegrationConfig.fromMap(root, configFile.absoluteFile.parentFile.toPath())
        this.clock = clock ?: ({ Instant.now() } as Supplier<Instant>)
        this.planStore = new PlanStore(integrationConfig.plansDir)
        this.applicationState = new ApplicationStateStore(integrationConfig.applicationsDir)
        this.decisionStore = new DecisionStore(integrationConfig.decisionsDir)
        this.deliveryLedger = new DeliveryLedger(integrationConfig.deliveriesDir)
        def todoistGateway = new TodoistRestGateway(integrationConfig.todoist)
        def calendarGateway = new CalDavHttpGateway([
            calendars          : integrationConfig.calendars,
            managedCalendarName: plannerConfig.outputCalendar,
            timezone           : plannerConfig.timezone,
            timeout            : integrationConfig.caldav.timeout,
            maxResponseBytes   : integrationConfig.caldav.maxResponseBytes
        ])
        this.todoistRead = todoistGateway
        this.todoistWrite = todoistGateway
        this.calendarRead = calendarGateway
        this.calendarWrite = calendarGateway
    }

    /** Dependency-injected composition seam used by hermetic end-to-end tests/embedders. */
    ProductionPlannerOrchestrator(PlannerConfig plannerConfig,
                                  ProductionIntegrationConfig integrationConfig,
                                  TodoistReadGateway todoistRead,
                                  TodoistWriteGateway todoistWrite,
                                  CalendarReadGateway calendarRead,
                                  CalendarWriteGateway calendarWrite,
                                  Supplier<Instant> clock = { Instant.now() }) {
        if ([plannerConfig, integrationConfig, todoistRead, todoistWrite, calendarRead, calendarWrite].any { it == null }) {
            throw new IllegalArgumentException('planner/integration config and all read/write gateways are required')
        }
        this.plannerConfig = plannerConfig
        this.integrationConfig = integrationConfig
        this.clock = clock ?: ({ Instant.now() } as Supplier<Instant>)
        this.planStore = new PlanStore(integrationConfig.plansDir)
        this.applicationState = new ApplicationStateStore(integrationConfig.applicationsDir)
        this.decisionStore = new DecisionStore(integrationConfig.decisionsDir)
        this.deliveryLedger = new DeliveryLedger(integrationConfig.deliveriesDir)
        this.todoistRead = todoistRead
        this.todoistWrite = todoistWrite
        this.calendarRead = calendarRead
        this.calendarWrite = calendarWrite
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
        Instant now = clock.get()
        List<Task> tasks = normalizedEligibleTasks()
        List<CalendarEvent> events = calendarRead.fetchEvents(rangeStart, rangeEnd)
        List<CalendarEvent> classified = new EventClassifier(plannerConfig).classifyAll(events)
        def availability = new AvailabilityCalculator(plannerConfig).calculate(rangeStart, rangeEnd, classified)
        Plan previous = loadPrevious(previousPlanId ?: integrationConfig.previousPlanId)
        WeatherForecast forecast = null
        if (plannerConfig.weather.enabled) {
            def wc = plannerConfig.weather
            WeatherReadGateway gateway = new OpenMeteoWeatherGateway(
                wc.latitude, wc.longitude, wc.timezone ?: plannerConfig.timezone,
                wc.forecastHorizonDays ?: 7,
                integrationConfig.weather.baseUrl.toString(),
                integrationConfig.weather.timeout as java.time.Duration,
                null, null,
                integrationConfig.weather.maxResponseBytes as long)
            forecast = gateway.fetchForecast(rangeStart, rangeEnd)
        }
        Plan plan = new DeterministicScheduler(plannerConfig).propose(
            tasks, availability.slots, rangeStart, rangeEnd, now, previous, [] as Set, forecast)
        planStore.save(plan)
        return plan
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
        Instant start = plan.slots ? plan.slots*.start.min() : plan.createdAt
        Instant end = plan.slots ? plan.slots*.end.max() : plan.createdAt.plusSeconds(86400)
        List<CalendarEvent> events = calendarRead.fetchEvents(start, end)
        def service = AiAssistanceService.create(plannerConfig,
            { new OpenAiCompatibleLlmGateway(plannerConfig.ai) } as Supplier,
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
        new PlanApplier(plannerConfig, managed, calendarRead, todoistWrite, todoistRead, applicationState, clock)
    }

    private MessagingService messagingService(boolean requireDeliveryGateway) {
        MessagingGateway gateway = null
        if (plannerConfig.messaging.enabled) {
            if (plannerConfig.messaging.provider != 'slack') {
                throw new IllegalStateException("Messaging provider must be explicitly slack, got ${plannerConfig.messaging.provider}")
            }
            Map opts = [
                mode         : plannerConfig.messaging.slackMode,
                destination  : plannerConfig.messaging.destination,
                webhookUrlEnv: plannerConfig.messaging.webhookUrlEnv ?: plannerConfig.messaging.secretEnv,
                botTokenEnv  : plannerConfig.messaging.botTokenEnv ?: plannerConfig.messaging.secretEnv
            ]
            gateway = new SlackMessagingGateway(opts)
        } else if (requireDeliveryGateway) {
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
