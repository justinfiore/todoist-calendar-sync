## ADDED Requirements

### Requirement: Manual testing begins with isolated provider and Slack data
The end-to-end guide SHALL require dedicated test Todoist project/labels, test calendars, and a test Slack channel/App installation using least-privilege credentials before production data is configured. It SHALL include backup/export and representative planning/feedback data.

#### Scenario: Isolated environment is prepared
- **WHEN** an operator follows setup
- **THEN** production task/calendar targets SHALL be absent, the Slack App SHALL use Socket Mode with no public Request URL, the default app name SHALL be SmartPlanner unless customized, and test actors/channel SHALL be explicitly allowlisted

### Requirement: Slack App registration is checklist-gated
The guide SHALL provide a configurable manifest-based app creation procedure, app-level `connections:write` token, bot token/scopes, event subscriptions, `/smartplanner` command, channel installation/invite, environment variables, and validation without printing secrets.

#### Scenario: App is registered from the template
- **WHEN** the operator uses the manifest unchanged
- **THEN** Slack SHALL show app and bot display name `SmartPlanner`, Socket Mode enabled, the documented command, and documented scopes/events

#### Scenario: Operator customizes app name
- **WHEN** the operator edits the manifest/app-name configuration before registration
- **THEN** setup and status-message expectations SHALL use the chosen registered name without requiring code changes

### Requirement: Daemon validation has explicit lifecycle gates
The guide SHALL specify commands, expected logs/state/Slack artifacts, and pass/fail criteria for startup configuration/provider probes, multiple horizon schedules, proposals, commands, thread feedback, iteration, apply, retries, restart, and graceful shutdown.

#### Scenario: Isolated daemon starts
- **WHEN** valid test credentials/configuration are used
- **THEN** startup probes SHALL succeed, readiness SHALL be visible, each run-on-startup horizon SHALL publish one parent proposal, and the process SHALL remain alive

#### Scenario: Non-fatal failure occurs
- **WHEN** test Slack/Weather/LLM or a transient planning cycle fails
- **THEN** the guide SHALL require evidence that the failure is classified/recorded, later work still executes, and the daemon remains alive

#### Scenario: Fatal startup condition occurs
- **WHEN** configuration is invalid or required Todoist/CalDAV authentication/connectivity fails
- **THEN** the daemon SHALL exit nonzero before readiness and the guide SHALL distinguish remediation from normal retry

### Requirement: Threaded interaction is manually verifiable
The guide SHALL test commands, proposal parent/thread correlation, working status, deterministic regex acknowledgement/rejection/replan, optional LLM feedback confirmation, repeated iterations, exact newest-plan approval, stale approval refusal, and actor/channel denial.

#### Scenario: User initiates planning from Slack
- **WHEN** an allowlisted user runs `/smartplanner plan daily`
- **THEN** Slack SHALL acknowledge promptly, show SmartPlanner working status in the associated thread, and produce a correlated proposal/result without blocking or opening an inbound port

#### Scenario: Feedback iterates in a thread
- **WHEN** the user provides replan feedback and confirms any required override
- **THEN** a new linked proposal/diff SHALL appear in the same thread and approval of the old iteration SHALL refuse writes

#### Scenario: Rejection is posted
- **WHEN** a configured rejection phrase is posted in the proposal thread
- **THEN** SmartPlanner SHALL acknowledge rejection and perform zero Todoist/CalDAV writes

### Requirement: Production rollout remains crawl, walk, run
The production procedure SHALL advance continuously running SmartPlanner through `preview`, `approval_required`, and `apply_safe_changes` only after explicit acceptance gates. `fully_automated` SHALL remain unavailable.

#### Scenario: Crawl uses daemon preview
- **WHEN** production testing begins
- **THEN** the operator SHALL back up data/state, use narrow/slow horizon schedules, enable proposal messaging, verify repeated stable proposals/thread handling, and confirm zero Todoist/CalDAV writes

#### Scenario: Walk uses exact threaded approval
- **WHEN** crawl gates pass
- **THEN** the operator SHALL grant minimal write permissions, generate a fresh plan, test missing/stale/rejected cases, approve one small current plan in-thread, and inspect deadline invariance, resources, receipts, idempotency, and daemon survival

#### Scenario: Run uses safe-only application
- **WHEN** walk gates pass cleanly
- **THEN** the operator SHALL enable safe-only application with short horizons, review receipts/conversations daily, keep protected changes withheld, and expand intervals/horizons only after multiple clean cycles

### Requirement: Recovery and rollback are executable
The guide SHALL identify service stop/start commands, backup scope for all durable stores including conversations/dedupe, evidence retention, remote comparison/restoration, state restore as one consistency unit, Slack disable/reconnect procedure, and prohibition on selective deletion/blind retry.

#### Scenario: Ambiguous write or corrupted conversation occurs
- **WHEN** a provider outcome or thread identity cannot be reconciled
- **THEN** the operator SHALL stop new work, retain logs/receipts/state, compare live provider/Slack artifacts, restore a matching complete snapshot as necessary, restart in preview, and revalidate before advancement

### Requirement: Live verification remains explicitly operator-owned
Automated verification SHALL not be represented as live Slack/Todoist/CalDAV success. Any unavailable live credential/workspace gate SHALL remain unchecked with exact procedure and expected evidence.

#### Scenario: Live credentials are unavailable during implementation
- **WHEN** repository implementation and hermetic tests pass without operator test accounts
- **THEN** the OpenSpec task SHALL remain open and the handoff SHALL state the blocker rather than claim live daemon operation
