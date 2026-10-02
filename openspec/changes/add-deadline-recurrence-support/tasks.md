# Tasks

## 1. Project Complete Recurrence State

- [ ] 1.1 Extend the Todoist task/domain projection with the complete Due tuple (`date`, `string`, `is_recurring`, `lang`, `timezone`), Deadline date, labels, description, timestamps, and `completed_count`; verify parsing tests cover date-only, zoned, malformed, absent, and rich recurring values without normalizing the recurrence expression.
- [ ] 1.2 Add explicit series and occurrence identity using Todoist task ID plus `completed_count`, including monotonicity validation; verify unit tests distinguish same-occurrence Due moves, next occurrences, skipped counts, and counter regression.
- [ ] 1.3 Introduce the versioned lifecycle marker codec as an end-of-description suffix containing a Markdown divider, warning, and fenced canonical single-line JSON with recurrence fingerprint, Deadline mode/source, pending legacy source date, last planner-verified Due, and command identity; verify exact-format, escaping, stable-key-order, empty/non-empty description, round-trip, replacement-without-duplication, malformed/relocated/duplicate marker, and concurrent-drift tests preserve the human prefix exactly or fail closed.
- [ ] 1.4 Add lifecycle-state classification for unmarked, initialized, advanced, user-edited, recurrence-removed, sentinel-missing, marker-missing, and unsupported states; verify table-driven tests cover every classified transition and fail-closed result.

## 2. Add a Recurrence-Preserving Todoist Sync Boundary

- [ ] 2.1 Add an authenticated Todoist Sync command path that fetches the live task and sends `item_update` with a changed Due `date` plus verbatim `string`, `is_recurring`, `lang`, and `timezone`; verify gateway contract tests assert the exact request shape and reject incomplete recurrence tuples.
- [ ] 2.2 Add narrowly scoped Todoist writes for lifecycle Deadline, sentinel label, and merged description metadata while retaining the existing Deadline prohibition for ordinary planner paths; verify adapter tests prove only validated lifecycle operations can mutate Deadline.
- [ ] 2.3 Implement stable command UUID handling and post-write re-fetch classification for committed, rejected, absent, and ambiguous outcomes; verify transport-failure tests prove there is no blind mutation retry and that retries require unchanged preconditions.
- [ ] 2.4 Add a disposable opt-in Sync contract test/script covering recurrence preservation and cleanup without using independent QA fixtures or credentials; verify its dry-run/fixture mode is automated and document the separately authorized live invocation and required cleanup checks.

## 3. Onboard Tasks Without Reinterpreting Legacy Work

- [ ] 3.1 Add validated configuration for `smartplanner-seen`, `smartplanner-onboard`, `hard`, rollout cutoff, and `hard_deadline_soon_days`; verify configuration tests reject missing/conflicting labels, non-positive urgency windows, and unsafe automatic conversion without a cutoff.
- [ ] 3.2 Implement pre-write classification for user-authored Due, existing Deadline, and initially undated tasks, recording planner Due provenance before scheduling; verify a later poll never copies SmartPlanner's own Due, while a later genuine user Due on a classified non-candidate task is copied once.
- [ ] 3.3 Implement legacy candidate discovery that preserves the original Due date while ordinary recurrence-safe SmartPlanning continues; verify logs contain task name, task ID, and `https://app.todoist.com/app/task/<task-id>` without leaking task descriptions or credentials.
- [ ] 3.4 Implement `smartplanner-onboard` conversion that copies the pending source date, verifies metadata and Deadline, adds `smartplanner-seen`, and removes the request label last; verify failure-injection tests retain the request label and reconcile every partial/ambiguous outcome.
- [ ] 3.5 Document mobile/voice capture, automatic new-task classification, incremental legacy labels, `%hard`, rollout cutoff, metadata block ownership, and feature rollback; verify all documented configuration and label workflows against fixtures.

## 4. Schedule Against Soft and Approaching Hard Deadlines

- [ ] 4.1 Make every Due time a scheduling preference and every non-hard Deadline a soft completion target; verify ordinary tasks can be placed after Deadline when stronger constraints require it and receive increasing lateness penalties.
- [ ] 4.2 Make `hard` Deadline a finish-by end-of-local-date constraint with urgency escalating only inside `hard_deadline_soon_days`; verify boundary tests show an approaching hard P4 can outrank a non-hard P1, no permanent boost outside the window, DST-safe date boundaries, and explicit risk when infeasible.
- [ ] 4.3 Include Deadline mode/date, live `hard` label, occurrence identity, and lifecycle provenance in plan/approval hashes; verify label, Deadline, count, recurrence, or provenance changes invalidate stale approval without treating a hard-label change as recurrence corruption.
- [ ] 4.4 Replace the fixed REST `due_datetime` apply for recurring tasks with the classified Sync operation, including unconverted legacy tasks; verify integration tests prove rich recurrence survives scheduling and incomplete recurrence prevents both Todoist and Calendar writes.
- [ ] 4.5 Re-fetch Todoist immediately before Calendar mutation and verify Due, Deadline, labels, marker, and occurrence identity; verify race tests for completion and human edits between planning/apply create no ghost event.
- [ ] 4.6 Update planner documentation to explain Due-time preference, soft Deadline, approaching hard Deadline, Todoist-priority interaction, and hard-risk reporting; verify examples cover both sides of the configured soon-window boundary.

## 5. Advance Native Occurrences Through Polling

