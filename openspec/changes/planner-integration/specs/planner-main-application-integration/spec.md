## ADDED Requirements

### Requirement: SmartPlanner has a long-running production lifecycle
The installed application SHALL expose `planner-daemon` as the primary SmartPlanner production operation. The daemon SHALL remain running until graceful shutdown or a classified fatal condition, while legacy sync and one-shot planner operations remain available.

#### Scenario: Daemon starts and blocks
- **WHEN** the application is invoked with `--operation planner-daemon` and valid production configuration
- **THEN** it SHALL validate startup prerequisites, start inbound messaging and planning schedules, and remain alive rather than exit after one operation

#### Scenario: Legacy sync remains compatible
- **WHEN** the application is invoked without an operation using valid legacy configuration
- **THEN** it SHALL execute the existing long-running legacy sync without requiring planner integration configuration

#### Scenario: One-shot diagnostics remain available
- **WHEN** an operator invokes capacity, preview, apply, safe apply, delivery, feedback, decision apply, or AI suggestion explicitly
- **THEN** the application SHALL execute that bounded operation with the same authority and safety rules and then exit

### Requirement: Multiple planning horizons are independently configurable
The daemon SHALL schedule one or more uniquely named planning runs. Each run SHALL have a positive configured planning horizon and periodicity, optional initial delay/run-on-startup behavior, bounded values, deterministic time-zone handling, and no overlapping execution for the same run.

#### Scenario: Multiple horizons become due
- **WHEN** daily, weekly, and medium runs have different configured horizons and intervals
- **THEN** each SHALL plan `[current time, current time plus its own horizon)` at its own due time and persist run identity with the plan

#### Scenario: Same run is triggered while active
- **WHEN** a periodic or Slack trigger arrives for a run already active or queued
- **THEN** the daemon SHALL coalesce duplicate work to at most one pending trigger rather than overlap provider/state mutations

#### Scenario: Schedule configuration is invalid
- **WHEN** a run name is blank/duplicate or a horizon/interval/delay is malformed, nonpositive, or outside documented bounds
- **THEN** startup SHALL fail before provider calls or background threads begin

### Requirement: Every scheduled plan is proposed through the Messaging Surface
After a successful planning run, the daemon SHALL persist the immutable plan and publish a proposal parent message to the configured channel. It SHALL durably correlate the returned provider channel and root thread identifier with exact plan/proposal identity.

#### Scenario: Scheduled plan succeeds
- **WHEN** a configured run produces a plan
- **THEN** SmartPlanner SHALL publish a channel parent containing bounded summary/diff/risk/unscheduled information and persist channel, thread, run, plan ID/version/hash, proposal ID, and iteration

#### Scenario: Proposal publication fails
- **WHEN** planning succeeds but the Messaging Surface cannot confirm publication
- **THEN** the plan SHALL remain persisted, delivery/conversation state SHALL record failed or unknown outcome, and the daemon SHALL not invent a thread identity or terminate for an optional Slack failure

### Requirement: Proposal conversations are thread-scoped and iterative
The daemon SHALL accept proposal-specific feedback only from the configured channel and the proposal's correlated root thread. It SHALL support multiple replies and replanning iterations in the same thread, with each iteration producing a new immutable exact plan identity.

#### Scenario: Authorized feedback arrives in proposal thread
- **WHEN** an allowed actor sends a non-bot message whose channel and root thread match an active proposal
- **THEN** SmartPlanner SHALL deduplicate, parse, audit, and process it against the conversation's current plan identity

#### Scenario: Message is outside the proposal thread
- **WHEN** a message is top-level chatter, in another channel/thread, from the bot itself, or not correlated to an active proposal
- **THEN** it SHALL not create a decision, replan, or provider write

#### Scenario: Feedback causes replan
- **WHEN** authorized current-plan feedback resolves to a confirmed replan action with valid temporary overrides
- **THEN** SmartPlanner SHALL generate a linked immutable plan, post its diff as a reply in the same thread, increment iteration, and make only the new identity eligible for later approval

