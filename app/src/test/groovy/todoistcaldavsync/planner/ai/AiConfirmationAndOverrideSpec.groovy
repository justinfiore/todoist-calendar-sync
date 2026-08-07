package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.feedback.FeedbackParser
import todoistcaldavsync.planner.scheduling.DeterministicScheduler
import todoistcaldavsync.planner.state.DecisionStore
import todoistcaldavsync.planner.state.PlanStoreException

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class AiConfirmationAndOverrideSpec extends Specification {
    private static final byte[] SIGNING_KEY=(0..<32).collect{(byte)it} as byte[]
    Instant now=Instant.parse('2026-08-07T12:00:00Z')

    private PlannerConfig config() {
        PlannerConfig.fromMap(planner:[mode:'preview',timezone:'UTC',
            availability:[working_windows:[weekday:['09:00-17:00']]],
            batching:[enabled:false],stability:[minimum_buffer_between_blocks_minutes:0],
            ai:[enabled:true,provider:'fixture',model:'fixture-v1']])
    }
    private Task task(String id,int minutes=30,Instant deadline=now.plusSeconds(7200)) {
        Task.builder().id(id).content(id).projectId('p').projectName('P').labels(['schedule'])
            .priority(2).deadline(deadline).effectiveDuration(Duration.ofMinutes(minutes)).durationSource('test').build()
    }
    private TimeSlot slot(int minutes=60) { TimeSlot.builder().start(now).end(now.plusSeconds(minutes*60L)).windowName('work').build() }
    private Plan baseline(List<Task> tasks=[task('t1'),task('t2')],int slotMinutes=60) {
        new DeterministicScheduler(config()).propose(tasks,[slot(slotMinutes)],now,now.plusSeconds(slotMinutes*60L),now)
    }
    private AiSuggestionBundle overrideBundle(Plan plan,String suggestionId='override-1',Map changes=[:]) {
        String hash=PlanHash.compute(plan)
        String inputHash=PlanningInputHash.compute(plan,[])
        Map row=[suggestionId:suggestionId,planId:plan.id,planVersion:plan.version,planHash:hash,planningInputHash:inputHash,
            taskIds:['t1'],overrideType:'duration_minutes',value:60,rangeStart:now.toString(),
            rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),
            confidence:0.8,rationale:'Temporary confirmed estimate']+changes
        def req=new LlmRequest(correlationId:'llm-corr',suggestionType:'temporary_planning_overrides',schemaVersion:1,
            provider:'fixture',model:'fixture-v1',planId:plan.id,planVersion:plan.version,planHash:hash,
            planningInputHash:inputHash,
            context:[:],allowedTaskIds:plan.tasks.collect{it.id} as Set,allowedEventIds:[] as Set,
            planningRangeStart:now,planningRangeEnd:now.plusSeconds(7200),maxTokens:500)
        def root=[schemaVersion:1,suggestionType:'temporary_planning_overrides',correlationId:'llm-corr',suggestions:[row]]
        def validated=new LlmSchemaValidator().validate(req,JsonOutput.toJson(root),now)
        assert validated.accepted
        validated.bundle
    }
    private AiSuggestionConfirmationService confirmations(Path dir,Collection actors=['alice']) {
        new AiSuggestionConfirmationService(new AiSuggestionDecisionStore(dir,SIGNING_KEY),
            AiSuggestionConfirmationService.allowlist(actors))
    }

    def "only an exact new authorized confirmation produces immutable override and deterministic schedule effect"() {
        given:
        Plan base=baseline(); def bundle=overrideBundle(base); Path dir=Files.createTempDirectory('ai-confirm-')
        def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY);def service=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice'])); def beforeHash=PlanHash.compute(base)

        when: 'the model suggestion exists but has not been confirmed'
        def unchanged=new DeterministicScheduler(config()).propose(base.tasks,[slot()],now,now.plusSeconds(3600),now)
        def decision=service.decide(bundle,'override-1',base,'alice','confirm-1','CONFIRM',now)
        def transformed=new ConfirmedOverrideApplier().apply(base.tasks,store,decision.record.decisionId,bundle,base,now,now.plusSeconds(3600),now)
        def changed=new DeterministicScheduler(config()).propose(transformed,[slot()],now,now.plusSeconds(3600),now)

        then:
        unchanged.scheduledBlocks.size()==base.scheduledBlocks.size()
        decision.confirmed
        decision.record.status=='CONFIRMED_TEMPORARY_OVERRIDE'
        decision.record.decisionId
        base.tasks.find{it.id=='t1'}.effectiveDuration==Duration.ofMinutes(30)
        PlanHash.compute(base)==beforeHash
        transformed.find{it.id=='t1'}.effectiveDuration==Duration.ofMinutes(60)
        changed.scheduledBlocks.size()==1
        changed.unscheduled*.task*.id==['t2']
    }

    def "confirmed duration override cannot bypass an existing task deadline"() {
        given:
        Plan base=baseline([task('t1',30,now.plusSeconds(1800))],60)
        def bundle=overrideBundle(base);def store=new AiSuggestionDecisionStore(Files.createTempDirectory('ai-deadline-'),SIGNING_KEY)
        def result=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))
            .decide(bundle,'override-1',base,'alice','deadline-confirm','CONFIRM',now)
        def transformed=new ConfirmedOverrideApplier().apply(base.tasks,store,result.record.decisionId,bundle,base,now,now.plusSeconds(3600),now)

        when:
        def plan=new DeterministicScheduler(config()).propose(transformed,[slot()],now,now.plusSeconds(3600),now)

        then:
        plan.scheduledBlocks.empty
        plan.unscheduled*.task*.id==['t1']
    }

    def "stale wrong expired unauthorized replay and conflict never produce another applicable override"() {
        given:
        Plan base=baseline(); def bundle=overrideBundle(base); Path dir=Files.createTempDirectory('ai-guards-'); def service=confirmations(dir)
        def first=service.decide(bundle,'override-1',base,'alice','same-correlation','CONFIRM',now)
        Plan stale=Plan.builder().id(base.id).version(2).createdAt(base.createdAt).mode(base.mode).tasks(base.tasks).build()

        expect:
        first.confirmed && first.record!=null
        !service.decide(bundle,'override-1',base,'alice','same-correlation','CONFIRM',now).confirmed
        !service.decide(bundle,'override-1',base,'mallory','unauthorized','CONFIRM',now).confirmed
        !service.decide(bundle,'missing',base,'alice','wrong-id','CONFIRM',now).confirmed
        !service.decide(bundle,'override-1',stale,'alice','stale','CONFIRM',now).confirmed
        !service.decide(bundle,'override-1',base,'alice','expired','CONFIRM',now.plusSeconds(7201)).confirmed
        !service.decide(bundle,'override-1',base,'alice','same-correlation','REJECT',now).confirmed
        new AiSuggestionDecisionStore(dir,SIGNING_KEY).list().any { it.status=='REJECTED_REPLAY_CONFLICT' }
    }

    def "persistent policy suggestion records auditable intent and never mutates planner config"() {
        given:
        Plan plan=baseline(); String hash=PlanHash.compute(plan); def cfg=config()
        def req=new LlmRequest(correlationId:'c',suggestionType:'task_suggestions',schemaVersion:1,provider:'fixture',model:'fixture-v1',
            planId:plan.id,planVersion:1,planHash:hash,planningInputHash:PlanningInputHash.compute(plan,[]),context:[:],allowedTaskIds:['t1','t2'] as Set,allowedEventIds:[] as Set,maxTokens:100)
        def json=JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'c',suggestions:[
            [suggestionId:'policy-1',taskId:'t1',kind:'context',proposedValue:'home',confidence:0.9,rationale:'A possible enduring label',evidenceIds:['t1']]
        ]])
        def bundle=new LlmSchemaValidator().validate(req,json,now).bundle
        def before=[cfg.defaultDurationMinutes,cfg.taskContexts,cfg.eventRules,cfg.ai.allowedSuggestionTypes]

        when:
        def result=confirmations(Files.createTempDirectory('ai-policy-')).decide(bundle,'policy-1',plan,'alice','policy-confirm','CONFIRM',now)

        then:
        result.confirmed
        result.record.status=='CONFIRMED_POLICY_SUGGESTION'
        result.structuredCommand==null
        [cfg.defaultDurationMinutes,cfg.taskContexts,cfg.eventRules,cfg.ai.allowedSuggestionTypes]==before
    }

    def "conversational interpretation is two-step and only exact confirmed command enters Phase 5 gate"() {
        given:
        Plan plan=baseline(); String hash=PlanHash.compute(plan); String proposal=Proposal.fromPlan(plan).id
        def req=new LlmRequest(correlationId:'nl-corr',suggestionType:'conversational_feedback_interpretation',schemaVersion:1,
            provider:'fixture',model:'fixture-v1',planId:plan.id,planVersion:1,planHash:hash,context:[feedback:'looks good'],
            planningInputHash:PlanningInputHash.compute(plan,[]),
            allowedTaskIds:plan.tasks.collect{it.id} as Set,allowedEventIds:[] as Set,maxTokens:200,
            expectedProposalId:proposal,allowedFeedbackActions:LlmSchemaValidator.FEEDBACK_ACTIONS)
        String reason='The natural language appears to approve'
        String command="approve ${proposal} ${hash} The natural language appears to decision"
        String json=JsonOutput.toJson([schemaVersion:1,suggestionType:'conversational_feedback_interpretation',correlationId:'nl-corr',suggestions:[
            [suggestionId:'feedback-1',proposalId:proposal,planId:plan.id,planVersion:1,planHash:hash,
             action:'APPROVE',reason:reason]]])
        def bundle=new LlmSchemaValidator().validate(req,json,now).bundle
        Path root=Files.createTempDirectory('ai-feedback-'); def phase5=new DecisionStore(root.resolve('phase5'))
        def service=confirmations(root.resolve('ai'))

        expect: 'model output alone is inert'
        phase5.listIds().empty
        service.confirmInterpretation(bundle,'feedback-1',plan,'mallory','confirm-nl',now).structuredCommand==null
        phase5.listIds().empty

        when: 'authorized user confirms, then caller explicitly invokes the existing parser'
        def confirmed=service.confirmInterpretation(bundle,'feedback-1',plan,'alice','confirm-nl',now)

        then:
        confirmed.confirmed
        confirmed.structuredCommand==command
        phase5.listIds().empty

        when:
        def parser=new FeedbackParser(phase5,FeedbackParser.allowlist(['alice']),{now})
        def parsed=parser.parseAndRecord(confirmed.structuredCommand,
            new FeedbackParser.FeedbackContext(actorId:'alice',correlationId:'phase5-explicit',plan:plan))

        then:
        parsed.accepted
        parsed.decision.action=='APPROVE'
        phase5.listIds().size()==1
        AiSuggestionConfirmationService.declaredFields.every { !it.type.name.contains('FeedbackParser') && !it.type.name.contains('PlanApplier') }
        AiAssistanceService.declaredFields.every { !it.type.name.contains('DecisionStore') && !it.type.name.contains('PlanStore') && !it.type.name.contains('WriteGateway') }
    }

    def "decision store classifies concurrent replay atomically"() {
        given:
        Plan plan=baseline(); def bundle=overrideBundle(plan); Path dir=Files.createTempDirectory('ai-concurrent-')
        def pool=Executors.newFixedThreadPool(6)

        when:
        def futures=(1..12).collect { pool.submit({ confirmations(dir).decide(bundle,'override-1',plan,'alice','one-correlation','CONFIRM',now) } as Callable) }
        def results=futures.collect{it.get()}; pool.shutdown()
        def records=new AiSuggestionDecisionStore(dir,SIGNING_KEY).list()

        then:
        results.count{it.confirmed}==1
        records.count{it.status=='CONFIRMED_TEMPORARY_OVERRIDE'}==1
        records.count{it.status=='IDEMPOTENT_REPLAY'}==11
    }

    def "decision persistence is fixed-path atomic corruption-detecting and interruption-safe"() {
        given:
        Plan plan=baseline(); def traversalBundle=overrideBundle(plan,'outside-safe-id')
        Path parent=Files.createTempDirectory('ai-store-parent-'); Path dir=parent.resolve('store')
        def result=confirmations(dir).decide(traversalBundle,'outside-safe-id',plan,'alice','traversal','CONFIRM',now)

        expect:
        result.confirmed
        Files.exists(dir.resolve('ai-suggestion-decisions.json'))
        !Files.exists(parent.resolve('outside'))
        new AiSuggestionDecisionStore(dir,SIGNING_KEY).list().size()==1

        when: 'corruption is surfaced'
        Files.writeString(dir.resolve('ai-suggestion-decisions.json'),'{bad')
        new AiSuggestionDecisionStore(dir,SIGNING_KEY).list()

        then:
        thrown(PlanStoreException)

        when: 'an interruption before atomic move leaves no committed file'
        Path interrupted=parent.resolve('interrupted')
        def failingStore=new AiSuggestionDecisionStore(interrupted,SIGNING_KEY,{throw new IOException('simulated interruption')})
        def service=new AiSuggestionConfirmationService(failingStore,AiSuggestionConfirmationService.allowlist(['alice']))
        service.decide(overrideBundle(plan), 'override-1', plan, 'alice', 'interrupt', 'CONFIRM', now)

        then:
        thrown(PlanStoreException)
        !Files.exists(interrupted.resolve('ai-suggestion-decisions.json'))
    }
}
