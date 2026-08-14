# Planner production configuration

Phase 7 configuration is under `planner`. The complete annotated template is
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

## Optional integrations

Weather is disabled unless `planner.weather.enabled: true`; configure latitude/longitude and the
explicit Open-Meteo endpoint under `planner.integration.weather`. Slack is disabled unless
`planner.messaging.enabled: true`, provider is exactly `slack`, a mode and destination are set, and
the matching secret env name exists. Feedback authorization is the exact
`planner.integration.feedback.allowed_actors` allowlist; empty means deny all. AI is disabled unless
explicitly enabled and remains a bounded suggestion-only side service. See the linked feature guides.

## Operations

All operations use `TodoistCalDavSync`/the installed `todoist-caldav-sync` launcher:

- `legacy-sync` (default): unchanged original sync/loop.
- `capacity`: live read-only capacity report; requires explicit start/end instants.
- `preview`: live deterministic proposal + local persistence; requires explicit start/end.
- `apply`, `apply-safe`: stored plan application through safety gates.
- `deliver`: explicit kind or due schedules through the durable ledger.
- `feedback`: parse/persist only; never applies.
- `apply-decision`: explicit, exact revalidated decision application.
- `ai-suggest`: bounded suggestion request; no mutation or automatic confirmation.
