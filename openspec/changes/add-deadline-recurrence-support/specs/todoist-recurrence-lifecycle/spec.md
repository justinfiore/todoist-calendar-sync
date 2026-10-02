# Spec Delta

## Purpose

Defines a portable, fail-closed lifecycle for scheduling each occurrence of a native recurring Todoist task without losing its recurrence, deadline semantics, task linkage, or calendar history.

## ADDED Requirements

### Requirement: Recurring tasks retain their native recurrence
The system SHALL schedule a recurring Todoist task only when it has retained the complete current Due recurrence tuple, and SHALL preserve that tuple while changing only the current occurrence's Due date or datetime. The system SHALL treat the recurrence expression as opaque Todoist-owned data rather than interpreting its natural-language dialect.

#### Scenario: Current occurrence is rescheduled
- **WHEN** a scheduling-eligible recurring task has a valid Due tuple and SmartPlanner applies a selected work time
- **THEN** the task's current Due date or datetime SHALL become the selected work time
- **AND** its recurrence string, recurring flag, language, and timezone SHALL remain unchanged

#### Scenario: Recurrence data is incomplete
- **WHEN** a task reports that it is recurring but any field required to preserve its Due recurrence tuple is unavailable or malformed
- **THEN** SmartPlanner SHALL withhold all Todoist and Calendar mutations for that task
- **AND** SHALL report that recurrence preservation could not be guaranteed

#### Scenario: Rich recurrence remains Todoist-owned
- **WHEN** a task uses a recurrence expression beyond simple daily, weekly, monthly, or yearly intervals
- **THEN** SmartPlanner SHALL preserve the expression verbatim
- **AND** SHALL rely on Todoist completion semantics to calculate the next occurrence

### Requirement: First observation distinguishes user and planner Due values
The system SHALL classify a scheduling-eligible task before writing its Due. It SHALL copy a user-authored Due's local date only when Deadline is absent and new-task policy permits conversion, preserve an existing Deadline, and record planner-authored Due provenance so later polls do not manufacture a Deadline.

#### Scenario: Newly captured Due-only task is eligible for automatic conversion
- **WHEN** a scheduling-eligible task has a user-authored Due, has no Deadline or sentinel, and was created on or after the configured rollout cutoff
- **THEN** SmartPlanner SHALL copy only the Due's local calendar date to Deadline before moving Due
- **AND** SHALL add the configured seen sentinel after verified initialization

#### Scenario: Newly observed task already has Deadline
- **WHEN** a scheduling-eligible task has a Deadline but lacks the sentinel
- **THEN** SmartPlanner SHALL preserve that Deadline unchanged
- **AND** SHALL record classification and add the seen sentinel without copying Due

#### Scenario: Newly observed task has no Due
- **WHEN** a scheduling-eligible task has no Due at classification time
- **THEN** SmartPlanner SHALL copy nothing to Deadline
- **AND** SHALL record any later SmartPlanner scheduling Due as planner-authored

#### Scenario: Planner-authored Due is observed on a later poll
- **WHEN** a task has no Deadline and its live Due matches the last verified planner write
- **THEN** SmartPlanner SHALL NOT copy that Due to Deadline

#### Scenario: Previously classified task receives a new user Due
- **WHEN** a task with no Deadline receives a user-authored Due after its initial classification and is not a pending legacy candidate
- **THEN** SmartPlanner SHALL copy the new Due's local date to Deadline before moving Due
- **AND** SHALL update the verified lifecycle marker without repeating first-observation migration

### Requirement: Legacy Deadline conversion is incremental and label-driven
The system SHALL continue normal recurrence-safe SmartPlanning for legacy tasks while withholding automatic Due-to-Deadline conversion. It SHALL log eligible Due-only candidates and SHALL convert a candidate only when it has the configured `smartplanner-onboard` label and lacks the seen sentinel.

#### Scenario: Legacy Due-only task is discovered
- **WHEN** a pre-cutoff scheduling-eligible task has a user-authored Due, no Deadline, and no seen sentinel
- **THEN** SmartPlanner SHALL continue recurrence-safe planning without creating Deadline
- **AND** SHALL log its task name, task ID, and `https://app.todoist.com/app/task/<task-id>` deep link
- **AND** SHALL preserve the original Due date as the pending conversion source even if SmartPlanner later moves Due