#### Scenario: Old iteration is approved
- **WHEN** approval names an earlier plan/version/hash after a newer iteration exists
- **THEN** it SHALL be refused as stale with zero Todoist/CalDAV writes

### Requirement: Feedback parsing is configurable and fail closed
The daemon SHALL evaluate ordered configured Java regular-expression rules before optional LLM interpretation. Rules SHALL be bounded and validated at startup, first match SHALL win, supported actions SHALL be explicit, actor/channel/thread authorization SHALL precede action, and unmatched feedback SHALL not imply approval.

#### Scenario: Configured approval regex matches
- **WHEN** an authorized thread reply matches the first `approve` rule
- **THEN** SmartPlanner SHALL create an approval/decision bound to the current exact plan identity and apply only if the configured mode permits it

#### Scenario: Configured rejection regex matches
- **WHEN** an authorized reply matches `reject`
- **THEN** SmartPlanner SHALL persist rejection and post acknowledgement with zero Todoist/CalDAV writes

#### Scenario: Replan regex captures feedback
- **WHEN** an authorized reply matches `replan` and includes bounded feedback or a configured override template
- **THEN** SmartPlanner SHALL persist the interpreted request and replan only after any required confirmation

#### Scenario: Regex is invalid or action is unsupported
- **WHEN** configuration contains an uncompilable, duplicate/blank, overlong, or unsupported feedback rule
- **THEN** startup SHALL fail with an actionable secret-free rule path

#### Scenario: Unmatched text arrives with AI disabled
- **WHEN** no rule matches and AI is disabled
- **THEN** SmartPlanner SHALL post supported command guidance and perform no replan/apply

### Requirement: LLM feedback remains bounded and confirmable
When AI is enabled, unmatched authorized thread feedback MAY be sent through the existing redacted, bounded, schema-validated conversational interpretation and temporary-override contracts. LLM output SHALL have no direct plan/state/provider mutation authority and SHALL require explicit deterministic confirmation bound to the current conversation identity.

#### Scenario: LLM proposes an override
- **WHEN** valid unmatched feedback yields a schema-valid temporary override suggestion
- **THEN** SmartPlanner SHALL post a bounded confirmation summary and persist only suggestion/audit state until an authorized confirmation is received

#### Scenario: LLM fails or returns invalid output
- **WHEN** the provider times out, rejects, exceeds bounds, or returns malformed/schema-invalid output
- **THEN** the event SHALL be recorded/replied to safely, no replan/apply SHALL occur, and the daemon SHALL remain alive

### Requirement: Temporary overrides are bounded and non-destructive
Replanning overrides SHALL be persisted with source conversation/event, exact plan binding, expiration, target run/tasks, and validated bounded fields. They SHALL affect only the new planning input and SHALL NOT rewrite Todoist source tasks or permanent configuration.

#### Scenario: Confirmed override is valid
- **WHEN** an authorized confirmation accepts an unexpired override for known tasks and allowed priority/exclusion/criteria bounds
- **THEN** the next iteration SHALL use it and record the override in plan explanations/audit metadata

#### Scenario: Override is stale or unsafe
- **WHEN** an override is expired, bound to an old plan, references unknown tasks, or exceeds configured bounds
- **THEN** it SHALL be refused with zero provider writes and no new plan mutation

### Requirement: Slack commands initiate bounded planner work
The Slack App SHALL support `/smartplanner plan [RUN]`, `replan [RUN] [feedback]`, `status`, and `help`, plus equivalent authorized app-mention commands. Commands SHALL be authorized, acknowledged promptly, and dispatched asynchronously so Slack callbacks never execute planning or provider writes inline.

#### Scenario: Plan command is authorized
- **WHEN** an allowed actor invokes `plan` in the configured channel for a known run
- **THEN** SmartPlanner SHALL acknowledge within three seconds, enqueue one run, show working state in a thread, and reply with the resulting proposal/error

