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

### Calendar move writeback

Calendar-to-Todoist Due writeback is default-off. Enable it explicitly with:

```yaml
planner:
  calendarWriteback:
    enabled: true
```

`enabled` must be a YAML boolean; strings such as `"true"` fail configuration validation before a
planner run. When enabled, each daemon planning tick polls only the configured output calendar's
incremental change feed before scheduling. A moved event is eligible only when it is planner-owned and
its durable mapping names exactly one open task. A calendar modification timestamp strictly newer than
Todoist `updated_at` updates only that task's Due and makes the adopted event span the durable applied
slot, so the following plan uses it as its baseline. Missing or equal timestamps leave both sides
unchanged. Unowned events and multi-task focus blocks never write back.

This newer-wins check runs during apply before manual-move drift handling. Therefore a newer eligible
calendar move is adopted whether `stability.keep_manual_moves` is `true` or `false`, and apply does not
upsert the event back to the proposed time. The setting still controls planner drift that is not a
newer eligible calendar edit. Preview and capacity remain read-only; enabling writeback does not add
mutations to either standalone operation. Approval and protected-block gates still apply before apply
can adopt a move, and SmartPlanner never writes Deadline from a calendar edit.

Non-recurring writeback uses the Due-only update. Recurring writeback and normal recurring apply share
the guarded recurrence operation: it rereads the live task, preserves recurrence expression, recurring
flag, language, timezone, Deadline, labels, and description, and never completes the task. An incomplete
tuple is not written. Failed verification permits exactly one retry of the intended date using the
saved original recurrence tuple; an unresolved result stops dependent calendar writes without rollback.
Apply retains its lifecycle-marker behavior, while calendar writeback does not rewrite that marker.

Incremental cursor and echo state is stored separately at
`applications_dir/calendar-writeback.json`. Cursor invalidation retries a full provider read without
discarding echo records. Outbound managed-event updates use the live etag for concurrency when one is
available and record an echo so their inbound copy cannot be mistaken for a user move.

## Production integration

### Routed configuration foundation

**Implementation status:** shared routing/configuration, exact-resource provider write ports,
schedule reconciliation and recoverable group replacement services have hermetic test coverage.
The routed CLI, independent daemon sync, approved-plan application and reviewed migration boundary
are connected. Migration and combined acceptance pass hermetic verification. This section
is not production rollout authorization. Keep existing operational configuration until live gates pass.
No service or destructive migration is authorized by parsing this example.

Omitting `planner.routing` retains the compatible `output_calendar` boundary. Opting in requires
`routing.mode: legacy_rules`, explicit `labelsToInclude` and/or `projectsToInclude`, and ordered
`rules` containing `calendarName` and `rule`. Inclusion is label **or** exact project name; only the
first matching destination rule wins. Matching is case-sensitive. Terms support ` AND ` and leading
`NOT `; `p:` names a project. There is no OR operator: repeat a destination in multiple rules.
Blank/unsupported expressions fail validation. Included tasks with no destination are unroutable,
not assigned a default calendar. Undated tasks acquire no schedule merely by matching a rule.

The following sanitized fragment replaces `output_calendar` and the selected calendar provider
section; retain the template's Todoist, state, and availability sections. Predicates, prefixes,
account and IDs are illustrative and **not** the owner's effective configuration.

<!-- routed-example:start -->
```yaml
planner:
  routing:
    mode: legacy_rules
    labelsToInclude: [calendar]
    projectsToInclude: [Family]
    rules:
      - {calendarName: 'J&J', rule: joint}
      - {calendarName: Jack, rule: jack}
      - {calendarName: Evan, rule: evan}
      - {calendarName: Emily, rule: emily}
      - {calendarName: Colin, rule: colin}
      - {calendarName: Kids, rule: 'p:Family AND NOT joint'}
      - {calendarName: Todoist, rule: calendar}
  integration:
    calendar:
      provider: google_calendar_api
      google_calendar_api:
        oauth_client_secret_file: planner-private/google-oauth-client.json
        token_store_dir: planner-private/normal-event-only
        account_email: operator@example.test
        calendars:
          - {name: 'J&J', id: joint@example.test, role: informational, managed_destination: true, prefix: 'Joint: '}
          - {name: Todoist, id: todoist@example.test, role: informational, managed_destination: true}
          - {name: Jack, id: jack@example.test, role: informational, managed_destination: true}
          - {name: Evan, id: evan@example.test, role: informational, managed_destination: true}
          - {name: Emily, id: emily@example.test, role: informational, managed_destination: true}
          - {name: Colin, id: colin@example.test, role: informational, managed_destination: true}
          - {name: Kids, id: kids@example.test, role: informational, managed_destination: true}
```
<!-- routed-example:end -->

