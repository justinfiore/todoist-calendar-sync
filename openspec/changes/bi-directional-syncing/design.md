# Design

## Context

See proposal.md for why calendar moves need to flow back to Todoist. Today the two runtimes do not share a writeback path.

Legacy sync (`TodoistCalDavSync.sync`) polls Todoist `POST /sync` with a stored `syncToken`, derives an event UID from `userId-taskId`, and delete-then-PUTs a CalDAV resource, including Google Calendar reached as CalDAV. It does not read calendar changes. The state file stores only the Todoist token and a v1 migration flag.

SmartPlanner reads a bounded calendar window and writes only owned events on the managed output calendar. `PlanApplier.detectBlockDrift` already notices a moved owned event when `stability.keep_manual_moves` is true and refuses to overwrite it, but it does not update the Todoist due. With the flag false, apply overwrites the calendar from the plan. Apply's only Todoist mutation is due-only `updateTaskDue`. Recurring dues are a full tuple (`string`, `is_recurring`, `lang`, `timezone`); a bare datetime update is not a safe reschedule.

Neither gateway persists a calendar cursor. Google `events.list` is `timeMin`/`timeMax` only. CalDAV REPORT requests `getetag` and discards it. `CalendarEvent` has no last-modified field. Todoist `updated_at` is already parsed onto `Task.updatedAt`.

Requirements live in the change specs for writeback, change detection, the Google gateway delta, and provider routing.

## Goals / Non-Goals

**Goals:**

- One conflict decision shared by legacy sync and SmartPlanner, used only when `calendarWriteback.enabled` is `true`: strictly newer last-modified wins; missing or equal timestamps do not write.
- Incremental calendar detection on both providers, with a defined fallback when a CalDAV server has no sync-collection.
- Echo suppression so a write this process just made is not treated as a user move.
- Keep existing safety gates: preview writes nothing, deadlines are never mutated, unowned events are never writeback candidates, and a recurring due moves only through the shared guarded tuple update.

**Non-Goals:**

- Writing calendar title, description, or completion back to Todoist.
- Google `events.watch` push channels. This process has no public inbound listener, and a notification is only a hint that still requires a sync.
- Merging the legacy CalDAV writer into `CalDavHttpGateway`, or changing provider selection.
- Completing a recurring task, or moving a recurring due with the datetime-only POST.
- Making `keep_manual_moves: false` overwrite a calendar move that is newer than the task. Newer-wins replaces that overwrite.

## Decisions

### 1. Shared writeback decision, two call sites

`calendarWriteback.enabled` defaults to false when omitted. Legacy config reads it at the top level. Planner config reads it under `planner`. A non-boolean value fails validation before either runtime starts. When the flag is false, neither runtime polls the calendar change feed for writeback and neither adopts a calendar move; existing Todoist-to-calendar writes stay as they are. When it is true, both call sites use the decision below.

Add a small decision type that takes the task id, whether the task is recurring, Todoist due instant, Todoist `updated_at`, calendar start, calendar last-modified, and whether the calendar change matches the last write this process recorded for that event. It returns one of: update Todoist due, update calendar from Todoist, no-op (echo or already equal), or record-and-skip (missing/equal timestamps or ambiguous identity). A recurring update-Todoist result selects the guarded recurrence operation, not the datetime-only POST.

Legacy `sync()` calls it after the existing Todoist item delta, using a calendar change feed for the configured CalDAV calendars. SmartPlanner calls the same decision from the daemon tick and from apply, before `detectBlockDrift` would either freeze or overwrite the event. Apply then persists the adopted slot on `AppliedMapping` so the next plan does not treat the user's move as a proposal to undo.

Alternative considered: only teach `PlanApplier` to write back, and leave legacy one-way. Rejected because the request requires both modes, and the conflict rule would otherwise diverge.

### 2. Last-modified comparison, not "calendar always wins"

Todoist Sync items include `updated_at`. Google event resources include `updated`. CalDAV exposes `getlastmodified` and, when present, `LAST-MODIFIED`. Compare instants. The strictly newer side is authoritative for the start/due instant only.

If either timestamp is absent, or they are equal while the times differ, record the pair and leave both sides unchanged. Do not fall back to "calendar wins" or "Todoist wins"; that would hide a clock or server gap as a silent overwrite.

Equal start times are not a conflict, regardless of timestamps.

Alternative considered: etag inequality as the conflict signal. Rejected as the primary rule because an etag changes for any property edit and does not say which side is newer. Etag remains useful as an echo and concurrency token (decision 4), not as the winner.

### 3. Identity and scope

Legacy candidate: UID equals the existing base32(`userId-taskId`) derivation, on a calendar this sync configures. One task, one event. A UID that decodes to no open included task is ignored.

