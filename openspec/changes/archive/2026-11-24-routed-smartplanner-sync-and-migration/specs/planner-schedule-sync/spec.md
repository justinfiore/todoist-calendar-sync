# Spec Delta

## Purpose

Maintain the user's current task schedule across Todoist and owned calendar events independently of generating or applying a new optimized plan, including editable focus groups.

## ADDED Requirements

### Requirement: Sync and planning have separate authority
SmartPlanner SHALL provide one-shot and independently scheduled sync-only operation. Explicit sync enablement SHALL authorize mirroring current schedules and manual edits, not choosing new work times. Plan generation SHALL not apply a plan. Preview-only operation with sync/lifecycle mutations disabled SHALL perform zero remote writes.

#### Scenario: Sync runs without planning
- **WHEN** sync-only runs on eligible dated tasks
- **THEN** it SHALL create/reconcile projections at their current Due with resolved duration and SHALL NOT invoke scheduling, grouping optimization, Deadline initialization, or optimizer-selected Due changes

#### Scenario: Preview daemon has no write capabilities enabled
- **WHEN** planner mode is preview and independent sync, writeback, and lifecycle mutation features are disabled
- **THEN** scheduled cycles SHALL perform no Todoist or calendar mutations

#### Scenario: User explicitly enables sync alongside proposal-only planning
- **WHEN** sync is enabled but plan application is not authorized
- **THEN** status SHALL distinguish enabled sync writes from unapplied proposals, and only manual mirroring/current-schedule projection SHALL occur

### Requirement: Manual edits are mirrored before future planning decisions
For an owned current occurrence, sync SHALL mirror timed Todoist Due/native-duration edits into calendar start/end and enabled calendar start/end edits into Todoist Due/native duration. Changes SHALL be reconciled from a verified common baseline. Deadline, recurrence, content, and completion SHALL remain unchanged by schedule mirroring.

#### Scenario: Todoist task moves
- **WHEN** the user changes a single task's Due from 10:00 to 14:00 without a competing calendar change
- **THEN** sync SHALL move its event to 14:00 without requiring plan approval or reusing an old proposal's time

#### Scenario: Calendar event is resized
- **WHEN** an owned single-task event changes from 30 minutes to 45 minutes without a competing task edit
- **THEN** enabled reverse sync SHALL verify a 45-minute native Todoist duration, even if the previous effective duration came from a label/default

#### Scenario: Recurring task is moved or resized
- **WHEN** sync updates a recurring task's current schedule
- **THEN** it SHALL preserve the full live recurrence tuple and completed count, retain the current occurrence identity, and SHALL NOT complete/reopen the task

#### Scenario: Date-only or absent Due
- **WHEN** initial reconstruction reads a date-only Due or a task without Due
- **THEN** it SHALL respectively create an all-day projection on that local date or no event, without inventing a Due time
- **AND** a later calendar edit that would convert all-day scheduling into timed scheduling SHALL be withheld as an explicit conflict rather than silently choosing a conversion

### Requirement: Dragging a focus group shifts every member
For an owned focus group with verified ordered member intervals, a calendar drag SHALL shift every member Due by the event start-time delta and preserve member durations and order. A moved and resized event SHALL use the new start and the proportional resize rule together.

#### Scenario: Two-member group is dragged
- **WHEN** a group containing a 30-minute task at 09:00 and a 60-minute task at 09:30 is moved to 13:00 without changing its span
- **THEN** their verified Dues SHALL become 13:00 and 13:30, and durations SHALL remain 30 and 60 minutes

### Requirement: Focus resizing proportionally distributes positive whole minutes
Resizing a focus group SHALL distribute the new whole-minute span proportionally to the last verified member durations, preserve order, and make intervals consecutive from the new start. Each member SHALL receive at least one minute and the sum SHALL exactly equal the event span. Rounding SHALL be deterministic, with equal remainders resolved in stored member order.

#### Scenario: Asymmetric proportional resize
- **WHEN** a group with 30-minute and 60-minute members is shortened to 60 minutes
- **THEN** the native task durations SHALL become 20 and 40 minutes and the second Due SHALL become 20 minutes after the new group start

