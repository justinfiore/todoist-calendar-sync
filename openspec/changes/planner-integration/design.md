## Context

SmartPlanner already has deterministic scheduling, immutable plans, guarded apply, durable plans/applications/decisions/deliveries, Slack outbound delivery, structured feedback, and bounded AI services. Production composition currently dispatches one operation and exits. The required runtime is instead a supervised daemon that coordinates periodic and user-triggered work while preserving the existing authority boundaries.

Slack is the first bidirectional Messaging Surface. Authoritative Slack documentation establishes these implementation constraints:

- Socket Mode receives Events API and interactive payloads over an outbound WebSocket, so no public Request URL is required.
- An app-level token with `connections:write` opens/maintains the connection; the bot token is separate.
- Slack recommends Bolt/SDK support; the current Java guide documents `com.slack.api:bolt-socket-mode:1.50.0` and requires Socket Mode payload acknowledgement within three seconds.
- Proposal replies are correlated by channel and parent `thread_ts`.
- `assistant.threads.setStatus` accepts `channel_id`, `thread_ts`, and `status`, uses `chat:write`, expires after two minutes, and clears on reply or an empty status.

Constraints remain Java 25, Groovy 5, Gradle 9, deterministic scheduling, hermetic tests, no Todoist deadline mutation, managed-calendar-only writes, exact approvals, and no direct LLM mutation authority.

## Goals / Non-Goals

**Goals**

- Keep SmartPlanner alive and independently schedule multiple named horizons.
- Publish every proposal as a channel parent and iterate in its thread.
- Receive Slack commands and thread feedback through outbound-only Socket Mode.
- Support deterministic regex actions and optional bounded LLM interpretation.
- Apply or replan only through persisted, plan-bound decisions and guarded gateways.
- Survive all non-fatal cycle/event failures and reconnect after optional-provider outages.
- Fail startup early for invalid configuration or unusable required Todoist/CalDAV credentials/connectivity.

**Non-Goals**

- Removing `legacy-sync` or one-shot diagnostics.
- Running an inbound HTTP server for Slack.
- Treating every arbitrary channel message as feedback.
- Allowing a regex, Slack user, or LLM response to bypass actor authorization, plan identity, or apply policy.
- Making `fully_automated` available.
- Provisioning a Slack app automatically or storing raw provider secrets.

## Decisions

### 1. Add a daemon operation without removing one-shot controls

`TodoistCalDavSync --operation planner-daemon` constructs `SmartPlannerDaemon` and blocks until graceful shutdown. Existing `capacity`, `preview`, `apply`, `apply-safe`, `deliver`, `feedback`, `apply-decision`, and `ai-suggest` remain troubleshooting/manual controls. `legacy-sync` remains the compatibility default when `--operation` is omitted.

The daemon owns lifecycle and orchestration; `ProductionPlannerOrchestrator` remains the synchronous application service for individual plan/apply/deliver operations.

### 2. Configure independent fixed-delay horizon schedules

Configuration uses `planner.daemon.planning_runs[]`:

```yaml
planner:
  daemon:
    enabled: true
    startup_connectivity_check: true
    shutdown_timeout: PT20S
    retry:
      initial_delay: PT5S
      max_delay: PT5M
      multiplier: 2.0
    planning_runs:
      - name: daily
        horizon: P2D
        interval: PT1H
        run_on_startup: true
      - name: weekly
        horizon: P7D
        interval: PT6H
        run_on_startup: true
      - name: medium
        horizon: P14D
        interval: P1D
        run_on_startup: false
```

Names are unique. Horizon and interval are positive ISO-8601 duration/period values with documented bounds. Each run plans `[now, now + horizon)` in the configured planner timezone. Fixed-delay scheduling prevents overlapping runs of the same name; a bounded single planning executor serializes provider/state mutations across horizons. Coalescing keeps at most one pending trigger per horizon.

### 3. Validate required providers before entering the loop

Startup order is: parse/validate all config and regexes; resolve required credential environment variables; construct stores/adapters; perform bounded authenticated Todoist and CalDAV read probes; then start Slack and schedules. Invalid config or classified Todoist/CalDAV authentication/connectivity failure exits nonzero before the daemon advertises readiness.