#### Scenario: Owner labels a legacy candidate for onboarding
- **WHEN** a logged candidate has `smartplanner-onboard` and does not have the seen sentinel
- **THEN** SmartPlanner SHALL copy the pending original Due date to Deadline
- **AND** SHALL add the seen sentinel and remove `smartplanner-onboard` after verifying initialization

#### Scenario: Legacy task is not selected for onboarding
- **WHEN** a legacy candidate lacks `smartplanner-onboard`
- **THEN** SmartPlanner SHALL leave Deadline absent while continuing ordinary recurrence-safe SmartPlanning

#### Scenario: Legacy onboarding is incomplete or ambiguous
- **WHEN** any conversion write has an ambiguous outcome or the resulting Deadline, metadata, and sentinel cannot all be verified
- **THEN** SmartPlanner SHALL NOT remove `smartplanner-onboard`
- **AND** SHALL reconcile live task state before another conversion attempt

### Requirement: Deadline date and hard label have separate semantics
Due's local date SHALL populate the same date-only Deadline regardless of `hard`, and Due time SHALL remain a scheduling preference. Without `hard`, Deadline SHALL be a soft target. With `hard`, it SHALL be a finish-by date with escalating priority inside the configured soon window.

#### Scenario: Due time is copied for either label state
- **WHEN** SmartPlanner initializes Deadline from a Due datetime
- **THEN** it SHALL copy only the Due's local calendar date
- **AND** SHALL NOT preserve the Due time as an exact cutoff

#### Scenario: Ordinary Deadline influences scheduling softly
- **WHEN** a task does not have `hard`
- **THEN** SmartPlanner SHALL prefer completion by Deadline
- **AND** MAY place it after Deadline when required by stronger constraints

#### Scenario: Hard Deadline is outside the soon window
- **WHEN** a task has `hard` and its Deadline lies outside the configured `hard_deadline_soon_days` window
- **THEN** `hard` SHALL NOT grant a permanent urgency boost
- **AND** the task SHALL remain constrained to finish by the end of its Deadline date

#### Scenario: Hard Deadline enters the soon window
- **WHEN** a task has `hard` and its Deadline is within the configured soon window
- **THEN** scheduling urgency SHALL increase as Deadline approaches
- **AND** SHALL be capable of outranking a non-hard task with a higher Todoist priority

#### Scenario: Hard Deadline cannot be met
- **WHEN** available capacity cannot finish a `hard` task by the end of its Deadline date
- **THEN** SmartPlanner SHALL report an explicit hard-deadline risk rather than silently scheduling it late

### Requirement: Native completion advances the lifecycle
The system SHALL let Todoist complete recurring occurrences natively and SHALL detect advancement from the active task's increasing `completed_count`. It SHALL NOT complete and reopen a task to simulate recurrence.

#### Scenario: Todoist advances a recurring occurrence
- **WHEN** an active Deadline-managed task reappears with a greater `completed_count` and Todoist has advanced its Due
- **THEN** SmartPlanner SHALL recognize a new occurrence
- **AND** SHALL replace the stale Todoist Deadline from the newly advanced Due before scheduling that occurrence

#### Scenario: Todoist advances a legacy scheduling-only recurrence
- **WHEN** an unconverted legacy recurring task advances to its next occurrence
- **THEN** SmartPlanner SHALL preserve recurrence and schedule the new occurrence
- **AND** SHALL NOT create or advance Deadline unless the task is subsequently selected for onboarding

#### Scenario: More than one completion occurred between polls
- **WHEN** `completed_count` advances by more than one
- **THEN** SmartPlanner SHALL preserve the historical identity of every already known occurrence
- **AND** SHALL initialize and schedule only Todoist's currently active occurrence
- **AND** SHALL report that intermediate occurrences were not observed