- [ ] 5.1 Extend daemon polling to compare every scheduling-eligible recurring task's `completed_count` and lifecycle state, including unconverted legacy tasks, treating a monotonic increase as native advancement without requiring completed/activity feeds; verify daemon tests observe advancement when those feeds return no rows.
- [ ] 5.2 On advancement of an onboarded recurrence, derive the new Deadline date from Todoist's advanced Due before scheduling; for an unconverted legacy recurrence, preserve scheduling without creating Deadline; verify both modes across daily, weekly, monthly, yearly, strict-relative, and explicit-time fixtures for two cycles.
- [ ] 5.3 Handle count jumps by retaining known history, reporting unobserved intermediate counts, and scheduling only the current active occurrence; verify no synthetic tasks/events are created for missed counts.
- [ ] 5.4 Implement fail-closed reconciliation for same-count Due changes, recurrence edits, Deadline edits, hard-label edits, recurrence removal, and marker/sentinel drift; verify each live edit remains untouched until the configured reconciliation action is approved.
- [ ] 5.5 Document native completion behavior and configured polling latency, including unsupported complete/reopen behavior and recovery actions for drift; verify the daemon runbook links each operator-visible classification to an action.

## 6. Make Calendar State Occurrence-Aware

- [ ] 6.1 Migrate persisted mappings from task-only identity to occurrence identity while retaining an active-series lookup and historical mappings; verify migration tests preserve current mappings, are idempotent, and can roll back without deleting events.
- [ ] 6.2 Derive managed block/event identity from stable occurrence identity rather than scheduled start, and embed series plus occurrence metadata in Google events; verify rescheduling one occurrence updates one event while advancing creates a different UID and leaves the prior event intact.
- [ ] 6.3 Update cleanup, collision, ownership, and idempotency checks so an active occurrence cannot overwrite or delete another occurrence's history; verify integration tests cover grouped blocks, global UID collision, duplicate managed events, and partial application.
- [ ] 6.4 Rebuild local indexes from valid Todoist lifecycle markers plus managed Google occurrence metadata after local-state loss; verify recovery tests avoid duplicate events and freeze on conflicting or corrupt authority.
- [ ] 6.5 Document Calendar history retention, occurrence metadata, reconstruction, and explicit cleanup boundaries; verify the recovery procedure succeeds against isolated fixtures with an empty local state store.
- [ ] 6.6 Add stable ID-only Todoist links to managed Google event descriptions, listing every task in multi-task focus events without disturbing ownership metadata; verify title changes retain links and rendered descriptions contain `https://app.todoist.com/app/task/<task-id>`.

## 7. Cross-Provider Automated Integration Coverage

- [ ] 7.1 Extend Todoist WireMock integration tests with exact fetch → Sync command → re-fetch sequences for date-only, zoned, timed, strict-relative, and opaque recurrence tuples; verify preserved fields, stable command UUID behavior, incomplete-tuple refusal, ambiguous response reconciliation, and exact mutation counts.
- [ ] 7.2 Add mocked two-cycle daemon/apply integration tests covering `completed_count` advance/jump/regression, same-occurrence move, distinct next-occurrence event, retained historical event, missing completed/activity rows, and stale-plan preflight refusal; verify every Todoist and Google request body and call order.
- [ ] 7.3 Add mocked onboarding integration tests for post-cutoff Due-only, existing Deadline, initially undated/planner-authored Due, later user Due, unlabelled legacy logging, pending source preservation, labelled conversion, and every partial/ambiguous label/Deadline/marker write boundary; verify no Deadline is derived from a planner Due.
- [ ] 7.4 Add deterministic scheduler integration tests for soft lateness, hard soon-window boundaries, monotonic urgency, hard P4 versus ordinary P1, duration finish-by behavior, DST transitions, and infeasible-hard risk; verify expected placement and explanations rather than only successful execution.
- [ ] 7.5 Add Google WireMock integration tests for occurrence UIDs, historical retention, recovery after Calendar success/receipt failure, single/multi-task ID-only links, title changes, ownership markers, collision refusal, and no duplicate creation after local-state loss.
- [ ] 7.6 Add mocked drift/compatibility tests for recurrence-rule, same-count Due, Deadline, `hard`, marker, and sentinel changes plus recurrence removal, preview no-write, non-recurring tasks, grouped focus blocks, and daemon restart; verify each case updates, replans, reconciles, or fails closed according to the specification.
- [ ] 7.7 Run the full automated test suite and strict OpenSpec validation, fixing only failures attributable to this change; verify `./gradlew test` and `npx -y @fission-ai/openspec@1.14.0 validate add-deadline-recurrence-support --strict` both pass.

## 8. Manual QA Plan, Live Validation, and Guarded Rollout

- [ ] 8.1 Add a recurrence/deadline section to `docs/SMARTPLANNER_QA_RUNBOOK.md` containing every live-only case and assertion from `proposal.md` and `design.md`, with preview-first authorization, stop conditions, sanitized provider snapshots, command-status index, receipts/state, redaction, hashes, rollback, and cleanup; verify a dry review maps every case to evidence artifacts before any live write.
- [ ] 8.2 Execute the new disposable live matrix only after task 7.7 passes, covering two cycles of each recurrence class, Todoist Today/Calendar alignment, historical/new event identity, clickable links, legacy labels/logs, initially undated and existing-Deadline tasks, soft/hard contention, UI edits, restart/recovery, property preservation, and safe failure injection; verify expected provider state after every transition.
- [ ] 8.3 Store the campaign under a new dated evidence path, generate its manifest/hashes/redaction report, and verify zero disposable projects/tasks/labels/events remain; do not alter the independent 2026-10-01 QA package, credentials, checksum, or conclusions.
- [ ] 8.4 Roll out in legacy-log, labelled incremental migration, and post-cutoff automatic-capture stages with an observed rollback drill; verify each gate reports recurrence preservation, Todoist/Calendar time alignment, clickable task links, no ghost/duplicate events, and no Deadline writes to unlabelled legacy tasks.