Every rule destination must match exactly one explicit `managed_destination: true` mapping.
Duplicate names/provider IDs, unknown destinations, mixed providers, and overlapping output models
fail validation before credential resolution or network requests. For CalDAV use the selected
`integration.caldav.calendars` with unique `name`/HTTPS `url`, existing environment-referenced `auth`,
and `managed_destination: true`; do not supply Google fields. Calendar prefixes belong to mappings,
not predicate expressions. `managed_destination` is a write allowlist flag, **not** an availability
role: managed calendars may still contain unrelated appointments. Routed mappings do not use the
single-output `managed_output` role. Routing fingerprints bind inclusion and rule order so future
sync/apply can reject stale routing snapshots rather than silently moving resources after rule edits.

Projection scope is distinct from optimizer/lifecycle scope: an included manual task can resolve a
destination but does not become eligible for automatic scheduling or lifecycle initialization.
The full schedule-mirroring acceptance remains open in the change's task list.

#### Routed service state and recovery (not a runtime enablement procedure)

The new service boundary writes version-1 `projections.json` and `sync-operations.json` beneath
`applications_dir`. This does not replace existing mappings/occurrence history or the separate
single-output calendar-writeback state. Portable version-1 `todoist-projection:` description
metadata retains destination, ordered occurrence intervals, membership revision, routing
fingerprint, Due tuple, native-duration presence, effective minutes and task timestamps. The
local projection also retains exact resource ID, ETag and verified event schedule. Historical
records contain only one completed occurrence; active records cannot overlap occurrence coverage.

Replacement operations persist their immutable source/target resources, task/lifecycle evidence
and per-resource pending/unknown/conflict/verified outcomes **before** mutation. They create and
verify replacements before conditionally retiring sources. Membership commits atomically only
after the whole set verifies. A restart reconciles the exact journaled resources, not a generic
UID match. Ordinary duplicate detection remains strict. Changed task/resource baselines stop
recovery; never delete a pending journal, rerun a resize as a fresh operation, or clear an unknown
outcome merely to resume writes. Cancelled calendar projections remain cancelled after restart;
calendar deletion does not complete the task or immediately recreate its event.

Restore the **entire** application state and associated plans, decisions, deliveries, cursor/inbox,
conversation and provider-resource evidence as one reviewed backup unit. Copying an old mappings
file alone cannot roll back remote changes or authorize stale cursor replay. Backups may contain
private task/calendar text and must stay outside published artifacts and source control; do not
bundle credentials. Portable metadata permits conservative recovery only when live task schedules,
routes and event intervals agree. Disagreement, stripped/malformed metadata and old groups without
trustworthy intervals require explicit review/repair, never guessed title/link-based membership.
Old single-task events can acquire portable state only when one fully applied local mapping,
exact series/occurrence markers, owned UID/block, fresh Todoist schedule and resolved destination
all agree. Upgrade conditionally changes only event metadata, retains the resource ID, schedule,
human text and mapping history, and journals ambiguous outcomes for exact-resource restart.
Lost old local mappings, old groups, malformed portable metadata or schedule disagreement stop
creation with an actionable repair report. Restore the complete evidence or review repair first;
task links and event titles are not substitutes for missing membership evidence.

Group splits retain maximal consecutive unchanged runs and leave gaps untouched. Completed
departures get individual historical intervals, not a historical copy of the former full group.
Eligible native successors project at fresh Todoist Due; undated/ineligible successors are reported.
No split/departure changes residual Todoist Dues, native durations, Deadline or completion. Group
resizes allocate positive whole minutes proportionally: [30,60] → 60 gives [20,40], [10,10] → 21
gives [11,10] (persisted-order tie), and [1,1,98] → 3 gives [1,1,1]. Too-short/non-minute spans and
calendar all-day/timed conversions are withheld. Hermetic CLI/daemon scenarios cover these contracts;
they are not evidence that installed-provider, migration or live acceptance has passed.

