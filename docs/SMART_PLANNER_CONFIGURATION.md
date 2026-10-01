# SmartPlanner configuration

SmartPlanner configuration is under `planner`. The complete annotated template is
`conf/todoist-planner.conf.example.yaml`. Production operations fail closed if integration endpoints
or state paths are missing. Relative state paths resolve from the config file directory.

## Safety and modes

- `preview` is the default and makes no Todoist or calendar writes.
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

`planner.integration.calendar.provider` is required and must be exactly `caldav` or
`google_calendar_api`. Provider inference and fallback are intentionally unavailable. Mixed sections,
unknown provider fields, incomplete selected-provider configuration, duplicate calendar mappings, or
a managed-output mismatch fail startup before credentials are resolved or a network client is built.

For `provider: caldav`, configure `planner.integration.caldav`. It accepts positive `timeout` and
`max_response_bytes` controls, and `calendars` is the complete read scope. Every row requires a unique
`name` and absolute HTTPS `url`. Auth is `none`, `basic` (`username` + `password_env`), or `bearer`
(`token_env`). Raw secrets are rejected. Do not include `google_calendar_api` fields.

For `provider: google_calendar_api`, omit `planner.integration.caldav` and configure:

```yaml
planner:
  output_calendar: Todoist Planned
  integration:
    calendar:
      provider: google_calendar_api
      google_calendar_api:
        oauth_client_secret_file: secrets/google-oauth-client.json
        token_store_dir: tokens/normal-event-only
        qa_token_store_dir: tokens/qa-calendar-management
        account_email: dedicated-qa-account@example.test
        oauth_callback_port: 8787
        calendars:
          - name: Todoist Planned
            id: <provider-returned-managed-calendar-id>
            role: managed_output
          - name: Work
            id: <provider-returned-blocker-calendar-id>
            role: hard_blocker
```

This snippet assumes the configuration itself is in ignored `.qa/`. All paths are references resolved
within the configuration directory boundary; keep the referenced
client material and the two distinct token stores in ignored, owner-private local storage. Inline
client secrets, authorization codes, access/refresh tokens, authorization headers, static durable
tokens, and CalDAV authentication fields are rejected for this provider. Google account passwords and
app passwords are not part of this flow and must not be requested, stored, or shared.

`account_email` pins consent and QA preflight to the expected dedicated account.
`oauth_callback_port` defaults to `8787`; bootstrap binds only `127.0.0.1`. Normal production
configuration requires unique `name` and `id` values, supported roles (`managed_output`,
`hard_blocker`, `soft_blocker`, or `informational`), exactly one `managed_output`, and an exact match
between that row's name and `planner.output_calendar`. Bootstrap-specific validation deliberately
permits no `calendars` rows because consent precedes provisioning. Normal `capacity`, `preview`,
`apply`, `apply-safe`, and `planner-daemon` still require the complete mapping.

For either provider, every configured calendar participates in availability and global UID collision
checks, while `planner.output_calendar` is the only write target. The Google gateway additionally
enforces the managed-output role, planner UID/ownership metadata, live ownership/block revalidation
before delete, and no blind retry after an indeterminate mutation. Normal planner operations cannot
list, create, rename, or delete calendars.

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

Startup validates the complete configuration and performs bounded read-only Todoist/calendar probes.
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
- `google-oauth-bootstrap`: Google-only, event-read/write consent into the normal token store. It
  accepts the pre-provisioning Google subset, prints the one-time URL only to the invoking terminal,
  persists a refresh-capable credential, and exits without planner or provisioning work.
- `google-oauth-bootstrap-qa`: Google-only, separate calendar-management consent into the QA token
  store. It exits without listing or provisioning calendars and never broadens the normal store.
- `google-oauth-import-legacy-qa`: requires `--confirm-legacy-qa-import --input-reference FILE`;
  validates the bounded referenced credential document for the configured account and exact QA scope,
  writes only the QA token store, and exits. It can never populate the normal store.
- `google-qa-calendars-list`: requires `--confirm-dedicated-qa-account`; uses only the QA credential,
  verifies the configured account against its primary calendar, and returns the calendar inventory.
- `google-qa-calendars-provision`: additionally requires `--qa-calendar
  'alias|role|name[;alias|role|name]'`; creates or exactly reuses named calendars and writes returned
  IDs only beneath ignored `.qa/state/calendar-ids.json`.
- `planner-daemon`: primary long-running multi-horizon planning, Slack proposal/thread feedback, and graceful shutdown.
- `capacity`: live read-only capacity report; requires explicit start/end instants.
- `preview`: live deterministic proposal + local persistence; requires explicit start/end.
- `apply`, `apply-safe`: stored plan application through safety gates.
- `deliver`: explicit kind or due schedules through the durable ledger.
- `feedback`: parse/persist only; never applies.
- `apply-decision`: explicit, exact revalidated decision application.
- `ai-suggest`: bounded suggestion request; no mutation or automatic confirmation.

The OAuth and QA operations are explicit one-shot launcher paths; they never start the daemon or run
planning. Use `capacity` and `preview` before any apply. Do not paste consent URLs, authorization
codes, credential documents, tokens, account passwords, or Slack secrets into Slack, tickets, logs,
receipts, screenshots, or evidence. For a remote browser, start
`ssh -N -L 8787:127.0.0.1:8787 hermes@<host>` (substitute the configured port), then open the URL
printed by the launcher locally; the callback returns through the tunnel without copying a code.
