package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig

import java.time.Instant

class SubscriptionResponsesGatewaySpec extends Specification {
    File root
    Instant now=Instant.parse('2026-10-04T12:00:00Z')
    def setup(){root=File.createTempDir('responses-subscription-','')}
    def cleanup(){root?.deleteDir()}

    private PlannerConfig.AiConfig config(String provider='codex_subscription', String model='gpt-test') {
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-12:00']]],ai:[
            enabled:true,provider:provider,model:model,subscription:[auth_root:root.path,
            experimental_protocol_acknowledged:true,codex:[allowed_hosts:['api.openai.com']],grok:[allowed_hosts:['api.x.ai']]]]]).ai
    }
    private LlmRequest request(Map patch=[:]){new LlmRequest([correlationId:'corr-1',suggestionType:'task_suggestions',provider:'codex_subscription',
        model:'gpt-test',planId:'plan-1',planVersion:1,planHash:'a'*64,planningInputHash:'b'*64,context:[tasks:[]],
        allowedTaskIds:['task-1'],allowedEventIds:[],maxTokens:500]+patch)}
    private SubscriptionCredentialService credentials(String provider='codex'){
        boolean codex=provider=='codex'
        def adapter=codex?new OpenAiSiwcAdapter():new XaiDeviceOAuthAdapter()
        def store=new SubscriptionCredentialStore(root.toPath(),provider)
        store.save(new SubscriptionCredential(provider:provider,adapterRevision:adapter.revision(),generation:1,
            issuer:codex?'https://auth.openai.com':'https://auth.x.ai',clientId:codex?'oaiapp_fixture':XaiDeviceOAuthAdapter.CLIENT_ID,
            hostId:codex?'urn:uuid:11111111-1111-4111-8111-111111111111':null,
            subject:'opaque',accessToken:'access-secret',refreshToken:'refresh-secret',expiresAt:now.plusSeconds(3600),
            scopes:codex?OpenAiSiwcAdapter.SCOPES:XaiDeviceOAuthAdapter.SCOPES))
        new SubscriptionCredentialService(store,adapter,[
            postForm:{a,b,c->throw new AssertionError()},get:{a,b,c->throw new AssertionError()}] as SubscriptionOAuthTransport,{now})
    }
    private static byte[] stream(Map output) {
        String text=JsonOutput.toJson(output)
        ("data: "+JsonOutput.toJson([type:'response.output_text.delta',delta:text])+"\n\n"+
            "data: "+JsonOutput.toJson([type:'response.completed',response:[usage:[input_tokens:11,output_tokens:7]]])+"\n\n").bytes
    }

    def "gateway emits official store-false streaming history contract with no tools and accepts only completed strict output"() {
        given:
        LlmTransportRequest captured
        LlmHttpTransport transport={r->captured=r;new LlmTransportResponse(200,[:],stream([
            schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[]]))} as LlmHttpTransport

        when: def result=new SubscriptionResponsesLlmGateway(config(),credentials(),transport).complete(request())
        then:
        result.success
        result.response.promptTokens==11 && result.response.completionTokens==7
        captured.endpoint.toString()=='https://api.openai.com/v1/responses'
        captured.headers.Authorization=='Bearer access-secret'
        captured.headers.Accept=='text/event-stream'
        Map body=new JsonSlurper().parse(captured.body) as Map
        body.store==false && body.stream==true
        body.input instanceof List && body.input.size()==1
        body.text.format.type=='json_schema' && body.text.format.strict==true
        !body.containsKey('tools') && !body.containsKey('tool_choice') && !body.containsKey('max_output_tokens')
        !body.containsKey('previous_response_id') && !body.containsKey('temperature')
        !new String(captured.body).contains('access-secret')
    }

    def "SSE parser rejects tool activity explicit failures incomplete and interrupted streams"() {
        expect:
        failure(event).errorClass==kind
        where:
        event << [
            [type:'response.output_item.added',item:[type:'function_call']],
            [type:'response.failed',response:[error:[code:'subscription_sharing_usage_limit_exceeded']]],
            [type:'response.incomplete'],
            [type:'response.output_text.delta',delta:'{}']
        ]
        kind << [LlmErrorClass.UNSAFE_OUTPUT,LlmErrorClass.ENTITLEMENT,LlmErrorClass.SCHEMA_REJECTED,LlmErrorClass.TRANSPORT]
    }

    def "xAI honors the output token budget and uses low reasoning only on verified compatible models"() {
        given:
        LlmTransportRequest captured
        LlmHttpTransport transport={r->captured=r;new LlmTransportResponse(200,[:],stream([
            schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[]]))} as LlmHttpTransport

        when:
        def result=new SubscriptionResponsesLlmGateway(config('grok_build_subscription',model),credentials('grok'),transport)
            .complete(request(provider:'grok_build_subscription',model:model,maxTokens:requestedTokens))
        Map body=new JsonSlurper().parse(captured.body) as Map

        then:
        result.success
        captured.endpoint.toString()=='https://api.x.ai/v1/responses'
        captured.maxResponseBytes==262144
        body.max_output_tokens==expectedTokens
        body.reasoning==effort
        !body.containsKey('tools') && !body.containsKey('tool_choice')
        body.store==false && body.stream==true

        where:
        model      | requestedTokens | expectedTokens | effort
        'grok-4.6' | 500             | 500            | [effort:'low']
        'grok-4.7' | 1800            | 1200           | [effort:'low']
        'grok-4.3' | 500             | 500            | null
    }

    def "temporary override inference receives exact range and expiry constraints enforced by validation"() {
        given:
        Instant reference=Instant.now()
        def req=request(suggestionType:'temporary_planning_overrides',requestedAt:reference,
            planningRangeStart:reference.plusSeconds(3600),planningRangeEnd:reference.plusSeconds(8*3600))
        LlmTransportRequest captured
        Map output=[schemaVersion:1,suggestionType:req.suggestionType,correlationId:'corr-1',suggestions:[
            [suggestionId:'override-1',planId:'plan-1',planVersion:1,planHash:'a'*64,planningInputHash:'b'*64,
             taskIds:['task-1'],overrideType:'duration_minutes',value:45,rangeStart:reference.plusSeconds(4200).toString(),
             rangeEnd:reference.plusSeconds(7*3600).toString(),expiresAt:reference.plusSeconds(8*3600).toString(),
             confidence:0.8,rationale:'Temporary research estimate']]]
        LlmHttpTransport transport={r->captured=r;new LlmTransportResponse(200,[:],stream(output))} as LlmHttpTransport

        when:
        def result=new SubscriptionResponsesLlmGateway(config(),credentials(),transport).complete(req)
        Map body=new JsonSlurper().parse(captured.body) as Map
        Map input=new JsonSlurper().parseText(body.input[0].content) as Map

        then:
        result.success
        input.constraints==[planningRangeStart:reference.plusSeconds(3600).toString(),
            planningRangeEnd:reference.plusSeconds(8*3600).toString(),validationTime:reference.toString(),
            maxOverrideExpiresAt:reference.plusSeconds(31L*86400).toString()]
        body.text.format.schema.get('properties').correlationId.const=='corr-1'
        body.text.format.schema.get('properties').suggestions.items.get('properties').findAll{k,v->k in ['planId','planVersion','planHash','planningInputHash']}
            .collectEntries{k,v->[k,v.const]}==[planId:'plan-1',planVersion:1,planHash:'a'*64,planningInputHash:'b'*64]
        !body.containsKey('max_output_tokens')
    }

    def "unknown provider stream errors are reduced to a safe class"() {
        when:
        def error=failure([type:'response.failed',response:[error:[code:'token-secret@example.test']]])

        then:
        error.errorClass==LlmErrorClass.HTTP_STATUS
        error.code=='response_failed'
        !error.message.contains('token-secret')
    }

    def "transport failures retain safe classifications and measured lower bounds"() {
        given:
        LlmHttpTransport transport={r->throw problem} as LlmHttpTransport

        when:
        def result=new SubscriptionResponsesLlmGateway(config(),credentials(),transport).complete(request())

        then:
        !result.success
        result.error.errorClass==kind
        result.error.statusCode==status
        result.error.responseBytes==bytes
        result.error.structuredTextBytes==null
        result.error.responseTruncated==truncated
        !result.error.detail.contains('private-secret-canary')

        where:
        problem                                                                  | kind                              | status | bytes | truncated
        new LlmBodyLimitException(200,65537)                                      | LlmErrorClass.RESPONSE_TOO_LARGE  | 200    | 65537 | true
        new LlmBodyLimitException()                                               | LlmErrorClass.RESPONSE_TOO_LARGE  | null   | null  | true
        new java.net.http.HttpTimeoutException('private-secret-canary')            | LlmErrorClass.TIMEOUT             | null   | null  | false
        new java.util.concurrent.TimeoutException('private-secret-canary')        | LlmErrorClass.TIMEOUT             | null   | null  | false
        new IOException('private-secret-canary')                                  | LlmErrorClass.TRANSPORT           | null   | null  | false
    }

    def "HTTP success with invalid semantic content retains byte metadata and rejects the entire bundle"() {
        given:
        Map output=[schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[
            [suggestionId:'s1',taskId:'private-identity-canary',kind:'duration',proposedValue:30,
             confidence:0.7,rationale:'Estimate',evidenceIds:[]]]]
        byte[] wire=stream(output)
        LlmHttpTransport transport={r->new LlmTransportResponse(200,[:],wire)} as LlmHttpTransport

        when:
        def result=new SubscriptionResponsesLlmGateway(config(),credentials(),transport).complete(request())

        then:
        !result.success
        result.response==null
        result.error.errorClass==LlmErrorClass.SCHEMA_REJECTED
        result.error.reasonCode=='unknown_task'
        result.error.statusCode==200
        result.error.responseBytes==wire.length
        result.error.structuredTextBytes==JsonOutput.toJson(output).getBytes('UTF-8').length
        !result.error.responseTruncated
        !result.error.detail.contains('private-identity-canary')
    }

    private static SubscriptionSseException failure(Map event) {
        try { SubscriptionSseParser.parse(("data: "+JsonOutput.toJson(event)+"\n\n").bytes);assert false }
        catch(SubscriptionSseException e){e}
    }
}