#### Independent routed synchronization

`planner.sync.enabled` defaults to false. Its validated `interval` defaults to `PT1M` and accepts
`PT10S` through `P1D`. Enabled sync requires explicit routed managed mappings. Do not enable it
over an active overlapping legacy writer: operational handoff/deletion/reconstruction approvals
remain separate; neither implementation nor a passing hermetic test authorizes production execution.

`planner-sync` performs one current-schedule reconciliation, requiring sync enablement;
`planner-sync-preview` reads and reports intended actions without provider mutations, cursor
checkpoints, operation replay or membership adoption. Both require `--range-start` and `--range-end`
as positive ISO instant bounds. Google incremental discovery covers the complete managed collection;
the range bounds CalDAV fallback reads, not authorization or a reason to infer missing tasks.
Only explicit managed destinations are polled; read-only blockers are not writeback targets.

Forward sync creates individual current projections and mirrors task edits without optimization,
including manual tasks, outside-hours scheduling and local-date all-day events. Reverse Due/native
duration mutation additionally requires `planner.calendarWriteback.enabled: true`. Sync never
authorizes plan application, Deadline initialization or completion. Existing single-output
writeback retains its due-only behavior outside routed mode. Standalone `capacity`, `preview` and
sync-preview remain read-only even when daemon sync is enabled; proposal-only is **not** read-only
when independent sync or lifecycle writes are enabled.

The daemon can use `planning_runs: []` when sync is enabled; its existing configured Slack control
surface is still required. Sync runs independently at the configured interval, serializes with
other mutation work, and exposes forward/reverse/lifecycle/planning authorities in status. Provider
outages retain last-success and retry/backoff status; no cross-destination write starts until all
managed feeds are available. Required-provider authentication errors stop the daemon.

Dedicated state lives under `applications_dir/sync/`: `todoist-items-sync.json` has its own cursor
and durable items inbox, and `calendar-writeback.json` retains per-destination cursors plus pending
event/tombstone evidence. Neither is the legacy cursor nor the separate recurrence lifecycle cursor.
All feeds are read before mutations; checkpoints retain unprocessed evidence for restart. Expired
calendar cursors reset only disposable provider caches, never membership/journals or cancellations.
Corrupt state fails closed rather than being silently cleared. The projection file atomically
retains verified-operation commit receipts so restart can finish a crash between remote verification
and local membership commit without replaying older revisions.

Exit status is 0 for successful/preview reconciliation, 1 for visible conflicts/pending recovery or
provider failure, and 2 for invalid configuration/arguments. A cancelled event suppresses immediate
recreation. Conflicts show the resource/destination and reason; inspect both live schedules and the
retained operation evidence, resolve the actual provider disagreement under review, then rerun preview.
Do not delete a journal, infer completion from feed absence, or reapply an old plan as conflict repair.
Historical events never write back to a current task. Lost-state groups are adopted only when their
portable intervals, fresh tasks and current event agree; otherwise initial creation is withheld to
avoid duplicate active coverage. Old group metadata upgrade still requires explicit repair.

#### Routed proposals, approval and manual intent

Planning reads current verified placements rather than borrowing a previous proposal's times. A
synced manual move remains the baseline, including outside working hours; `keep_manual_moves`,
explicit freezes and the freeze horizon retain occupied placements. Real appointments and included
manual tasks remain blockers even when their managed calendar is informational. Replaceable task
projections do not block themselves. Focus batching partitions by destination as well as project and
context; sync itself never invents focus groups or work times. A mixed group with a protected member
is retained whole, not partly moved. Missing required calendars or disagreeing membership/schedules
withhold planning rather than treating unavailable time as free.

Every routed proposal stores and hashes its original Due, native/resolved duration, occurrence,
routing fingerprint and current resource/membership snapshots separately from proposed intervals.
An exact approval authorizes those proposed intervals only while the source remains unchanged.
Moving/resizing a calendar event, editing Due/duration, changing route, or syncing a manual edit
between proposal and approval requires a fresh proposal and review; approval of the old Slack message
cannot overwrite the edit. The Slack response reports failed/stale application rather than claiming
success. Generating or acknowledging a proposal never changes remote schedules.

