## ADDED Requirements

### Requirement: Automated provider and daemon tests are hermetic
The automated suite SHALL perform no live provider calls, open no public inbound port, require no real credentials, and use checked-in fake Todoist JSON, CalDAV XML/ICS, Weather JSON, Slack Socket Mode/Web API payloads, and LLM JSON through WireMock, fake surfaces, or injected transports.

#### Scenario: Suite runs offline
- **WHEN** real provider hosts and credentials are unavailable
- **THEN** every test SHALL execute without skip against local/in-memory boundaries and SHALL prove no attempted real-host connection

### Requirement: Todoist and CalDAV contracts remain fully verified
WireMock tests SHALL verify Todoist authenticated pagination/read probes/due-only writes and CalDAV authenticated range/UID/GET/managed PUT/DELETE contracts, including limits, timeout, rate limit, failures, ownership, collision, ambiguity, and secret redaction.

#### Scenario: Startup probes succeed
- **WHEN** the daemon performs bounded Todoist and CalDAV startup probes
- **THEN** WireMock SHALL observe authenticated read-only requests and zero writes before readiness

#### Scenario: Startup authentication fails
- **WHEN** either required provider returns an authentication/authorization failure
- **THEN** startup SHALL classify it fatal, redact credentials, and issue no planner write or Slack-ready message

#### Scenario: Deadline mutation is attempted
- **WHEN** any daemon or feedback path attempts a Todoist deadline update
- **THEN** the gateway SHALL refuse before WireMock receives a request

#### Scenario: Write result is ambiguous
- **WHEN** a provider may have accepted a request but the response is lost
- **THEN** state SHALL become reconciliation-required and later daemon cycles SHALL issue no blind resend

### Requirement: Slack Socket Mode lifecycle is verified
Tests SHALL cover outbound-only Socket Mode listener registration, separate app/bot credentials, prompt acknowledgement, normalized slash command/app mention/thread message events, reconnect/disconnect behavior, callback isolation, bounds, filtering, and durable deduplication without connecting to Slack.

#### Scenario: Envelope is received
- **WHEN** a fake Socket Mode command or event envelope arrives
- **THEN** the adapter SHALL acknowledge within the callback before enqueueing normalized work and SHALL not run planning inline

#### Scenario: Duplicate event is redelivered
- **WHEN** Slack repeats the same envelope/event ID or channel/message timestamp after reconnect/restart
- **THEN** exactly one normalized work item/reply/decision SHALL be accepted

#### Scenario: Socket disconnect occurs
- **WHEN** the client disconnects or receives a refresh signal
- **THEN** it SHALL reconnect with bounded backoff and the daemon SHALL remain alive

#### Scenario: Bot or unrelated message arrives
- **WHEN** an event is from SmartPlanner itself, another channel, outside a correlated thread, edited/deleted unsupported content, or oversized/malformed
- **THEN** it SHALL be ignored or classified safely with no decision, replan, apply, or secret leak

### Requirement: Slack Web API parent, thread, and status contracts are verified
WireMock tests SHALL verify `chat.postMessage` proposal parents and replies plus `assistant.threads.setStatus` set/clear calls, with bearer auth, exact channel/thread/body fields, bounded content, strict success parsing, rate limits, timeouts, errors, redaction, and ambiguity handling.

#### Scenario: Proposal parent is published
- **WHEN** a plan is ready for proposal
- **THEN** WireMock SHALL observe `chat.postMessage` with the configured channel and no `thread_ts`, and returned channel/`ts` SHALL be persisted as conversation root

#### Scenario: Iteration reply is published
- **WHEN** feedback produces a new plan iteration
- **THEN** WireMock SHALL observe `chat.postMessage` with the original root `thread_ts` and bounded new plan identity/diff

#### Scenario: Working status is set and cleared
- **WHEN** Slack-requested work starts and completes
- **THEN** WireMock SHALL observe `assistant.threads.setStatus` with exact channel/root/status and then either a result reply or an empty-status clear

#### Scenario: Status call fails
- **WHEN** the status API returns provider error, 429, malformed body, oversized body, or timeout
- **THEN** work authorization/outcome SHALL remain unchanged and the daemon SHALL continue

### Requirement: Regex, LLM, and override paths are verified
Tests SHALL cover ordered regex validation/matching, captures, supported actions, authorization, unmatched behavior, bounded LLM interpretation, explicit confirmation, override validation/expiry, and zero direct LLM mutation.

#### Scenario: First regex wins
- **WHEN** multiple rules could match an authorized message
- **THEN** only the first configured rule SHALL produce one action and one durable event result

#### Scenario: LLM interpretation requires confirmation
- **WHEN** unmatched feedback yields a valid override suggestion
- **THEN** tests SHALL observe a confirmation reply and zero replanning/provider writes until a current-plan deterministic confirmation arrives

#### Scenario: Override is applied to replan only
- **WHEN** a valid confirmed temporary override is used
- **THEN** the new plan/explanations SHALL reflect it while fixture Todoist source tasks and permanent config remain unchanged

### Requirement: Daemon orchestration is deterministically verified
The suite SHALL use injected clock/scheduler/executor/shutdown and fake messaging/provider ports to test multiple horizons, triggers, coalescing, conversations, iterations, application, resilience, restart, and shutdown without wall-clock sleeps.

#### Scenario: Multiple horizon timeline runs
- **WHEN** virtual time advances across configured due times
- **THEN** each run SHALL execute only when due with its own range, no same-run overlap, deterministic prior-plan selection, persisted proposal correlation, and predictable next-run state

#### Scenario: Cycle fails and later succeeds
- **WHEN** an injected transient provider/planning/messaging failure occurs
- **THEN** the daemon SHALL record it, remain alive, retry according to policy, and later process other work successfully

#### Scenario: Threaded approve/reject/replan matrix runs
- **WHEN** fake Slack events exercise approval, rejection, regex replan, LLM-confirmed replan, stale approval, unauthorized actor, and duplicate delivery
- **THEN** exact decisions, new plan lineage, replies, provider writes/zero-writes, receipts, and dedupe state SHALL match each contract

#### Scenario: Process restarts
- **WHEN** the daemon is reconstructed with existing temporary state
- **THEN** conversations, event dedupe, plan lineage, decisions, deliveries, and reconciliation barriers SHALL remain effective

### Requirement: Build and distribution verification is inspectable
Gradle tests SHALL emit JUnit XML and HTML. The acceptance gate SHALL include focused tests, `:app:test --rerun-tasks`, `build`, `installDist`, installed help, hermetic one-shot preview, and bounded daemon smoke tests.

#### Scenario: Acceptance gate passes
- **WHEN** the documented clean verification gate runs
- **THEN** reports SHALL identify individual tests, the distribution SHALL include Slack runtime dependencies/manifest/docs, and the installed application SHALL expose and successfully smoke-test `planner-daemon`
