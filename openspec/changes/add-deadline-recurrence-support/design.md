# Design

## Context

See `proposal.md` for motivation and scope. The current Todoist projection retains only the resolved Due instant and all-day flag; `TodoistRestGateway.updateTaskDue` writes a fixed REST `due_datetime`; `PlanApplier` uses that write while prohibiting Deadline changes. For recurring tasks this discards information required to preserve recurrence. Existing mappings are keyed by task ID, while block/event identity also includes scheduled start, so neither explicitly identifies a recurring occurrence.

Todoist Deadline is date-only and non-recurring. Todoist Due owns recurrence through a tuple including `date`, `string`, `is_recurring`, `lang`, and `timezone`. Native completion advances that Due and increments the active task's `completed_count`, but leaves Deadline stale. The public completed-task and activity reads did not expose recurring occurrence rows during the live spike.

The disposable Sync API spike validated recurrence-preserving current-occurrence moves, Deadline coexistence, native completion, and a second planning cycle for `every day`, `every week`, `every Monday`, `every month`, `every year`, `every! 2 days`, and `every day at 9am`. All temporary projects, tasks, and labels were removed. This is strong empirical evidence for the selected Sync shape, but remains covered by regression/live-contract tests because the public API documentation does not promise a named "next occurrence only" operation.

## Goals / Non-Goals

**Goals:**

- Preserve one native Todoist task as the user's actionable Today item while its managed Google event shows the same work time.
- Make native Todoist state sufficient to recover series and occurrence lifecycle after local-state loss.
- Preserve recurrence expressions without implementing Todoist's natural-language recurrence engine.
- Keep prior occurrence events as calendar history while allowing idempotent movement of the active occurrence.
- Fail closed across concurrent edits, ambiguous writes, daemon gaps, and partial initialization.

**Non-Goals:**

- Emulating Todoist recurrence or changing Todoist's native completion advancement semantics.
- Creating anchor tasks and generated work tasks in the default architecture.
- Treating Due time as a hard cutoff or adding time precision to Todoist Deadline.
- Reconstructing unobserved intermediate occurrences when several completions happen between polls.
- Using completed-task/activity feeds as required lifecycle inputs.

## Decisions

### 1. Keep one native recurring Todoist task

The managed object remains the user's original recurring task. This retains one Today item, native reminders, recurrence editing, completion history, project placement, labels, comments, subtasks, assignment, filters, and task quota behavior. SmartPlanner changes the active occurrence's Due and manages its date-only Deadline.

An anchor/generated-work-task architecture is rejected as the default. Although it separates recurrence from scheduling cleanly, it creates duplicate-looking tasks, moves completion away from the native recurring item, complicates reminders/comments/subtasks/assignees and filters, consumes quota, and makes voice capture require later transformation. It remains a fallback only if Todoist removes the validated Sync behavior.

Completing then reopening a task is also rejected. Native recurring completion already advances the series and records an occurrence; reopening is not a generally documented recurring-task lifecycle operation after the short UI Undo window, and using it would distort completion history.

### 2. Preserve and update Due through Todoist Sync

The Todoist boundary will fetch the latest task immediately before mutation and issue a Sync `item_update` command whose `due` object changes only `date` while copying `string`, `is_recurring`, `timezone`, and `lang` from that live task. It will use a stable command UUID, then re-fetch and classify the result.

This follows Todoist's own open-source MCP implementation and the successful live spike. REST `due_datetime`/`due_date` updates are not used for recurring tasks because creating Due from a concrete value can replace recurrence. There is no separately documented public "next-occurrence-only" flag; the chosen operation is the empirically validated due-object update used by Todoist's implementation.

The gateway must distinguish rejected, committed, and ambiguous outcomes. An ambiguous command is never blindly replayed: re-read first, accept a matching postcondition, retry only against unchanged preconditions when absence of the mutation is proven, otherwise stop for reconciliation.

### 3. Treat recurrence as an opaque tuple