#### Scenario: Completed feeds contain no recurring occurrence row
- **WHEN** Todoist's completed-task or activity feeds do not expose the recurring completion
- **THEN** active-task polling and `completed_count` SHALL remain sufficient to advance the lifecycle

### Requirement: Lifecycle authority is portable on the Todoist task
The system SHALL store the versioned lifecycle state needed to interpret and recover the current occurrence in a reserved suffix of the Todoist task description. Local plans, mappings, and receipts SHALL be reconciliation evidence and SHALL NOT override a conflicting on-task lifecycle marker.

#### Scenario: Marker is appended to a human description
- **WHEN** SmartPlanner writes lifecycle metadata to a task with a human-authored description and no marker
- **THEN** it SHALL preserve the existing description prefix exactly
- **AND** SHALL append a Markdown divider, SmartPlanner warning, and fenced canonical single-line JSON marker at the end

#### Scenario: Existing marker is updated
- **WHEN** SmartPlanner updates a valid lifecycle marker anchored at the end of the description
- **THEN** it SHALL replace only that recognized suffix
- **AND** SHALL retain exactly one marker without reformatting the human-authored prefix

#### Scenario: Concurrent human description edit wins
- **WHEN** post-write verification finds the intended old or absent marker with a newer human-authored prefix and all lifecycle preconditions remain unchanged
- **THEN** SmartPlanner SHALL merge the intended marker into the latest prefix and retry with the same command identity
- **AND** SHALL stop after at most two additional attempts

#### Scenario: Concurrent retry is unsafe
- **WHEN** verification finds a different marker generation, lifecycle drift, an unclassifiable ambiguous result, or exhausted retries
- **THEN** SmartPlanner SHALL preserve the live task and withhold further mutation for reconciliation

#### Scenario: Description marker is unsafe to merge
- **WHEN** a marker is malformed, duplicated, relocated away from the suffix, or concurrently changed
- **THEN** SmartPlanner SHALL preserve the live description and withhold lifecycle mutations for reconciliation

#### Scenario: Local planner state is lost
- **WHEN** local state is unavailable but the Todoist task retains a valid lifecycle marker and sentinel
- **THEN** SmartPlanner SHALL recover the series and current occurrence identity from Todoist
- **AND** SHALL reconcile managed Calendar events before applying further writes

#### Scenario: Lifecycle marker is deleted
- **WHEN** a previously onboarded task retains the sentinel but its lifecycle marker is missing
- **THEN** SmartPlanner SHALL stop mutating that task and its Calendar event
- **AND** SHALL require explicit repair or re-onboarding

#### Scenario: Sentinel is deleted
- **WHEN** a task retains lifecycle metadata but its sentinel label is missing
- **THEN** SmartPlanner SHALL treat the state as drift rather than as a never-seen task
- **AND** SHALL not repeat first-observation conversion

#### Scenario: Marker version is unsupported
- **WHEN** the on-task marker has an unknown schema version
- **THEN** SmartPlanner SHALL preserve it unchanged and withhold lifecycle mutations

### Requirement: User edits are distinguished from scheduler state
The system SHALL compare live recurrence, current Due, Deadline, labels, and lifecycle metadata before every mutation. It SHALL classify recurrence edits, same-rule Due moves, Deadline edits, recurrence removal, and marker edits without silently overwriting user intent.

#### Scenario: User changes the recurrence expression
- **WHEN** the live recurrence tuple differs from the tuple recorded at the last verified lifecycle transition
- **THEN** SmartPlanner SHALL preserve the new tuple opaquely
- **AND** SHALL require reconciliation of the current occurrence constraint before rescheduling

#### Scenario: User moves Due without changing the recurrence rule
- **WHEN** `completed_count` is unchanged and live Due differs from the last planner-verified Due
- **THEN** SmartPlanner SHALL treat the move as a user edit rather than a new occurrence
- **AND** SHALL not overwrite it until the configured review policy resolves the drift

#### Scenario: User edits Deadline
- **WHEN** live Deadline differs from the lifecycle's last verified occurrence Deadline
- **THEN** SmartPlanner SHALL treat the Deadline as user-authored drift
- **AND** SHALL not replace it automatically during the same occurrence

