# Spec Delta

## Purpose

Propagate a newer calendar time change for a synced task back to the Todoist due, in both legacy sync and SmartPlanner, without treating unrelated calendar edits as tasks.

## ADDED Requirements

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
The system SHALL write a calendar time change back to Todoist only for an event it created and still associates with exactly one open Todoist task. Legacy sync associates that event by its derived task UID. SmartPlanner associates it by managed-event ownership on the configured output calendar.

#### Scenario: Legacy synced event is moved
- **WHEN** a calendar event whose UID was derived from a Todoist user and task is moved to a different start time
- **THEN** the system SHALL treat that event as a writeback candidate for that task

#### Scenario: Planner-owned event is moved
- **WHEN** an event on the managed output calendar carries planner ownership and maps to one scheduled task
- **THEN** the system SHALL treat that event as a writeback candidate for that task

#### Scenario: Unrelated calendar event is ignored
- **WHEN** a calendar event was not created by this sync, lacks ownership, or maps to no task or to more than one task
- **THEN** the system SHALL NOT update any Todoist task from that event

### Requirement: Newer last-modified time wins
When a writeback candidate and its Todoist task disagree on start time, the system SHALL adopt the side whose last-modified time is strictly newer. Todoist last-modified is the task `updated_at`. Calendar last-modified is the provider event modification time. The system SHALL NOT write when either timestamp is missing or the two timestamps are equal.

#### Scenario: Calendar modification is newer
- **WHEN** a candidate's start differs from the task due and the calendar modification time is strictly after the task `updated_at`
- **THEN** the system SHALL update only that task's due to the calendar start and SHALL NOT change the task deadline, content, or completion state

#### Scenario: Todoist modification is newer
- **WHEN** a candidate's start differs from the task due and the task `updated_at` is strictly after the calendar modification time
- **THEN** the system SHALL update the calendar event from the Todoist due and SHALL NOT write the calendar time back to Todoist

#### Scenario: Timestamp is missing or equal
- **WHEN** the start times differ and either last-modified time is missing or the two timestamps are equal
- **THEN** the system SHALL leave both sides unchanged and SHALL record the disagreement

### Requirement: Self-inflicted calendar writes are not writeback
The system SHALL ignore a calendar modification that matches a calendar write it has just completed for that event, so a Todoist-to-calendar update cannot immediately write the same time back to Todoist.

#### Scenario: Sync writes the event it just read from Todoist
- **WHEN** the system updates a calendar event from a Todoist due and the next calendar change is that same write
- **THEN** the system SHALL NOT issue a Todoist due update for that change

### Requirement: Writeback runs in legacy sync and SmartPlanner
The system SHALL apply the same newer-wins decision in `legacy-sync` and in SmartPlanner daemon and apply flows. A newer calendar move SHALL be adopted as the task due before a later plan or sync overwrites the calendar event.

#### Scenario: Legacy sync sees a newer calendar move
- **WHEN** `legacy-sync` observes a newer calendar move for a synced task
- **THEN** it SHALL update the Todoist due and SHALL NOT restore the previous calendar time on that run

#### Scenario: SmartPlanner sees a newer calendar move
- **WHEN** SmartPlanner observes a newer move of an owned managed event, whether or not manual-move retention is enabled
- **THEN** it SHALL update the member task due to the calendar start and SHALL treat that time as the applied slot instead of overwriting the event

#### Scenario: Preview does not write back
- **WHEN** SmartPlanner runs in preview
- **THEN** it SHALL NOT update Todoist or the calendar from a detected move

### Requirement: Recurring writeback uses the guarded recurrence update
A newer calendar move for a recurring task SHALL update only that task's next due through the same guarded recurrence operation SmartPlanner apply uses. The operation SHALL keep the live recurrence expression, language, timezone, and recurring flag, and SHALL NOT complete the task. A datetime-only due POST SHALL NOT be used for a recurring task.

#### Scenario: Recurring synced task is moved on the calendar
- **WHEN** a writeback candidate belongs to a recurring Todoist task, the calendar start differs from the due, and the calendar modification is strictly newer
- **THEN** the system SHALL replace only the next due date through the shared guarded operation and SHALL NOT complete the task

#### Scenario: Live recurrence tuple is incomplete before writing
- **WHEN** the live recurring due tuple is incomplete before the update
- **THEN** the system SHALL record the skip, SHALL send no due update, and SHALL NOT complete the task

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