SmartPlanner compares and copies the complete recurrence tuple but never parses `due.string` to compute later dates. Todoist owns dialect, locale, relative-vs-strict recurrence, timezone, DST behavior, and next-occurrence calculation.

On native completion, a higher `completed_count` and Todoist's advanced active Due define the new occurrence. This supports rich expressions without reproducing Todoist semantics. The cost is dependence on Todoist to expose a complete tuple and active task; if it does not, planning fails closed.

### 4. Use sentinel label plus a versioned description block

The configured sentinel label is visible discovery state: it tells users and filters that SmartPlanner has seen the task. It is not sufficient lifecycle authority by itself. A reserved, machine-readable, versioned block in the task description stores the portable series/occurrence state. The block has explicit delimiters and contains at least:

- schema version and planner ownership marker;
- series task ID and current `completed_count` occurrence sequence;
- last verified native Due and recurrence tuple fingerprint;
- current occurrence's Deadline source/mode and any pending legacy source date;
- last planner-applied Due, monotonic marker generation, and stable lifecycle command identity.

The original recurrence string and fields remain authoritative in Todoist Due and need not be duplicated in full when a collision-resistant fingerprint is enough to detect changes; the live tuple is always copied from Todoist, never reconstructed from metadata.

The marker is always a suffix at the end of the human description, after a Markdown divider and human-readable warning. Its fenced body is canonical single-line JSON: UTF-8, no insignificant whitespace, stable key ordering, and JSON escaping. For example:

~~~markdown
Remember to put the bins by the back gate.

---

**SmartPlanner metadata — do not edit**

```json
{"schema_version":1,"owner":"smartplanner","task_id":"6hgHR3qRjPrPGqM7","completed_count":12,"deadline_mode":"managed","deadline_source":"initial_user_due","deadline_date":"2026-10-09","pending_legacy_source_date":null,"last_verified_due":"2026-10-06T18:30:00-04:00","recurrence_fingerprint":"sha256:example","last_planner_due":"2026-10-06T18:30:00-04:00","marker_generation":3,"last_command_id":"example-command-id"}
```
~~~

Description is preferred over a task comment because it arrives with task reads, avoids comment pagination and notification/conversation clutter, and may be updated in the same Sync command as other task fields. Todoist updates the description as one field rather than appending atomically, so the implementation must fetch the latest value, preserve the human-authored prefix exactly, append the suffix once, and on later writes replace only a recognized suffix anchored at the end. It must never accumulate duplicate blocks.

After every description write, SmartPlanner re-fetches the task and verifies the entire merged description and lifecycle preconditions. If a concurrent human edit won and the live task contains the old/absent intended marker plus a changed human prefix, SmartPlanner rebases the same intended marker onto that latest prefix and retries with the same stable command identity. Retry is bounded to two additional attempts. It is permitted only when Due, Deadline, labels, recurrence tuple, `completed_count`, and prior marker generation still match the intended transition. A different valid marker generation, lifecycle drift, malformed/duplicate/relocated marker, ambiguous write that cannot be classified, or retry exhaustion fails closed for reconciliation.

This recovery handles the ordering where the human write lands last. Todoist exposes no documented compare-and-swap for description, so it cannot recover a human edit that lands first and is then overwritten by SmartPlanner's whole-field write. Minimizing marker writes to lifecycle transitions and keeping the post-write verification window short reduces but does not eliminate that residual risk.

A sentinel comment is the fallback if a spike shows description writes cannot be made safely. Comments preserve the user's description but add an API read/pagination path, may notify collaborators, clutter task conversation, and complicate selecting the authoritative version. Labels alone are too small for occurrence state and Due provenance. Encoding everything in labels pollutes filters and the UI. Hidden local state is rejected as authority because it is neither visible nor portable; existing local storage remains valuable operation evidence.

### 5. Classify new tasks automatically and migrate legacy tasks by label