#### Scenario: User changes hard classification
- **WHEN** the live `hard` label state differs from the approved plan
- **THEN** SmartPlanner SHALL invalidate and replan using the live label and Deadline date
- **AND** SHALL NOT treat the label change as recurrence corruption

#### Scenario: User removes recurrence
- **WHEN** an onboarded task is no longer recurring
- **THEN** SmartPlanner SHALL stop recurring-lifecycle advancement
- **AND** SHALL preserve the task for normal non-recurring planning under explicit reconciliation

### Requirement: Occurrences have stable independent Calendar identity
Each recurring occurrence SHALL have an identity stable across rescheduling and distinct from every other occurrence of the same task. Moving one occurrence SHALL update its managed event, while advancing recurrence SHALL preserve prior occurrence events and create a distinct current event.

#### Scenario: Current occurrence moves
- **WHEN** SmartPlanner changes the selected work time without a change in occurrence identity
- **THEN** it SHALL update the same managed Calendar event

#### Scenario: Next occurrence becomes active
- **WHEN** Todoist advances `completed_count`
- **THEN** SmartPlanner SHALL retain the prior occurrence's managed Calendar event as history
- **AND** SHALL use a distinct identity for the newly active occurrence and its event

#### Scenario: Calendar mapping state is missing
- **WHEN** local mappings are missing but managed Calendar events contain planner ownership and occurrence metadata
- **THEN** SmartPlanner SHALL reconcile those events by occurrence identity before creating a replacement

### Requirement: Managed Calendar events link to Todoist tasks
Every managed Google Calendar event SHALL include stable Todoist task links in its description while retaining planner ownership and occurrence metadata.

#### Scenario: Event represents one task
- **WHEN** SmartPlanner creates or updates a managed event for one Todoist task
- **THEN** its description SHALL include `https://app.todoist.com/app/task/<task-id>`

#### Scenario: Focus event represents multiple tasks
- **WHEN** a managed event contains multiple Todoist tasks
- **THEN** its description SHALL list each task name with its distinct ID-only Todoist deep link

#### Scenario: Task content changes
- **WHEN** a task title changes but its task ID does not
- **THEN** SmartPlanner SHALL retain the same ID-only deep-link target without generating or depending on a title slug

### Requirement: Apply preflights lifecycle state and reconciles ambiguous writes
The system SHALL re-read the live Todoist task before a Calendar mutation, and SHALL use stable command identity plus live verification to resolve ambiguous Todoist mutations without blind retry.

#### Scenario: Task advances after plan creation
- **WHEN** preflight finds that occurrence identity or lifecycle state differs from the approved plan
- **THEN** SmartPlanner SHALL withhold both Todoist scheduling and Calendar mutation for the stale plan

#### Scenario: Todoist write result is ambiguous
- **WHEN** transport failure prevents determining whether a Todoist lifecycle mutation committed
- **THEN** SmartPlanner SHALL record the stable command identity and re-read the task
- **AND** SHALL retry only if live state proves the mutation did not commit and the original preconditions still hold

#### Scenario: Todoist verification fails before Calendar write
- **WHEN** the expected Due, Deadline, sentinel, or lifecycle metadata cannot be verified after a Todoist mutation
- **THEN** SmartPlanner SHALL NOT create or update the Calendar event

### Requirement: Existing unopted behavior remains compatible
The recurrence lifecycle SHALL affect only explicitly eligible and onboarded tasks. Preview SHALL remain read-only, and non-recurring or unopted tasks SHALL retain their existing planning and provider behavior.

#### Scenario: Preview evaluates an eligible recurring task
- **WHEN** SmartPlanner runs in preview mode
- **THEN** it SHALL describe proposed onboarding or scheduling without changing Todoist, Calendar, or on-task metadata

#### Scenario: Task is not opted into lifecycle management
- **WHEN** a task does not satisfy recurrence-lifecycle eligibility and rollout policy
- **THEN** SmartPlanner SHALL NOT initialize or advance its Deadline or sentinel
- **AND** MAY continue existing recurrence-safe scheduling behavior with provenance metadata
