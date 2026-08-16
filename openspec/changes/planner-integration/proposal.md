## Why

The current integration exposes SmartPlanner as a collection of one-shot operations that plan, deliver, receive feedback, or apply and then exit. That does not match the production lifecycle: the existing sync application is a long-running poller, and SmartPlanner must likewise remain running, execute multiple planning horizons periodically, publish proposals, consume threaded feedback, and safely replan or apply without an operator driving separate CLI invocations.

Slack must be a bidirectional messaging surface without an inbound public port. SmartPlanner therefore needs Slack Socket Mode, thread-correlated proposal conversations, app commands, and visible working status while a Slack-triggered request is processed.

## What Changes

- Make `planner-daemon` the primary SmartPlanner runtime operation. It validates configuration and provider connectivity at startup, remains alive, schedules independently configured planning horizons, and keeps one-shot operations as diagnostics/operator controls.
- Add configurable daemon lifecycle, clock, retry/backoff, planning horizon, periodicity, startup behavior, concurrency, and graceful-shutdown settings.
- Generate and persist deterministic plans for each due horizon, publish each proposal as a parent message in the configured messaging channel, and durably correlate channel/thread identifiers with the exact plan ID/version/hash.
- Add a bidirectional Messaging Surface contract. Slack uses outbound-only Socket Mode with an app-level `xapp-` token, bot `xoxb-` token, prompt envelope acknowledgement, reconnect handling, event deduplication, and no inbound HTTP listener.
- Add a default Slack App manifest named **SmartPlanner**. Operators may edit the app/display name before registration. The manifest defines Socket Mode, least-privilege scopes, event subscriptions, and the `/smartplanner` command.
- Add Slack commands including `/smartplanner plan [horizon]`, `/smartplanner replan [horizon]`, `/smartplanner status`, and `/smartplanner help`. Commands acknowledge immediately, enqueue bounded work, and never bypass configured authorization or apply policy.
- Publish proposals as channel parent messages. Accept proposal-specific acknowledgements, approvals, rejections, and iterative feedback only from the associated Slack thread. Ignore bot/self events and messages outside the configured channel/thread correlation.
- Add ordered configurable regular-expression feedback rules with validated Java regex patterns and explicit actions. Unmatched feedback can invoke bounded LLM interpretation only when AI is enabled; LLM output remains schema-validated and cannot directly mutate providers.
- Replan from thread feedback using bounded, persisted temporary overrides. Every iteration creates a new immutable plan/proposal linked to the prior proposal and remains in the same Slack thread. Approval is bound to the newest exact plan identity.
- Apply approved or safe-only plans through the existing guarded Todoist/CalDAV boundaries. Rejections never write. Feedback handling and provider mutation remain auditable and idempotent.
- Use Slack `assistant.threads.setStatus` to show configurable working text while processing Slack-requested work, then clear it by replying or explicitly sending an empty status. Status failure is observable but does not grant authority or terminate the daemon.
- Fail fast only for invalid startup configuration or inability to authenticate/connect to required Todoist and CalDAV providers during startup validation. After startup, isolate cycle/event failures, log and persist classified outcomes, retry transient failures with bounded backoff, and keep the process alive. Slack, Weather, and LLM outages are degraded optional-provider failures, not daemon-fatal failures.
- Expand hermetic Spock/WireMock coverage for scheduling, daemon resilience, Socket Mode envelopes/reconnects, Slack Web API thread/status calls, regex and LLM feedback, command authorization, iteration, exact apply, and restart recovery.
- Rewrite configuration, Slack App setup, operations, and end-to-end rollout documentation for the daemon-first model.

## Capabilities

### New Capabilities

- `planner-main-application-integration`: Long-running production composition, scheduling, feedback/replanning, guarded application, diagnostics, and legacy compatibility.
- `planner-http-integration-testing`: Hermetic verification of Todoist, CalDAV, Weather, Slack Socket Mode/Web API, and LLM boundaries.
- `planner-operational-rollout`: Isolated and production rollout for a continuously running planner with explicit safety gates and recovery.
- `planner-feature-documentation`: Complete daemon, Slack App/Socket Mode, LLM, Weather, configuration, and operator documentation.

### Modified Capabilities

None. This repository has no existing OpenSpec baseline capabilities; this active change defines the integrated SmartPlanner contracts.

## Impact

- Runtime: `TodoistCalDavSync`, `ProductionPlannerOrchestrator`, new daemon/scheduler/event-loop components, feedback parsing, and graceful lifecycle management.
- Slack: Bolt for Java Socket Mode support, Web API calls for parent/thread messages and `assistant.threads.setStatus`, an app manifest template, and thread-correlation persistence.
- Configuration/state: daemon schedules, Slack tokens/app metadata/commands, regex rules, transient override policy, event deduplication, and conversation correlation. Secrets remain environment-variable references.
- Tests: deterministic clocks/sleepers/executors, in-memory messaging, Socket Mode listener seams, and WireMock HTTP boundaries; no real credentials or provider calls.
- Documentation: README, quick start, annotated example configuration, SmartPlanner configuration, Slack integration, LLM integration, and end-to-end testing guide.
- Compatibility: `legacy-sync` remains a long-running operation and one-shot planner operations remain available. `planner-daemon` is the production SmartPlanner lifecycle. Existing safety, ownership, deadline-invariance, and ambiguous-write barriers remain mandatory.
