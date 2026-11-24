# Design

## Context

See `proposal.md` for motivation and `specs/` for behavioral contracts. This is a cross-module ownership/migration change and needs this design before implementation.

Observed repository boundaries:

- `TodoistCalDavSync.identifyCalendarName` implements ordered first-match label/project rules; inclusion is separately label OR project based. Legacy `updateCalendars` uses user/task-derived UIDs and deletes those UIDs from other configured calendars before writing.
- `ProductionPlannerOrchestrator.normalizedEligibleTasks` excludes manual tasks and optionally applies eligible labels; it does not apply legacy routing. `ProjectBatcher` groups by project/context, not destination.
- `PlanApplier` writes one output calendar plus Todoist Due. It uses occurrence-derived IDs for recurring tasks; `ApplicationStateStore` retains occurrence history. `ManagedCalendarWriteGateway`, provider configuration and Google/CalDAV adapters assume one output.
- `PlannerCalendarWritebackService` polls one output, accepts single-task mappings, changes Due but not native duration, and defers Todoist-winning calendar writes to plan apply. Daemon polling is tied to planning; the recurrence items loop is independent and currently not passed through ordinary planning eligibility.
- `ScheduledBlock.memberIntervals` describes ordered contiguous members. `TodoistWriteGateway` has no native-duration write operation. `TodoistRestGateway.updateTaskDue` permits only ordinary Due; recurring Due goes through `GuardedRecurringDueUpdate`.

Production legacy-active/planner-disabled state and the seven desired calendar names are owner-supplied. Effective runner predicates, provider IDs, scopes, service configuration, interval and writeback settings have not been inspected. No credential or runtime state transfer is implied.

## Goals / Non-Goals

**Goals:** one event owner per task scope; schedule mirroring without optimization; grouped manual edits with durable recovery; existing routes with strict ownership; deletion-first onboarding that cannot accidentally start planning.

**Non-Goals:** native Google recurring series; parsing Todoist natural-language recurrence; reconstructing missed historical occurrences; production execution during development; automatically provisioning calendars; a new inbound webhook service; guaranteeing atomic distributed transactions or zero calendar downtime during deletion-first migration.

## Decisions

### 1. Reuse routing semantics, separate projection scope from planning scope

Extract the pure inclusion and ordered-rule evaluator from `TodoistCalDavSync` and characterize it through public routing behavior before changing either caller. Both legacy and routed planner callers use this evaluator; do not instantiate the legacy network sync service merely to resolve a destination. Preserve exact case, `p:` project matching, `AND`/`NOT`, rule order, prefixes, and repeated-rule OR behavior. Reject unsupported/malformed expressions rather than invent a more expressive language.

Proposed configuration adds `planner.routing.mode: legacy_rules` with explicit inclusion lists and ordered rules using the existing rule field names, plus provider mappings explicitly marked as managed destinations. Absence retains `output_calendar` compatibility; mixing both active models is invalid. Documentation shows sanitized illustrative mappings only. Import/copy effective non-secret rules once under operator review; do not require planner to load a legacy configuration containing credentials. Persist a canonical routing fingerprint to detect drift; changes to rules require a new reviewed routing snapshot before automatic relocation/retirement.

Projection scope is legacy inclusion plus a resolved managed destination, independently of optimizer eligibility. Thus `manual` tasks and project-included tasks still mirror their current schedules, but remain excluded from automatic planning as appropriate. Planning candidates additionally satisfy existing planner eligibility; a routable undated candidate can be scheduled by an authorized plan, not sync. Apply the correct scope to recurrence polling too: no lifecycle writes on excluded/manual tasks merely because they are returned by a full Sync poll.

Alternative rejected: copy label matching into a second parser or route by calendar display name without managed-ID validation. Both drift from production semantics and undermine fail-closed ownership.

### 2. Introduce explicit sync authority and a separate cadence

Add proposed operations `planner-sync` (one-shot) and `planner-sync-preview` (no remote writes); daemon configuration `planner.sync.enabled` defaults false with validated `interval` (proposed default PT1M). Existing `planner.calendarWriteback.enabled` additionally gates reverse Due/duration mutation. A sync-only daemon must be able to run with no planning schedules; planning remains separately enabled and uses existing mode/actor/exact-approval policy. Status reports forward projection, reverse sync, lifecycle and planning authorities separately.

