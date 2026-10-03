# SmartPlanner configuration

SmartPlanner configuration is under `planner`. The complete annotated template is
`conf/todoist-planner.conf.example.yaml`. Production operations fail closed if integration endpoints
or state paths are missing. Relative state paths resolve from the config file directory.

## Safety and modes

- `preview` is the default and makes the normal `apply` path refuse Todoist/calendar writes.
  `capacity` and `preview` themselves make no provider writes. The explicit `apply-safe` operation and
  separately enabled recurrence lifecycle processing remain write-capable.
- `approval_required` writes only with an approval whose plan id, version, and full semantic hash
  exactly match the stored plan.
- `apply_safe_changes` may write only ordinary blocks. Frozen, manual-override, and
  `approvalRequired` blocks remain withheld without an exact approval. The explicit `apply-safe`
  operation uses this same safe-only gate.
- `fully_automated` is not available. Both ordinary apply and safe apply refuse it with zero writes.

The calendar write boundary accepts only deterministic planner UIDs with the ownership marker on
`planner.output_calendar`. UID lookup searches every configured calendar; cross-calendar collisions
are errors. Ordinary Todoist scheduling writes send only fixed `due_datetime`, and that path refuses
Deadline writes. Native recurring tasks instead use Todoist Sync `item_update`, preserving the opaque
recurrence tuple. The explicitly enabled recurrence lifecycle is the only path permitted to initialize
or advance Deadline, and it verifies the live postcondition before Calendar mutation.

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

## Native recurrence and Deadline lifecycle

`planner.tasks.recurrence.enabled` gates native recurring-task management. The configured
`rollout_cutoff` is mandatory when enabled. Tasks created at or after it are classified before the
planner first changes Due. A user Due contributes only its local calendar date to Todoist Deadline;
an existing Deadline is preserved; an initially undated task records the later planner Due as
planner-authored and never copies it back into Deadline. The cutoff compares Todoist creation time;
missing creation time is treated as legacy. Lifecycle processing applies only to native recurring
tasks.

Pre-cutoff Due-only tasks remain recurrence-safe but are only logged as legacy candidates. Add
`smartplanner-onboard` in Todoist to migrate one candidate. SmartPlanner preserves its original Due
date in the description marker, writes and verifies Deadline and `smartplanner-seen`, and removes the
request label last. A pre-cutoff task that already has Deadline can be classified without conversion
or the request label. The configured `hard` label means the date-only Deadline is a finish-by
constraint. Without it, Deadline is a soft target and work may be placed late with an increasing
penalty. Hard urgency is
boosted only inside `hard_deadline_soon_days`; outside the window, Todoist priority remains the
ordinary ordering signal.

The final description suffix beginning `**SmartPlanner metadata — do not edit**` is portable
lifecycle authority. Human text before it remains owned by the user. A malformed, moved, duplicated,
missing, or unsupported marker freezes writes. Rollback means disable recurrence apply and return to
preview; it deliberately leaves marker, sentinel, mappings, and historical events intact for repair.
Disabling recurrence also restores the legacy scheduler policy in which every Deadline is hard, so
compare a fresh disabled preview before treating rollback output as equivalent.

Recurring Due movement preserves Todoist's full opaque tuple. A floating tuple (`timezone` present
but null) receives a planner-zone civil datetime; a fixed tuple with an IANA timezone receives a UTC
instant while retaining that timezone, preserving local wall-clock time across DST. Users complete
recurring tasks through Todoist's native recurring-completion behavior. SmartPlanner only observes the
result: it does not complete tasks, uncomplete them, or use Todoist's simplified REST/Sync `close`
operations, which live QA found can remove the future Due of a fixed-zone recurrence.

Mobile and voice capture use the same Todoist workflow: capture the task normally, then add its native
recurrence and Due. Post-cutoff tasks are classified automatically; pre-cutoff tasks stay in legacy-log
mode until `smartplanner-onboard` is added. Do not dictate or paste the SmartPlanner metadata block.
SmartPlanner owns that suffix and preserves the human description above it.

Managed Calendar events retain one identity per Todoist occurrence and include series/occurrence
ownership metadata plus ID-only Todoist links. Historical occurrences are not cleanup candidates when
the active occurrence advances. If local application state is lost, start in preview, inventory the
valid Todoist lifecycle marker and matching managed Calendar occurrence metadata, and apply only when
they agree; deterministic UID lookup then rebuilds the active index without creating a duplicate.
Conflicting, corrupt, or duplicate provider authority is a stop condition. Rollback never deletes
provider events or metadata; disposable or obsolete history is removed only by an explicitly approved,
inventory-based cleanup using the QA runbook.

The daemon starts a dedicated Todoist `items` Sync poll immediately and repeats it every five minutes.
Its cursor and pending-delta inbox are atomically stored at
`applications_dir/recurrence/todoist-items-sync.json`, isolated from the legacy synchronizer token.
Restart drains the inbox before another fetch. Missing/corrupt cursor state bootstraps a full item
snapshot; empty deltas remain incremental no-ops. Poll transport/rate-limit failures retain the state
for the next cadence, while required-provider 401/403 responses stop the daemon. Todoist mutations are
not blindly retried: ambiguous results require a live re-read and matching preconditions.

Occurrence history is also durable under `applications_dir`: the active mapping remains indexed by
task, while `mapping-history.json` retains mappings by `(task ID, completed_count)`. Back up and restore
the complete applications directory with the other three state directories.

### Recurrence reconciliation guide

| Observed state | Required operator action |
| --- | --- |
| Same count with a user Due or recurrence-rule edit | Leave provider state untouched; review the new intent and generate a fresh preview before reconciling. |
| User Deadline or `hard` edit | Treat the old plan/approval as stale and replan from the live value. |
| Missing sentinel with valid marker | Repair or explicitly retire management; never repeat first-observation conversion. |
| Missing, malformed, duplicate, moved, or unsupported marker | Preserve the raw description and stop writes until an explicit repair is reviewed. |
| Recurrence removed | Stop lifecycle advancement and explicitly reconcile the task into ordinary non-recurring planning. |
| `completed_count` jumps | Schedule only Todoist's current active occurrence; retain known history and review the reported unobserved gap. |
| Counter regression or incomplete recurrence tuple | Stop writes; capture provider state and investigate rather than rewriting authority. |
| Todoist committed but Calendar failed, or response was ambiguous | Preserve receipts/state, re-read both providers, and resume only the same occurrence after postconditions are classified. |
