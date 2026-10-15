# Proposal

## Why

Rearranging a synced task is often faster in Google Calendar or a CalDAV client than by editing the Todoist due date, but the current sync only writes Todoist to the calendar. A calendar move is therefore overwritten the next time the task's due date is synced, and SmartPlanner records the move as drift instead of adopting it.

## What Changes

- Add calendar-to-Todoist due writeback for events this system created, in both `legacy-sync` and SmartPlanner (`planner-daemon`, plus apply paths that would otherwise overwrite a newer calendar move). It is off unless `calendarWriteback.enabled` is `true`.
- Resolve a disagreement by the newer last-modified time: Todoist `updated_at` versus the calendar event's last modification. A missing or equal timestamp does not write; the disagreement is recorded and left unchanged.
- Ignore calendar edits that this process just wrote, and ignore events that were not created by this sync (personal meetings, other calendars, unowned events).
- Detect calendar changes incrementally instead of rereading every event: Google Calendar `syncToken` for the API provider, and CalDAV `sync-collection` where the server advertises it, with a collection `ctag` check before any full reread.
- Move a recurring task's next due through the same guarded recurrence update SmartPlanner apply already uses, extracted so apply and writeback share it. That update keeps the recurrence expression and does not complete the task. The datetime-only due POST stays limited to non-recurring tasks.
- Save the pre-update due and recurrence information; if post-write due verification fails, retry the intended update once using that saved recurrence information and the new date. If repair fails or cannot be verified, log the task title, original/intended/reread due information and field differences, and stop dependent writes without a rollback.
- Do not change recurrence completion, deadline writes, provider selection, or the existing rule that SmartPlanner never mutates deadlines.

## Capabilities

### New Capabilities

- `calendar-to-todoist-writeback`: Decide which side wins when a synced task and its calendar event disagree, write only a newer calendar time change back to the Todoist due, and keep that decision consistent in legacy sync and SmartPlanner.
- `calendar-change-detection`: Discover changed calendar events without listing every event, for both the Google Calendar API and CalDAV, including unsupported-server fallback.

### Modified Capabilities

- `google-calendar-api-gateway`: Event reads must carry the provider last-modified time and support an incremental sync cursor, in addition to the existing bounded range read.
- `calendar-provider-routing`: Provider selection must keep calendar-to-Todoist writeback available on both providers without weakening preview no-write, deadline invariance, ownership checks, or approval gates.

## Impact

- Legacy sync in `TodoistCalDavSync`: today it polls Todoist `POST /sync`, derives an event UID, and delete-then-PUTs CalDAV (including Google-as-CalDAV). It stores only the Todoist `syncToken`. It needs a reverse path and a per-calendar change cursor.
- SmartPlanner apply in `PlanApplier`: with `stability.keep_manual_moves`, a moved owned event is already detected as `manual_calendar_move` and left in place, but the Todoist due is not updated. Writeback must adopt that move instead of letting the next plan overwrite it. With the flag off, apply currently overwrites the calendar; writeback must win when the calendar modification is newer.
- `GoogleCalendarApiGateway` and `CalDavHttpGateway` today request etags but do not persist `updated`, etag, `syncToken`, or CalDAV sync-token. `CalendarEvent` has no last-modified field.
- Todoist already exposes `updated_at` on Sync items (`Task.updatedAt`) and an incremental items cursor (`todoist-items-sync.json`). Non-recurring due writes stay on the due-only `updateTaskDue` POST. Recurring due writes stay on the existing Sync `item_update` that replaces only the next date inside the live recurrence tuple.
- Google `events.watch` is not the primary detector: this process has no public inbound listener, and notifications are hints that still require a sync. A bounded `updatedMin` poll is the fallback if a sync token is rejected, not the steady-state path.
- New local cursor state must stay separate from the legacy Todoist sync token and the planner items cursor.
- Existing configs omit `calendarWriteback.enabled`, so they keep today's one-way behavior. The key is top-level in the legacy config and under `planner` in the planner config.