Use the daemon's serialized mutation boundary for sync, lifecycle and apply, with a process-level store/ownership lock so another planner process cannot mutate the same scope. This does not magically lock Todoist or Google users: preflight and conditional writes still matter. Each sync cycle reconciles pending operations first, reads/stages Todoist deltas and each managed calendar feed, then mirrors changes. Cursors advance only after changes are durably staged/processed. Use a dedicated durable Todoist cursor/inbox, never the legacy cursor or an independently advanced lifecycle cursor.

Factor scheduling reconciliation into a coherent service around existing stores/gateways. Do not call `preview` or manufacture a fake approved plan for sync. Existing standalone capacity/preview must remain read-only even if the config contains enabled daemon sync settings. Remove implicit daemon reverse-write calls from proposal generation in favor of the explicit sync boundary. If legacy-compatible writeback outside routed sync remains supported, retain its previous opt-in semantics.

Alternative rejected: sync only when applying a proposal. That would not meet independent manual mirroring or initial reconstruction requirements.

### 3. Store verified schedules and portable group membership

Extend mappings and event metadata with schema version, logical projection identity, provider resource ID/destination, current task/occurrence identities, ordered member intervals, group revision, routing fingerprint, verified task Due/native and effective durations, task timestamps, calendar start/end/ETag and operation provenance. Native duration absence must remain distinct from a resolved label/default duration.

Persist portable membership in versioned owned event metadata/description as well as local state, with strict size/format checks and preserved human description content. This allows reconciliation after local-state loss rather than guessing members from links or titles. Unknown schema, stripped markers, changed occurrence counts, duplicate identities or disagreement between portable/local records freeze affected writes. Migrate old state conservatively; trustworthy single-task events can be reconstructed, but old multi-task events lacking intervals require explicit reconciliation, not guessed adoption. Provenance updates to an existing Todoist lifecycle suffix must preserve human content and obey its merge/preflight rules without initializing Deadline or labels during sync.

Keep existing recurring task/completed-count identity and historical mapping semantics. A stable occurrence keeps identity when moved; completion creates a new current occurrence, leaving historical events untouched. Non-recurring single projections retain compatible identity. Group identity derives from occurrence membership, not start time; membership edits create revised projections with an explicit retirement relationship. No calendar RRULE is written.

### 4. Reconcile fields against a baseline, not provider clocks alone

Compare relevant schedule/membership/route fields against the last verified common state. Ignore unrelated title/priority changes for scheduling conflict detection, though their `updated_at` cannot prove a schedule edit by itself. Require complete timestamps and source versions for reverse disagreement resolution. One-sided changes are mirrored; both-sided divergent schedule changes, equal/missing clocks, all-day/timed conversions, or missing baselines become visible conflicts. Exactly matching sides can establish a baseline without remote mutation.

For an existing owned event with lost baseline and disagreement, do not silently choose Todoist. A brand-new projection or explicit migration reconstruction is different: fresh Todoist defines the initial schedule. Calendar-only end changes must be detected even when start is unchanged. Normalize native duration hours/minutes to integer minutes without changing recurrence timezone semantics. All-day reconstruction uses local date and exclusive next date, with no invented time; all-day/timed calendar conversion is withheld for review. Timed Todoist edits to a previously all-day task are explicit user intent and can update its projection.

A manually deleted owned event is recorded as cancellation/conflict rather than immediately recreated, preventing a deletion/recreation loop. Todoist Due removal retires the owned active projection without deleting the task. Completion/deletion stops active-task writeback and retains known past occurrence history; unverified future orphan projections require reconciliation. Do not imply that a calendar record proves completion.

Write native duration through a narrow new scheduling gateway operation using the provider's supported request shape, verified with current official Todoist API documentation and WireMock before implementation is accepted. Fetch the live task before each write; preserve Deadline, labels, priority, human description and completion. Recurring Due keeps the shared guarded operation, including its existing exactly-one intended-update repair; a separate duration mutation must verify the recurrence tuple/completed count too. If the provider cannot express duration safely alongside recurring Due, use independently verified steps in a journal, never a datetime-only recurring POST.

### 5. Transform groups deterministically

