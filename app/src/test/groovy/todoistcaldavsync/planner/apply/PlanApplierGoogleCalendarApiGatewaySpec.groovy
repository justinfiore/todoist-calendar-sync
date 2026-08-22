package todoistcaldavsync.planner.apply

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import spock.lang.Specification
import todoistcaldavsync.planner.CalendarProviderConfig
import todoistcaldavsync.planner.adapters.GoogleCalendarApiGateway
import todoistcaldavsync.planner.adapters.InMemoryTodoistGateway
import todoistcaldavsync.planner.adapters.ManagedCalendarWriteGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.ApplyItemStatus
import todoistcaldavsync.planner.domain.Approval
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.state.ApplicationStateStore

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class PlanApplierGoogleCalendarApiGatewaySpec extends Specification {
    static final String MANAGED = 'Todoist Planned'
    WireMockServer server
    Path dir
    InMemoryTodoistGateway todoist
    ApplicationStateStore stateStore

    def setup() {
        server = new WireMockServer(options().dynamicPort())
        server.start()
        dir = Files.createTempDirectory('plan-applier-google-')
        todoist = new InMemoryTodoistGateway([
            [id: 't1', content: 'Task One', priority: 2,
             deadline: [date: '2026-08-20'], due: [date: '2026-08-01T10:00:00Z']]
        ])
        stateStore = new ApplicationStateStore(dir)
    }

    def cleanup() {
        server?.stop()
        dir?.toFile()?.deleteDir()
    }

    def "approved apply writes only the managed Google calendar and never mutates Todoist deadlines"() {
        given:
        Instant start = Instant.parse('2026-08-10T14:00:00Z')
        Plan plan = planWith(start)
        String uid = ManagedEventIds.uidForBlock('b1')
        stubUidLookup('output-id', uid, [])
        stubUidLookup('blocker/id', uid, [])
        server.stubFor(post(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .willReturn(okJson(JsonOutput.toJson(googleEvent('new-live-id', uid, start)))))

        when:
        def receipt = applier().apply(plan, approvalFor(plan))

        then:
        receipt.overallStatus == ApplyItemStatus.APPLIED
        todoist.deadlineOf('t1') == '2026-08-20'
        todoist.deadlineUpdates.empty
        todoist.dueUpdates.size() == 1
        !todoist.dueUpdates[0].hasDeadlineField
        server.verify(1, postRequestedFor(urlPathEqualTo('/calendar/v3/calendars/output-id/events'))
            .withRequestBody(notMatching('(?s).*"iCalUID".*'))
            .withRequestBody(matchingJsonPath('$.extendedProperties.private.plannerUid', equalTo(uid))))
        server.verify(0, postRequestedFor(urlPathMatching('/calendar/v3/calendars/blocker.*')))
        server.verify(0, putRequestedFor(anyUrl()))
        server.verify(0, deleteRequestedFor(anyUrl()))
    }

    def "Google apply seam refuses a cross-calendar write before mutation"() {
        given:
        Instant start = Instant.parse('2026-08-10T14:00:00Z')
        Plan plan = planWith(start)
        def write = new GoogleCalendarApiGateway(gatewayOptions())

        when:
        write.upsertEvent(todoistcaldavsync.planner.domain.CalendarEvent.builder()
            .id(ManagedEventIds.uidForBlock('b1'))
            .uid(ManagedEventIds.uidForBlock('b1'))
            .title('Wrong calendar')
            .description(ManagedEventIds.buildDescription('b1', plan.id))
            .calendarName('Work Blockers')
            .start(start)
            .end(start.plusSeconds(1800))
            .build())

        then:
        thrown(IllegalStateException)
        server.allServeEvents.empty
        todoist.deadlineUpdates.empty
        todoist.dueUpdates.empty
    }

    private PlanApplier applier() {
        def google = new GoogleCalendarApiGateway(gatewayOptions())
        PlannerConfig config = PlannerConfig.fromMap(planner: [
            mode: 'approval_required',
            timezone: 'America/New_York',
            output_calendar: MANAGED,
            availability: [
                working_windows: [weekday: ['09:00-17:00']],
                calendars: [[calendar: MANAGED, default_role: 'managed_output']]
            ],
            stability: [keep_manual_moves: true]
        ])
        new PlanApplier(config, new ManagedCalendarWriteGateway(google, google, MANAGED),
            google, todoist, todoist, stateStore, { Instant.parse('2026-08-07T15:00:00Z') })
    }

    private Map gatewayOptions() {
        [
            config: new CalendarProviderConfig.GoogleCalendarApiConfig(
                Path.of('/ignored/client.json'), Path.of('/ignored/token'), Path.of('/ignored/qa-token'),
                'owner@example.test', 8787, [
                    [name: MANAGED, id: 'output-id', role: 'managed_output'],
                    [name: 'Work Blockers', id: 'blocker/id', role: 'hard_blocker']
                ]),
            managedCalendarName: MANAGED,
            timezone: ZoneId.of('America/New_York'),
            baseUrl: "http://localhost:${server.port()}/calendar/v3",
            allowInsecureHttp: true,
            accessTokenSupplier: { 'access-token' },
            timeout: Duration.ofSeconds(2),
            maxPagesPerCalendar: 3,
            maxResultsPerPage: 50,
            maxEventsPerCalendar: 100,
            maxResponseBytes: 65_536,
            readAttempts: 1
        ]
    }

    private void stubUidLookup(String calendarId, String uid, List<Map> items) {
        String encoded = java.net.URLEncoder.encode(calendarId, java.nio.charset.StandardCharsets.UTF_8).replace('+', '%20')
        server.stubFor(get(urlPathEqualTo("/calendar/v3/calendars/${encoded}/events"))
            .withQueryParam('iCalUID', equalTo(uid))
            .willReturn(okJson(JsonOutput.toJson([items: items]))))
        server.stubFor(get(urlPathEqualTo("/calendar/v3/calendars/${encoded}/events"))
            .withQueryParam('privateExtendedProperty', equalTo("plannerUid=${uid}"))
            .willReturn(okJson(JsonOutput.toJson([items: items]))))
    }

    private static Map googleEvent(String id, String uid, Instant start) {
        [id: id, iCalUID: 'google-generated-uid', summary: 'Block b1',
         description: ManagedEventIds.buildDescription('b1', 'plan-google-1'),
         extendedProperties: [private: [plannerUid: uid, blockId: 'b1']],
         start: [dateTime: start.toString()], end: [dateTime: start.plusSeconds(1800).toString()]]
    }

    private static Plan planWith(Instant start) {
        Plan.builder()
            .id('plan-google-1')
            .version(1)
            .createdAt(Instant.parse('2026-08-07T12:00:00Z'))
            .mode('approval_required')
            .tasks([Task.builder().id('t1').content('Task One').priority(2)
                .effectiveDuration(Duration.ofMinutes(30)).durationSource('test')
                .deadline(Instant.parse('2026-08-20T23:59:59Z')).build()])
            .scheduledBlocks([ScheduledBlock.builder().id('b1').start(start)
                .end(start.plusSeconds(1800)).taskIds(['t1']).title('Block b1').reason('test').build()])
            .build()
    }

    private static Approval approvalFor(Plan plan) {
        Approval.builder().id('appr-1').planId(plan.id).planVersion(plan.version)
            .planHash(PlanHash.compute(plan)).approvedAt(Instant.parse('2026-08-07T14:00:00Z'))
            .approvedBy('jorsten').build()
    }
}