After readiness, a failed cycle/event is caught at its work-item boundary. Authentication/authorization loss for required Todoist/CalDAV is classified fatal and requests orderly daemon termination. Transient timeouts, 429s, 5xx responses, malformed optional-provider responses, Slack disconnects, Weather failures, and LLM failures are recorded and retried/degraded without killing the process. Uncaught `Error` is logged and does not silently disappear, but JVM-fatal conditions are not promised recoverable.

### 4. Make Messaging Surface bidirectional and conversation-aware

Introduce narrow ports:

- `MessagingSurface.start(handler)` / `close()` for inbound lifecycle.
- `publishProposal(message)` returning channel/message/thread identity.
- `reply(channel, threadTs, message)`.
- `setWorkingStatus(channel, threadTs, status)` and `clearWorkingStatus(...)`.
- inbound `MessagingEvent` with provider event ID, actor, channel, message timestamp, parent thread timestamp, text, and event type.

`SlackSocketModeMessagingSurface` uses Bolt Socket Mode with separate app-token and bot-token environment references. Listener callbacks acknowledge immediately and enqueue normalized events; no planning, LLM, or provider mutation occurs on the Socket Mode callback thread. Event IDs and `(channel, messageTs)` are durably deduplicated.

The existing outbound Slack gateway remains useful for HTTP contract code, but daemon proposals use chat API so Slack returns `channel` and `ts`. Webhook-only mode is not valid for bidirectional daemon operation.

### 5. Ship a configurable Slack App manifest template

`conf/smartplanner-slack-app-manifest.example.yaml` defaults both app name and bot display name to `SmartPlanner`, enables Socket Mode, declares `/smartplanner`, and includes the event subscriptions/scopes needed by the configured channel type. Operators may edit names before app creation. Runtime `planner.messaging.app_name` defaults to `SmartPlanner` for messages/docs/validation but does not pretend to rename an already registered Slack app.

Minimum public-channel bot scopes are `chat:write`, `commands`, `app_mentions:read`, and `channels:history`; private-channel deployments additionally need the documented private-channel history/access scope. The app-level token has `connections:write`.

### 6. Define command and thread behavior explicitly

Supported slash/app commands:

- `plan [RUN_NAME]`: enqueue a plan for one configured horizon, or all when omitted.
- `replan [RUN_NAME] [feedback]`: enqueue a new iteration using feedback/overrides.
- `status`: report readiness, Socket Mode state, last/next runs, and queued work without secrets.
- `help`: show allowed commands and current authorization limits.

Commands are accepted only from configured actors and channel. Slash commands are acknowledged within three seconds. Work is bounded and asynchronous. For commands with no source thread, SmartPlanner posts a command receipt parent, applies `assistant.threads.setStatus` there, then posts the result. App mentions and thread requests use their existing thread. Status text/loading messages are configurable and bounded; API failure is recorded and processing continues.

### 7. Correlate each proposal thread with immutable plan identity

A successful proposal parent persists a conversation record containing provider, channel, root `thread_ts`, horizon/run name, current plan ID/version/hash, proposal ID, iteration number, prior plan/proposal IDs, and timestamps. Only messages whose channel and root `thread_ts` match an active conversation are treated as proposal feedback. Bot/self messages, edits/deletes unless explicitly supported, duplicate event IDs, other channels, and top-level chatter are ignored.

A replan stays in the same thread, persists a new immutable plan, updates the conversation's current exact identity, and posts a diff/summary reply. Any approval for an older iteration is stale and refuses writes.

### 8. Parse deterministic regex rules before optional LLM interpretation

Configuration uses ordered rules:

```yaml
planner:
  integration:
    feedback:
      allowed_actors: [U123]
      rules:
        - name: approve
          pattern: '(?i)^\\s*(approve|acknowledge|yes)\\s*$'
          action: approve
        - name: reject
          pattern: '(?i)^\\s*(reject|no)\\s*(?<reason>.*)$'
          action: reject
        - name: replan
          pattern: '(?is)^\\s*(replan|change)\\b(?<feedback>.*)$'
          action: replan
```