Before SmartPlanner first writes Due, it classifies the live task and records Due provenance. A post-cutoff task with user-authored Due and no Deadline copies only Due's local date to Deadline. An existing Deadline is preserved. An undated task copies nothing, and its eventual SmartPlanner-selected Due is marked planner-authored so a later poll cannot manufacture a Deadline from it. If a classified task later receives a genuinely user-authored Due while Deadline remains absent, that post-rollout edit becomes a new automatic source date unless the task is already a pending legacy candidate.

Pre-cutoff Due-only tasks continue ordinary recurrence-safe SmartPlanning but do not get automatic Deadline conversion. SmartPlanner logs each candidate's name, ID, and stable Todoist URL. If scheduling moves its Due, the marker retains the original user Due date as the pending conversion source.

The selected defaults are `smartplanner-onboard` for migration request and `smartplanner-seen` for verified classification. On a legacy candidate carrying `smartplanner-onboard` without `smartplanner-seen`, SmartPlanner copies the pending source date, verifies Deadline and marker, adds `smartplanner-seen`, then removes `smartplanner-onboard`. An ambiguous result leaves the request label in place for reconciliation. This lets the owner migrate incrementally entirely in Todoist.

Initialization is ordered as one classified lifecycle transaction: capture live Due/Deadline/labels/description, determine provenance and conversion policy, write and verify any Deadline plus metadata, add and verify sentinel, then allow the newly classified plan to apply. If Sync supports safely combining fields, a single command is preferred; regardless of request count, no Calendar event is written from stale lifecycle state.

Rollback disables new onboarding and scheduling but leaves the sentinel and metadata intact so disabling the feature cannot make tasks look unseen. Removal is an explicit migration operation, not automatic rollback behavior.

### 6. Keep Deadline population separate from hard-deadline priority

Due's local date populates the same date-only Deadline whether or not `hard` is present. Due time is always an action-time preference and is never persisted as an exact cutoff.

Without `hard`, Deadline is a soft target: lateness is penalized, but stronger constraints may place work after it. With `hard`, the task must finish by the end of the Deadline date in the planner timezone. Hardness does not provide a permanent score boost. Inside configured `planner.tasks.hard_deadline_soon_days`, urgency rises monotonically as the date approaches and is weighted strongly enough that an approaching hard P4 task can outrank a non-hard P1 task. Fixed calendar occupancy and other true infeasibility constraints remain inviolable; if capacity cannot satisfy the date, the plan reports a hard-deadline risk.

After native completion of an onboarded recurrence, the newly advanced Todoist Due date replaces the stale Deadline before scheduling. For unconverted legacy recurrences, completion remains recurrence-safe but does not create or advance Deadline. Adding or removing `hard` invalidates any stale plan and triggers replanning; it does not alter recurrence state or Due-to-Deadline provenance.

### 7. Identify occurrences by task ID and completed count

The canonical occurrence key is `(todoistTaskId, completed_count)`. It is stable when SmartPlanner moves the current Due and increments when native completion advances recurrence. The lifecycle marker records the current sequence so local-state loss is recoverable.

Persisted mappings and managed event metadata will carry both series task ID and occurrence key. Current mappings keyed only by task ID must become occurrence-aware: an active-series index may point to the current mapping, while historical mappings/events remain addressable by occurrence. Block IDs must not use scheduled start as occurrence identity; changing a time updates the active occurrence's event rather than creating a new historical event. A new occurrence creates a new deterministic managed UID and never cleans up the prior occurrence merely because the task ID matches.

If `completed_count` jumps, SmartPlanner records the newly observed count, retains all known history, and does not invent events for missed counts. Counter regression or reuse is corruption/drift and fails closed.

### 8. Detect transitions from active-task polling

The daemon's ordinary active-task polling is the required detector. For each marked recurring task it compares live `completed_count`, Due tuple, Deadline, labels, description marker, and last verified Due. A count increase means native advancement. Same count plus changed Due means a user or external move of the active occurrence. Changed recurrence tuple means a recurrence edit. Removed recurrence, Deadline edits, or marker/sentinel mismatch each enter an explicit reconciliation state.

