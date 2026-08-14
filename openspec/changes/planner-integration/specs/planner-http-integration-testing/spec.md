## ADDED Requirements

### Requirement: Automated provider tests are hermetic and fixture-driven
The automated suite SHALL perform no live provider calls and SHALL require no real credentials. Representative Todoist JSON, CalDAV XML/ICS, Open-Meteo JSON, Slack JSON, and LLM JSON SHALL be checked-in fixtures or explicit test bodies with fake credentials.

#### Scenario: Test suite runs offline from production services
- **WHEN** the integration test suite runs with network access to real provider hosts unavailable
- **THEN** all tests SHALL execute against WireMock or in-memory gateways and SHALL not skip for missing production credentials

### Requirement: Todoist HTTP contract is verified
WireMock tests SHALL verify Todoist bearer authentication, paginated task/project reads, request limits/cursors, normalization-relevant response fields, due-datetime-only writes, bounded retries for safe reads, and non-retry or reconciliation behavior for writes.

#### Scenario: Paginated task and project read
- **WHEN** Todoist returns multiple task pages and project data
- **THEN** the adapter SHALL request the expected cursor sequence with bearer auth and return tasks enriched with project names

#### Scenario: Due datetime update
- **WHEN** the application updates a task after an authorized apply
- **THEN** WireMock SHALL observe only the documented due-datetime field and SHALL not observe a deadline field

#### Scenario: Deadline update is refused locally
- **WHEN** the deadline-write method is invoked
- **THEN** the adapter SHALL throw before WireMock receives a Todoist write

#### Scenario: Todoist failure is bounded
- **WHEN** Todoist returns retryable errors, rate limits, malformed data, timeout, or oversized data
- **THEN** the adapter SHALL follow its bounded retry/body/time policy and return a classified secret-free failure

### Requirement: CalDAV HTTP contract is verified
WireMock tests SHALL verify range REPORT, UID REPORT across every configured calendar, GET of live resources, managed PUT, ownership-checked DELETE, authentication, XML/ICS parsing, calendar-name preservation, collision detection, and classified failures.

#### Scenario: Calendar events are read
- **WHEN** a configured calendar returns a CalDAV multistatus with calendar data
- **THEN** the adapter SHALL issue the expected REPORT and normalize each event with its configured calendar name

#### Scenario: UID search spans all calendars
- **WHEN** the application checks a planner UID before mutation
- **THEN** the adapter SHALL query every configured calendar and SHALL reject duplicate/cross-calendar matches

#### Scenario: Managed event is written
- **WHEN** an owned event is upserted to the configured output calendar
- **THEN** WireMock SHALL observe the deterministic resource path, expected auth/content type, UID, ownership marker, and exact UTC time fields

#### Scenario: Delete validates live ownership
- **WHEN** an event deletion is requested
- **THEN** the adapter SHALL re-read the live resource and SHALL send DELETE only when calendar, UID, ownership marker, and expected block metadata match

#### Scenario: CalDAV write outcome is ambiguous
- **WHEN** a PUT or DELETE connection fails after request transmission
- **THEN** the adapter/orchestrator SHALL classify the item as needing reconciliation rather than blindly resending

### Requirement: Optional provider HTTP contracts are verified
WireMock tests SHALL cover Open-Meteo, Slack webhook, Slack chat API, and OpenAI-compatible HTTP boundaries, including disabled behavior, authentication, request shape, response parsing, redaction, bounded sizes/timeouts, and classified failure.

#### Scenario: Weather forecast succeeds and fails safely
- **WHEN** Open-Meteo returns valid hourly forecast fixture data
- **THEN** the gateway SHALL normalize it for the configured location/timezone; malformed, stale, oversized, or failed responses SHALL follow the configured fail-open/fail-closed policy

#### Scenario: Slack webhook and chat API send
- **WHEN** delivery is explicitly due and Slack is enabled
- **THEN** WireMock SHALL observe exactly one request in the configured mode with expected destination/body/auth and no persisted raw secret

#### Scenario: Slack ambiguous delivery is not blindly resent
- **WHEN** provider send may have succeeded but the response or ledger update is uncertain
- **THEN** delivery SHALL be recorded as unknown/reconciliation-required and a repeated operation SHALL not issue a blind duplicate

#### Scenario: LLM endpoint enforces boundary controls
- **WHEN** an explicit AI suggestion is sent to an allowed WireMock host
- **THEN** the test SHALL verify redacted bounded context, authentication, strict structured-output request, deterministic model settings, redirect refusal, schema validation, and zero mutation side effects

### Requirement: Orchestration and CLI integration behavior is verified
The suite SHALL test the production composition seam and main command dispatcher in addition to individual adapters. It SHALL cover operation-specific required arguments, exit codes, preview no-write, exact approvals, safe-only application, feedback separation, disabled options, idempotency, failure receipts, and automatic-mode refusal.

#### Scenario: Full hermetic preview-to-safe flow
- **WHEN** fixture tasks/events are previewed and then applied through each supported mode using injected test gateways
- **THEN** assertions SHALL identify every allowed, refused, or withheld Todoist/calendar mutation and durable artifact

#### Scenario: CLI rejects unsafe or incomplete invocation
- **WHEN** required bounds, plan identifiers, approvals, actors, correlation identifiers, or enabled-provider configuration are absent or invalid
- **THEN** the command SHALL return a nonzero code, a secret-free actionable error, and zero unintended writes

### Requirement: Verification commands publish inspectable test results
The project SHALL configure Gradle tests to produce JUnit XML and HTML reports, and the documented acceptance gate SHALL include focused tests, full tests, build, distribution creation, and an installed-launcher smoke test.

#### Scenario: Acceptance gate passes
- **WHEN** the documented automated gate is executed from a clean worktree
- **THEN** all tests and build tasks SHALL pass, reports SHALL identify individual test cases, and the installed distribution SHALL expose the documented operations
