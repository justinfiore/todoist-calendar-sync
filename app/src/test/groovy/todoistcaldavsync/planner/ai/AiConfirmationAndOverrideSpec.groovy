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
import todoistcaldavsync.planner.recurrence.LifecycleMarker
import todoistcaldavsync.planner.recurrence.LifecycleMarkerCodec
import todoistcaldavsync.planner.scheduling.DeterministicScheduler
import todoistcaldavsync.planner.state.DecisionStore
import todoistcaldavsync.planner.state.PlanStoreException

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
        overrideSet(plan,[[suggestionId:suggestionId]+changes])
    }
    private AiSuggestionBundle overrideSet(Plan plan,List<Map> suggestions) {
        String hash=PlanHash.compute(plan)
        String inputHash=PlanningInputHash.compute(plan,[])
        List<Map> rows=suggestions.collect { Map changes ->
            [suggestionId:'override-1',planId:plan.id,planVersion:plan.version,planHash:hash,planningInputHash:inputHash,
            taskIds:['t1'],overrideType:'duration_minutes',value:60,rangeStart:now.toString(),
            rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),
            confidence:0.8,rationale:'Temporary confirmed estimate']+changes
        }
        def req=new LlmRequest(correlationId:'llm-corr',suggestionType:'temporary_planning_overrides',schemaVersion:1,
            provider:'fixture',model:'fixture-v1',planId:plan.id,planVersion:plan.version,planHash:hash,
            planningInputHash:inputHash,
            context:[:],allowedTaskIds:plan.tasks.collect{it.id} as Set,allowedEventIds:[] as Set,
            planningRangeStart:now,planningRangeEnd:now.plusSeconds(7200),maxTokens:500)
        def root=[schemaVersion:1,suggestionType:'temporary_planning_overrides',correlationId:'llm-corr',suggestions:rows]
        def validated=new LlmSchemaValidator().validate(req,JsonOutput.toJson(root),now)
        assert validated.accepted
        validated.bundle
    }
    private AiSuggestionConfirmationService confirmations(Path dir,Collection actors=['alice']) {
        new AiSuggestionConfirmationService(new AiSuggestionDecisionStore(dir,SIGNING_KEY),
            AiSuggestionConfirmationService.allowlist(actors))
    }

    def "selected mixed overrides compose on one baseline regardless of order without changing sources or control"() {
        given:
        Plan base=baseline([task('t1'),task('t2',20),task('control',15)],120)
        def bundle=overrideSet(base,[
            [suggestionId:'duration',taskIds:['t1','t2'],value:45],
            [suggestionId:'context',overrideType:'context_label',value:'home'],
            [suggestionId:'unselected',taskIds:['control'],value:90]])
        def store=new AiSuggestionDecisionStore(Files.createTempDirectory('ai-combined-'),SIGNING_KEY)
        def service=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))
        List<String> ids=['duration','context'].collect {
            def result=service.decide(bundle,it,base,'alice',"confirm-${it}",'CONFIRM',now)
            assert result.confirmed
            result.record.decisionId
        }
        String inputBefore=PlanningInputHash.compute(base,[])
        def applier=new ConfirmedOverrideApplier()

        when:
        def transformed=applier.apply(base.tasks,store,ids,bundle,base,[],now,now.plusSeconds(7200),now)
        def reversed=applier.apply(base.tasks,store,ids.reverse(),bundle,base,now,now.plusSeconds(7200),now)
        def revised=new DeterministicScheduler(config()).propose(transformed,[slot(120)],now,now.plusSeconds(7200),now)

        then:
        transformed*.id==base.tasks*.id
        transformed.find{it.id=='t1'}.effectiveDuration==Duration.ofMinutes(45)
        transformed.find{it.id=='t1'}.labels==['schedule','home']
        transformed.find{it.id=='t1'}.durationSource=='ai-confirmed-temporary:duration'
        transformed.find{it.id=='t2'}.effectiveDuration==Duration.ofMinutes(45)
        transformed.find{it.id=='t2'}.labels==['schedule']
        transformed.find{it.id=='control'}.is(base.tasks.find{it.id=='control'})
        transformed*.properties==reversed*.properties
        revised.scheduledBlocks.find{it.taskIds==['t1']}.duration()==Duration.ofMinutes(45)
        revised.scheduledBlocks.find{it.taskIds==['control']}.duration()==Duration.ofMinutes(15)
        PlanningInputHash.compute(base,[])==inputBefore
        base.tasks.collectEntries{[(it.id):it.effectiveDuration]}==
            [t1:Duration.ofMinutes(30),t2:Duration.ofMinutes(20),control:Duration.ofMinutes(15)]
        base.tasks*.labels==[['schedule'],['schedule'],['schedule']]
    }

    def "a selected set with #invalid is rejected wholly rather than applying its valid duration"() {
        given:
        Plan base=baseline()
        def bundle=overrideSet(base,[
            [suggestionId:'valid',value:45],
            [suggestionId:'context',overrideType:'context_label',value:'home',
             rangeEnd:now.plusSeconds(1800).toString(),expiresAt:now.plusSeconds(1800).toString()]])
        def store=new AiSuggestionDecisionStore(Files.createTempDirectory('ai-set-guards-'),SIGNING_KEY)
        def service=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))
        String valid=service.decide(bundle,'valid',base,'alice','valid-confirm','CONFIRM',now).record.decisionId
        String context=service.decide(bundle,'context',base,'alice','context-confirm',action,now).record.decisionId
        List<String> selected=invalid=='unknown decision' ? [valid,'missing-decision'] :
            invalid=='duplicate decision' ? [valid,valid] : [valid,context]
        String before=PlanningInputHash.compute(base,[])
        List<Task> output=null

        when:
        output=new ConfirmedOverrideApplier().apply(base.tasks,store,selected,bundle,base,
            now,now.plusSeconds(3600),now.plusSeconds(elapsed))

        then:
        thrown(IllegalArgumentException)
        output==null
        PlanningInputHash.compute(base,[])==before
        base.tasks*.effectiveDuration==[Duration.ofMinutes(30),Duration.ofMinutes(30)]
        base.tasks*.labels==[['schedule'],['schedule']]

        where:
        invalid                 | action    | elapsed
        'unknown decision'      | 'CONFIRM' | 0
        'duplicate decision'    | 'CONFIRM' | 0
        'rejected decision'     | 'REJECT'  | 0
        'narrow context range'  | 'CONFIRM' | 0
        'expired context'       | 'CONFIRM' | 1800
    }

    def "both copy paths preserve asymmetric native recurrence deadline lifecycle and manual metadata"() {
        given:
        def codec=new LifecycleMarkerCodec()
        List<Task> sources=(1..2).collect { int n ->
            String id="t${n}"
            def marker=new LifecycleMarker(taskId:id,completedCount:n+3,deadlineMode:n==1?'managed':'preserve',
                deadlineSource:'initial_user_due',deadlineDate:"2026-08-${10+n}",
                pendingLegacySourceDate:n==1?'2026-08-09':null,lastVerifiedDue:"2026-08-0${n+5}",
                recurrenceFingerprint:"fixture-${n}",lastPlannerDue:"2026-08-0${n+6}",
                markerGeneration:n+7,lastCommandId:"command-${n}")
            Task.fromTodoistMap([id:id,content:"Distinct content ${n}",project_id:"project-${n}",
                project_name:"Project ${n}",labels:(n==1 ? ['manual','schedule'] : ['schedule','HOME']),priority:n+1,
                due:[date:n==1?'2026-08-08':'2026-08-09T10:15:00',string:n==1?'every day':'every week',
                     is_recurring:true,lang:n==1?'en':'de',timezone:n==1?null:'Europe/Berlin'],
                deadline:[date:"2026-08-${10+n}"],duration:[amount:n*13,unit:'minute'],
                description:codec.merge("Human description ${n}",marker),completed_count:n+3,
                added_at:"2026-07-0${n}T09:00:00Z",updated_at:"2026-08-0${n}T11:00:00Z"],
                new Task.DurationResolver(30,[:]),'manual',ZoneId.of('UTC'))
        }
        // The scheduler filters manual tasks; bind this copy contract to the full original input.
        Plan base=Plan.builder().id('metadata-plan').createdAt(now).tasks(sources).slots([slot(120)]).build()
        def bundle=overrideSet(base,[[suggestionId:'duration',value:45],
            [suggestionId:'context',taskIds:['t2'],overrideType:'context_label',value:'office']])
        def store=new AiSuggestionDecisionStore(Files.createTempDirectory('ai-metadata-'),SIGNING_KEY)
        def service=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))
        List<String> ids=['duration','context'].collect {
            service.decide(bundle,it,base,'alice',"metadata-${it}",'CONFIRM',now).record.decisionId
        }
        def snapshots=sources.collect{new LinkedHashMap(it.properties)}

        when:
        def copies=new ConfirmedOverrideApplier().apply(base.tasks,store,ids,bundle,base,now,now.plusSeconds(3600),now)
        def single=new ConfirmedOverrideApplier().apply(base.tasks,store,ids[0],bundle,base,now,now.plusSeconds(3600),now)

        then: 'every public Task field except the confirmed duration/provenance is preserved'
        copies[0].properties==snapshots[0]+[effectiveDuration:Duration.ofMinutes(45),durationSource:'ai-confirmed-temporary:duration']
        copies[1].properties==snapshots[1]+[labels:['schedule','HOME','office']]
        !copies[0].is(sources[0])
        !copies[1].is(sources[1])
        copies*.todoistDue*.snapshotMap()==sources*.todoistDue*.snapshotMap()
        copies*.lifecycleMarker*.canonicalMap()==sources*.lifecycleMarker*.canonicalMap()
        copies*.manual==[true,false]
        copies*.allDayDue==[true,false]
        copies*.createdAt.every{it!=null}
        copies*.updatedAt.every{it!=null}
        single[0].properties==copies[0].properties
        single[1].is(sources[1])
        sources*.properties==snapshots
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
