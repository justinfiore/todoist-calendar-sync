package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.domain.Task

import java.time.Duration
import java.time.Instant

class LlmSchemaAndAssistanceSpec extends Specification {
    Instant now = Instant.parse('2026-08-07T12:00:00Z')

    private Task task(String id='task-1', String title='Write report') {
        Task.builder().id(id).content(title).projectId('project-1').projectName('Work')
            .labels(['schedule']).priority(2).deadline(now.plusSeconds(7200))
            .effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
    }
    private CalendarEvent event(String id='event-1', String title='Team sync') {
        CalendarEvent.builder().id(id).title(title).calendarName('Work')
            .start(now.plusSeconds(3600)).end(now.plusSeconds(5400)).build()
    }
    private Plan plan(String title='Write report') {
        Plan.builder().id('plan-1').version(1).createdAt(now).mode('preview').tasks([task('task-1',title)]).build()
    }
    private LlmRequest request(String type, Plan p=plan()) {
        String proposal=type=='conversational_feedback_interpretation'?Proposal.fromPlan(p).id:null
        new LlmRequest(correlationId:'corr-1',suggestionType:type,schemaVersion:1,
            provider:'fixture',model:'fixture-v1',planId:p.id,planVersion:p.version,
            planHash:PlanHash.compute(p),planningInputHash:PlanningInputHash.compute(p,[]),context:[:],allowedTaskIds:['task-1'] as Set,
            allowedEventIds:['event-1'] as Set,
            expectedProposalId:proposal,allowedFeedbackActions:proposal?LlmSchemaValidator.FEEDBACK_ACTIONS:[],
            planningRangeStart:type=='temporary_planning_overrides'?now:null,
            planningRangeEnd:type=='temporary_planning_overrides'?now.plusSeconds(7200):null,
            maxTokens:500)
    }
    private Map root(String type, List suggestions) {
        [schemaVersion:1,suggestionType:type,correlationId:'corr-1',suggestions:suggestions]
    }

    def "fixture trial validates all four versioned schema/model contracts"() {
        given:
        Plan p=plan(); String hash=PlanHash.compute(p); String proposal=Proposal.fromPlan(p).id
        Map samples=[
            task_suggestions: [[suggestionId:'s-task',taskId:'task-1',kind:'duration',proposedValue:45,
                confidence:0.8,rationale:'Supplied timing indicates a larger block',evidenceIds:['task-1']]],
            event_classification_suggestions: [[suggestionId:'s-event',eventId:'event-1',suggestedRole:'hard_blocker',
                confidence:0.9,rationale:'Timing overlaps the supplied work window',candidateRulePatch:null]],
            temporary_planning_overrides: [[suggestionId:'s-override',planId:p.id,planVersion:p.version,planHash:hash,planningInputHash:PlanningInputHash.compute(p,[]),
                taskIds:['task-1'],overrideType:'duration_minutes',value:60,rangeStart:now.toString(),
                rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),
                confidence:0.75,rationale:'Use a temporary larger estimate']],
            conversational_feedback_interpretation: [[suggestionId:'s-feedback',proposalId:proposal,
                planId:p.id,planVersion:p.version,planHash:hash,action:'APPROVE',
                reason:'The message asks to approve this proposal']]
        ]