SmartPlanner candidate: event is on the managed output calendar and passes the existing ownership check (planner UID plus ownership marker). The mapping must name exactly one task. A block that contains more than one task is not a writeback candidate; moving one shared event cannot be split into per-task dues without a new product rule.

Unowned events, events on other calendars, and events this sync did not create are never candidates. Change detection may see them; writeback must not act on them.

### 4. Echo suppression

Before each outbound calendar PUT/update, record event UID, written start/end, and a generation token (Google etag if returned, CalDAV `ETag` if returned, otherwise the written start plus a local write id). The next inbound change that matches that token, or whose start equals the written start and whose modification time is not after the local write time, is an echo and produces no Todoist write.

Advance the stored token only after the inbound change has been classified, so a crash between calendar PUT and token save cannot drop a user edit that landed in the same window. A matching echo is the only case that may be skipped without a timestamp comparison.

Google and CalDAV writes should send `If-Match` when an etag is known, so a user move that landed between read and write fails instead of being overwritten. Legacy sync today delete-then-PUTs with no precondition; the reverse path must not copy that pattern for events it might also be reading. Keep delete-then-PUT only for the Todoist-wins direction after the decision has selected it, and only for the target UID.

### 5. Incremental detection

**Google Calendar API.** Add a list-changes call beside the existing range read. Initial sync paginates `events.list` with the same filters that later incremental calls will use, stores `nextSyncToken` only from the last page, and does not write back during the initial cursor establishment except for events whose `updated` is newer than the task and newer than any recorded local write. Incremental calls pass only `syncToken` (plus `pageToken`). Do not combine it with `timeMin`, `timeMax`, `updatedMin`, `orderBy`, `iCalUID`, or `privateExtendedProperty`; the API returns 400. `singleEvents` must be identical on the initial and incremental calls; use `true` so a moved instance is visible, matching the current range read.

On 410, discard that calendar's cursor and local change baseline, run a new full sync, and do not apply a partial page. Store one cursor per configured calendar, in a new state file, not in the legacy Todoist `.state` token and not in `todoist-items-sync.json`.

`updatedMin` is not the steady-state path. It cannot be combined with `syncToken`, and it is only a recovery query if cursor establishment itself fails. `events.watch` is out of scope: notifications carry no body, channels expire, and this deployment has no inbound HTTPS listener.

**CalDAV.** PROPFIND `supported-report-set` once per calendar and cache the result with the cursor. If `sync-collection` is advertised, REPORT with the stored `sync-token` (empty token for the initial full member list), request `getetag` and `getlastmodified`, and GET calendar-data only for changed members that match a synced UID. Store the returned token only after classification. An invalid token is the same recovery as Google 410: discard and full-sync that collection.

If `sync-collection` is not advertised, PROPFIND `CS:getctag` (and `DAV:sync-token` if present). An unchanged ctag skips the body download. A changed or missing ctag falls back to the existing time-range REPORT, then compares `LAST-MODIFIED` or `getlastmodified` against the stored stamp for candidate UIDs only. Store the new ctag only after that comparison. This is the compatibility path for servers that never implemented RFC 6578; it is not the preferred path when sync-collection exists.

Legacy Google-as-CalDAV uses the CalDAV path, not the Google API cursor. The API cursor belongs to SmartPlanner's `google_calendar_api` provider only. Routing stays fail-closed: a Google provider run must not construct the CalDAV client, and the reverse.

### 6. SmartPlanner interaction

Call writeback before scheduling and before apply overwrite:

- Daemon tick: poll the selected provider's change feed, apply the decision, then plan. A newly adopted due is visible to the scheduler as a user-scheduled start, so stability treats it as the baseline rather than as drift to undo.
- Apply: if the live owned event is newer than the task, adopt it (due update plus mapping slot update) and do not upsert the event back to the proposed time. This holds whether `keep_manual_moves` is true or false. The flag still controls planner-proposed moves that are not a newer external calendar edit.
- If the task `updated_at` is newer, keep the current Todoist-wins calendar update.
- If the block has multiple tasks, skip writeback and keep today's drift behavior.
- Recurring (`TodoistDue.recurring`): call the shared guarded recurrence update with the calendar start as the replacement next date. Do not call `updateTaskDue`. Do not complete the task. An incomplete live tuple skips before writing. Failed post-write due verification triggers the single intended-update retry described below; an unresolved outcome stops dependent writes for that task rather than claiming the due is unchanged.
- Preview and capacity: may read the change feed for display, must not update Todoist or the calendar.
- Deadline fields stay untouched. `updateTaskDeadline` remains unsupported.

A non-recurring writeback uses the same due-only REST body apply already uses (`due_datetime` only). A recurring writeback uses the shared operation below. After a successful update, refresh the local task `updated_at` from the response or from the next Sync poll before the calendar writer runs, so the echo rule sees the Todoist side as current.

