# Spec Delta

## Purpose

Defines bounded subscription-backed AI suggestion behavior that retains SmartPlanner's strict schemas,
minimum-data disclosure, deterministic confirmation, and complete separation from mutation authority.

## ADDED Requirements

### Requirement: Subscription gateways are explicit and fail closed
The system SHALL select a provider-specific subscription gateway only for an explicitly configured and acknowledged subscription profile, and SHALL never fall back to an API key, another provider, or another account.

#### Scenario: Subscription profile is ready
- **WHEN** AI is enabled with a compatible authenticated profile and an allowed suggestion type
- **THEN** the request SHALL use only that profile's model, account binding, credential, protocol, and bounded endpoint

#### Scenario: Compatibility acknowledgment is absent
- **WHEN** a profile depends on an experimental subscription protocol or preview public contract
- **THEN** configuration validation SHALL keep it disabled until the operator explicitly acknowledges the experimental compatibility boundary

#### Scenario: Provider protocol drifts
- **WHEN** version, capability, request, response, authentication, or entitlement checks no longer match the tested contract
- **THEN** the gateway SHALL return a classified compatibility failure before accepting output and SHALL not retry through a different transport

### Requirement: Subscription execution preserves the existing AI authority boundary
Subscription-backed AI SHALL receive the same bounded redacted request, return the same versioned suggestion contracts, and have no Todoist, calendar, Slack, planner state, configuration, filesystem, shell, web-search, MCP, or tool execution authority.

#### Scenario: Valid subscription suggestion returns
- **WHEN** a provider returns one response matching the selected strict schema and exact request identities
- **THEN** the existing independent schema and semantic validator SHALL issue the same immutable suggestion bundle and metadata-only audit as the API-key path

#### Scenario: Provider offers coding-agent tools
- **WHEN** the selected provider surface cannot prove tools and ambient local authority are absent from the request
- **THEN** SmartPlanner SHALL refuse to use that surface rather than relying on prompting, a model promise, or a read-only working directory

#### Scenario: Provider returns tool activity or unstructured output
- **WHEN** a response contains tool/function calls, agent events, extra text, missing structured output, duplicate JSON keys, or schema-invalid content
- **THEN** the entire response SHALL be rejected and SHALL cause no plan, decision, delivery, Todoist, or calendar write

### Requirement: Subscription requests are bounded and cancellable
Each request SHALL enforce configured connect, execution, response-size, token/item/string, and wall-clock bounds; cancellation SHALL terminate in-flight work and SHALL not leave a reusable child process or partial accepted result.

#### Scenario: Provider exceeds a bound
- **WHEN** startup, refresh, request, model execution, or output exceeds a configured time/size/token bound
- **THEN** the operation SHALL terminate the bounded transport, classify the failure without raw output, and leave all mutation surfaces unchanged

#### Scenario: Public SIWC response is streamed
- **WHEN** an OpenAI subscription request is sent
- **THEN** it SHALL target exactly `https://api.openai.com/v1/responses`, set `store: false` and `stream: true`, send complete request context in `input`, omit tools and preview-unsupported fields, and succeed only after `response.completed`

#### Scenario: Daemon shuts down during a request
- **WHEN** graceful shutdown begins while subscription inference is active
- **THEN** no new request SHALL start, the active request SHALL receive bounded cancellation/drain, and late output SHALL not be accepted after shutdown

### Requirement: Subscription transport retains minimum disclosure
The gateway SHALL disclose only the existing redacted allowlisted planner context and schema/identity fields needed for one suggestion, and SHALL not expose local credentials or unrelated host/process data.

#### Scenario: Suggestion context is constructed
- **WHEN** the provider request is serialized
- **THEN** it SHALL retain existing item/string/body limits and redaction counts and SHALL exclude credential stores, environment values, state paths, files, logs, and unrelated tasks/events

#### Scenario: Credential is applied
- **WHEN** the authenticated request is sent
- **THEN** the access credential SHALL exist only in the transport authorization boundary and SHALL never be included in model-visible messages, schemas, subprocess arguments, or retained audit data

### Requirement: Provider lifecycle errors are classified consistently
Authentication, entitlement, compatibility, rate-limit, timeout, malformed-output, and transient provider failures SHALL map to bounded safe error classes without automatic replay of a non-idempotent suggestion request.

#### Scenario: Subscription entitlement is exhausted or absent
- **WHEN** the provider reports no eligible subscription allowance, workspace restriction, or usage exhaustion
- **THEN** the result SHALL distinguish entitlement from invalid credentials without exposing account identity and SHALL not switch to separately billed API usage

#### Scenario: Provider rate limits a request
- **WHEN** the provider returns a rate limit and bounded retry metadata
- **THEN** SmartPlanner SHALL return redacted retry metadata and SHALL not automatically resend the suggestion

### Requirement: Subscription compatibility is verified before support
Each subscription provider SHALL have hermetic protocol tests and an operator-authorized disposable live matrix covering login, status, refresh, structured suggestions, daemon use, failure, logout, and cleanup before documentation marks it supported.

#### Scenario: Hermetic provider matrix runs
- **WHEN** automated tests execute without vendor binaries, network, or credentials
- **THEN** fake processes/transports SHALL cover command arguments, environment isolation, device success/denial/timeout, credential parsing, locking, rotation, status, strict output, bounds, cancellation, and secret redaction

#### Scenario: Live compatibility matrix runs
- **WHEN** an operator authorizes disposable Codex and Grok Build subscription testing
- **THEN** the installed distribution SHALL prove both provider flows independently, record pinned CLI/provider versions and non-secret outcomes, clean temporary state, and leave raw credentials outside evidence

#### Scenario: One provider fails live compatibility
- **WHEN** only one subscription provider passes its complete live matrix
- **THEN** only that profile MAY be documented as supported and the failing profile SHALL remain disabled with its limitation recorded