Completed-task and activity feeds may add diagnostics but cannot gate correctness: neither returned immediate recurring completion evidence in the spike. Poll cadence controls how quickly Todoist Today and Calendar realign after completion and will be documented as user-visible latency.

### 9. Preflight Todoist before Calendar and preserve local evidence

Apply re-fetches and validates the Todoist task immediately before writes. Todoist lifecycle writes and verification precede Calendar mutation, preventing a stale plan from creating a ghost event. Existing approval hashes must incorporate occurrence identity and relevant lifecycle preconditions.

Plans, mappings, application receipts, decisions, deliveries, and conversation state continue to store idempotency, diagnostics, command UUIDs, and recovery evidence. They may not silently substitute an old recurrence tuple, Deadline provenance, hard-label state, or occurrence number when the on-task marker disagrees. Recovery starts from Todoist, inventories managed Google events, then rebuilds local indexes.

### 10. Put stable Todoist deep links in Calendar descriptions

Todoist API v1 intentionally omits the former task `url` property. Its authoritative migration documentation defines task URLs as `https://app.todoist.com/app/task/<v2_id>`. Although copied UI links may include a title slug, the supplied test task confirmed that the documented ID-only URL and slugged URL both resolve. SmartPlanner therefore generates the stable ID-only form and never implements title slugging.

Managed event descriptions retain their ownership, block, plan, series, and occurrence markers, then include a human-readable Todoist section. Single-task events contain the task name and URL; multi-task focus events list each task name and URL. A title change updates display text without changing link identity.

## Failure and Recovery

| Failure | Required behavior |
|---|---|
| Missing/incomplete recurrence tuple | No task or Calendar mutation; report unsupported live shape. |
| Concurrent human Due/Deadline/description edit | Precondition mismatch; preserve live fields and request reconciliation. |
| Ambiguous Sync response | Re-read by stable command context; never blind retry. |
| Todoist committed, Calendar failed | Keep verified task state, record partial receipt, retry/reconcile only the same occurrence event. |
| Calendar committed, receipt failed | Discover by managed occurrence metadata/UID before creating anything. |
| Completion between plan and apply | Preflight rejects stale occurrence; next poll initializes new one. |
| Marker missing/corrupt/unknown version | Freeze lifecycle writes; preserve raw metadata for repair. |
| Sentinel missing but marker exists | Drift/repair, never first-observation conversion. |
| Local database lost | Rebuild from marked Todoist tasks and occurrence-tagged managed events. |
| Multiple completions between polls | Schedule current active occurrence only; retain known history and report gap. |
| Legacy onboarding write is ambiguous | Keep `smartplanner-onboard`, re-read live state, and never copy from a planner-authored Due. |

## Risks / Trade-offs

