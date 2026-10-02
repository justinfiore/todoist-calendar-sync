# Proposal

## Why

SmartPlanner currently replaces a Todoist task's Due value with a fixed `due_datetime`, which can erase native recurrence and makes the current apply path unsafe for the owner's daily, weekly, monthly, and yearly tasks. SmartPlanner needs to keep one native recurring Todoist task as the actionable item in Today, align its current Due time with the managed Google event, and maintain a separate date-only Deadline for each managed occurrence without making hidden local state the recurrence authority.

## What Changes

- Preserve Todoist's native recurrence opaquely and reschedule only the current occurrence through the Sync `item_update` due-object pattern validated by a disposable live spike.
- Classify newly observed eligible tasks before SmartPlanner writes Due: when a user-authored Due exists and Deadline is absent, copy only Due's local date to Deadline; preserve an existing Deadline; and record that an initially undated task's later Due was planner-authored rather than treating it as a new deadline source.
- Add incremental legacy migration with configured `smartplanner-onboard` and `smartplanner-seen` labels. Continue recurrence-safe SmartPlanning for unconverted legacy tasks, log each eligible Due-only candidate's name, ID, and Todoist deep link, and convert it only after the owner adds `smartplanner-onboard`.
- Let SmartPlanner move Due to its selected work time while retaining `due.string`, `due.is_recurring`, language, timezone, and native next-occurrence behavior. Keep the pre-scheduling user Due date in portable metadata for a pending legacy conversion.
- Treat every Due time as a scheduling preference, never an exact cutoff. The copied date-only Deadline is soft by default; the configured `hard` label makes that date a finish-by constraint whose urgency escalates within a configurable `hard_deadline_soon_days` window and can outrank ordinary Todoist priority.
- Keep one Todoist task, its project placement, labels, reminders, comments, subtasks, assignee, and native completion history. Do not complete/uncomplete tasks or generate recurrence-anchor/work-task pairs in the default design.
- Detect recurring completion by comparing the active task's durable occurrence metadata and Todoist `completed_count`; update Deadline for Deadline-managed recurrences, leave unconverted legacy recurrences without Deadline, and schedule the new occurrence in either mode. Completed-task/activity feeds are optional evidence because the live spike did not return recurring completion rows.
- Store a versioned, portable lifecycle marker on the Todoist task and use existing local plans/mappings/receipts only as reconciliation evidence, not recurrence authority.
- Give every recurrence an explicit occurrence identity so scheduling the next occurrence creates a distinct managed calendar event and does not overwrite or delete prior occurrence history.
- Include the stable Todoist task deep link `https://app.todoist.com/app/task/<task-id>` in every managed Google Calendar event description.
- Replace blind recurring-task writes with fail-closed drift, ambiguity, retry, and migration behavior; retain Todoist's native next-occurrence semantics rather than parsing its recurrence language.
- **BREAKING**: the blanket "Todoist Deadline is never mutated" safety contract becomes "Deadline is mutated only by validated automatic initialization, label-approved legacy conversion, and Deadline-managed occurrence advancement." All other planner paths remain deadline-invariant.

## Capabilities

### New Capabilities

- `todoist-recurrence-lifecycle`: First-observation classification, label-driven legacy conversion, recurrence-preserving current-occurrence scheduling, hard-deadline urgency, native completion advancement, on-task lifecycle authority, occurrence identity, task deep links, and recovery behavior.

### Modified Capabilities

- `calendar-provider-routing`: Preserve existing provider and apply safety while permitting only validated automatic initialization, label-approved legacy conversion, and Deadline-managed occurrence advancement writes.

## Impact

- Todoist domain and gateway: retain the complete Due recurrence tuple, Deadline source semantics, labels, description/comment metadata, creation/update timestamps, and completion count; add Sync command support and classified live verification.
- Planning and apply: distinguish task/series/occurrence identity, treat ordinary Deadline dates as soft targets, escalate approaching `hard` Deadline dates, preflight live task state before calendar writes, and reconcile Todoist/Calendar mutations without recurrence loss.
- State and Google Calendar: key current and historical mappings by occurrence identity, preserve prior occurrence events, include stable Todoist task links, and retain enough provider metadata to rebuild from Todoist plus Google if local state is lost.
- Configuration and UX: configure `smartplanner-seen`, `smartplanner-onboard`, and `hard` labels, rollout cutoff, hard-deadline urgency window, metadata location, and operator-visible legacy candidate logs.
- Daemon: poll active recurring tasks frequently enough to observe `completed_count` advancement and schedule the new occurrence without relying on completed-task feeds.
- Tests and QA: add hermetic recurrence, migration, failure/recovery, reminder-preservation, identity, and history coverage plus a post-implementation live matrix isolated from the existing independent QA campaign.
- Documentation: explain voice/mobile capture, legacy onboarding labels, soft versus hard Deadline dates, Todoist-native recurrence behavior, Calendar task links, manual edits, recovery, and rollout.

## Required Validation

Implementation SHALL add automated mocked-API integration coverage wherever provider behavior can be represented deterministically. Using the project's WireMock patterns, tests SHALL exercise:

- exact Sync `item_update` request and verification sequences for date-only, zoned, timed, strict-relative, and opaque rich recurrence tuples;
- two native-completion cycles, `completed_count` advancement/jumps/regression, same-occurrence rescheduling, distinct next-occurrence UIDs, and preservation of historical events;
- post-cutoff Due-only, existing-Deadline, initially undated, later user-Due, planner-authored Due, unlabelled legacy, and `smartplanner-onboard` → `smartplanner-seen` transitions;
- soft Deadline placement on both sides of its date, hard-window boundaries, approaching hard P4 versus ordinary P1, timezone/DST boundaries, and infeasible hard dates;
- recurrence/Due/Deadline/`hard`/marker edits between plan and apply, incomplete tuples, ambiguous Todoist writes, partial Todoist/Calendar success, receipt loss, restart, and local-state reconstruction;
- exact Calendar descriptions for single- and multi-task events, stable ID-only links after title changes, ownership markers, preview no-write behavior, and non-recurring compatibility.

Implementation SHALL also add a separate recurrence/deadline section to `docs/SMARTPLANNER_QA_RUNBOOK.md` and perform it only after automated and mocked integration checks pass. The disposable live campaign SHALL validate behavior that mocks cannot establish confidently:

- native Todoist completion and next-occurrence advancement for daily, weekly, weekday-specific, monthly, yearly, strict-relative, timed, and richer recurrence expressions across two cycles;
- Todoist Today and Google Calendar alignment, old-event retention, new-event creation, and clickable Todoist links in single- and multi-task event descriptions;
- real Todoist label workflow and logs for unlabelled legacy candidates, incremental onboarding, verified request-label removal, initially undated tasks, and preserved existing Deadlines;
- soft versus approaching-hard scheduling and Todoist-priority override using disposable capacity contention, including explicit infeasible-hard reporting;
- recurrence-rule, Due, Deadline, and `hard` edits in Todoist UI; daemon restart; local-state-loss reconstruction; ambiguous/partial recovery where safely injectable; and reminder/project/label/comment/description/subtask/assignee preservation;
- preview-first authorization, sanitized before/after provider exports, receipts and state snapshots, redaction scan, evidence manifest/hashes, rollback, and zero disposable projects/tasks/labels/events after cleanup.

This campaign SHALL use a new dated evidence location and SHALL NOT modify the independent Google/Todoist QA package, credentials, checksums, or prior results.