Routed apply uses the same writer lease and durable operation store as synchronization. It verifies
all members/sources before writes, journals each Due and exact calendar resource, verifies target
projections, then conditionally retires superseded representations. A partial apply keeps its journal
and blocks overlapping sync/lifecycle/planning. Resume only the same stored plan with its original
authorization; sync authority cannot complete a pending plan. Reapplying a successful plan is a no-op
only when its own operation's verified tasks/resources still match live state. A later human edit is
not an idempotent echo. Never clear operation evidence to make an old proposal applicable.

Existing authorization policies remain: `approval_required` needs an exact plan/version/hash;
`apply_safe_changes` withholds frozen, manual-override and approval-required blocks without valid
explicit approval; `fully_automated` continues to refuse writes. Sync enablement grants none of
these planning permissions. Operational migration/release approvals remain separate.

#### Reviewed deletion-first migration

**Production/live gates remain unavailable and unchecked.** Owner-reported legacy-active/planner-disabled
state is context, not verified runner evidence. Do not copy QA credentials, infer account/IDs/rules from
this example, or enable/stop production services to test this implementation. Before live acceptance,
obtain isolated Google/Todoist accounts, credentials provisioned through their authorized mechanisms,
scopes/calendar IDs and permission to mutate only those fixtures. Production handoff additionally needs
sanitized runner-side routing/inclusion/prefix/timezone/account checks, writer-stop evidence, an exact
manifest deletion approval and separate approval for each service/configuration change.

The installed command is `todoist-caldav-sync -f <config> -l <logging> --operation planner-migrate`.
It defaults to read-only remote inventory; `--migration-step` selects an explicit step:

| Step | Authority and outcome |
| --- | --- |
| `inventory` | Reads full managed collections and live Todoist identity/tasks; writes only private local export/evidence. |
| `status` | Reads local stage, hashes, counts, exclusions, skipped tasks and per-resource outcomes. |
| `approve-delete --approval <file>` | Revalidates exact manifest/account/routing/versions, private complete backup, authorized actor and writer handoff; persists approval only. |
| `delete` | Uses that live expiring capability and conditional exact-resource deletion. No reconstruction happens here. |
| `reconstruct` | Requires **every** approved legacy deletion absent; creates only individual projections at fresh Todoist Due/resolved duration. |
| `verify` | Requires exact schedules/destinations/membership, complete coverage, no legacy IDs/UIDs or duplicates, and unchanged Todoist input. Commits verified local projection baselines. |
| `release-sync --approval <file>` | Separately permits normal sync after parity. It does not enable sync or change a service. |
| `release-planning --approval <file>` | Separately releases first proposals after sync release and fresh parity. Normal exact plan application approval still applies. |
| `review-rollback` | Reads live replacement versions and missing/surviving legacy resources; persists a new rollback review hash without remote writes. |
| `rollback --approval <file>` | Removes only reviewed unchanged migration-created replacements, then restores reviewed missing legacy payloads. Preserves newer human edits and all current Todoist fields. |

Stages persist as `INVENTORIED → DELETION_APPROVED → LEGACY_DELETED → RECONSTRUCTING → VERIFIED →
PLANNING_RELEASED`, with separately persisted normal-sync release. Rollback uses `ROLLING_BACK →
ROLLED_BACK`; planner authorities remain held afterwards. The migration directory is
`applications_dir/migration`, with atomic checksummed `state.json` and immutable hash-named exports.
Back up the **entire** directory after every maintenance-window step along with the four normal state
units. A missing/corrupt state file is a stop condition, not permission to reset the migration.

Inventory candidates need a current task association and exact legacy user/task-derived UID, a strong
provider version, and a restorable payload. Appointments, uncertain/unmapped history, duplicate UIDs,
native calendar series and unsupported resources remain visible exclusions. Full raw Google JSON or
original GET iCalendar is retained, including reminders, locations and unknown iCalendar properties;
normalized planner events alone are not a backup. The private export also holds raw task schedules/
recurrence, routing snapshot/fingerprints and complete plans/applications/decisions/deliveries file
contents (including cursors/conversations, excluding live lock files and recursive migration exports).
Credential paths must not overlap state units; authenticated `/user` response export is whitelisted to
ID/email because Todoist may return a secret token in that response. Exports are owner-only files under
an owner-only directory. Keep these private files out of repository artifacts/logs.

