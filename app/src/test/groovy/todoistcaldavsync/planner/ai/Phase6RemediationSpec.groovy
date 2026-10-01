package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.state.PlanStoreException

import java.lang.reflect.Modifier
import java.net.http.HttpTimeoutException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class Phase6RemediationSpec extends Specification {
    private static final byte[] SIGNING_KEY=(0..<32).collect{(byte)it} as byte[]
    Instant now=Instant.parse('2026-08-07T12:00:00Z')

    private PlannerConfig config(Map extra=[:]) {
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-17:00']]],
            ai:[enabled:true,provider:'fixture',model:'fixture-v1']+extra])
    }
    private PlannerConfig openAiConfig() {
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-17:00']]],ai:[
            enabled:true,provider:'openai_compatible',model:'gpt-fixture',secret_env:'LLM_KEY',
            endpoint:'https://api.openai.com/v1/chat/completions',allowed_hosts:['api.openai.com']]])
    }
    private Task task(String id='t1') {
        Task.builder().id(id).content('Safe task').projectId('p').projectName('P').labels(['schedule'])
            .priority(2).deadline(now.plusSeconds(10800)).effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
    }
    private Plan plan(List<Task> tasks=[task()]) {
        Plan.builder().id('plan-1').version(1).createdAt(now).mode('preview').tasks(tasks).metrics([all:1]).humanDiff('No changes').build()
    }
    private LlmRequest request(String type,Plan p=plan(),String correlation='corr-1') {
        String proposal=type=='conversational_feedback_interpretation'?Proposal.fromPlan(p).id:null
        new LlmRequest(correlationId:correlation,suggestionType:type,schemaVersion:1,provider:'fixture',model:'fixture-v1',
            planId:p.id,planVersion:p.version,planHash:PlanHash.compute(p),context:[:],allowedTaskIds:p.tasks*.id as Set,
            planningInputHash:PlanningInputHash.compute(p,[]),
            allowedEventIds:['event-1'] as Set,planningRangeStart:type=='temporary_planning_overrides'?now:null,
            planningRangeEnd:type=='temporary_planning_overrides'?now.plusSeconds(7200):null,maxTokens:300,
            expectedProposalId:proposal,allowedFeedbackActions:proposal?LlmSchemaValidator.FEEDBACK_ACTIONS:[])
    }
    private Map root(String type,List rows,String correlation='corr-1') {
        [schemaVersion:1,suggestionType:type,correlationId:correlation,suggestions:rows]
    }
    private Map overrideFixture(Plan p=plan(),List<Map> rows=null) {
        String hash=PlanHash.compute(p)
        List actual=rows ?: [[suggestionId:'override-1',planId:p.id,planVersion:p.version,planHash:hash,planningInputHash:reqHash(p),taskIds:['t1'],
            overrideType:'duration_minutes',value:45,rangeStart:now.toString(),rangeEnd:now.plusSeconds(7200).toString(),
            expiresAt:now.plusSeconds(7200).toString(),confidence:0.8,rationale:'Temporary estimate']]
        LlmRequest req=request('temporary_planning_overrides',p)
        String json=JsonOutput.toJson(root('temporary_planning_overrides',actual))
        def result=new LlmSchemaValidator().validate(req,json,now)
        assert result.accepted
        [request:req,json:json,bundle:result.bundle]
    }
    private AiSuggestionConfirmationService service(AiSuggestionDecisionStore store) {
        new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))
    }

    def "resolver transport gateway and validator exceptions are contained with fixed redacted errors"() {
        given:
        String sensitive='sk-supersecretvalue hooks.slack.com alice@example.com'
        def cfg=openAiConfig()
        def req=new LlmRequest(correlationId:'corr-1',suggestionType:'task_suggestions',provider:cfg.ai.provider,model:cfg.ai.model,
            planId:'plan-1',planVersion:1,planHash:'a'*64,planningInputHash:'b'*64,context:[:],allowedTaskIds:['t1'] as Set,allowedEventIds:[] as Set,maxTokens:100)
        def resolverResult=new OpenAiCompatibleLlmGateway(cfg.ai,({r->assert false} as LlmHttpTransport),
            {name->throw new IllegalStateException(sensitive)}).complete(req)
        def transportResult=new OpenAiCompatibleLlmGateway(cfg.ai,({r->throw new IOException(sensitive)} as LlmHttpTransport),
            {name->'credential'}).complete(req)
        def validator=new LlmSchemaValidator(){
            @Override ValidationResult validate(LlmRequest ignored,String json,Instant clock){throw new IllegalStateException(sensitive)}
        }
        LlmGateway validGateway={r->
            String json=JsonOutput.toJson(root('task_suggestions',[]))
            LlmGatewayResult.success(new LlmResponse(r.correlationId,r.suggestionType,1,json,json.bytes.length))
        } as LlmGateway
        def validatorResult=new AiAssistanceService(config().ai,validGateway,null,validator,{now}).suggest('task_suggestions','corr-1',plan())
        LlmGateway throwing={r->throw new IllegalStateException(sensitive)} as LlmGateway
        def gatewayResult=new AiAssistanceService(config().ai,throwing,null,new LlmSchemaValidator(),{now}).suggest('task_suggestions','corr-1',plan())
        Path persisted=Files.createTempFile('ai-redacted-errors-','.json')
        Files.writeString(persisted,JsonOutput.toJson([resolverResult.error.toString(),transportResult.error.toString(),
            validatorResult.error.toString(),gatewayResult.error.toString(),gatewayResult.audit.toMap()]))

        expect:
        resolverResult.error.errorClass==LlmErrorClass.AUTHENTICATION
        transportResult.error.errorClass==LlmErrorClass.TRANSPORT
        validatorResult.error.errorClass==LlmErrorClass.SCHEMA_REJECTED
        gatewayResult.error.errorClass==LlmErrorClass.TRANSPORT
        [resolverResult.error,transportResult.error,validatorResult.error,gatewayResult.error].every { error ->
            String rendered=error.detail+' '+error.toString()
            !rendered.contains('sk-')&&!rendered.contains('alice@')&&!rendered.contains('hooks.slack')
        }
        !JsonOutput.toJson(gatewayResult.audit.toMap()).contains('sk-')
        !Files.readString(persisted).contains('sk-')&&!Files.readString(persisted).contains('alice@')&&!Files.readString(persisted).contains('hooks.slack')
    }

    def "conversational identity and action come from request and model command injection is impossible"() {
        given:
        Plan p=plan();LlmRequest req=request('conversational_feedback_interpretation',p);String expected=req.expectedProposalId
        Map good=[suggestionId:'feedback-1',proposalId:expected,planId:p.id,planVersion:p.version,planHash:req.planHash,
            action:'APPROVE',reason:'Looks good']
        Map invented=good+[proposalId:'prop-invented',planHash:'b'*64,proposedCommand:"approve prop-invented ${'b'*64}"]

        expect:
        !new LlmSchemaValidator().validate(req,JsonOutput.toJson(root(req.suggestionType,[invented])),now).accepted
        !new LlmSchemaValidator().validate(req,JsonOutput.toJson(root(req.suggestionType,[good+[proposalId:'wrong']])),now).accepted
        !new LlmSchemaValidator().validate(req,JsonOutput.toJson(root(req.suggestionType,[good+[action:'DELETE']])),now).accepted
        !new LlmSchemaValidator().validate(req,JsonOutput.toJson(root(req.suggestionType,[good.findAll{k,v->k!='proposalId'}])),now).accepted

        when:
        def accepted=new LlmSchemaValidator().validate(req,JsonOutput.toJson(root(req.suggestionType,[good])),now)

        then:
        accepted.accepted
        accepted.bundle.suggestions[0].proposedCommand=="approve ${expected} ${req.planHash} Looks good"
    }

    def "unsafe actionable output is rejected while sensitive rationale is deterministically redacted"() {
        given:
        LlmRequest req=request('task_suggestions')
        Map base=[suggestionId:'s1',taskId:'t1',kind:'context',proposedValue:'home',confidence:0.5,rationale:'Safe basis',evidenceIds:['t1']]
        List<String> unsafe=['rm -rf workspace','execute bash now','curl https://evil.invalid','post to webhook',
            'api_key=sk-abcdefghijklmnop','alice@example.com','212-555-0199','<@U12345678>',
            'write config now','tool_call execute',"home\u0000hidden"]

        expect:
        unsafe.every { value ->
            def result=new LlmSchemaValidator().validate(req,JsonOutput.toJson(root('task_suggestions',[base+[proposedValue:value]])),now)
            !result.accepted && result.error.errorClass in [LlmErrorClass.UNSAFE_OUTPUT,LlmErrorClass.SCHEMA_REJECTED]
        }

        when:
        String pii='Contact alice@example.com or 212-555-0199 token=sk-abcdefghijklmnop <@U12345678>'
        def redacted=new LlmSchemaValidator().validate(req,JsonOutput.toJson(root('task_suggestions',[base+[rationale:pii]])),now)

        then:
        redacted.accepted
        redacted.bundle.suggestions[0].rationale.contains('[REDACTED]')
        !redacted.bundle.suggestions[0].rationale.contains('alice@example.com')
        !redacted.bundle.suggestions[0].rationale.contains('555-0199')
        !redacted.bundle.suggestions[0].rationale.contains('sk-')
        !redacted.bundle.suggestions[0].rationale.contains('U12345678')
        !redacted.bundle.toString().contains('alice@example.com')
        and:
        String canonical=canonicalJsonOf(redacted.bundle)
        !canonical.contains('alice@example.com')&&!canonical.contains('555-0199')&&!canonical.contains('sk-')&&!canonical.contains('U12345678')
    }

    def "resource and semantic validators share strict kind-dependent structural contracts"() {
        given:
        Plan p=plan();String hash=PlanHash.compute(p);String proposal=Proposal.fromPlan(p).id
        Map cases=[
            task_suggestions:[request('task_suggestions',p),[suggestionId:'s1',taskId:'t1',kind:'duration',proposedValue:30,confidence:0.5,rationale:'Safe',evidenceIds:['t1']],[proposedValue:'30']],
            event_classification_suggestions:[request('event_classification_suggestions',p),[suggestionId:'e1',eventId:'event-1',suggestedRole:'hard_blocker',confidence:0.5,rationale:'Safe',candidateRulePatch:[name:'Rule']],[candidateRulePatch:[name:'Rule',unknown:true]]],
            temporary_planning_overrides:[request('temporary_planning_overrides',p),[suggestionId:'o1',planId:p.id,planVersion:1,planHash:hash,planningInputHash:reqHash(p),taskIds:['t1'],overrideType:'context_label',value:'home',rangeStart:now.toString(),rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),confidence:0.5,rationale:'Safe'],[value:30]],
            conversational_feedback_interpretation:[request('conversational_feedback_interpretation',p),[suggestionId:'f1',proposalId:proposal,planId:p.id,planVersion:1,planHash:hash,action:'REJECT',reason:'Not suitable'],[proposedCommand:'reject invented']]
        ]

        expect:
        cases.every { type, values ->
            String accepted=JsonOutput.toJson(root(type,[values[1]]))
            String rejected=JsonOutput.toJson(root(type,[values[1]+values[2]]))
            LlmSchemaValidator.resourceAccepts(type,accepted) && new LlmSchemaValidator().validate(values[0] as LlmRequest,accepted,now).accepted &&
                !LlmSchemaValidator.resourceAccepts(type,rejected) && !new LlmSchemaValidator().validate(values[0] as LlmRequest,rejected,now).accepted
        }
    }

    def "resource validator rejects bounds duplicates formats unknown nesting and non-finite JSON"() {
        given:
        Plan p=plan();String hash=PlanHash.compute(p)
        Map taskRow=[suggestionId:'s1',taskId:'t1',kind:'duration',proposedValue:30,confidence:0.5,rationale:'Safe',evidenceIds:['t1']]
        Map overrideRow=[suggestionId:'o1',planId:p.id,planVersion:1,planHash:hash,planningInputHash:reqHash(p),taskIds:['t1'],overrideType:'duration_minutes',value:30,
            rangeStart:now.toString(),rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),confidence:0.5,rationale:'Safe']
        List<Map> invalid=[
            [type:'task_suggestions',request:request('task_suggestions',p),root:root('task_suggestions',[taskRow+[confidence:2]])],
            [type:'task_suggestions',request:request('task_suggestions',p),root:root('task_suggestions',[taskRow+[evidenceIds:['t1','t1']]])],
            [type:'event_classification_suggestions',request:request('event_classification_suggestions',p),root:root('event_classification_suggestions',[[suggestionId:'e1',eventId:'event-1',suggestedRole:'hard_blocker',confidence:0.5,rationale:'Safe',candidateRulePatch:[:]]])],
            [type:'temporary_planning_overrides',request:request('temporary_planning_overrides',p),root:root('temporary_planning_overrides',[overrideRow+[taskIds:['t1','t1']]])],
            [type:'temporary_planning_overrides',request:request('temporary_planning_overrides',p),root:root('temporary_planning_overrides',[overrideRow+[rangeStart:'not-an-instant']])],
            [type:'conversational_feedback_interpretation',request:request('conversational_feedback_interpretation',p),root:root('conversational_feedback_interpretation',[[suggestionId:'f1',proposalId:Proposal.fromPlan(p).id,planId:p.id,planVersion:1,planHash:hash,action:'APPROVE',reason:'Safe',nested:[x:[y:[z:true]]]]])]
        ]

        expect:
        invalid.every { sample ->
            String json=JsonOutput.toJson(sample.root)
            !LlmSchemaValidator.resourceAccepts(sample.type as String,json) &&
                !new LlmSchemaValidator().validate(sample.request as LlmRequest,json,now).accepted
        }
        !LlmSchemaValidator.resourceAccepts('task_suggestions',
            JsonOutput.toJson(root('task_suggestions',[taskRow])).replace('"confidence":0.5','"confidence":NaN'))
        !LlmSchemaValidator.resourceAccepts('task_suggestions',JsonOutput.toJson(root('task_suggestions',(1..101).collect{taskRow+[suggestionId:"s${it}"]})))
    }

    def "validator provenance and exact persisted decision are required for override application"() {
        given:
        Plan p=plan();def fixture=overrideFixture(p);AiSuggestionBundle bundle=fixture.bundle
        Path dir=Files.createTempDirectory('ai-provenance-');def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY)
        def confirmed=service(store).decide(bundle,'override-1',p,'alice','confirm-1','CONFIRM',now)
        def applier=new ConfirmedOverrideApplier()
        def fabricated=new AiSuggestionBundle(bundle.suggestionType,bundle.schemaVersion,bundle.correlationId,bundle.planId,
            bundle.planVersion,bundle.planHash,bundle.planningInputHash,bundle.suggestions,bundle.contentHash,fixture.json as String,fixture.request as LlmRequest,now)
        List<AiSuggestionBundle> altered=[
            fabricated,
            new AiSuggestionBundle(bundle.suggestionType,2,bundle.correlationId,bundle.planId,bundle.planVersion,bundle.planHash,bundle.planningInputHash,bundle.suggestions,bundle.contentHash,fixture.json as String,fixture.request as LlmRequest,now),
            new AiSuggestionBundle('task_suggestions',1,bundle.correlationId,bundle.planId,bundle.planVersion,bundle.planHash,bundle.planningInputHash,bundle.suggestions,bundle.contentHash,fixture.json as String,fixture.request as LlmRequest,now),
            new AiSuggestionBundle(bundle.suggestionType,1,bundle.correlationId,'wrong-plan',bundle.planVersion,bundle.planHash,bundle.planningInputHash,bundle.suggestions,bundle.contentHash,fixture.json as String,fixture.request as LlmRequest,now),
            new AiSuggestionBundle(bundle.suggestionType,1,bundle.correlationId,bundle.planId,bundle.planVersion,bundle.planHash,bundle.planningInputHash,bundle.suggestions,'f'*64,fixture.json as String,fixture.request as LlmRequest,now)]

        expect:
        AiSuggestionBundle.declaredConstructors.every{!Modifier.isPublic(it.modifiers)}
        AiSuggestionDecisionRecord.declaredConstructors.every{!Modifier.isPublic(it.modifiers)}
        altered.every{!LlmSchemaValidator.isAuthentic(it)}
        altered.every{!service(store).decide(it,'override-1',p,'alice',"fake-${altered.indexOf(it)}",'CONFIRM',now).confirmed}

        when: 'an unpersisted or wrong-store decision is presented'
        applier.apply(p.tasks,store,'missing',bundle,p,now,now.plusSeconds(3600),now)
        then: thrown(IllegalArgumentException)

        when:
        applier.apply(p.tasks,new AiSuggestionDecisionStore(Files.createTempDirectory('wrong-store-'),SIGNING_KEY),
            confirmed.record.decisionId,bundle,p,now,now.plusSeconds(3600),now)
        then: thrown(Exception)

        when: 'the exact store decision and validator-issued content are supplied'
        def transformed=applier.apply(p.tasks,store,confirmed.record.decisionId,bundle,p,now,now.plusSeconds(3600),now)
        then:
        transformed[0].effectiveDuration==Duration.ofMinutes(45)
        p.tasks[0].effectiveDuration==Duration.ofMinutes(30)
    }

    def "reject and confirm reserve both correlations and suggestion identities across store instances"() {
        given:
        Plan p=plan();def bundle=overrideFixture(p).bundle;Path rejectedDir=Files.createTempDirectory('ai-reject-replay-')
        def first=service(new AiSuggestionDecisionStore(rejectedDir,SIGNING_KEY)).decide(bundle,'override-1',p,'alice','corr-a','REJECT',now)
        def sameCorrelation=service(new AiSuggestionDecisionStore(rejectedDir,SIGNING_KEY)).decide(bundle,'override-1',p,'alice','corr-a','CONFIRM',now)
        def changedCorrelation=service(new AiSuggestionDecisionStore(rejectedDir,SIGNING_KEY)).decide(bundle,'override-1',p,'alice','corr-b','CONFIRM',now)
        Path confirmedDir=Files.createTempDirectory('ai-confirm-replay-')
        def confirmed=service(new AiSuggestionDecisionStore(confirmedDir,SIGNING_KEY)).decide(bundle,'override-1',p,'alice','corr-a','CONFIRM',now)
        def changedConfirm=service(new AiSuggestionDecisionStore(confirmedDir,SIGNING_KEY)).decide(bundle,'override-1',p,'alice','corr-b','CONFIRM',now)

        expect:
        !first.confirmed && first.outcome=='NEW_REJECTED'
        !sameCorrelation.confirmed && sameCorrelation.outcome=='CONFLICT'
        !changedCorrelation.confirmed && changedCorrelation.outcome=='CONFLICT'
        confirmed.confirmed
        !changedConfirm.confirmed && changedConfirm.outcome=='IDEMPOTENT_REPLAY'

        when: 'a replay record is presented as if it authorized application'
        new ConfirmedOverrideApplier().apply(p.tasks,new AiSuggestionDecisionStore(confirmedDir,SIGNING_KEY),changedConfirm.record.decisionId,
            bundle,p,now,now.plusSeconds(3600),now)
        then:
        thrown(IllegalArgumentException)
    }

    def "distinct suggestions decide independently while correlation conflicts and interrupted updates preserve prior bytes"() {
        given:
        Plan p=plan([task('t1'),task('t2')]);String hash=PlanHash.compute(p)
        List rows=[
            [suggestionId:'o1',planId:p.id,planVersion:1,planHash:hash,planningInputHash:reqHash(p),taskIds:['t1'],overrideType:'duration_minutes',value:40,rangeStart:now.toString(),rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),confidence:0.8,rationale:'First'],
            [suggestionId:'o2',planId:p.id,planVersion:1,planHash:hash,planningInputHash:reqHash(p),taskIds:['t2'],overrideType:'duration_minutes',value:50,rangeStart:now.toString(),rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),confidence:0.8,rationale:'Second']]
        def fixture=overrideFixture(p,rows);Path dir=Files.createTempDirectory('ai-distinct-');def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY)

        when:
        def one=service(store).decide(fixture.bundle,'o1',p,'alice','one','CONFIRM',now)
        def two=service(new AiSuggestionDecisionStore(dir,SIGNING_KEY)).decide(fixture.bundle,'o2',p,'alice','two','CONFIRM',now)

        then:
        one.confirmed && two.confirmed
        store.list().count{it.status=='CONFIRMED_TEMPORARY_OVERRIDE'}==2

        when: 'a used correlation is applied to a distinct suggestion'
        def conflict=service(new AiSuggestionDecisionStore(dir,SIGNING_KEY)).decide(fixture.bundle,'o2',p,'alice','one','CONFIRM',now)
        then:
        !conflict.confirmed && conflict.outcome=='CONFLICT'

        when: 'a later atomic update is interrupted'
        byte[] before=Files.readAllBytes(store.recordsPath())
        def interrupted=new AiSuggestionDecisionStore(dir,SIGNING_KEY,{throw new IOException('simulated crash')})
        service(interrupted).decide(fixture.bundle,'o1',p,'alice','three','REJECT',now)
        then:
        thrown(PlanStoreException)
        Arrays.equals(before,Files.readAllBytes(store.recordsPath()))
        store.list().count{it.isOriginalTerminal()}==2
    }

    def "atomic move unsupported and interrupted writes fail closed without publishing decisions"() {
        given:
        Plan p=plan();def bundle=overrideFixture(p).bundle;Path dir=Files.createTempDirectory('ai-atomic-')
        new AiSuggestionDecisionStore(dir,SIGNING_KEY).provenanceId()
        AtomicFileMover unsupported={Path source,Path target->throw new AtomicMoveNotSupportedException(source.toString(),target.toString(),'fixture')} as AtomicFileMover
        def failing=new AiSuggestionDecisionStore(dir,SIGNING_KEY,null,unsupported)

        when:
        service(failing).decide(bundle,'override-1',p,'alice','atomic','CONFIRM',now)

        then:
        thrown(PlanStoreException)
        !Files.exists(dir.resolve('ai-suggestion-decisions.json'))
        !Files.list(dir).withCloseable{it.anyMatch{x->x.fileName.toString().endsWith('.tmp')}}
        new AiSuggestionDecisionStore(dir,SIGNING_KEY).list().empty
    }

    def "override application requires explicit clock full scope and valid expiry including DST instants"() {
        given:
        Plan p=plan();def fixture=overrideFixture(p);Path dir=Files.createTempDirectory('ai-scope-');def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY)
        def confirmed=service(store).decide(fixture.bundle,'override-1',p,'alice','scope','CONFIRM',now)
        def applier=new ConfirmedOverrideApplier()

        expect:
        applier.apply(p.tasks,store,confirmed.record.decisionId,fixture.bundle,p,now,now.plusSeconds(7200),now.minusSeconds(1))[0].effectiveDuration==Duration.ofMinutes(45)

        when: applier.apply(p.tasks,store,confirmed.record.decisionId,fixture.bundle,p,now,now.plusSeconds(3600),null)
        then: thrown(IllegalArgumentException)
        when: applier.apply(p.tasks,store,confirmed.record.decisionId,fixture.bundle,p,now.minusSeconds(1),now.plusSeconds(3600),now)
        then: thrown(IllegalArgumentException)
        when: applier.apply(p.tasks,store,confirmed.record.decisionId,fixture.bundle,p,now.plusSeconds(1),now.plusSeconds(7201),now)
        then: thrown(IllegalArgumentException)
        when: applier.apply(p.tasks,store,confirmed.record.decisionId,fixture.bundle,p,now.plusSeconds(7201),now.plusSeconds(7300),now)
        then: thrown(IllegalArgumentException)
        when: applier.apply(p.tasks,store,confirmed.record.decisionId,fixture.bundle,p,now,now.plusSeconds(7200),now.plusSeconds(7200))
        then: thrown(IllegalArgumentException)

    }

    def "override scope remains exact across a daylight-saving transition"() {
        given:
        Plan p=plan();String hash=PlanHash.compute(p)
        Instant validationTime=Instant.parse('2026-03-08T06:00:00Z')
        Instant start=Instant.parse('2026-03-08T06:30:00Z');Instant end=Instant.parse('2026-03-08T08:30:00Z')
        def req=new LlmRequest(correlationId:'dst',suggestionType:'temporary_planning_overrides',provider:'fixture',model:'fixture-v1',
            planId:p.id,planVersion:1,planHash:hash,context:[:],allowedTaskIds:['t1'] as Set,allowedEventIds:[] as Set,
            planningInputHash:reqHash(p),
            planningRangeStart:start,planningRangeEnd:end,maxTokens:100)
        def row=[suggestionId:'dst-override',planId:p.id,planVersion:1,planHash:hash,taskIds:['t1'],overrideType:'context_label',
            planningInputHash:reqHash(p),value:'dst-safe',rangeStart:start.toString(),rangeEnd:end.toString(),expiresAt:end.toString(),confidence:0.7,rationale:'Exact instant scope']
        def validated=new LlmSchemaValidator().validate(req,JsonOutput.toJson(root('temporary_planning_overrides',[row],'dst')),validationTime)
        Path dir=Files.createTempDirectory('ai-dst-');def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY)
        def confirmed=service(store).decide(validated.bundle,'dst-override',p,'alice','dst-confirm','CONFIRM',validationTime)

        when:
        def transformed=new ConfirmedOverrideApplier().apply(p.tasks,store,confirmed.record.decisionId,validated.bundle,p,
            start,Instant.parse('2026-03-08T07:30:00Z'),Instant.parse('2026-03-08T06:15:00Z'))

        then:
        transformed[0].labels.contains('dst-safe')
        p.tasks[0].labels==['schedule']
    }

    def "AI config rejects unknown nested and normalized secret aliases or raw values"() {
        when:
        config(bad as Map)

        then:
        def error=thrown(IllegalArgumentException)
        error.message.contains('planner.ai')

        where:
        bad << [[harmless_key:true],[apiKey:'raw-value'],[API_KEY:'raw-value'],[BearerToken:'abc'],
            [provider:[type:'fixture']],[safety:[unknown_flag:true]],[safety:[webhook:'https://hooks.slack.com/services/A/B/C']],
            [secret_env:'sk-abcdefghijklmnop'],[secret_env:'https://hooks.slack.com/services/A/B/C']]
    }

    def "AI config normalizes known case underscore and hyphen aliases without ignoring values"() {
        when:
        def parsed=config(['MAX-ITEMS':7,'MAX_STRING_CHARS':80,'ALLOWED-HOSTS':['api.openai.com']])

        then:
        parsed.ai.maxItems==7
        parsed.ai.maxStringChars==80
        parsed.ai.allowedHosts==['api.openai.com'] as Set
    }

    private static String canonicalJsonOf(AiSuggestionBundle bundle) {
        def field=AiSuggestionBundle.getDeclaredField('originalJson')
        field.accessible=true
        field.get(bundle) as String
    }

    private static String reqHash(Plan plan) { PlanningInputHash.compute(plan,[]) }
}
