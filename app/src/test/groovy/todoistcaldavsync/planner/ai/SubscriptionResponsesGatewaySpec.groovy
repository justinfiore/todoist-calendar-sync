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

    private PlannerConfig.AiConfig config() {
        PlannerConfig.fromMap(planner:[availability:[working_windows:[weekday:['09:00-12:00']]],ai:[
            enabled:true,provider:'codex_subscription',model:'gpt-test',subscription:[auth_root:root.path,
            experimental_protocol_acknowledged:true,codex:[allowed_hosts:['api.openai.com']]]]]).ai
    }
    private LlmRequest request(){new LlmRequest(correlationId:'corr-1',suggestionType:'task_suggestions',provider:'codex_subscription',
        model:'gpt-test',planId:'plan-1',planVersion:1,planHash:'a'*64,planningInputHash:'b'*64,context:[tasks:[]],
        allowedTaskIds:['task-1'],allowedEventIds:[],maxTokens:500)}
    private SubscriptionCredentialService credentials(){
        def store=new SubscriptionCredentialStore(root.toPath(),'codex')
        store.save(new SubscriptionCredential(provider:'codex',adapterRevision:OpenAiSiwcAdapter.REVISION,generation:1,
            issuer:'https://auth.openai.com',clientId:'oaiapp_fixture',hostId:'urn:uuid:11111111-1111-4111-8111-111111111111',
            subject:'opaque',accessToken:'access-secret',refreshToken:'refresh-secret',expiresAt:now.plusSeconds(3600),
            scopes:OpenAiSiwcAdapter.SCOPES))
        new SubscriptionCredentialService(store,new OpenAiSiwcAdapter(),[
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

    def "unknown provider stream errors are reduced to a safe class"() {
        when:
        def error=failure([type:'response.failed',response:[error:[code:'token-secret@example.test']]])

        then:
        error.errorClass==LlmErrorClass.HTTP_STATUS
        error.code=='response_failed'
        !error.message.contains('token-secret')
    }

    private static SubscriptionSseException failure(Map event) {
        try { SubscriptionSseParser.parse(("data: "+JsonOutput.toJson(event)+"\n\n").bytes);assert false }
        catch(SubscriptionSseException e){e}
    }
}