Review the actual private export before approval. Calendar/task schedule disagreements are flagged;
adopt intended calendar edits into Todoist through the existing reviewed process **before inventory**,
or list each exact resource ID in `declinedCalendarEdits` to explicitly choose fresh Todoist instead.
Reinventory before any deletion attempt can replace an unexecuted manifest and revoke its approval;
old hash-named exports remain. After a journaled deletion starts, retain its evidence and resume or
review rollback instead of replacing the manifest.

An approval JSON/YAML object uses these fields (placeholders are not authorizations):

```json
{
  "action": "delete",
  "manifestHash": "<status.manifestHash>",
  "reviewHash": "<status.manifestHash>",
  "actor": "<configured feedback.allowed_actors member>",
  "approvedAt": "<reviewed ISO-8601 instant>",
  "expiresAt": "<later ISO-8601 instant, at most 24 hours after approval>",
  "declinedCalendarEdits": [],
  "handoff": {
    "legacyWritersStopped": true,
    "otherMutationLoopsHeld": true,
    "scopeHash": "<status.scopeHash>",
    "checkedAt": "<recent ISO-8601 instant>",
    "evidence": "<operator-reviewed runner maintenance/service-stop evidence reference>"
  }
}
```

The handoff is an explicit, scope-bound **operator attestation**, not automatic inspection of another
runner's services. Do not fill it from an orb assumption. Deletion/reconstruction require disabled
daemon/sync/writeback/lifecycle configuration, the application-store writer lease and recent handoff
evidence. Local migration guards also hold all normal planner/lifecycle writes until release.
Expiry/version/account/routing drift requires renewed exact review; no boolean cleanup flag bypasses it.
For release, use a distinct action `release-sync` or `release-planning` with the exact manifest review
hash. For rollback, use action `rollback`, original manifest hash and the new `status.rollbackReviewHash`
as `reviewHash`, plus renewed writer-stop evidence. Approval records do not change services or acquire
credentials. Operator service changes require their separate authorization.

During the intentional empty-calendar window, rerun the same `delete` or `reconstruct` step after
restart. Unknown writes are reread before retry: an already absent approved deletion or exactly matching
created target is reconciled, not repeated. Any remaining uncertain deletion blocks **all** creation.
Reconstruction preserves outside-hours and date-only local dates, explicitly reports undated/ineligible/
unroutable tasks, never batches/plans, and never changes Due, native duration, Deadline, recurrence,
description/labels/priority or completion. Task edits **before** reconstruction supply its fresh inputs;
concurrent changes during a partial reconstruction/parity stop for review rather than restoring an old
task snapshot. After normal sync release, first-planning parity checks fresh live common baselines,
not the inventory's obsolete schedule.

For rollback, stop/hold new writers again, inspect subsequent edits and run `review-rollback` before
the separate approval. Changed replacements are conflicts, never deletion candidates. Conditional
CalDAV restoration uses the complete original payload and `If-None-Match: *`; Google restoration uses
`events.import` for a private copy with the original iCalUID and records the **new** provider ID/ETag.
Read-only Google identity/version fields are retained in backup but regenerated by the provider;
full writable event fields are verified on restoration. Never supply both Google `id` and `iCalUID`.
Ambiguous imports reconcile the exact migration marker and payload before any retry. Do not restore
old Todoist Due/completed counts, blindly reinstall old application snapshots/cursors, or restart
legacy automatically. Reconcile current local/remote ownership and obtain new exclusive-writer/service
approval before any operational restart.

Hermetic acceptance lives in `ProjectionMembershipE2ESpec` (real HTTP adapters, durable restart, CLI,
daemon and approved plan application; only provider servers are fixtures) and
`RoutedProjectionGatewayWireMockSpec` (including original CalDAV payload restoration). The combined
scenario runs inventory/deletion, individual reconstruction, parity/release, approved grouping, drag,
proportional resize, split/route move and interrupted native-departure recovery without planning.
It also removes Due/inclusion and verifies deletion evidence without changing unrelated task fields.
These checks do **not** satisfy the separately authorized isolated live or production gates.

