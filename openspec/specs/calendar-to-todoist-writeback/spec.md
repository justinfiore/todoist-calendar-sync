# calendar-to-todoist-writeback Specification

## Purpose

Propagate a newer calendar time change for a synced task back to the Todoist due, in both legacy sync and SmartPlanner, without treating unrelated calendar edits as tasks.

## Requirements

### Requirement: Calendar writeback is off unless enabled
The system SHALL NOT read calendar changes for writeback or update a Todoist due from a calendar event unless configuration sets `calendarWriteback.enabled` to `true`. An omitted key or `false` SHALL preserve one-way Todoist-to-calendar sync. A non-boolean value SHALL fail configuration validation before any sync or planner run.

#### Scenario: Flag omitted
- **WHEN** the config file omits `calendarWriteback.enabled`
- **THEN** a calendar move SHALL NOT update Todoist and SHALL remain subject to the existing one-way calendar write

#### Scenario: Flag enabled
- **WHEN** `calendarWriteback.enabled` is `true`
- **THEN** the newer-wins writeback rules in this capability SHALL apply

#### Scenario: Flag is not a boolean
- **WHEN** `calendarWriteback.enabled` is present and is not `true` or `false`
- **THEN** configuration validation SHALL fail before a sync or planner run starts

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

### Requirement: Recurring writeback uses the guarded recurrence update
A newer calendar move for a recurring task SHALL update only that task's next due through the same guarded recurrence operation SmartPlanner apply uses. The operation SHALL keep the live recurrence expression, language, timezone, and recurring flag, except that post-write verification SHALL accept a present non-null timezone when the saved timezone was explicitly null. The operation SHALL NOT complete the task. A datetime-only due POST SHALL NOT be used for a recurring task.

#### Scenario: Recurring synced task is moved on the calendar
- **WHEN** a writeback candidate belongs to a recurring Todoist task, the calendar start differs from the due, and the calendar modification is strictly newer
- **THEN** the system SHALL replace only the next due date through the shared guarded operation and SHALL NOT complete the task

#### Scenario: Live recurrence tuple is incomplete before writing
- **WHEN** the live recurring due tuple is incomplete before the update
- **THEN** the system SHALL record the skip, SHALL send no due update, and SHALL NOT complete the task

#### Scenario: Provider populates an explicitly null timezone
- **WHEN** the saved timezone is explicitly null, the post-write reread contains a non-null timezone, and all other guarded postconditions hold
- **THEN** verification SHALL succeed without a repair solely for that timezone difference
- **AND** this exception SHALL apply to verification after either the initial write or the one permitted repair
- **AND** missing timezone fields and differences from an expected non-null timezone SHALL NOT be ignored

### Requirement: Recurring due verification permits one intended-update retry
The shared guarded recurrence operation used by apply and writeback SHALL save the pre-update task title and full due information before writing. It SHALL construct the intended due from that saved information with only the next date replaced. If a reread after the initial write does not confirm the intended due date and preserved recurrence expression, recurring flag, language, and timezone, including when that reread is unavailable, the operation SHALL retry the intended update exactly once and SHALL reread again to verify it. The retry SHALL use the saved pre-update recurrence information, not the potentially damaged reread information, and SHALL NOT restore the original due date or use a datetime-only due POST.

#### Scenario: Intended-update retry repairs the due
- **WHEN** the initial post-write reread fails due verification and the reread after the single retry confirms the intended due and preserved recurrence information
- **THEN** the operation SHALL report the verified update as successful and SHALL send no further retry

#### Scenario: Retry fails or cannot be verified
- **WHEN** the single intended-update retry is rejected, fails, or cannot be verified by the subsequent reread
- **THEN** the operation SHALL log an error containing the task ID and saved task title, the full pre-update due information, the intended due information, and the latest available reread due information
- **AND** the log SHALL explicitly call out field-by-field differences for date, recurrence expression, recurring flag, language, and timezone, distinguishing missing fields from explicit null values and distinguishing the intended date change from unexpected differences
- **AND** an unavailable reread SHALL be explicitly identified, with any last available reread information labeled as such
- **AND** the operation SHALL record an unsuccessful or ambiguous outcome, SHALL stop dependent writes for that task, SHALL NOT claim the original due is unchanged, SHALL NOT complete the task, and SHALL NOT issue another repair or rollback

#### Scenario: Initial write verifies successfully
- **WHEN** the initial post-write reread confirms the intended due date and preserved recurrence information
- **THEN** the operation SHALL send no retry

### Requirement: Schedule writeback leaves independent task fields unchanged
SmartPlanner calendar schedule edits SHALL update only current Due and native duration plus validated sync provenance needed for recovery. Recurring Due changes SHALL retain the existing guarded recurrence verification and single intended-update repair contract. Sync SHALL NOT initialize/advance Deadline or alter human content, labels, priority, or completion as a side effect of moving/resizing an event.

#### Scenario: Native duration overrides a label-derived estimate
- **WHEN** an event representing a label-estimated 30-minute task is resized to 45 minutes
- **THEN** SmartPlanner SHALL write and verify native duration of 45 minutes without changing the duration label or Deadline