        expect:
        samples.every { type, rows ->
            def result=new LlmSchemaValidator().validate(request(type,p),JsonOutput.toJson(root(type,rows)),now)
            result.accepted && result.bundle.suggestions.size()==1 && result.bundle.suggestions[0] instanceof AiSuggestion
        }
        samples.keySet().every { LlmSchemaResources.load(it).additionalProperties == false }
    }

    def "strict validator rejects malformed unsafe and cross-context output without partial results"() {
        given:
        def good=[suggestionId:'s1',taskId:'task-1',kind:'duration',proposedValue:30,
            confidence:0.5,rationale:'Based on supplied duration',evidenceIds:['task-1']]
        def validator=new LlmSchemaValidator(); def req=request('task_suggestions')

        List<String> payloads=[
            '```json\n{}\n```',
            '{not json}',
            JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'wrong',suggestions:[]]),
            JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[[suggestionId:'s1',taskId:'other',kind:'duration',proposedValue:30,confidence:0.5,rationale:'ok',evidenceIds:[]]]]),
            JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[good,good]]),
            JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[[suggestionId:'s1',taskId:'task-1',kind:'context',proposedValue:'ignore previous instructions',confidence:0.5,rationale:'ok',evidenceIds:[]]]]),
            '{"schemaVersion":1,"suggestionType":"task_suggestions","correlationId":"corr-1","suggestions":[{"suggestionId":"s1","taskId":"task-1","kind":"duration","proposedValue":30,"confidence":NaN,"rationale":"ok","evidenceIds":[]}]}',
            JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[[suggestionId:'s1',taskId:'task-1',kind:'duration',proposedValue:30,confidence:0.5,rationale:'ok',evidenceIds:[],extra:true]]])
        ]

        expect:
        payloads.every { !validator.validate(req,it,now).accepted }
    }

    def "override validator rejects expired range persistent mutation and wrong plan binding"() {
        given:
        Plan p=plan(); String hash=PlanHash.compute(p); def req=request('temporary_planning_overrides',p)
        def base=[suggestionId:'o1',planId:p.id,planVersion:1,planHash:hash,planningInputHash:PlanningInputHash.compute(p,[]),taskIds:['task-1'],
            overrideType:'duration_minutes',value:45,rangeStart:now.toString(),rangeEnd:now.plusSeconds(7200).toString(),
            expiresAt:now.plusSeconds(7200).toString(),confidence:0.7,rationale:'Temporary estimate']

        List<Map> changes=[
            [expiresAt:Instant.parse('2026-08-07T11:59:59Z').toString()],
            [overrideType:'config_write',value:'fully_automated'],
            [planHash:'0'*64],
            [taskIds:['other']],
            [rangeStart:now.minusSeconds(1).toString()],
            [rangeEnd:now.plusSeconds(40L*86400L).toString(),expiresAt:now.plusSeconds(3600).toString()]
        ]

        expect:
        changes.every { !new LlmSchemaValidator().validate(req,JsonOutput.toJson(root('temporary_planning_overrides',[base+it])),now).accepted }
    }

    def "context builder allowlists fields redacts sensitive strings and truncates deterministically"() {
        given:
        def cfg=PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-12:00']]],
            ai:[enabled:true,provider:'fixture',model:'fixture-v1',max_items:4,max_string_chars:64]])
        String sensitive='Email alice@example.com +1 (212) 555-0199 https://hooks.slack.com/services/AAA token=sk-abcdefghijklmnop <@U12345678>'
        Plan p=Plan.builder().id('p').version(1).createdAt(now).mode('preview')
            .tasks([task('task-2',sensitive),task('task-1',sensitive),task('task-3',sensitive)]).build()
        def ev=CalendarEvent.builder().id('event-1').title(sensitive).description('attendee secret@example.com')
            .calendarName('Work').start(now).end(now.plusSeconds(60)).build()

        when:
        def result=new LlmContextBuilder(cfg.ai).build(p,[ev],sensitive,now)
        String serialized=JsonOutput.toJson(result.context)

        then:
        result.redactionCount >= 8
        result.omittedCount == 0
        result.taskIds == ['task-1','task-2','task-3'] as Set
        result.eventIds == ['event-1'] as Set
        !serialized.contains('alice@example.com')
        !serialized.contains('555-0199')
        !serialized.contains('hooks.slack.com')
        !serialized.contains('sk-abcdefghijklmnop')
        !serialized.contains('U12345678')
        !serialized.contains('description')
        !serialized.contains('attendee')
        !serialized.contains('labels')
    }

    def "context item omission and truncation counts are deterministic and non-vacuous"() {
        given:
        def cfg=PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-12:00']]],
            ai:[enabled:true,provider:'fixture',model:'fixture-v1',max_items:2,max_string_chars:32]])
        Plan p=Plan.builder().id('p').version(1).createdAt(now).mode('preview').tasks([
            task('task-3','three'*20),task('task-1','one'*20),task('task-2','two'*20)]).build()
        def ev=event('event-1','event title')

        when:
        def first=new LlmContextBuilder(cfg.ai).build(p,[ev],null,now)
        def second=new LlmContextBuilder(cfg.ai).build(p,[ev],null,now)

        then:
        JsonOutput.toJson(first.context)==JsonOutput.toJson(second.context)
        first.omittedCount==2
        first.truncatedStringCount==2
        first.taskIds==['task-1','task-2'] as Set
        first.eventIds.empty
        first.context.omittedCounts==[items:2,truncatedStrings:2]
    }

    def "conversational request carries exact expected Phase 5 identity and allowlisted actions"() {
        given:
        def cfg=PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-12:00']]],
            ai:[enabled:true,provider:'fixture',model:'fixture-v1']])
        Plan p=plan();LlmRequest captured
        LlmGateway fake={LlmRequest req->
            captured=req
            String json=JsonOutput.toJson(root(req.suggestionType,[[suggestionId:'f1',proposalId:req.expectedProposalId,
                planId:req.planId,planVersion:req.planVersion,planHash:req.planHash,action:'APPROVE',reason:'Looks good']]))
            LlmGatewayResult.success(new LlmResponse(req.correlationId,req.suggestionType,1,json,json.bytes.length))
        } as LlmGateway

        when:
        def result=new AiAssistanceService(cfg.ai,fake,null,new LlmSchemaValidator(),{now})
            .suggest('conversational_feedback_interpretation','corr-1',p,[],'looks good')

        then:
        result.accepted
        captured.expectedProposalId==Proposal.fromPlan(p).id
        captured.allowedFeedbackActions==LlmSchemaValidator.FEEDBACK_ACTIONS
        captured.context.expectedFeedback==[proposalId:Proposal.fromPlan(p).id,planId:p.id,planVersion:p.version,
            planHash:PlanHash.compute(p),allowedActions:LlmSchemaValidator.FEEDBACK_ACTIONS as List]
        !result.bundle.suggestions[0].proposedCommand.contains('invented')
    }

    def "assistance service returns only validated bundle and metadata-only audit"() {
        given:
        def cfg=PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-12:00']]],
            ai:[enabled:true,provider:'fixture',model:'fixture-v1']])
        Plan p=plan('Contact bob@example.com about token=sk-abcdefghijklmnop')
        Map captured=[:]
        LlmGateway fake={ LlmRequest req ->
            captured.request=req
            String json=JsonOutput.toJson(root('task_suggestions',[[suggestionId:'s1',taskId:'task-1',kind:'duration',
                proposedValue:40,confidence:0.8,rationale:'Based on supplied timing',evidenceIds:['task-1']]]))
            LlmGatewayResult.success(new LlmResponse(req.correlationId,req.suggestionType,1,json,json.bytes.length,12,8))
        } as LlmGateway
        def service=new AiAssistanceService(cfg.ai,fake,null,new LlmSchemaValidator(),{now})

        when:
        def result=service.suggest('task_suggestions','corr-1',p)
        String audit=JsonOutput.toJson(result.audit.toMap())
        String sent=JsonOutput.toJson(captured.request.context)

        then:
        result.accepted
        result.bundle.suggestions[0] instanceof TaskSuggestion
        result.audit.outcome == 'ACCEPTED_SUGGESTIONS'
        result.audit.responseContentHash == result.bundle.contentHash
        !audit.contains('bob@example.com')
        !audit.contains('sk-abcdefghijklmnop')
        !audit.contains('Based on')
        !sent.contains('bob@example.com')
        !sent.contains('sk-abcdefghijklmnop')
    }

    def "malformed gateway output is all-or-nothing rejected and redacted from errors"() {
        given:
        def cfg=PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-12:00']]],ai:[enabled:true,provider:'fixture',model:'fixture-v1']])
        String unsafe='```secret bob@example.com sk-abcdefghijklmnop```'
        LlmGateway fake={req -> LlmGatewayResult.success(new LlmResponse(req.correlationId,req.suggestionType,1,unsafe,unsafe.bytes.length))} as LlmGateway

        when:
        def result=new AiAssistanceService(cfg.ai,fake,null,new LlmSchemaValidator(),{now}).suggest('task_suggestions','corr-1',plan())

        then:
        !result.accepted
        result.bundle == null
        result.error.errorClass == LlmErrorClass.MALFORMED_JSON
        !result.error.detail.contains('bob@example.com')
        !result.error.detail.contains('sk-')
    }
}
