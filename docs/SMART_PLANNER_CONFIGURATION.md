# SmartPlanner configuration

SmartPlanner configuration is under `planner`. The complete annotated template is
`conf/todoist-planner.conf.example.yaml`. Production operations fail closed if integration endpoints
or state paths are missing. Relative state paths resolve from the config file directory.

## Safety and modes

- `preview` is the default and makes no Todoist or CalDAV writes.
- `approval_required` writes only with an approval whose plan id, version, and full semantic hash
  exactly match the stored plan.
- `apply_safe_changes` may write only ordinary blocks. Frozen, manual-override, and
  `approvalRequired` blocks remain withheld without an exact approval. The explicit `apply-safe`
  operation uses this same safe-only gate.
- `fully_automated` is not available. Both ordinary apply and safe apply refuse it with zero writes.

The calendar write boundary accepts only deterministic planner UIDs with the ownership marker on
`planner.output_calendar`. UID lookup searches every configured calendar; cross-calendar collisions
are errors. Todoist writes send only `due_datetime`; the production adapter refuses deadline writes.

## Production integration

`planner.integration.todoist` requires:

- `base_url`: explicit absolute HTTPS REST base (normally `https://api.todoist.com/api/v1`).
- `token_env`: environment-variable name containing the bearer token.
- `timeout`, `max_pages`, `max_response_bytes`, `include_project_names`: bounded transport/read controls.

`planner.integration.caldav` also accepts positive `timeout` and `max_response_bytes` controls.
`planner.integration.caldav.calendars` is the complete read scope. Every row requires unique `name`
and absolute HTTPS `url`. Auth is `none`, `basic` (`username` + `password_env`), or `bearer`
(`token_env`). Raw secrets are rejected. The calendar named by `planner.output_calendar` is the only
write target; all listed calendars participate in availability and UID collision checks.

`planner.integration.state` requires four independent paths:

- `plans_dir`: immutable plan snapshots and stability history.
- `applications_dir`: task/event mappings and application receipts.
- `decisions_dir`: append-only structured feedback decisions.
- `deliveries_dir`: message idempotency ledger and delivery receipts.

Back up all four together. Do not share one directory between different planner installations.

## Planning inputs

`timezone`, `availability.working_windows`, calendar defaults/rules, task labels/durations/contexts,
batching, and stability are consumed by the deterministic scheduler. Live Todoist tasks are
normalized, `@manual` tasks are excluded, live CalDAV events retain configured calendar names, and
the latest persisted plan is the default stability baseline. Set
`planner.integration.previous_plan_id` to pin a specific baseline.

## Daemon scheduling and conversations

`planner-daemon` is the primary SmartPlanner production lifecycle. `planner.daemon.planning_runs` is a
non-empty list when enabled. Every run has a unique `name`, positive ISO-8601 `horizon`, `interval`,
optional `initial_delay`, and `run_on_startup`. Horizons are bounded to PT5M..P90D, intervals to
PT10S..P30D, and initial delay to PT0S..P30D. Runs are scheduled independently with per-run overlap
protection and a process-wide mutation lock so horizons cannot overlap provider/state mutations; an
already-active duplicate run trigger is coalesced/skipped. Retry delay is exponential and bounded by
`initial_delay <= PT1H`, `initial_delay <= max_delay <= P1D`, and multiplier 1..10. The graceful
`shutdown_timeout` is positive and at most PT10M.

Startup validates the complete configuration and performs bounded read-only Todoist/CalDAV probes.
Configuration or startup provider/authentication failure terminates startup. After readiness, failed
cycles, Slack interruptions, malformed feedback, status API failures, and LLM failures are contained;
the scheduler remains alive. `shutdown_timeout` bounds graceful SIGTERM/SIGINT drain.

A proposal is a channel-root message. Its channel/thread, run name, exact plan id/version/hash,
proposal id, iteration lineage, status, and temporary overrides are atomically stored under
`deliveries_dir/conversations`. Inbound event ids and recoverable payloads use durable
`PENDING`/`PROCESSING`/`COMPLETED` state for restart-safe retry and deduplication; payload content is
removed after completion.
Feedback is accepted only from `allowed_actors` in the matching proposal thread.

`feedback.rules` is an ordered list of unique names, Java regex patterns, actions, and optional
conversation-scoped overrides. The first full-string match wins. Supported actions are acknowledge,
approve, reject, replan, apply_safe, status, and help. Supported deterministic overrides are horizon,
per-task priority, task exclusion, and task freezing. Regex compilation errors fail configuration.

## Optional integrations

Weather is disabled unless `planner.weather.enabled: true`; configure latitude/longitude and the
explicit Open-Meteo endpoint under `planner.integration.weather`. The daemon requires
`planner.messaging.enabled: true`, provider `slack`, `slack_mode: socket_mode`, a channel id, and bot
plus app-token environment-variable references. Socket Mode opens only an outbound WebSocket. The
default Slack App manifest registers **SmartPlanner**, `/smartplanner`, message/app-mention events,
and working-status support; operators can edit the manifest and `app_name` to rename it. Feedback
authorization is the exact `planner.integration.feedback.allowed_actors` allowlist; empty means deny
all. AI is disabled unless explicitly enabled and remains a bounded interpretation/temporary-override
side service with no direct mutation port. See `SLACK_INTEGRATION.md` and the other feature guides.

## Operations

All operations use `TodoistCalDavSync`/the installed `todoist-caldav-sync` launcher:

- `legacy-sync` (default): unchanged original sync/loop.
- `planner-daemon`: primary long-running multi-horizon planning, Slack proposal/thread feedback, and graceful shutdown.
- `capacity`: live read-only capacity report; requires explicit start/end instants.
- `preview`: live deterministic proposal + local persistence; requires explicit start/end.
- `apply`, `apply-safe`: stored plan application through safety gates.
- `deliver`: explicit kind or due schedules through the durable ledger.
- `feedback`: parse/persist only; never applies.
- `apply-decision`: explicit, exact revalidated decision application.
- `ai-suggest`: bounded suggestion request; no mutation or automatic confirmation.