#### Scenario: Command is unknown or unauthorized
- **WHEN** a command/action/run is unknown or actor/channel is unauthorized
- **THEN** SmartPlanner SHALL acknowledge safely, explain allowed usage or deny it, and enqueue no planning/apply work

#### Scenario: Status command is requested
- **WHEN** an authorized actor invokes `status`
- **THEN** SmartPlanner SHALL return readiness, Socket Mode connection state, queue/activity, and last/next run summaries without secrets or sensitive task content

### Requirement: Slack working status is visible and bounded
For Slack-requested work with a usable channel/thread, SmartPlanner SHALL call `assistant.threads.setStatus` with configured bounded status/loading text before long work and SHALL clear it by posting the result or explicitly sending an empty status. Status API failures SHALL be observable but non-authoritative and non-fatal.

#### Scenario: Work begins and completes
- **WHEN** a Slack command or feedback event enqueues planning/LLM/apply work
- **THEN** the associated thread SHALL show configured working status during processing and clear when a final reply is posted

#### Scenario: Status API fails
- **WHEN** Slack rejects or times out the status call
- **THEN** SmartPlanner SHALL record the classified failure, continue the requested authorized work, and post a normal result when possible

### Requirement: Startup validation and runtime resilience are classified
The daemon SHALL fail before readiness for invalid configuration or inability to authenticate/connect to required Todoist and CalDAV providers during bounded startup probes. After readiness it SHALL isolate each scheduled/event work item, retry transient failures with bounded backoff, remain alive after non-fatal failures, and terminate orderly only for a classified fatal required-provider authentication loss or explicit shutdown.

#### Scenario: Startup provider probe fails
- **WHEN** Todoist or configured CalDAV cannot be authenticated/reached during startup validation
- **THEN** daemon startup SHALL return nonzero without announcing readiness or starting periodic mutations

#### Scenario: Scheduled run has transient failure
- **WHEN** a ready daemon encounters Todoist/CalDAV timeout/429/5xx, Slack disconnect, Weather failure, LLM failure, malformed optional response, or local work-item exception classified non-fatal
- **THEN** it SHALL record failure, apply bounded retry/degradation, continue other work, and remain alive

#### Scenario: Required provider credentials become invalid
- **WHEN** a later Todoist/CalDAV call is definitively classified authentication/authorization failure
- **THEN** SmartPlanner SHALL stop accepting new work, persist/log the fatal reason, close messaging/schedulers, and terminate nonzero

#### Scenario: Graceful shutdown is requested
- **WHEN** the process receives its shutdown signal
- **THEN** it SHALL stop new scheduling, close Socket Mode, allow bounded active-work completion, flush state, and exit without corrupting durable artifacts

### Requirement: Existing safety and recovery boundaries remain final
Daemon orchestration SHALL preserve managed-calendar-only writes, Todoist due-datetime-only updates, deadline invariance, exact current-plan approvals, safe-only withholding, `fully_automated` refusal, idempotency, and reconciliation barriers for ambiguous writes.

#### Scenario: Approval is exact and mode permits apply
- **WHEN** an authorized current-thread approval matches the newest stored plan ID/version/hash in `approval_required`
- **THEN** guarded application SHALL persist an itemized receipt and perform only eligible managed calendar/Todoist due writes

#### Scenario: Rejection or acknowledgement is processed
- **WHEN** a thread response resolves to reject or acknowledge
- **THEN** it SHALL perform zero Todoist and calendar writes

#### Scenario: Ambiguous write exists on later cycle
- **WHEN** a prior Todoist/CalDAV outcome is reconciliation-required
- **THEN** no periodic or Slack-triggered run SHALL blindly resend that mutation

#### Scenario: Fully automated mode is selected
- **WHEN** any daemon path reaches apply under `fully_automated`
- **THEN** it SHALL fail closed with durable refusal and zero remote writes
