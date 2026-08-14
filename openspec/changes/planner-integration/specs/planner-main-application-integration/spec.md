## ADDED Requirements

### Requirement: Existing application exposes planner operations
The installed `TodoistCalDavSync` application SHALL expose explicit operations for legacy sync, capacity reporting, preview generation, stored-plan application, safe-only application, message delivery, feedback capture, decision application, and bounded AI suggestions. Planner operations SHALL be composed through a production composition root rather than embedding provider access into deterministic services.

#### Scenario: Legacy operation remains default
- **WHEN** the application is invoked without an explicit planner operation using a valid legacy configuration
- **THEN** it SHALL execute the existing legacy sync behavior without requiring planner integration configuration

#### Scenario: Planner operation uses production composition
- **WHEN** an operator invokes a planner operation with valid planner integration configuration
- **THEN** the application SHALL assemble the configured adapters and durable stores and execute only the selected operation

### Requirement: Preview is remotely read-only
The application SHALL allow capacity and preview operations to read live Todoist and calendar data and persist local plan artifacts, but SHALL NOT mutate Todoist, CalDAV, Slack, or an LLM provider as a consequence of planning.

#### Scenario: Preview creates a plan without remote writes
- **WHEN** the operator runs preview for explicit start and end instants
- **THEN** the application SHALL persist and return a deterministic plan while issuing no Todoist or CalDAV write request

#### Scenario: Optional providers remain opt-in
- **WHEN** weather, Slack, or AI is disabled
- **THEN** the composition root SHALL NOT construct or call the corresponding remote adapter

### Requirement: Application authority follows fail-closed modes
The application SHALL enforce `preview`, `approval_required`, and `apply_safe_changes` semantics at the final write boundary. `fully_automated` SHALL remain unsupported and SHALL produce zero remote writes.

#### Scenario: Preview apply is skipped
- **WHEN** apply is requested for a plan configured in `preview` mode
- **THEN** the application SHALL produce a skipped/refused receipt and perform zero remote writes

#### Scenario: Exact approval authorizes approval-required plan
- **WHEN** apply is requested in `approval_required` mode with an approval matching the stored plan ID, version, and semantic hash
- **THEN** the application SHALL apply only the authorized plan through guarded write gateways and persist an itemized receipt

#### Scenario: Missing or stale approval is refused
- **WHEN** approval is missing or any plan-bound value differs
- **THEN** the application SHALL persist a refusal and perform zero remote writes

#### Scenario: Safe-only mode withholds protected work
- **WHEN** safe application encounters frozen, manually moved, drifted, protected, or approval-required changes without exact approval
- **THEN** it SHALL apply only ordinary eligible changes and list each withheld change in the receipt

#### Scenario: Fully automated mode is refused
- **WHEN** apply or safe apply is requested with `fully_automated`
- **THEN** the application SHALL fail closed with zero Todoist and CalDAV writes

### Requirement: Provider writes preserve ownership and task semantics
The production integration SHALL write calendar resources only to the configured managed output calendar using deterministic planner ownership metadata. It SHALL update Todoist due datetime only and SHALL NOT change Todoist deadlines.

#### Scenario: Managed event is applied
- **WHEN** an authorized scheduled block is applied without collision or drift
- **THEN** the application SHALL upsert a managed event with deterministic UID and ownership marker and update the task due datetime to the block start

#### Scenario: UID collision outside managed calendar is detected
- **WHEN** the deterministic UID exists in another configured calendar
- **THEN** the application SHALL refuse the affected write and record a collision without deleting or overwriting the external event

#### Scenario: Deadline mutation is requested
- **WHEN** any integration path attempts to update a Todoist deadline
- **THEN** the Todoist write boundary SHALL refuse the operation before an HTTP request is sent

### Requirement: Feedback, Slack, and LLM retain separate authority boundaries
Slack delivery, structured feedback, and LLM suggestions SHALL be explicit operations. Feedback capture SHALL persist a decision but SHALL NOT apply it in the same operation. AI output SHALL have no plan, state, Todoist, calendar, or messaging mutation port.

#### Scenario: Authorized feedback is captured without applying
- **WHEN** an allowlisted actor submits valid plan-bound feedback
- **THEN** the application SHALL persist the structured decision and perform zero planning writes until a separate decision-apply operation

#### Scenario: Unauthorized feedback is denied
- **WHEN** an actor is absent from the exact configured allowlist
- **THEN** the application SHALL reject the command without persisting an authorizing decision or applying changes

#### Scenario: LLM suggestion returns bounded output only
- **WHEN** AI is enabled and an explicit suggestion operation succeeds
- **THEN** the application SHALL return validated suggestions and metadata without modifying a plan or remote system

### Requirement: Production configuration is explicit and secret-safe
Planner production operations SHALL require explicit Todoist endpoint/token environment-variable name, configured CalDAV calendars and auth environment-variable names, and independent plan/application/decision/delivery state paths. Inline production secrets SHALL be rejected, relative state paths SHALL resolve from the config file directory, and invalid configuration SHALL fail before remote mutation.

#### Scenario: Required integration value is absent
- **WHEN** a planner production operation loads configuration missing a required endpoint, calendar, credential reference, or state path
- **THEN** startup SHALL fail with an actionable secret-free error before a provider call

#### Scenario: Inline secret is present
- **WHEN** a production credential value is embedded directly in planner integration configuration
- **THEN** configuration validation SHALL reject it and direct the operator to an environment-variable reference

### Requirement: Durable state supports safe recovery
Plans, applications/mappings, decisions, and deliveries SHALL use explicit durable stores. Repeated operations SHALL be idempotent where the provider outcome is known; ambiguous provider success SHALL be recorded for reconciliation and SHALL NOT be blindly retried.

#### Scenario: Exact apply is repeated
- **WHEN** an already successful plan application is invoked again against unchanged provider state
- **THEN** the application SHALL produce no duplicate managed event and no unnecessary Todoist write

#### Scenario: Provider result is ambiguous
- **WHEN** a write may have succeeded but its response or local receipt persistence is uncertain
- **THEN** the application SHALL record an unknown/reconciliation-required outcome and prevent an automatic blind resend
