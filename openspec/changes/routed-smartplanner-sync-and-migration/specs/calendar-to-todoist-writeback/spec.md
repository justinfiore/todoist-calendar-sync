# Spec Delta

## MODIFIED Requirements

### Requirement: Only synced events are writeback candidates
Legacy writeback SHALL accept only a derived task UID associated with one open task. SmartPlanner SHALL accept only owned current-occurrence events in explicitly managed destinations with verified single-task or focus-group membership and intervals. Missing, ambiguous, advanced or historical associations SHALL NOT authorize writes to active tasks.

#### Scenario: Legacy synced event is moved
- **WHEN** a calendar event whose UID was derived from a Todoist user and task is moved to a different start time
- **THEN** the system SHALL treat that event as a writeback candidate for that task

#### Scenario: Planner-owned event is moved
- **WHEN** an event on a managed destination carries planner ownership and maps to one current scheduled task
- **THEN** the system SHALL treat that event as a writeback candidate for that task

#### Scenario: Owned focus group is edited
- **WHEN** an owned event maps to several current tasks with verified ordered intervals and no conflicting edits
- **THEN** SmartPlanner SHALL use the specified group shift/proportional-resize policy instead of refusing solely because several tasks are associated

#### Scenario: Unrelated calendar event is ignored
- **WHEN** an event was not created by this sync, lacks ownership, or lacks a valid current task/group association
- **THEN** the system SHALL NOT update any Todoist task from that event

### Requirement: Newer last-modified time wins
For legacy due-only writeback, strictly newer last-modified time SHALL win, with missing/equal timestamps withholding writes. Routed SmartPlanner SHALL first compare scheduling fields against a verified common baseline: a one-sided change with valid newer timestamps SHALL be mirrored, while competing changes on both sides SHALL be reported as a conflict rather than resolved by clocks alone. Calendar-driven duration writes SHALL require explicit SmartPlanner sync/writeback authority.

#### Scenario: Calendar modification is newer
- **WHEN** a valid candidate has a one-sided newer calendar start change
- **THEN** the system SHALL update the task Due to that start without changing Deadline, content or completion
- **AND** for enabled routed SmartPlanner, an accompanying valid end change SHALL update native duration under its single-task/group policy

#### Scenario: Todoist modification is newer
- **WHEN** a valid candidate has a one-sided newer Todoist schedule edit
- **THEN** the system SHALL update the calendar from the live Todoist schedule and SHALL NOT restore a prior plan's time

#### Scenario: Timestamp is missing or equal
- **WHEN** schedules disagree and either modification timestamp is missing or both are equal
- **THEN** the system SHALL leave both sides unchanged and SHALL record the disagreement

#### Scenario: Both schedules changed since baseline
- **WHEN** routed SmartPlanner observes conflicting edits on both sides since its last verified common schedule
- **THEN** it SHALL suspend affected writes and expose both schedules for resolution regardless of timestamp ordering

### Requirement: Self-inflicted calendar writes are not writeback
The system SHALL recognize its own verified scheduling writes, including Due, native duration, member intervals and destination changes, so their echoes cannot trigger another group transformation or erase a newer manual edit. Legacy due-only echo behavior SHALL remain compatible.

#### Scenario: Sync writes the event it just read from Todoist
- **WHEN** the system updates a calendar event from Todoist and the next change is that same write
- **THEN** the system SHALL NOT issue another Todoist schedule update for that echo

#### Scenario: Group resize produces member changes
- **WHEN** Todoist returns member Due/duration changes produced by an in-progress verified group resize
- **THEN** sync SHALL reconcile them with that operation and SHALL NOT split those members as if they were independent user edits

### Requirement: Writeback runs in legacy sync and SmartPlanner
Legacy SHALL retain enabled newer-wins due writeback. Routed SmartPlanner SHALL reconcile manual edits on an independently scheduled sync cadence and before planning/apply, without requiring a plan to propagate them. Preview operations SHALL remain read-only; independently enabled sync authority SHALL be visible and SHALL NOT authorize plan application.

#### Scenario: Legacy sync sees a newer calendar move
- **WHEN** `legacy-sync` observes a newer calendar move for a synced task
- **THEN** it SHALL update Todoist Due and SHALL NOT restore the previous calendar time on that run

#### Scenario: SmartPlanner sees a newer calendar move
- **WHEN** enabled SmartPlanner sync observes a one-sided newer move of an owned current event, regardless of manual-retention policy
- **THEN** it SHALL adopt the move as the current schedule and invalidate stale proposal inputs before later plan application

#### Scenario: Todoist changes between planning runs
- **WHEN** a task Due or native duration changes and no planning run is due
- **THEN** enabled routed sync SHALL mirror it on the sync cadence without applying a proposal

#### Scenario: Preview does not write back
- **WHEN** SmartPlanner invokes preview
- **THEN** that operation SHALL NOT update Todoist or the calendar from a detected move or resize

## ADDED Requirements

### Requirement: Schedule writeback leaves independent task fields unchanged
SmartPlanner calendar schedule edits SHALL update only current Due and native duration plus validated sync provenance needed for recovery. Recurring Due changes SHALL retain the existing guarded recurrence verification and single intended-update repair contract. Sync SHALL NOT initialize/advance Deadline or alter human content, labels, priority, or completion as a side effect of moving/resizing an event.

#### Scenario: Native duration overrides a label-derived estimate
- **WHEN** an event representing a label-estimated 30-minute task is resized to 45 minutes
- **THEN** SmartPlanner SHALL write and verify native duration of 45 minutes without changing the duration label or Deadline
