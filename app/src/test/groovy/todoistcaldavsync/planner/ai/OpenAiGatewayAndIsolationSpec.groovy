package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.scheduling.DeterministicScheduler
import todoistcaldavsync.planner.adapters.InMemoryCalendarGateway
import todoistcaldavsync.planner.adapters.InMemoryTodoistGateway
import todoistcaldavsync.planner.state.PlanStore

import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class OpenAiGatewayAndIsolationSpec extends Specification {
    Instant now=Instant.parse('2026-08-07T12:00:00Z')

    private PlannerConfig config(Map ai=[:]) {
        Map planner=[mode:'preview',timezone:'UTC',availability:[working_windows:[weekday:['09:00-12:00']]]]
        if(ai!=null)planner.ai=ai
        PlannerConfig.fromMap(planner:planner)
    }
    private PlannerConfig openAiConfig(Map extra=[:]) {
        config([enabled:true,provider:'openai_compatible',endpoint:'https://api.openai.com/v1/chat/completions',
                model:'gpt-fixture',secret_env:'LLM_TEST_KEY']+extra)
    }
    private LlmRequest request(PlannerConfig cfg) {
        new LlmRequest(correlationId:'corr-1',suggestionType:'task_suggestions',schemaVersion:1,
            provider:cfg.ai.provider,model:cfg.ai.model,planId:'plan-1',planVersion:1,planHash:'a'*64,
            planningInputHash:'b'*64,
            context:[tasks:[[id:'task-1',title:'Safe']]],allowedTaskIds:['task-1'] as Set,allowedEventIds:[] as Set,maxTokens:300)
    }
    private static byte[] envelope(String content) {
        JsonOutput.toJson([choices:[[message:[role:'assistant',content:content],finish_reason:'stop']],
            usage:[prompt_tokens:10,completion_tokens:4]]).getBytes(StandardCharsets.UTF_8)
    }
    private static String emptyOutput() {
        JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[]])
    }

    def "disabled default constructs no gateway makes no calls and example stays disabled"() {
        given:
        def cfg=config(null); def constructed=new AtomicInteger(); def called=new AtomicInteger()

        when:
        def service=AiAssistanceService.create(cfg,{
            constructed.incrementAndGet()
            ({ req -> called.incrementAndGet(); null } as LlmGateway)
        })

        then:
        service.empty
        constructed.get()==0
        called.get()==0
        !cfg.ai.enabled
        !PlannerConfig.load(repoFile('conf/todoist-planner.conf.example.yaml')).ai.enabled
    }

    def "enabled but uninvoked AI remains isolated from byte-field deterministic scheduling"() {
        given:
        def disabled=config([enabled:false])
        def enabled=config([enabled:true,provider:'fixture',model:'fixture-v1'])
        def calls=new AtomicInteger()
        def side=AiAssistanceService.create(enabled,{({req -> calls.incrementAndGet(); null} as LlmGateway)},{now})
        def task=Task.builder().id('t1').content('Task').projectId('p').projectName('P').labels(['schedule'])
            .priority(2).deadline(now.plusSeconds(3600)).effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
        def slot=TimeSlot.builder().start(now).end(now.plusSeconds(3600)).windowName('work').build()

        when:
        def off=new DeterministicScheduler(disabled).propose([task],[slot],now,now.plusSeconds(3600),now)
        def on=new DeterministicScheduler(enabled).propose([task],[slot],now,now.plusSeconds(3600),now)

        then:
        side.present
        calls.get()==0
        PlanHash.canonicalize(off)==PlanHash.canonicalize(on)
        off.humanDiff==on.humanDiff
        off.metrics==on.metrics
        off.createdAt==on.createdAt
        off.id==on.id
    }

    def "enabled invoked suggestions preserve complete persisted plan and every write surface byte for byte"() {
        given:
        def enabled=config([enabled:true,provider:'fixture',model:'fixture-v1'])
        def task=Task.builder().id('t1').content('Task').projectId('p').projectName('P').labels(['schedule'])
            .priority(2).deadline(now.plusSeconds(3600)).effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
        def slot=TimeSlot.builder().start(now).end(now.plusSeconds(3600)).windowName('work').build()
        Plan plan=new DeterministicScheduler(enabled).propose([task],[slot],now,now.plusSeconds(3600),now)
        Path root=Files.createTempDirectory('ai-isolation-');def planStore=new PlanStore(root.resolve('plans'));planStore.save(plan)
        ['application','phase5-decisions','deliveries'].each { name ->
            Files.createDirectories(root.resolve(name));Files.writeString(root.resolve(name).resolve('sentinel'),"${name}-unchanged")
        }
        Map<String,byte[]> before=snapshot(root)
        def todoist=new InMemoryTodoistGateway();def calendar=new InMemoryCalendarGateway()
        LlmGateway gateway={LlmRequest req->
            String json=JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:req.correlationId,suggestions:[
                [suggestionId:'s1',taskId:'t1',kind:'duration',proposedValue:40,confidence:0.8,rationale:'Bounded estimate',evidenceIds:['t1']]
            ]])
            LlmGatewayResult.success(new LlmResponse(req.correlationId,req.suggestionType,1,json,json.bytes.length))
        } as LlmGateway

        when:
        def result=new AiAssistanceService(enabled.ai,gateway,null,new LlmSchemaValidator(),{now})
            .suggest('task_suggestions','corr-1',plan)
        Map<String,byte[]> after=snapshot(root)

        then:
        result.accepted
        before.keySet()==after.keySet()
        before.every { path,bytes -> Arrays.equals(bytes,after[path]) }
        Files.readAllBytes(planStore.pathFor(plan.id)).toList()==before["plans/${planStore.pathFor(plan.id).fileName}"].toList()
        PlanStore.toJson(planStore.load(plan.id))==PlanStore.toJson(plan)
        todoist.dueUpdates.empty && todoist.deadlineUpdates.empty
        calendar.upserts.empty && calendar.deletes.empty && calendar.rejectedWrites.empty
        todoist.dueCallCount==0
        calendar.upsertCallCount==0 && calendar.deleteCallCount==0
    }

    def "adapter sends deterministic strict schema request with redirect and bounds controls"() {
        given:
        def cfg=openAiConfig(); LlmTransportRequest captured
        LlmHttpTransport transport={ LlmTransportRequest req ->
            captured=req
            new LlmTransportResponse(200,['content-type':['application/json']],envelope(emptyOutput()))
        } as LlmHttpTransport

        when:
        def result=new OpenAiCompatibleLlmGateway(cfg.ai,transport,{name -> 'super-secret-value'}).complete(request(cfg))
        Map sent=new JsonSlurper().parseText(new String(captured.body,StandardCharsets.UTF_8)) as Map

        then:
        result.success
        !captured.followRedirects
        captured.endpoint.scheme=='https'
        captured.connectTimeout==Duration.ofSeconds(5)
        captured.requestTimeout==Duration.ofSeconds(30)
        captured.maxResponseBytes==65536
        captured.headers.Authorization=='Bearer super-secret-value'
        !new String(captured.body,StandardCharsets.UTF_8).contains('super-secret-value')
        sent.temperature==0
        sent.max_tokens==300
        sent.response_format.type=='json_schema'
        sent.response_format.json_schema.strict==true
        sent.response_format.json_schema.schema.additionalProperties==false
        !sent.containsKey('tools')
        !sent.containsKey('functions')
    }

    def "sensitive source data is absent from exact transport body errors and metadata audit"() {
        given:
        def cfg=openAiConfig(); LlmTransportRequest captured
        LlmHttpTransport transport={ LlmTransportRequest req ->
            captured=req
            new LlmTransportResponse(200,[:],envelope(emptyOutput()))
        } as LlmHttpTransport
        def gateway=new OpenAiCompatibleLlmGateway(cfg.ai,transport,{name -> 'sk-live-transport-secret'})
        String source='Call alice@example.com at 212-555-0199 via https://secret.example/hook token=sk-source-abcdefghijklmnop'
        def task=Task.builder().id('task-1').content(source).projectId('p').projectName('Work')
            .labels(['schedule']).priority(2).effectiveDuration(Duration.ofMinutes(30)).durationSource('test').build()
        def plan=Plan.builder().id('plan-1').version(1).createdAt(now).mode('preview').tasks([task]).build()
        String eventTitle='Meet carol@example.com at +1 646-555-0111 <@U87654321>'
        String eventDescription='comments token=sk-description-abcdefghijkl attendee=dave@example.com location=https://private.invalid/room'
        def event=CalendarEvent.builder().id('event-1').title(eventTitle).description(eventDescription)
            .calendarName('Private https://calendar.invalid').start(now.plusSeconds(60)).end(now.plusSeconds(120)).build()

        when:
        def result=new AiAssistanceService(cfg.ai,gateway,null,new LlmSchemaValidator(),{now})
            .suggest('task_suggestions','corr-1',plan,[event])
        String body=new String(captured.body,StandardCharsets.UTF_8)
        String audit=JsonOutput.toJson(result.audit.toMap())

        then:
        result.accepted
        result.audit.redactionCount>=4
        !body.contains('alice@example.com')
        !body.contains('212-555-0199')
        !body.contains('secret.example')
        !body.contains('sk-source-')
        !body.contains('sk-live-transport-secret')
        body.contains('task-1')
        body.contains('event-1')
        body.contains('[REDACTED]')
        !body.contains('carol@example.com')
        !body.contains('646-555-0111')
        !body.contains('U87654321')
        !body.contains('sk-description-')
        !body.contains('dave@example.com')
        !body.contains('private.invalid')
        !body.contains('description')
        !body.contains('comments')
        !body.contains('location')
        !audit.contains('alice@example.com')
        !audit.contains('sk-')
    }

    def "adapter classifies rate limits status redirects timeout body cap unsafe tools and malformed envelopes"() {
        given:
        def cfg=openAiConfig(max_response_bytes:1024)
        def responseByCase=[
            rate: new LlmTransportResponse(429,['Retry-After':['17']],new byte[0]),
            redirect: new LlmTransportResponse(302,['Location':['https://evil.invalid']],new byte[0]),
            status: new LlmTransportResponse(503,[:],new byte[0]),
            large: new LlmTransportResponse(200,[:],new byte[1025]),
            tools: new LlmTransportResponse(200,[:],envelope(JsonOutput.toJson([schemaVersion:1,suggestionType:'task_suggestions',correlationId:'corr-1',suggestions:[]]))),
            malformed: new LlmTransportResponse(200,[:],'{broken'.bytes)
        ]
        // Replace the normal assistant message with a tool-call attempt for that case.
        responseByCase.tools=new LlmTransportResponse(200,[:],JsonOutput.toJson([
            choices:[[message:[role:'assistant',content:emptyOutput(),tool_calls:[[id:'x']]]]]]).bytes)

        expect:
        def gateway=new OpenAiCompatibleLlmGateway(cfg.ai,({req -> responseByCase[name]} as LlmHttpTransport),{n -> 'secret'})
        def result=gateway.complete(request(cfg))
        !result.success
        result.error.errorClass==expected
        if(name=='rate') { assert result.error.retryAfter==Duration.ofSeconds(17) }

        where:
        name        | expected
        'rate'      | LlmErrorClass.RATE_LIMITED
        'redirect'  | LlmErrorClass.HTTP_STATUS
        'status'    | LlmErrorClass.HTTP_STATUS
        'large'     | LlmErrorClass.RESPONSE_TOO_LARGE
        'tools'     | LlmErrorClass.UNSAFE_OUTPUT
        'malformed' | LlmErrorClass.MALFORMED_JSON
    }

    def "adapter maps timeout and never leaks exception credentials"() {
        given:
        def cfg=openAiConfig()
        LlmHttpTransport timeout={req -> throw new HttpTimeoutException('secret=sk-abcdefghijklmnop alice@example.com')} as LlmHttpTransport

        when:
        def result=new OpenAiCompatibleLlmGateway(cfg.ai,timeout,{n -> 'sk-live-abcdefghijklmnop'}).complete(request(cfg))

        then:
        result.error.errorClass==LlmErrorClass.TIMEOUT
        !result.error.detail.contains('sk-')
        !result.error.detail.contains('alice@example.com')
    }

    def "configuration rejects insecure endpoints raw secrets unallowlisted hosts and unsafe limits"() {
        when:
        config([enabled:true,provider:'openai_compatible',endpoint:endpoint,model:'m',secret_env:'KEY',allowed_hosts:hosts]+extra)

        then:
        thrown(IllegalArgumentException)

        where:
        endpoint                                      | hosts                | extra
        'http://api.openai.com/v1/chat/completions'   | ['api.openai.com']   | [:]
        'https://evil.invalid/v1/chat/completions'    | ['api.openai.com']   | [:]
        'https://user:pass@api.openai.com/v1/chat'    | ['api.openai.com']   | [:]
        'https://api.openai.com:8443/v1/chat'         | ['api.openai.com']   | [:]
        'https://api.openai.com/v1/chat/completions'  | ['api.openai.com']   | [api_key:'raw-secret']
        'https://api.openai.com/v1/chat/completions'  | ['api.openai.com']   | [request_timeout:'PT10M']
        'https://api.openai.com/v1/chat/completions'  | ['api.openai.com']   | [max_request_bytes:100]
        'https://api.openai.com/v1/chat/completions'  | ['api.openai.com']   | [require_confirmation:false]
        'https://api.openai.com/v1/chat/completions'  | ['api.openai.com']   | [redaction_enabled:false]
    }

    private static File repoFile(String relative) {
        File f=new File('..',relative); f.exists()?f:new File(relative)
    }

    private static Map<String,byte[]> snapshot(Path root) {
        Map<String,byte[]> files=[:]
        Files.walk(root).withCloseable { stream -> stream.filter { Files.isRegularFile(it) }.forEach { path ->
            files[root.relativize(path).toString().replace('\\','/')]=Files.readAllBytes(path)
        }}
        files
    }
}