Patterns use Java regex syntax, compile at startup, are length/count bounded, and first match wins. Supported actions are `acknowledge`, `approve`, `reject`, `replan`, `apply_safe`, `status`, and `help`; write-capable actions remain constrained by planner mode and actor authorization. Capture groups such as `feedback`/`reason` are bounded and persisted after secret/mention-safe normalization.

Unmatched thread text is ordinary feedback. If AI is disabled, SmartPlanner asks for a supported deterministic command. If AI is enabled and conversational interpretation is allowed, it sends bounded/redacted context and accepts only the existing strict interpretation/temporary-override schemas. It posts a confirmation summary; no LLM output is applied until a subsequent authorized deterministic confirmation binds it to the current plan.

### 9. Model replanning overrides as temporary, bounded inputs

`PlanningOverride` records source event/conversation, target run/plan, expiration, optional task IDs, exclusions, priority adjustments within configured bounds, and bounded textual criteria. Regex rules may supply predefined configured override templates; LLM output may propose the same schema. Confirmed overrides are persisted in the decision store and passed as planning input to a new iteration; they do not rewrite Todoist source tasks or permanent config. Expired, stale-plan, unknown-task, or out-of-range overrides are refused.

### 10. Keep apply and ambiguity safety unchanged

Approval in a thread creates a decision bound to the conversation's newest plan ID/version/hash. `approval_required` applies only after exact approval. `apply_safe_changes` may apply only ordinary eligible items after an authorized `apply_safe` action. `preview` never writes. `fully_automated` remains refused. Reject/acknowledge/replan never apply. Ambiguous provider writes retain reconciliation barriers and are never blindly retried by later cycles.

### 11. Test time, transport, and lifecycle through injected seams

`SmartPlannerDaemon` accepts injected clock, scheduler/sleeper/executor, messaging surface, and shutdown signal. Tests advance virtual time instead of sleeping. Socket Mode listener tests inject normalized envelopes or a fake surface; Web API request contracts use WireMock. A small SDK-composition test proves listener registration and asynchronous dispatch without connecting to Slack.

## Risks / Trade-offs

- **Bolt dependency/runtime conflicts** → Pin documented compatible versions, inspect dependency resolution, run full distribution/build tests, and isolate SDK usage behind one adapter.
- **Duplicate Socket Mode events or reconnects** → acknowledge quickly, durable dedupe, idempotent work keys, and conversation identity checks.
- **Long work blocks Slack callbacks** → callbacks only normalize/ack/enqueue; bounded worker executors perform work.
- **Thread feedback authorizes stale plans** → conversation always tracks newest exact identity; old approvals fail.
- **Status API may be unavailable for app configuration** → record failure, post normal thread progress/result, never change authority or daemon liveness.
- **Repeated failing cycles create noise** → exponential bounded retry, coalescing, structured health status, and optional alert throttling.
- **Arbitrary feedback changes planning unexpectedly** → ordered rules, allowlists, bounded override schema, explicit confirmation, expiry, immutable source data, and diff replies.

## Migration Plan

1. Commit this revised OpenSpec artifact set before code edits.
2. Add daemon/lifecycle/config models and deterministic tests.
3. Add conversation/override persistence and regex parser tests.
4. Add Slack manifest, Socket Mode surface, commands, thread correlation, status API, and WireMock/fake-surface tests.
5. Wire periodic and Slack-triggered planning, iteration, decisions, and guarded apply.
6. Update docs/config examples and remove one-shot-primary wording.
7. Run focused tests, full rerun, build, installDist, installed help, and hermetic daemon smoke tests.
8. Keep live Slack/Todoist/CalDAV verification open until operator credentials/test workspace are available; document exact commands and acceptance gates.

## Resolved Decisions

- Implementation uses Hermes directly, not delegated coding agents.
- Slack uses outbound-only Socket Mode and Bolt for Java.
- Default app/display name is SmartPlanner; operators may edit the manifest/configured display name.
- Proposal feedback is thread-scoped and supports multiple iterations.
- Bot working state uses `assistant.threads.setStatus` with graceful degradation.
- `fully_automated` remains unavailable.