### 6a. One guarded recurrence update

Extract the recurring due write that `PlanApplier` already performs into one operation used by apply, legacy writeback, and SmartPlanner writeback. The operation rereads the live task, requires a complete recurrence tuple, and saves the pre-update task title and full due information, including field presence and explicit null values. An incomplete tuple is a recorded skip before any due write. It sends Sync `item_update` with that saved tuple and only the next date replaced, then rereads and checks the intended date, recurrence expression, recurring flag, language, timezone, deadline, and labels. Apply's lifecycle marker update stays in apply; writeback does not rewrite the marker.

Post-write verification allows one asymmetric timezone difference: when the saved timezone is explicitly null, a present non-null provider timezone is accepted. This alone does not trigger a repair. Missing fields, changes to an existing non-null timezone, and differences in other guarded fields remain failures. Diagnostics label accepted timezone initialization separately from unexpected differences.

If the post-write reread cannot verify the intended due date and recurrence information, including an unavailable reread, retry the intended due update exactly once using the original saved recurrence tuple and the intended new date. Use a new command ID so the provider does not deduplicate the repair as the original command. Do not rebuild the tuple from the potentially damaged reread, restore the original date, or fall back to `updateTaskDue`. Reread after the retry and run the verification again; a verified repair succeeds only if the other guarded postconditions also hold. The repair changes only due information, never deadline, labels, description, or completion.

If the retry is rejected, fails, or cannot be verified, log an error containing the task ID and saved title, the full pre-update due information, intended due information, and latest available reread due information. Explicitly list field-by-field differences for date, recurrence expression, recurring flag, language, and timezone, preserving missing-versus-null distinctions and labeling the intended date change separately from unexpected differences. If the final reread is unavailable, say so and label any earlier reread as the last available observation. Record an unsuccessful or ambiguous outcome and stop dependent writes for that task. Do not claim the due stayed unchanged, issue another repair, or roll back.

### 7. What "delete" on the calendar means

An incremental feed can return a deleted or cancelled event. Do not complete or delete the Todoist task. For a candidate UID, clear the stored calendar stamp and let the existing Todoist-to-calendar writer recreate the event on the next Todoist-wins pass. Record the deletion so a tight loop does not recreate and immediately treat the recreate as a user move (echo rule covers the recreate).

## Risks / Trade-offs

- [Google sync token cannot be time-bounded] → The cursor is per calendar and independent of the planner window. Writeback still filters to known task UIDs, so a large calendar does not become a large Todoist write set. Initial full sync is the expensive step; incremental polls are the steady state.
- [CalDAV servers differ on sync-collection and ctag] → Probe `supported-report-set`. Prefer sync-token, then ctag, then a bounded reread. Do not assume Google's CalDAV endpoint and a generic server behave the same.
- [Last-modified clocks disagree across vendors] → Strictly-newer plus record-on-tie avoids a silent preference. Echo suppression uses etag when the server returns one, so a self-write is not judged only by clock skew.
- [Legacy delete-then-PUT races a user move] → Decision runs before the PUT. Todoist-wins is the only path that rewrites the event. Calendar-wins updates Todoist and leaves the event.
- [Multi-task planner blocks] → Writeback refuses rather than copying one event start onto every member due. Those blocks keep today's drift/overwrite behavior.
- [Recurring due tuple] → Apply and writeback share a guarded Sync tuple replacement with one intended-update retry on failed due verification. Failed repair logs original, intended, and observed due information and stops dependent writes; there is no datetime-only fallback or rollback.
- [Repair races concurrent edits] → The single retry deliberately reuses the saved recurrence information and may overwrite a concurrent recurrence edit. Accept that risk for this bounded repair; retain detailed failure diagnostics and never extend it into an unbounded retry loop.
- [410 / invalid token during a user move] → Recovery full-sync must still compare timestamps; it must not treat every event in the full result as a fresh user edit. Echo records and stored stamps survive cursor invalidation. Only the cursor is discarded.

## Migration Plan

1. Ship detection and decision behind no new provider selection. Existing one-way sync remains the Todoist-wins path when the calendar is not newer.
2. Add the cursor state file next to the existing state files. Absence of the file means "no cursor"; the first run establishes it and does not rewrite historical events whose stamps are not newer than the task.
3. Rollback is config-free: reverting the binary stops reverse writes. Stale cursor files are ignored by older builds. Do not reuse the Todoist sync token field for a calendar cursor, so a rollback cannot corrupt item sync.

## Open Questions

None that change the specs or the task breakdown. Server-specific CalDAV gaps are handled by the probe-and-fallback decision rather than by naming a single server.