Provider contracts: [Todoist API v1](https://developer.todoist.com/api/v1/) (`/user` and Sync
`item_update.duration` with `{amount, unit}`), [Google events.import](https://developers.google.com/workspace/calendar/api/v3/reference/events/import),
and [CalDAV RFC 4791 §§5.3, 7.8](https://www.rfc-editor.org/rfc/rfc4791).

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
- `ai-auth-login`, `ai-auth-status`, `ai-auth-logout`: require `--ai-provider codex|grok`; login
  defaults to `--auth-flow device`, status alone accepts `--remote`, and all accept `--json`. OpenAI
  login requires explicit browser mode; its unproven Codex CLI device import fails closed. xAI uses
  direct RFC 8628 device login; browser mode fails closed. Neither implemented flow executes a vendor
  CLI or reads personal credentials. Merging leaves both disabled until explicitly configured,
  authenticated, and enabled. See
  [`AI_SUBSCRIPTION_AUTH.md`](AI_SUBSCRIPTION_AUTH.md).
- `planner-daemon`: primary long-running multi-horizon planning, Slack proposal/thread feedback, and graceful shutdown.
- `planner-sync`: independently authorized routed mirroring; explicit start/end required, no plan generation.
- `planner-sync-preview`: read-only routed reconciliation inspection; explicit start/end required.
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
or the request label. Deadline classification is independent of this lifecycle gate. `planner.tasks.default_deadline_mode`
defaults to `hard` for recurring and ordinary tasks. Optional `hard_label` and `soft_label` both
default to no match and override that default; when both match, hard wins and the proposal names the
conflict. A label literally named `hard` has no meaning until configured. Nested
`recurrence.hard_label` and `recurrence.hard_deadline_soon_days` are rejected. Move them to
`planner.tasks.hard_label` and `planner.tasks.hard_deadline_soon_days`. Four windows
(`hard_deadline_soon_days`, `soft_deadline_soon_days`, `messaging.hard_deadline_warning_days`,
`messaging.soft_deadline_warning_days`) each default to seven local calendar days and do not imply
each other. Select `default_deadline_mode: soft` to keep the previous unlabeled soft treatment.
Installations that previously relied on the implicit `hard` label must set `hard_label` explicitly.

Hard Deadlines still cannot finish late. The scheduler protects capacity a feasible hard Deadline
still needs, including against an earlier soft task, and keeps manual or frozen placements even when
that leaves the hard task unscheduled. A soft task may run first when the hard work can still finish
on time. Soft urgency rises with the approaching civil date and with scarcity of remaining feasible
capacity. Unknown coverage adds no invented free time. Inside its urgency window, a soft P4 outranks
P1–P3 work with no Deadline, with or without Due, without changing native priority. Overdue soft work
keeps that elevated urgency.

Proposal warnings use the warning window, not the urgency window. A task is at risk when the selected
plan leaves it unscheduled or finishing after Deadline. An unscheduled task whose Deadline is after
the proposal horizon is marked unverified, not impossible. Slack shows those rows in a native table
with a proposal-specific number, linked task, project, Deadline, hard/soft mode, and P1–P4 priority.
Numbers stay bound to that proposal and thread. A numbered reply may change only the requested native
Deadline or priority after the configured interpreter returns a complete action set. Any unresolved
instruction asks in the same thread and writes nothing. A fully resolved authorized reply is the write
authority; it does not approve Calendar apply and does not widen temporary AI planning overrides.
When a proposal is revised in the same thread, start each Deadline reply (including clarification)
with the exact `proposal ...` line shown in that revision. This prevents a reply to an older table
from silently selecting a different task in the newer table.

Before every edit, SmartPlanner rereads the task and rejects changed or unavailable preconditions;
it rereads the edited field afterward and reports an unverified outcome as unknown. Todoist's
documented task update does not expose a conditional version check in this integration. An external
edit between the final read and the unconditional write can therefore still be overwritten, including
human description edits during a lifecycle marker update. Post-write verification cannot recover such
an edit. Avoid editing the same task concurrently with an authorized Deadline reply.

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