#### Scenario: Rounding tie
- **WHEN** two 10-minute members are resized to a 21-minute group
- **THEN** the first member SHALL receive 11 minutes and the second 10 minutes

#### Scenario: Minimum allocation constrains proportions
- **WHEN** members of 1, 1, and 98 minutes are resized to a 3-minute group
- **THEN** each SHALL receive one minute rather than a zero or negative duration

#### Scenario: Invalid target size
- **WHEN** a three-member group is resized to two minutes or to a span not expressible as whole minutes
- **THEN** no Todoist mutation SHALL begin and a visible conflict SHALL identify the unsupported span

### Requirement: Editing a task splits it out of a focus group
A user Due or native-duration edit to a group member SHALL remove it from that group and create its individual event at the edited schedule and resolved destination. Unaffected members SHALL keep their Dues and durations. Remaining consecutive members SHALL retain grouped representation; disjoint runs SHALL become separate projections rather than implying work in the removed member's gap.

#### Scenario: Middle member moves
- **WHEN** the middle task in a three-member focus block moves in Todoist
- **THEN** it SHALL gain an individual event, the old group SHALL no longer claim it, and the first/last tasks SHALL keep their original intervals as individual remaining runs

#### Scenario: Two members remain consecutive
- **WHEN** the last task of a three-member group changes duration in Todoist
- **THEN** the first two SHALL remain grouped unchanged and the edited task SHALL have an individual event with its new duration

#### Scenario: Group becomes empty or single-member
- **WHEN** splitting leaves zero or one member
- **THEN** sync SHALL respectively remove the verified owned empty event or retain a single-task representation, without duplicate task projections

### Requirement: Verified member departures preserve history and remaining schedules
Independent sync SHALL reconcile verified native completion/recurrence advancement, task deletion, Due removal, and projection inclusion/routing loss as member departures without waiting for planning. It SHALL remove only the departing occurrence from active membership, preserve unaffected task Dues/durations, and project maximal consecutive remaining runs at their existing intervals. Each verified completed member SHALL retain an independently addressable historical single-occurrence event covering only its last verified interval, never the entire former group. Deletion, Due removal, or eligibility loss alone SHALL NOT fabricate completed history; prior historical occurrences SHALL remain untouched. No resulting event SHALL mix historical and active members.

Departures SHALL require authoritative task/lifecycle evidence and matching persisted occurrence/membership baselines; an absent task in a partial response or an unexplained completed-count change SHALL NOT prove completion/deletion. A verified advanced occurrence that remains included, routable and dated SHALL receive an individual current projection from fresh Todoist Due/resolved duration without planning or lifecycle mutation. Ineligible or undated successors SHALL be reported without invented schedules.

#### Scenario: Recurring member advances while another remains open
- **WHEN** a verified native completion advances A:0 to A:1 in a group containing A:0 at 09:00 for 30 minutes and B at 09:30 for 60 minutes
- **THEN** sync SHALL retain A:0 as history only at 09:00–09:30, project B alone at 09:30–10:30 unchanged, and project eligible dated A:1 individually at its fresh Todoist schedule without a planning run
- **AND** the former mixed group SHALL be retired, with no duplicate active coverage or writeback from A:0 to A:1

#### Scenario: Non-recurring member completes or is deleted
- **WHEN** authoritative evidence verifies completion or deletion of a member of a focus group
- **THEN** sync SHALL detach that member and preserve the remaining runs and task schedules; completion SHALL retain only that member's verified historical interval, while deletion alone SHALL not create completed history

#### Scenario: Middle member loses Due or projection eligibility
- **WHEN** the middle member of a three-task group loses Due, inclusion, or a resolved managed route through a verified change
- **THEN** sync SHALL retire its active coverage, represent the first and last members separately at their unchanged intervals, preserve prior history and the Todoist task, and report the departure reason

#### Scenario: Departure replacement is interrupted
- **WHEN** historical/residual/successor replacements have been partly created and old-group retirement fails or has an unknown outcome
- **THEN** the durable operation SHALL identify exact source and target resources, expose pending status, withhold overlapping edits/planning, and reconcile before retry; successful completion SHALL leave exactly one active representation per remaining/current occurrence