Dragging with unchanged span adds the same instant delta to every member start/end, retaining durations. A combined drag/resize applies the new start plus new allocations. Fixed-zone and floating recurring Due formatting reuse existing guards; DST tests compare actual intervals and preserved recurrence timezone, not naive string arithmetic.

For a target of T whole minutes and n members with positive verified weights d:

1. Reject T < n, non-whole-minute spans, invalid intervals or incomplete memberships before any member write.
2. Allocate real-valued quotas proportionally among remaining members. Fix any quota below one minute at one, subtract those allocations and recompute the other quotas until all satisfy the minimum. This is constrained proportional allocation, not subtracting one minute from every weight before scaling.
3. Floor the remaining quotas and distribute remaining minutes to the largest fractional remainders, with ties in persisted member order. Use exact integer/rational comparisons to avoid floating-point tie drift.
4. Lay out consecutive intervals at the new start and write each member's Due and native duration. The sum must exactly match the calendar span.

Examples: [30,60] to 60 yields [20,40]; [10,10] to 21 yields [11,10]; [1,1,98] to 3 yields [1,1,1]. The last case prevents a naive floor/minimum clamp from overallocating. Resize ratios use the last verified member weights, never already partly updated task durations after a crash.

A genuine Todoist Due/native-duration edit splits the changed task out. Remove it from the old membership, preserve all other task times/durations, and emit maximal consecutive remaining runs (singletons become individual events). This deliberately avoids shifting unrelated tasks to close a hole or leaving a group claiming the removed task's time. If several tasks were independently edited, split all changed members together. Changes known to come from our own pending group operation are echoes, not split triggers. Route changes use the same mechanism if a member leaves the group's destination.

Alternative rejected: assign all resized time to the last task; the owner explicitly selected proportional allocation. Automatic compaction on split is also rejected because it silently moves tasks the user did not edit.

### 6. Journal multi-resource changes and narrowly permit relocation pairs

Before mutation, persist an operation ID, immutable source baselines, target states, member/resource preconditions and progress. Preflight every member before the first mutation, then recheck immediately before each task write. Read back every affected task and conditional calendar mutation. Group writes are not atomic: stop on failure/ambiguity, retain actual per-member success and block overlapping sync/apply until reconciliation. Never blindly replay an entire resize or roll back over a newer human edit.

For split/replacement, create and verify target projections, then retire old owned representations with exact IDs/versions; commit membership only when all targets and retirements reconcile. Ordinary relocation likewise verifies the destination before retiring the source. This may briefly duplicate a projection, unlike the separately approved deletion-first migration. Operation state identifies that exact transitional pair/set. Ordinary global lookup must still throw for unrelated duplicate identities; relocation recovery uses exact recorded provider resource IDs, not a weakened generic collision rule. Partial updates/retirement failures remain visible.

Google writes use current ETags/If-Match; CalDAV uses equivalent conditional resources. Unknown creates require identity lookup/readback before another create. Self-write echo records cover event start/end/destination and task Due/duration/member revisions, so provider timestamps alone do not misclassify our operation as a human edit.

### 7. Bind plans to synchronized inputs and reconcile capacity

Extend `PlanHash` and application preflight to bind native/effective duration, Due, occurrence/recurrence, route/rule fingerprint and current projection/membership revision. The plan carries a source snapshot as well as proposed target times, so intended schedule differences are not confused with stale input. An edit after approval invalidates the plan before any dependent mutation. Keep existing safe-only/protected policies and fully-automated refusal.

Build the planning baseline from reconciled placements and preserve manual intent under stability policy. A newly authorized plan can deliberately move it; stale proposals cannot. Availability distinguishes replaceable projections from real appointments and retained/frozen/manual occupied intervals, rather than treating all managed calendar contents as free. Partition `ProjectBatcher` by route in addition to project/context and use routed ownership in `PlanApplier`. Sync-only never regroups tasks into new focus blocks; only planning chooses grouping.

### 8. Make migration an explicit deletion-first state machine

Proposed CLI migration suboperations are inventory/export, approve/delete, reconstruct, verify and rollback; exact flag spelling can follow existing operation dispatch conventions. Keep destructive legacy deletion separate from normal planner-owned delete ports: it receives only a manifest-bound capability, never general permission to delete unowned events. This narrow migration path is needed precisely because legacy resources lack planner ownership markers.

