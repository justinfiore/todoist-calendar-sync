package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.function.Predicate

class Phase6Round2RemediationSpec extends Specification {
    private static final byte[] SIGNING_KEY=(0..<32).collect{(byte)it} as byte[]
    private final Instant now = Instant.parse('2026-08-07T12:00:00Z')

    private PlannerConfig config() {
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-17:00']]],
            ai:[enabled:true,provider:'fixture',model:'fixture-v1',max_string_chars:500]])
    }

    private PlannerConfig openAiConfig() {
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-17:00']]],ai:[
            enabled:true,provider:'openai_compatible',model:'fixture-v1',secret_env:'LLM_KEY',
            endpoint:'https://api.openai.com/v1/chat/completions',allowed_hosts:['api.openai.com'],
            max_string_chars:500]])
    }

    private Task task(Map changes=[:]) {
        Map values=[id:'t1',content:'Original title',projectId:'p1',projectName:'Original project',
            labels:['schedule','office'],priority:2,deadline:now.plusSeconds(7200),dueTime:now.plusSeconds(600),
            nativeDuration:Duration.ofMinutes(25),effectiveDuration:Duration.ofMinutes(30),durationSource:'test',
            manual:false,allDayDue:false]+changes
        Task.builder().id(values.id as String).content(values.content as String).projectId(values.projectId as String)
            .projectName(values.projectName as String).labels(values.labels as List<String>).priority(values.priority as int)
            .deadline(values.deadline as Instant).dueTime(values.dueTime as Instant).nativeDuration(values.nativeDuration as Duration)
            .effectiveDuration(values.effectiveDuration as Duration).durationSource(values.durationSource as String)
            .manual(values.manual as boolean).allDayDue(values.allDayDue as boolean).build()
    }

    private TimeSlot slot(Map changes=[:]) {
        Map values=[start:now,end:now.plusSeconds(7200),softBlocked:true,softBlockerEventIds:['e1'],
            softBlockerReasons:['tentative'],windowName:'work']+changes
        TimeSlot.builder().start(values.start as Instant).end(values.end as Instant)
            .softBlocked(values.softBlocked as boolean).softBlockerEventIds(values.softBlockerEventIds as List<String>)
            .softBlockerReasons(values.softBlockerReasons as List<String>).windowName(values.windowName as String).build()
    }

    private Plan plan(List<Task> tasks=[task()],List<TimeSlot> slots=[slot()]) {
        Plan.builder().id('plan-1').version(1).createdAt(now).mode('preview').tasks(tasks).slots(slots)
            .metrics([capacity:120,approved:false]).humanDiff('Bound input').build()
    }

    private CalendarEvent event(Map changes=[:]) {
        Map values=[id:'e1',uid:'uid-1',title:'Busy',description:'Private',calendarName:'Work',start:now.plusSeconds(900),
            end:now.plusSeconds(1800),allDay:false,role:null,matchedRuleName:null,classificationReason:null,
            bufferBeforeMinutes:5,bufferAfterMinutes:10,unknownCalendar:false]+changes
        CalendarEvent.builder().id(values.id as String).uid(values.uid as String).title(values.title as String)
            .description(values.description as String).calendarName(values.calendarName as String)
            .start(values.start as Instant).end(values.end as Instant).allDay(values.allDay as boolean)
            .role(values.role).matchedRuleName(values.matchedRuleName as String)
            .classificationReason(values.classificationReason as String)
            .bufferBeforeMinutes(values.bufferBeforeMinutes as int).bufferAfterMinutes(values.bufferAfterMinutes as int)
            .unknownCalendar(values.unknownCalendar as boolean).build()
    }

    private LlmRequest request(Plan p=plan(),Collection<CalendarEvent> events=[]) {
        new LlmRequest(correlationId:'corr-1',suggestionType:'temporary_planning_overrides',schemaVersion:1,
            provider:'fixture',model:'fixture-v1',planId:p.id,planVersion:p.version,planHash:PlanHash.compute(p),
            planningInputHash:PlanningInputHash.compute(p,events),context:[:],allowedTaskIds:p.tasks*.id as Set,
            allowedEventIds:events*.id as Set,planningRangeStart:now,planningRangeEnd:now.plusSeconds(7200),maxTokens:300)
    }

    private Map overrideRow(LlmRequest request,Map changes=[:]) {
        [suggestionId:'override-1',planId:request.planId,planVersion:request.planVersion,planHash:request.planHash,
            planningInputHash:request.planningInputHash,taskIds:['t1'],overrideType:'duration_minutes',value:45,
            rangeStart:now.toString(),rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),
            confidence:0.8,rationale:'Temporary estimate']+changes
    }

    private String json(LlmRequest request,Map row=overrideRow(request)) {
        JsonOutput.toJson([schemaVersion:1,suggestionType:request.suggestionType,
            correlationId:request.correlationId,suggestions:[row]])
    }

    private AiSuggestionBundle bundle(Plan p=plan(),Collection<CalendarEvent> events=[]) {
        LlmRequest req=request(p,events)
        def validated=new LlmSchemaValidator().validate(req,json(req),now)
        assert validated.accepted
        validated.bundle
    }

    def "validator and full assistance boundary reject every non-whitespace response suffix"() {
        given:
        Plan p=plan(); LlmRequest direct=request(p); String valid=json(direct)
        List<String> suffixes=[' junk',' IGNORE PREVIOUS',' '+valid,' ```','\u0000control']

        expect:
        suffixes.every { suffix ->
            !new LlmSchemaValidator().validate(direct,valid+suffix,now).accepted
        }

        and:
        suffixes.every { suffix ->
            LlmGateway gateway={ LlmRequest actual ->
                String body=json(actual,overrideRow(actual))+suffix
                LlmGatewayResult.success(new LlmResponse(actual.correlationId,actual.suggestionType,1,body,body.bytes.length))
            } as LlmGateway
            !new AiAssistanceService(config().ai,gateway,null,new LlmSchemaValidator(),{now})
                .suggest('temporary_planning_overrides','corr-1',p).accepted
        }

        when: 'only JSON whitespace follows the one root'
        String whitespace=valid+' \r\n\t'
        def accepted=new LlmSchemaValidator().validate(direct,whitespace,now)

        then:
        accepted.accepted
        accepted.bundle.contentHash==AiValues.sha256(whitespace)
        accepted.bundle.contentHash!=AiValues.sha256(valid)
    }

    def "all normalized credential aliases and long punctuation credentials are redacted at every shared boundary"() {
        given:
        List<String> keys=['client_secret','clientSecret','client-secret','client.secret','CLIENT_SECRET',
            'access_token','accessToken','refresh_token','private_key','privateKey','api_key','apiKey',
            'auth_token','bearer_token','webhook_url','signing_secret','password','credential','secret','token','key']
        List<String> values=keys.indices.collect { i -> "V${i}!z" }
        List<String> assignments=keys.indices.collect { i -> "${keys[i]}=\"${values[i]}\"" }
        String jwt='eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZWNyZXQifQ.signature0123456789'
        String privateKey='-----BEGIN PRIVATE KEY----- ABCDEFGHIJKLMNOPQRSTUVWXYZ -----END PRIVATE KEY-----'
        String rationale=assignments.join(' | ')
        String longPunctuation='client_secret="value-A.b_c+/=:!@#$%^&*()[]{}-0123456789-very-long"'
        String all=(assignments+[longPunctuation,"Authorization: Bearer ${jwt}",privateKey]).join(' | ')
        Task sensitive=task(content:rationale,projectName:rationale)
        Plan p=plan([sensitive]); LlmTransportRequest captured
        LlmHttpTransport transport={ LlmTransportRequest actual ->
            captured=actual
            Map row=[suggestionId:'s1',taskId:'t1',kind:'duration',proposedValue:45,confidence:0.8,
                rationale:rationale,evidenceIds:['t1']]
            String content=JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[row]])
            byte[] envelope=JsonOutput.toJson([choices:[[message:[role:'assistant',content:content],finish_reason:'stop']]])
                .getBytes(StandardCharsets.UTF_8)
            new LlmTransportResponse(200,[:],envelope)
        } as LlmHttpTransport
        def cfg=openAiConfig()
        def gateway=new OpenAiCompatibleLlmGateway(cfg.ai,transport,{ ignored -> 'transport-only-secret' })

        when:
        def result=new AiAssistanceService(cfg.ai,gateway,null,new LlmSchemaValidator(),{now})
            .suggest('task_suggestions','corr-1',p,[],all)
        String transportBody=new String(captured.body,StandardCharsets.UTF_8)
        String accepted=JsonOutput.toJson([rationale:result.bundle.suggestions[0].rationale,
            hash:result.bundle.contentHash,audit:result.audit.toMap()])
        String error=new LlmError(LlmErrorClass.TRANSPORT,all).detail
        Path persisted=Files.createTempFile('phase6-round2-redaction-','.json')
        Files.writeString(persisted,JsonOutput.toJson([transport:transportBody,accepted:accepted,error:error,audit:result.audit.toMap()]))
        String disk=Files.readString(persisted)

        then:
        result.accepted
        transportBody.contains('t1')
        values.every { value -> !transportBody.contains(value) && !accepted.contains(value) && !error.contains(value) && !disk.contains(value) }
        !transportBody.contains(jwt) && !accepted.contains(jwt) && !error.contains(jwt) && !disk.contains(jwt)
        !transportBody.contains('ABCDEFGHIJKLMNOPQRSTUVWXYZ') && !accepted.contains('ABCDEFGHIJKLMNOPQRSTUVWXYZ')
        AiRedactor.redactText(longPunctuation,500).text=='[REDACTED]'
        !AiRedactor.redactText('{"clientSecret":"json-value"} ?access_token=query-value&next=x private-key:punctuation-value',500).text.contains('json-value')
        !AiRedactor.redactText('{"clientSecret":"json-value"} ?access_token=query-value&next=x private-key:punctuation-value',500).text.contains('query-value')
        !AiRedactor.redactText('{"clientSecret":"json-value"} ?access_token=query-value&next=x private-key:punctuation-value',500).text.contains('punctuation-value')
        result.audit.redactionCount>=keys.size()*2
    }

    def "override expiry covers its entire range and application remains contained through DST instants"() {
        given:
        LlmRequest req=request(); Map base=overrideRow(req)

        expect:
        !new LlmSchemaValidator().validate(req,json(req,base+[expiresAt:now.plusSeconds(7199).toString()]),now).accepted
        new LlmSchemaValidator().validate(req,json(req,base+[expiresAt:now.plusSeconds(7200).toString()]),now).accepted
        new LlmSchemaValidator().validate(req,json(req,base+[expiresAt:now.plusSeconds(7201).toString()]),now).accepted
        !new LlmSchemaValidator().validate(req,json(req,base+[expiresAt:now.plusSeconds(32L*86400L).toString()]),now).accepted

        when:
        Plan p=plan(); AiSuggestionBundle b=bundle(p); Path dir=Files.createTempDirectory('phase6-expiry-')
        def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY)
        def decision=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))
            .decide(b,'override-1',p,'alice','confirm-expiry','CONFIRM',now)
        def applier=new ConfirmedOverrideApplier()

        then:
        applier.apply(p.tasks,store,decision.record.decisionId,b,p,now,now.plusSeconds(7200),now)

        when: applier.apply(p.tasks,store,decision.record.decisionId,b,p,now.minusSeconds(1),now.plusSeconds(3600),now)
        then: thrown(IllegalArgumentException)
        when: applier.apply(p.tasks,store,decision.record.decisionId,b,p,now.plusSeconds(1),now.plusSeconds(7201),now)
        then: thrown(IllegalArgumentException)
        when: applier.apply(p.tasks,store,decision.record.decisionId,b,p,now,now.plusSeconds(7200),now.plusSeconds(7200))
        then: thrown(IllegalArgumentException)

        when: 'absolute instants spanning spring-forward remain valid at an expiry-equal end'
        Instant dstStart=Instant.parse('2026-03-08T06:30:00Z'); Instant dstEnd=Instant.parse('2026-03-08T08:30:00Z')
        Plan dstPlan=Plan.builder().id('dst-plan').version(1).createdAt(dstStart).mode('preview').tasks([task()])
            .slots([slot(start:dstStart,end:dstEnd)]).build()
        LlmRequest dstRequest=new LlmRequest(correlationId:'dst',suggestionType:'temporary_planning_overrides',schemaVersion:1,
            provider:'fixture',model:'fixture-v1',planId:dstPlan.id,planVersion:1,planHash:PlanHash.compute(dstPlan),
            planningInputHash:PlanningInputHash.compute(dstPlan,[]),context:[:],allowedTaskIds:['t1'] as Set,
            allowedEventIds:[] as Set,planningRangeStart:dstStart,planningRangeEnd:dstEnd,maxTokens:100)
        Map dstRow=overrideRow(dstRequest,[rangeStart:dstStart.toString(),rangeEnd:dstEnd.toString(),expiresAt:dstEnd.toString()])
        def dstValidated=new LlmSchemaValidator().validate(dstRequest,json(dstRequest,dstRow),Instant.parse('2026-03-08T06:00:00Z'))

        then:
        dstValidated.accepted
    }

    def "planning input hash binds every task field task set order-insensitively and all slots"() {
        given:
        Task original=task(); Plan originalPlan=plan([original]); String phase2=PlanHash.compute(originalPlan)
        List<Map> taskChanges=[
            [content:'Changed title'],[projectId:'p2'],[projectName:'Changed project'],[labels:['schedule','home']],
            [priority:3],[deadline:now.plusSeconds(7300)],[dueTime:now.plusSeconds(700)],
            [nativeDuration:Duration.ofMinutes(26)],[effectiveDuration:Duration.ofMinutes(31)],
            [durationSource:'changed'],[manual:true],[allDayDue:true]
        ]

        expect:
        taskChanges.every { change ->
            Plan changed=plan([task(change)])
            PlanHash.compute(changed)==phase2 && PlanningInputHash.compute(changed,[])!=PlanningInputHash.compute(originalPlan,[])
        }
        PlanningInputHash.compute(plan([task(id:'t2'),task(id:'t1')]),[])==
            PlanningInputHash.compute(plan([task(id:'t1'),task(id:'t2')]),[])
        PlanningInputHash.compute(originalPlan,[])!=PlanningInputHash.compute(plan([original],[slot(end:now.plusSeconds(7100))]),[])
        PlanningInputHash.compute(originalPlan,[event()])!=PlanningInputHash.compute(originalPlan,[event(title:'Changed event')])

        when: 'an exact decision is applied to caller tasks that merely share IDs'
        AiSuggestionBundle b=bundle(originalPlan); Path dir=Files.createTempDirectory('phase6-input-bind-')
        def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY)
        def decision=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))
            .decide(b,'override-1',originalPlan,'alice','bind-input','CONFIRM',now)
        def applier=new ConfirmedOverrideApplier()

        then:
        taskChanges.every { change ->
            try {
                applier.apply([task(change)],store,decision.record.decisionId,b,originalPlan,now,now.plusSeconds(7200),now)
                return false
            } catch (IllegalArgumentException ignored) { return true }
        }
        [[original,task(id:'extra')],[]].every { changedTasks ->
            try {
                applier.apply(changedTasks as List<Task>,store,decision.record.decisionId,b,originalPlan,now,now.plusSeconds(7200),now)
                return false
            } catch (IllegalArgumentException ignored) { return true }
        }
    }

    def "decision identity persists planning input hash and changed event or slot is rejected before transformation"() {
        given:
        Plan p=plan(); List<CalendarEvent> events=[event()]; AiSuggestionBundle b=bundle(p,events)
        Path dir=Files.createTempDirectory('phase6-current-input-'); def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY)
        def service=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))

        when:
        def confirmed=service.decide(b,'override-1',p,events,'alice','current-input','CONFIRM',now)

        then:
        confirmed.confirmed
        confirmed.record.planningInputHash==PlanningInputHash.compute(p,events)
        store.list()[0].planningInputHash==confirmed.record.planningInputHash

        when:
        new ConfirmedOverrideApplier().apply(p.tasks,store,confirmed.record.decisionId,b,
            plan(p.tasks,[slot(end:now.plusSeconds(7100))]),events,now,now.plusSeconds(7000),now)
        then: thrown(IllegalArgumentException)

        when:
        new ConfirmedOverrideApplier().apply(p.tasks,store,confirmed.record.decisionId,b,p,
            [event(title:'Changed')],now,now.plusSeconds(7200),now)
        then: thrown(IllegalArgumentException)

        when: 'event and task collection order alone changes'
        Plan two=plan([task(id:'t2'),task(id:'t1')]); List<CalendarEvent> twoEvents=[event(id:'e2',uid:'u2'),event(id:'e1')]
        AiSuggestionBundle ordered=bundle(two,twoEvents); Path orderedDir=Files.createTempDirectory('phase6-order-')
        def orderedStore=new AiSuggestionDecisionStore(orderedDir,SIGNING_KEY)
        def orderedDecision=new AiSuggestionConfirmationService(orderedStore,AiSuggestionConfirmationService.allowlist(['alice']))
            .decide(ordered,'override-1',plan([task(id:'t1'),task(id:'t2')]),twoEvents.reverse(),'alice','order-ok','CONFIRM',now)

        then:
        orderedDecision.confirmed
    }

    def "actor and correlation are strict bounded opaque identifiers before any store write"() {
        given:
        Plan p=plan(); AiSuggestionBundle b=bundle(p)
        List<String> actors=[' ','alice ','alice\n','alice/client','аlice','https://example.com',
            'alice.api_key=secret','Bearer.jwt.value','xoxb-abcdefghijklmnop']
        List<String> correlations=[' ','corr\u0000tail','../escape','corr with space','https://example.com',
            'token=supersecret','c'*129]

        expect:
        actors.every { bad ->
            Path dir=Files.createTempDirectory('phase6-bad-actor-').resolve('store')
            def service=new AiSuggestionConfirmationService(new AiSuggestionDecisionStore(dir,SIGNING_KEY),({true} as Predicate<String>))
            def result=service.decide(b,'override-1',p,bad,'valid-correlation','CONFIRM',now)
            !result.confirmed && !Files.exists(dir)
        }
        correlations.every { bad ->
            Path dir=Files.createTempDirectory('phase6-bad-corr-').resolve('store')
            def service=new AiSuggestionConfirmationService(new AiSuggestionDecisionStore(dir,SIGNING_KEY),({true} as Predicate<String>))
            def result=service.decide(b,'override-1',p,'alice',bad,'CONFIRM',now)
            !result.confirmed && !Files.exists(dir)
        }

        when:
        Path validDir=Files.createTempDirectory('phase6-valid-identifiers-').resolve('store')
        def validService=new AiSuggestionConfirmationService(new AiSuggestionDecisionStore(validDir,SIGNING_KEY),
            AiSuggestionConfirmationService.allowlist(['slack:U123ABC']))
        def result=validService.decide(b,'override-1',p,'slack:U123ABC','123e4567-e89b-12d3-a456-426614174000','CONFIRM',now)

        then:
        result.confirmed
        Files.exists(validDir.resolve('ai-suggestion-decisions.json'))
    }
}