### Requirement: Manual edits invalidate stale plans and preserve current placements
An approved plan SHALL bind task schedule, native/effective durations, recurrence/occurrence, destination/rule fingerprint, and projection membership/revision. A changed bound input SHALL invalidate application before dependent writes. New proposals SHALL begin from reconciled current placements; only a fresh authorized plan SHALL intentionally supersede a manual placement under existing approval/protection policy.

#### Scenario: Edit after approval
- **WHEN** a task or group changes after its plan was approved
- **THEN** applying that plan SHALL report stale input and SHALL NOT restore its old schedule

#### Scenario: Proposal without application
- **WHEN** a new proposal suggests moving a manually placed task
- **THEN** current Todoist/calendar placements SHALL remain unchanged until a fresh matching plan is authorized and applied

### Requirement: Group writes are resumable and never pretend to be atomic
Sync SHALL durably record verified baselines, intended postconditions and per-member outcomes before group/split/departure/relocation mutations. Departure operations SHALL bind lifecycle evidence, historical/residual/successor identities and exact source/target resources, verify replacements before conditional source retirement, and commit membership only after all postconditions reconcile. Only those journal-bound resources SHALL be allowed as temporary duplicate coverage; availability SHALL count their logical placements once. Any failed or ambiguous outcome SHALL suspend dependent changes and overlapping planning until live reconciliation proves a safe continuation. Restart/echo processing SHALL not apply a drag or resize twice.

#### Scenario: Second member update times out
- **WHEN** the first member succeeds and the second update has an unknown outcome
- **THEN** status SHALL show partial/unknown state, later writes for that operation SHALL stop, and recovery SHALL reread live tasks and event before continuation

#### Scenario: Missing group metadata
- **WHEN** an event lacks trustworthy member intervals or references an advanced/completed occurrence without verified lifecycle evidence matching its baseline
- **THEN** sync SHALL withhold group edits and report repair required rather than infer members from title or event duration

#### Scenario: Verified completion is not metadata corruption
- **WHEN** trustworthy group metadata and authoritative native completion evidence establish a normal member departure
- **THEN** sync SHALL reconcile the departure under its journal rather than require manual group repair; the stale mixed event SHALL NOT authorize calendar-driven task edits during that transition

### Requirement: Conflicts do not grant scheduling authority
Missing/equal modification timestamps, edits to both schedules since a common baseline, invalid spans, and unsafe membership SHALL produce visible conflicts without destructive resolution. Sync SHALL retain the user's conflicting remote edits and block stale-plan application until explicit resolution or a fresh reviewed plan.

#### Scenario: Both sides changed
- **WHEN** a task and its event have different schedule edits since their last verified baseline
- **THEN** sync SHALL not pick a winner merely because one provider reports a later clock and SHALL expose the conflicting values

### Requirement: Historical occurrences are not current-task writeback targets
Native Todoist completion SHALL advance current occurrence identity while retained historical events remain independently addressable. A completed member of a mixed group SHALL be preserved through the verified departure transition rather than retaining a historical event that also claims active members. Sync SHALL not interpret editing a past occurrence as a request to move the active task, and SHALL not reconstruct unobserved occurrences or emit native calendar recurrence rules.

#### Scenario: Old occurrence is dragged
- **WHEN** the user moves an owned event for a prior completed occurrence
- **THEN** the active Todoist task SHALL remain unchanged and sync SHALL report the event as historical

### Requirement: Removing a schedule is distinct from deleting a task
Removing Todoist Due SHALL retire only its verified owned active coverage, using the member-departure transition for shared projections rather than deleting unaffected members' representation. A manual calendar deletion SHALL be recorded as cancellation/conflict, not task completion or an immediate event-recreation request. Task completion/deletion SHALL stop active-task writeback; known historical occurrences SHALL remain preserved and uncertain future orphan events SHALL require reconciliation.

#### Scenario: User removes Due in Todoist
- **WHEN** an active projected task loses Due
- **THEN** sync SHALL retire its owned current projection without deleting the task or prior historical events

#### Scenario: User deletes a calendar event
- **WHEN** an owned active event is manually deleted
- **THEN** sync SHALL leave Todoist completion and Due unchanged, report cancellation/conflict, and SHALL NOT immediately recreate the event