Stages are INVENTORIED, DELETION_APPROVED, LEGACY_DELETED, RECONSTRUCTING, VERIFIED and PLANNING_RELEASED, with per-item failed/unknown states. Bind review to a manifest hash/account/routing snapshot and expiring resource preconditions, not merely a boolean flag. Retain private backups of full event payloads, task schedules/recurrence metadata, config fingerprints and complete application/cursor/conversation state; never export secrets into repository artifacts.

The owner chose deletion first over create-before-delete. Do not create any replacement until every approved deletion is verified absent. Positively identify legacy resources by account/task-derived UID plus reviewed task association/resource payload. Never delete all events from a destination calendar. Unknown historical mappings stay excluded. Pending calendar-only moves must be adopted into Todoist or explicitly rejected in the review before deletion.

After deletion, fresh Todoist is authoritative for reconstruction: create individual owned events, preserving current Due/duration/routing even outside working windows and with no conflict-driven optimization. Record all skipped/missing-Due/unroutable tasks. Verify every expected live resource, no surviving approved legacy IDs, no duplicates and unchanged Todoist fields. Any mismatch keeps planning held. Explicit operator release enables first proposal runs; their application still requires normal authorization.

Existing `migrateCalendarEventsFromV9` is an API-ID migration, not this ownership handoff, and must not be repurposed as an unconditional cleanup. The new manifest supplies recovery evidence even if the application stops in the intentional empty-calendar window.

## Risks / Trade-offs

- Calendar gap after approved deletion → prevalidated mapping/credentials, full export, resumable reconstruction, bounded operator maintenance window; accept this gap as the owner's chosen order.
- Provider constraints/partial group edits → validate documented duration payloads, journal immutable intent, verify each step, report partial results; do not promise transactionality.
- Rule/config errors can relocate or retire events → review/fingerprint routing changes, dry run, narrow managed destination allowlist and ownership/version revalidation.
- Remote users race with reads → conditional calendar writes plus Todoist pre/post rereads and conflict freeze; no claim of unavailable provider-level task CAS.
- Group membership/state portability can exceed provider metadata limits → bound groups/metadata size before planning and reject unsafe writes; retain durable recoverable memberships.
- Existing active specs still mention due-only/single-output behavior → this delta explicitly replaces affected main requirements; during implementation reconcile active recurrence/planner integration deltas without deleting their independent safety contracts or marking live gates passed.
- Long-running sync may stall → expose last success, configured interval, retry/backoff and pending/conflict counts; serialize writes and keep unaffiliated read-only work available. Never interpret an unavailable feed as no changes.

## Migration Plan

1. Implement and validate hermetically; keep all new remote-write capabilities opt-in. Maintain legacy/single-output compatibility tests.
2. Run isolated authorized live acceptance for routing, individual/group edits, recurrence, restart recovery and deletion/reconstruction. Retain sanitized evidence; unavailable accounts leave live gates open.
3. On the authorized production runner, verify effective non-secret routing/inclusion/prefixes, provider account/IDs/scopes, timezone and state backups. Do not assume this orb sees that configuration or copy QA credentials.
4. Approve a reviewed manifest and maintenance window, stop legacy for the migrated scope and hold all planner sync/lifecycle/planning writes. Reread versions and execute approved deletion. Unknown/changed resources stop the transition.
5. Only after deletion verification, run reconstruction with Todoist writes and lifecycle processing disabled. Resume idempotently after failures; verify parity before enabling normal bidirectional sync.
6. Release planning separately, first proposals then a small fresh approved application. Retain exclusive routed ownership; legacy must not restart on overlapping tasks.
7. Roll back only under a new explicit action: stop new writes, inspect subsequent edits, remove unchanged migration-created replacements, restore only reviewed missing legacy payloads, reconcile complete local state with live resources, and re-enable legacy only after exclusive ownership is proven. Do not restore old Todoist Due/completion counts or stale cursors over newer state. Changed resources require owner resolution; a local backup restore alone cannot undo remote writes.

## Open Questions

No unresolved product-policy question blocks this proposal. Effective production rule predicates, IDs and provider permissions are deliberately deferred operational inputs, required before live migration rather than guessed in development. The exact current Todoist duration payload and provider limits must be confirmed during adapter implementation against official documentation; failure to meet the specified contract blocks acceptance rather than changing behavior silently.
