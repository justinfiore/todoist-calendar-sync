# Proposal

## Why

SmartPlanner should own calendar events without abandoning the owner's existing label/project routing or manual scheduling in Todoist and Google Calendar. Today its single-output, plan-driven writes cannot independently mirror both sides' edits, support editing grouped events, or safely replace legacy calendar ownership.

## What Changes

- Add routed managed destinations using the existing ordered legacy label/project rules and inclusion semantics, with explicit provider calendar mappings. The owner's intended destinations are J&J, Todoist, Jack, Evan, Emily, Colin, and Kids; effective runner rules and IDs must be verified separately without importing credentials.
- Add independently scheduled sync-only operation that mirrors current Todoist schedules without generating or applying a plan. Separate explicit manual-edit synchronization authority from authority to choose new work times.
- Mirror owned event moves and resizes to Todoist Due and native duration; mirror Todoist Due/duration edits to calendar events. Preserve native recurrence, deadlines, historical occurrence identity, and manual changes until a fresh authorized plan supersedes them.
- Support focus-block edits: dragging shifts every member; resizing distributes the total proportionally with deterministic positive whole-minute rounding; editing one member in Todoist splits it into an individual routed event without rescheduling unaffected members.
- Strengthen persisted membership, conflict detection, echo suppression, partial-operation recovery, stale-plan invalidation, and ownership across multiple destinations. Partition focus groups by destination.
- Add reviewed, deletion-first migration: freeze overlapping writers, export and review identified legacy events, explicitly approve deletion, verify removal, rebuild individual planner events from current Todoist schedules in sync-only mode, verify parity, then separately authorize first planning runs.
- **BREAKING for opted-in routed sync:** calendar edits may now change native Todoist duration and all members of a group. A separately enabled sync loop may mirror manual edits without plan approval; existing preview-only use remains read-only. Existing single-output configurations and legacy mode remain supported outside migrated ownership scope.

## Capabilities

### New Capabilities

- `task-calendar-routing`: Authoritative task eligibility and ordered destination routing, routed ownership, and destination-compatible focus grouping.
- `planner-schedule-sync`: Independent manual schedule synchronization, group transforms, native duration writeback, conflict recovery, and plan/sync authority separation.
- `legacy-calendar-migration`: Reviewed legacy-event deletion, current-schedule reconstruction, verification gates, and recoverable onboarding/rollback.

### Modified Capabilities

- `calendar-provider-routing`: Validate one selected provider with either compatible single output or explicitly mapped routed managed destinations; preserve preview and lifecycle safety.
- `calendar-to-todoist-writeback`: Extend explicitly enabled SmartPlanner writeback to owned groups and duration changes while preserving legacy behavior and guarded recurrence.
- `google-calendar-api-gateway`: Replace the single-output-only mutation boundary with validated routed ownership and narrowly scoped relocation recovery.
- `calendar-change-detection`: Poll each configured managed destination independently, regardless of planning cadence, without scanning unrelated calendars.

## Impact

Affected areas include `TodoistCalDavSync` routing extraction; planner/provider configuration; Google/CalDAV gateways and `ManagedCalendarWriteGateway`; Todoist write ports; `PlanApplier`, daemon/orchestrator, batching and availability; event membership/occurrence metadata, hashes, mappings and durable receipts; CLI, example configuration, operational/QA documentation, and hermetic tests.

This change reconciles with the active `planner-integration` and `add-deadline-recurrence-support` contracts rather than assuming their deltas are already in main specs. No new dependency is assumed. Planning does not authorize implementation, production access or mutation, service enablement, legacy deletion, or credential transfer. Live acceptance remains a separate operator gate.