- **[Sync due-object behavior is empirically rather than explicitly guaranteed]** → Keep contract tests and a disposable live QA gate; fail closed if re-fetch shows tuple drift.
- **[Description metadata is visible and can conflict with human editing]** → Use a compact delimited block, merge against a fresh read, verify post-state, and document it as user-visible planner state.
- **[Soft and hard tasks share Todoist's date-only Deadline UI]** → Keep `hard` visible as a label and explain its scheduler-only urgency/feasibility effect.
- **[Legacy candidate Due may be moved before opt-in]** → Persist the original user-authored source date in on-task metadata and never derive conversion from the later planner Due.
- **[Polling introduces completion-to-reschedule latency]** → Define and monitor cadence; do not add unreliable feed authority.
- **[`completed_count` behavior could change]** → Validate monotonicity and occurrence transition in automated/live tests; freeze on regression.
- **[Sentinel labels add filter/UI surface]** → Use one configurable, documented label; this visibility is intentional and preferable to hidden migration authority.
- **[Historical events increase calendar volume]** → Retention is intentional completion history; cleanup requires a separate explicit policy.

## Migration Plan

1. Add read-only projection and diagnostics for full Due tuples, completion count, description marker, sentinel, and `%hard`; recurring writes remain disabled.
2. Add recurrence-safe Sync gateway operations, classified verification, and hermetic contract tests. Repeat the disposable live matrix without touching independent QA fixtures.
3. Add first-observation classification and legacy candidate logs with ID-only Todoist links. Establish the automatic new-task cutoff and test incremental `smartplanner-onboard` → `smartplanner-seen` conversion.
4. Add soft Deadline scoring, configurable approaching-hard urgency, and native completion advancement while Calendar writes remain withheld in shadow mode.
5. Migrate mappings and event ownership metadata to occurrence identity, add Todoist links to managed descriptions, reconcile existing managed events, then enable guarded Calendar apply.
6. Expand from a small labelled migration cohort to all configured eligible new tasks after observing at least daily, weekly, monthly, yearly, strict-relative, timezone/DST, recurrence-edit, hard-window, and recovery cases.

Rollback disables onboarding and recurring apply, preserving markers, sentinels, mappings, and managed events for diagnosis. It does not restore previous Due values automatically or delete history. A forward repair reconciles from live Todoist state before re-enabling.

## Post-Fix Live QA Matrix

Use new disposable projects/tasks/labels and separate evidence from the independent QA campaign. Verify each case through creation, first schedule, native completion, Deadline advancement, second schedule, and cleanup:

| Dimension | Cases |
|---|---|
| Recurrence | daily, weekly, weekday-specific, monthly, yearly, `every!`, richer opaque expression |
| Due shape | date-only, local datetime, timezone-aware datetime, recurrence with explicit time |
| Constraint | soft Deadline before/after target, hard outside/at/inside soon-window boundary, P4 hard versus P1 ordinary, infeasible hard date |
| Lifecycle | post-cutoff Due-only, existing Deadline, initially undated, planner-authored Due, logged legacy candidate, label-approved conversion, count jump |
| Edits | recurrence rule changed, same-rule Due move, Deadline change, add/remove `%hard`, remove recurrence, delete sentinel, delete/corrupt marker |
| Apply failure | ambiguous Todoist response, Todoist success/Calendar failure, Calendar success/receipt failure, completion between plan/apply |
| Identity | same-occurrence reschedule updates one event; next occurrence creates a different event; old event remains |
| Recovery | local state removed, daemon restart, marker-only reconstruction, duplicate managed-event detection |
| Preservation | reminders, project, labels, comments, human description, subtasks, assignee, native completion history, Calendar task links |

For every recurring case assert the exact pre/post Due tuple, Deadline date/mode, marker version and occurrence key, `completed_count`, Todoist Today placement, matching Google time, ID-only Todoist link, managed UID, and absence of duplicate or ghost events.

### Automation boundary

Before live QA, reproduce every deterministic transition with Spock and WireMock using the existing `ProductionHttpGatewaysWireMockSpec`, `GoogleCalendarApiGatewayWireMockSpec`, and `PlanApplierGoogleCalendarApiGatewaySpec` conventions. Mocked sequences must verify exact Sync command payloads, live re-reads, Google request bodies, call ordering, no-write branches, timeout/ambiguous-write reconciliation, and mutation counts—not merely final domain objects.

Keep live cases only where they establish an external product contract or rendered UX: Todoist's native recurrence advancement, `completed_count`, labels and Deadline UI, reminder/property preservation, clickable Google description links, Today/Calendar alignment, real daemon polling/restart, and cleanup. Add these as a dedicated recurrence/deadline section in `docs/SMARTPLANNER_QA_RUNBOOK.md` during implementation, then store the run under a new dated evidence path with normalized snapshots, command statuses, manifests, hashes, and redaction results. Do not amend or regenerate the independent 2026-10-01 evidence package.

## Open Questions

- What poll interval provides acceptable post-completion realignment latency within the owner's Todoist/API usage limits?
