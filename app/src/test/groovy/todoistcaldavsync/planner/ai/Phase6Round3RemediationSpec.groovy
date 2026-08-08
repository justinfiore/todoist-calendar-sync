package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.state.PlanStoreException

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class Phase6Round3RemediationSpec extends Specification {
    private static final byte[] SIGNING_KEY = (0..<32).collect { (byte) it } as byte[]
    private final Instant now = Instant.parse('2026-08-07T12:00:00Z')

    private PlannerConfig fixtureConfig() {
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-17:00']]],
            ai:[enabled:true,provider:'fixture',model:'fixture-v1',max_string_chars:1000]])
    }

    private PlannerConfig openAiConfig() {
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-17:00']]],ai:[
            enabled:true,provider:'openai_compatible',model:'fixture-v1',secret_env:'LLM_KEY',
            endpoint:'https://api.openai.com/v1/chat/completions',allowed_hosts:['api.openai.com'],
            max_string_chars:1000]])
    }

    private Task task(String content='Task') {
        Task.builder().id('t1').content(content).projectId('p1').projectName(content).labels(['schedule'])
            .priority(2).effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
    }

    private Plan plan(String content='Task') {
        def slot=TimeSlot.builder().start(now).end(now.plusSeconds(7200)).windowName('work').build()
        Plan.builder().id('plan-1').version(1).createdAt(now).mode('preview').tasks([task(content)]).slots([slot]).build()
    }

    private LlmRequest request(String type='task_suggestions', Map changes=[:]) {
        Map values=[correlationId:'corr-1',suggestionType:type,schemaVersion:1,provider:'fixture',model:'fixture-v1',
            planId:'plan-1',planVersion:1,planHash:'a'*64,planningInputHash:'b'*64,context:[:],
            allowedTaskIds:['t1'] as Set,allowedEventIds:['e1'] as Set,maxTokens:300]
        if(type=='temporary_planning_overrides') values += [planningRangeStart:now,planningRangeEnd:now.plusSeconds(7200)]
        if(type=='conversational_feedback_interpretation') values += [expectedProposalId:'proposal-1',
            allowedFeedbackActions:['APPROVE','REJECT'] as Set]
        new LlmRequest(values+changes)
    }

    private String response(LlmRequest request, List<Map> rows) {
        JsonOutput.toJson([schemaVersion:1,suggestionType:request.suggestionType,
            correlationId:request.correlationId,suggestions:rows])
    }

    private Map overrideRow(LlmRequest r, Map changes=[:]) {
        [suggestionId:'override-1',planId:r.planId,planVersion:r.planVersion,planHash:r.planHash,
            planningInputHash:r.planningInputHash,taskIds:['t1'],overrideType:'duration_minutes',value:45,
            rangeStart:now.toString(),rangeEnd:now.plusSeconds(7200).toString(),expiresAt:now.plusSeconds(7200).toString(),
            confidence:0.8,rationale:'Temporary estimate']+changes
    }

    private AiSuggestionBundle overrideBundle(Plan p) {
        LlmRequest r=new LlmRequest(correlationId:'corr-1',suggestionType:'temporary_planning_overrides',schemaVersion:1,
            provider:'fixture',model:'fixture-v1',planId:p.id,planVersion:p.version,planHash:PlanHash.compute(p),
            planningInputHash:PlanningInputHash.compute(p,[]),context:[:],allowedTaskIds:['t1'] as Set,
            allowedEventIds:[] as Set,planningRangeStart:now,planningRangeEnd:now.plusSeconds(7200),maxTokens:300)
        def result=new LlmSchemaValidator().validate(r,response(r,[overrideRow(r)]),now)
        assert result.accepted
        result.bundle
    }

    def "provider envelope rejects trailing roots junk and duplicate keys at every depth"() {
        given:
        def cfg=openAiConfig(); String content=response(request(),[]); String quoted=JsonOutput.toJson(content)
        String valid="""{"choices":[{"message":{"role":"assistant","content":${quoted}}}]}"""
        Map<String,String> attacks=[
            trailing:valid+' junk', secondRoot:valid+' {}',
            duplicateChoices:"""{"choices":[],"choices":[{"message":{"role":"assistant","content":${quoted}}}]}""",
            duplicateMessage:"""{"choices":[{"message":{"role":"assistant","content":${quoted}},"message":{"role":"assistant","content":${quoted}}}]}""",
            duplicateContent:"""{"choices":[{"message":{"role":"assistant","content":"{}","content":${quoted}}}]}""",
            unsafeThenNull:"""{"choices":[{"message":{"role":"assistant","content":${quoted},"tool_calls":[{"id":"attack"}],"tool_calls":null}}]}""",
            nullThenUnsafe:"""{"choices":[{"message":{"role":"assistant","content":${quoted},"tool_calls":null,"tool_calls":[{"id":"attack"}]}}]}"""
        ]

        expect:
        attacks.every { name, body ->
            def gateway=new OpenAiCompatibleLlmGateway(cfg.ai,
                ({ ignored -> new LlmTransportResponse(200,[:],body.getBytes(StandardCharsets.UTF_8)) } as LlmHttpTransport),
                { ignored -> 'transport-secret' })
            def result=gateway.complete(request(cfg.ai.provider=='fixture' ? 'task_suggestions' : 'task_suggestions',
                [provider:cfg.ai.provider,model:cfg.ai.model]))
            !result.success && result.error.errorClass==LlmErrorClass.MALFORMED_JSON &&
                result.error.detail=='provider response envelope is malformed JSON' &&
                !result.error.toString().contains('attack')
        }
    }

    def "international phones bare Slack ids and natural-language credentials are redacted from transport output error and audit"() {
        given:
        List<String> secrets=['+44 20 7946 0958','+1-202-555-0199','U12345678','W12345678','C12345678','G12345678',
            'Hunter2!','api-secret-value','token-secret-value','client-secret-value']
        String seeded='Call +44 20 7946 0958 or +1-202-555-0199; users U12345678 W12345678 channels C12345678 G12345678; ' +
            'password is Hunter2! api key is api-secret-value token was token-secret-value client secret: client-secret-value'
        def cfg=openAiConfig(); LlmTransportRequest captured
        LlmHttpTransport transport={ LlmTransportRequest actual ->
            captured=actual
            String content=JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[
                [suggestionId:'s1',taskId:'t1',kind:'duration',proposedValue:45,confidence:0.8,rationale:seeded,evidenceIds:['t1']]
            ]])
            new LlmTransportResponse(200,[:],JsonOutput.toJson([choices:[[message:[role:'assistant',content:content]]]]).bytes)
        } as LlmHttpTransport

        when:
        def result=new AiAssistanceService(cfg.ai,new OpenAiCompatibleLlmGateway(cfg.ai,transport,{ ignored -> 'gateway-secret' }),
            null,new LlmSchemaValidator(),{now}).suggest('task_suggestions','corr-1',plan(seeded),[],seeded)
        String body=new String(captured.body,StandardCharsets.UTF_8)
        String bundle=JsonOutput.toJson([suggestionId:result.bundle.suggestions[0].suggestionId,
            rationale:result.bundle.suggestions[0].rationale,contentHash:result.bundle.contentHash])
        String error=new LlmError(LlmErrorClass.TRANSPORT,seeded).detail
        String audit=result.audit.toString()

        then:
        result.accepted
        body.contains('t1') && bundle.contains('s1')
        secrets.every { raw -> !body.contains(raw) && !bundle.contains(raw) && !error.contains(raw) && !audit.contains(raw) }
        body.contains('[REDACTED]') && bundle.contains('[REDACTED]') && error.contains('[REDACTED]')
    }

    @Unroll
    def "AI config error for #field never echoes rejected scalar list or map material"() {
        given:
        Map ai=[enabled:true,provider:'openai_compatible',endpoint:'https://api.openai.com/v1/chat/completions',
            model:'fixture-v1',secret_env:'LLM_KEY',allowed_hosts:['api.openai.com']]
        ai[field]=value

        when:
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-17:00']]],ai:ai])

        then:
        def ex=thrown(IllegalArgumentException)
        sensitive.every { !ex.message.contains(it) && !ex.toString().contains(it) }

        where:
        field                       | value                                                   | sensitive
        'enabled'                   | 'password is EXACT-SENSITIVE-VALUE'                     | ['EXACT-SENSITIVE-VALUE']
        'provider'                  | 'client_secret=EXACT-SENSITIVE-VALUE'                   | ['EXACT-SENSITIVE-VALUE']
        'endpoint'                  | 'https://hooks.slack.com/services/EXACT/SENSITIVE/VALUE' | ['EXACT','SENSITIVE','VALUE']
        'model'                     | 'private@example.com'                                   | ['private@example.com']
        'secret_env'                | '+44 20 7946 0958'                                      | ['+44 20 7946 0958']
        'connect_timeout'           | 'Bearer EXACT-SENSITIVE-VALUE'                          | ['EXACT-SENSITIVE-VALUE']
        'request_timeout'           | 'password is EXACT-SENSITIVE-VALUE'                     | ['EXACT-SENSITIVE-VALUE']
        'timeout'                   | 'token was EXACT-SENSITIVE-VALUE'                       | ['EXACT-SENSITIVE-VALUE']
        'max_request_bytes'         | 'private@example.com'                                   | ['private@example.com']
        'max_response_bytes'        | '+1-202-555-0199'                                       | ['+1-202-555-0199']
        'max_items'                 | 'client_secret=EXACT-SENSITIVE-VALUE'                   | ['EXACT-SENSITIVE-VALUE']
        'max_string_chars'          | 'https://hooks.slack.com/services/EXACT/SENSITIVE/VALUE' | ['EXACT','SENSITIVE','VALUE']
        'max_tokens'                | 'Bearer EXACT-SENSITIVE-VALUE'                          | ['EXACT-SENSITIVE-VALUE']
        'allowed_suggestion_types'  | ['client_secret=EXACT-SENSITIVE-VALUE']                 | ['EXACT-SENSITIVE-VALUE']
        'allowed_hosts'             | ['private@example.com']                                 | ['private@example.com']
        'require_confirmation'      | 'token was EXACT-SENSITIVE-VALUE'                       | ['EXACT-SENSITIVE-VALUE']
        'redaction_enabled'         | 'password is EXACT-SENSITIVE-VALUE'                     | ['EXACT-SENSITIVE-VALUE']
        'safety'                    | [never_apply_changes_directly:'Bearer EXACT-SENSITIVE-VALUE'] | ['EXACT-SENSITIVE-VALUE']
    }

    def "unknown AI config keys and nested keys never echo their names or values"() {
        given:
        String secret='client_secret=EXACT-SENSITIVE-VALUE'

        when:
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-17:00']]],
            ai:[(secret):[(secret):secret]]])

        then:
        def ex=thrown(IllegalArgumentException)
        !ex.message.contains(secret) && !ex.toString().contains(secret)
    }

    def "semantic validation rejects duplicate logical targets for every suggestion contract"() {
        given:
        LlmRequest taskReq=request(); Map taskRow=[suggestionId:'s1',taskId:'t1',kind:'duration',proposedValue:30,
            confidence:0.7,rationale:'Estimate',evidenceIds:['t1']]
        LlmRequest eventReq=request('event_classification_suggestions'); Map eventRow=[suggestionId:'e-s1',eventId:'e1',
            suggestedRole:'hard_blocker',confidence:0.7,rationale:'Busy',candidateRulePatch:null]
        LlmRequest overrideReq=request('temporary_planning_overrides'); Map override=overrideRow(overrideReq)
        LlmRequest feedbackReq=request('conversational_feedback_interpretation'); Map feedback=[suggestionId:'f1',proposalId:'proposal-1',
            planId:'plan-1',planVersion:1,planHash:'a'*64,action:'APPROVE',reason:'Looks good']
        def validator=new LlmSchemaValidator()

        expect:
        !validator.validate(taskReq,response(taskReq,[taskRow,taskRow+[suggestionId:'s2',proposedValue:45]]),now).accepted
        !validator.validate(eventReq,response(eventReq,[eventRow,eventRow+[suggestionId:'e-s2',suggestedRole:'soft_blocker']]),now).accepted
        !validator.validate(overrideReq,response(overrideReq,[override,override+[suggestionId:'override-2',value:60,
            rangeStart:now.plusSeconds(60).toString()]]),now).accepted
        !validator.validate(feedbackReq,response(feedbackReq,[feedback,feedback+[suggestionId:'f2',action:'REJECT']]),now).accepted

        and: 'schema constraints reject exactly duplicated entries where expressible'
        !LlmSchemaValidator.resourceAccepts('task_suggestions',response(taskReq,[taskRow,taskRow]))
        !LlmSchemaValidator.resourceAccepts('conversational_feedback_interpretation',response(feedbackReq,[feedback,feedback+[suggestionId:'f2']]))
    }

    @Unroll
    def "request identities validate original bytes without trimming for #bad"() {
        when:
        new LlmRequest([correlationId:'corr',suggestionType:'conversational_feedback_interpretation',schemaVersion:1,
            provider:'fixture',model:'fixture-v1',planId:'plan',planVersion:1,planHash:'a'*64,planningInputHash:'b'*64,
            context:[:],allowedTaskIds:['t1'] as Set,allowedEventIds:[] as Set,expectedProposalId:'proposal',
            allowedFeedbackActions:['APPROVE'] as Set,maxTokens:100]+[(field):bad])

        then:
        thrown(IllegalArgumentException)

        where:
        field                | bad
        'correlationId'      | ' corr '
        'correlationId'      | 'corr\n'
        'correlationId'      | 'corr\t'
        'correlationId'      | 'corr\u00a0'
        'correlationId'      | 'c\u043err'
        'planId'             | 'plan '
        'planId'             | '../plan'
        'expectedProposalId' | ' proposal'
    }

    def "spaced correlation is rejected before gateway call or audit creation"() {
        given:
        def calls=new AtomicInteger(); def gateway={ ignored -> calls.incrementAndGet(); throw new AssertionError() } as LlmGateway

        when:
        new AiAssistanceService(fixtureConfig().ai,gateway,null,new LlmSchemaValidator(),{now})
            .suggest('task_suggestions',' corr ',plan())

        then:
        thrown(IllegalArgumentException)
        calls.get()==0
    }

    def "strict signed ledger rejects corruption tampering forgery and cross-store replay"() {
        given:
        Plan p=plan(); AiSuggestionBundle bundle=overrideBundle(p); Path dir=Files.createTempDirectory('phase6-signed-ledger-')
        def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY)
        def confirmed=new AiSuggestionConfirmationService(store,AiSuggestionConfirmationService.allowlist(['alice']))
            .decide(bundle,'override-1',p,'alice','confirm-1','CONFIRM',now)
        String original=Files.readString(store.recordsPath())
        Map parsed=new JsonSlurper().parseText(original) as Map

        expect:
        confirmed.confirmed
        parsed.records[0].signature==~ /^[0-9a-f]{64}$/
        !original.contains(SIGNING_KEY.encodeHex().toString())

        when: 'each strict JSON corruption is introduced after a valid write'
        List<String> corruptions=[original+' junk',original+' {}',
            original.replaceFirst('"schemaVersion"', '"schemaVersion":1,"schemaVersion"'),
            original.replaceFirst('"action"\s*:\s*"CONFIRM"', '"action":"CONFIRM","action":"CONFIRM"'),
            original.replaceFirst('"signature"\s*:', '"signature":"'+('0'*64)+'","signature":')]

        then:
        corruptions.every { corrupt ->
            Files.writeString(store.recordsPath(),corrupt)
            try { new AiSuggestionDecisionStore(dir,SIGNING_KEY).list(); return false }
            catch (PlanStoreException ignored) { return true }
            finally { Files.writeString(store.recordsPath(),original) }
        }

        when: 'a signed record field is altered, its signature removed, or a guessed signature supplied'
        List<Map> forgeries=[]
        Map altered=new JsonSlurper().parseText(original) as Map; altered.records[0].actorId='mallory'; forgeries << altered
        Map missing=new JsonSlurper().parseText(original) as Map; missing.records[0].remove('signature'); forgeries << missing
        Map guessed=new JsonSlurper().parseText(original) as Map; guessed.records[0].signature='0'*64; forgeries << guessed

        then:
        forgeries.every { forged ->
            Files.writeString(store.recordsPath(),JsonOutput.toJson(forged))
            try { new AiSuggestionDecisionStore(dir,SIGNING_KEY).verifiedOverride(confirmed.record.decisionId,bundle,p,now); return false }
            catch (PlanStoreException ignored) { return true }
            finally { Files.writeString(store.recordsPath(),original) }
        }

        when: 'the signed record is rebound to a different valid store id'
        Path otherDir=Files.createTempDirectory('phase6-other-ledger-'); def other=new AiSuggestionDecisionStore(otherDir,SIGNING_KEY)
        String otherId=other.provenanceId(); Map copied=new JsonSlurper().parseText(original) as Map
        copied.storeId=otherId; copied.records.each { it.storeId=otherId }
        Files.writeString(other.recordsPath(),JsonOutput.toJson(copied))
        other.verifiedOverride(confirmed.record.decisionId,bundle,p,now)

        then:
        thrown(PlanStoreException)
    }

    def "decision store refuses absent short or low-entropy signing keys with redacted errors"() {
        when: new AiSuggestionDecisionStore(Files.createTempDirectory('phase6-no-key-'))
        then: def absent=thrown(IllegalArgumentException); absent.message=='decision signing key is required'

        when: new AiSuggestionDecisionStore(Files.createTempDirectory('phase6-short-key-'),'EXACT-SENSITIVE-VALUE'.bytes)
        then:
        def shortKey=thrown(IllegalArgumentException)
        shortKey.message=='decision signing key must contain at least 256 bits of high-entropy material'
        !shortKey.toString().contains('EXACT-SENSITIVE-VALUE')

        when: new AiSuggestionDecisionStore(Files.createTempDirectory('phase6-weak-key-'),new byte[32])
        then: thrown(IllegalArgumentException)
    }

    def "standalone forged confirmed ledger cannot authorize or transform a task"() {
        given:
        Plan p=plan(); AiSuggestionBundle bundle=overrideBundle(p); Path dir=Files.createTempDirectory('phase6-forged-ledger-')
        def store=new AiSuggestionDecisionStore(dir,SIGNING_KEY); String storeId=store.provenanceId()
        Map forged=[schemaVersion:1,storeId:storeId,records:[[
            storeId:storeId,decisionId:'ai-confirmed-forged',suggestionId:'override-1',
            schemaType:'temporary_planning_overrides',schemaVersion:1,bundleContentHash:bundle.contentHash,
            planId:bundle.planId,planVersion:bundle.planVersion,planHash:bundle.planHash,
            planningInputHash:bundle.planningInputHash,actorId:'alice',correlationId:'forged-correlation',
            decidedAt:now.toString(),action:'CONFIRM',status:'CONFIRMED_TEMPORARY_OVERRIDE',
            priorDecisionId:null,signature:'0'*64
        ]]]
        Files.writeString(store.recordsPath(),JsonOutput.toJson(forged))
        Duration before=p.tasks[0].effectiveDuration

        when:
        new ConfirmedOverrideApplier().apply(p.tasks,store,'ai-confirmed-forged',bundle,p,
            now,now.plusSeconds(7200),now)

        then:
        thrown(PlanStoreException)
        p.tasks[0].effectiveDuration==before
    }
}
