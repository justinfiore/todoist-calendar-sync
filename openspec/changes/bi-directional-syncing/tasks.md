# Tasks

## 1. Writeback decision

- [x] 1.1 Add a shared decision type that returns update-Todoist, update-calendar, echo no-op, or record-and-skip from task due, task `updated_at`, calendar start, calendar last-modified, recurring flag, and echo token. A recurring update-Todoist result names the guarded recurrence operation. Verify unit tests cover newer calendar, newer Todoist, equal times, missing timestamp, equal timestamps with different times, echo match, and recurring selection.
- [x] 1.2 Add candidate identity checks: legacy base32 `userId-taskId` UID on a configured calendar, and SmartPlanner owned managed event mapped to exactly one task. Verify tests reject unowned events, unknown UIDs, and multi-task blocks without producing a due update.
- [x] 1.3 Add a cursor/echo store separate from the legacy Todoist `.state` sync token and `todoist-items-sync.json`, with per-calendar cursor, collection tag, and last-write etag or write id. Verify a round-trip test reloads the file and a missing file means "no cursor".

## 2. Calendar change detection

- [x] 2.1 Extend `CalendarEvent` and Google `fromGoogleEvent` so range, UID, and change reads carry provider `updated` or an explicit absent modification time. Verify a gateway test maps a resource with `updated` and one without it.
- [x] 2.2 Add a Google per-calendar changes list that paginates to `nextSyncToken`, refuses to mix the cursor with time or property filters, and surfaces 410 without treating a partial page as success. Verify WireMock tests for an incremental page, a last-page token, and a 410.
- [x] 2.3 Add CalDAV capability probe plus `sync-collection` delta read (etag and last-modified, bodies only for changed candidate members) and invalid-token recovery. Verify a fixture test that an advertised server returns only changed members and an invalid token does not persist.
- [x] 2.4 Add the CalDAV fallback: unchanged `getctag` skips body download; changed or missing tag rereads and compares stored stamps for candidate UIDs only. Verify a fixture test for unchanged tag, changed tag, and a server with neither sync-collection nor ctag.
- [x] 2.5 Keep provider construction fail-closed so Google change detection does not build the CalDAV client and CalDAV detection does not build Google OAuth. Verify the existing routing specs still pass with one new case per provider.

## 3. Legacy sync writeback

- [x] 3.1 After the Todoist item delta in `legacy-sync`, poll each configured calendar's change feed and run the shared decision before any delete-then-PUT. Verify a sync test that a newer calendar move updates only the Todoist due and does not restore the old event time.
- [x] 3.2 When Todoist `updated_at` is newer, keep the existing calendar PUT and record an echo token; the next inbound copy of that write must not call Todoist. Verify a two-step sync test for echo suppression.
- [x] 3.3 For a newer recurring calendar move, call the shared guarded recurrence update and do not use the datetime-only POST. A cancelled candidate event does not complete or delete the Todoist task; record it and let a later Todoist-wins pass recreate. Verify tests for a preserved recurrence tuple and a cancelled candidate event.
- [x] 3.4 Parse top-level `calendarWriteback.enabled`, defaulting an omitted key to false and rejecting a non-boolean value before sync starts. Verify a config test for omitted, false, true, and invalid values.
- [x] 3.5 Document the legacy reverse path, the default-off flag, and the separate calendar cursor file in `README.md` and `conf/todoist-calendar-sync.conf.example.yaml`. Verify the documented state-file name matches the store from 1.3 and the example leaves the flag false.

## 4. SmartPlanner writeback

- [x] 4.1 On the daemon tick, poll the selected provider, apply the decision, and only then plan, so an adopted due is the scheduler baseline. Verify a daemon test that a newer owned move updates the due and is not planned back to the previous slot.
- [x] 4.2 In apply, adopt a newer owned single-task move before drift overwrite, whether or not `keep_manual_moves` is set, and persist the adopted slot on the mapping. Verify apply tests for both flag values that the event is not upserted back to the proposed time.
- [x] 4.3 Keep preview and capacity free of Todoist and calendar mutations, and keep deadline updates unsupported. Verify a preview test with a detected move sends no due update and no event upsert.
- [x] 4.4 Extract the guarded recurring due update from `PlanApplier` into one operation, point apply at it, and use it for recurring writeback. Refuse writeback for multi-task blocks. Save the pre-update title and full due information; on failed post-write due verification, retry the intended update exactly once with the saved recurrence tuple and intended new date, then reread. On failed or unverifiable repair, log task ID/title, original/intended/latest reread due information and field-by-field differences (including missing/null distinctions and unavailable rereads), record an unsuccessful or ambiguous outcome, and stop dependent writes without rollback. Verify apply and writeback tests cover incomplete-tuple no-write, first-attempt success with no retry, successful repair, failed/rejected/unverifiable repair diagnostics, and the one-retry limit; preserve recurrence and other guarded postconditions, never call `updateTaskDue` for recurring tasks, and never complete the task.
- [x] 4.5 Parse `planner.calendarWriteback.enabled`, defaulting an omitted key to false and rejecting a non-boolean value before a planner run. Verify a planner config test for omitted, false, true, and invalid values.
- [x] 4.6 Document SmartPlanner writeback, the default-off flag, the `keep_manual_moves` interaction, and the shared recurring due update in `docs/SMART_PLANNER_CONFIGURATION.md` and `conf/todoist-planner.conf.example.yaml`. Verify the documented flag and recurrence behavior matches 4.2 and 4.4 and the example leaves the flag false.

## 5. Integration

- [x] 5.1 Run the planner and legacy sync test suites and confirm no new failure in provider routing, deadline invariance, or preview no-write. Record the command and result in the implementation notes for this change.

## Implementation notes

- 2026-10-15: `./gradlew --no-daemon :app:test` — BUILD SUCCESSFUL; 1,070 tests, zero failures, errors, or skipped tests. Includes both provider WireMock suites, routing, legacy sync, daemon/apply, recurrence repair, deadline invariance, and preview no-write coverage.
- Integration review added regressions for persisted legacy user identity on calendar-only recreation, live verification rather than synthetic Todoist timestamps, unresolved conflicts on Todoist-only deltas, and preserving the conflict-decision etag across a later Google lookup.
- Recurring repair always uses the original saved tuple with the intended new date and a new command ID. Rejected/failed repairs still receive a final reread; non-due guard failures do not authorize a due repair.
- Provider checks are hermetic fixtures/WireMock, not live account mutations. Both writeback flags remain false in examples. No deployment or production writes were performed.
