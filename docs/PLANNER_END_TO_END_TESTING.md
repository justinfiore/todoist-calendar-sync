# Planner end-to-end testing and production rollout

This procedure separates service-boundary validation from production rollout. It does not use your
primary task list or calendar until the production crawl stage. The automated suite uses fixtures
and WireMock only; passing it is not proof that live credentials, permissions, or provider-specific
server behavior are correct.

## 1. Automated acceptance gate

Run from a clean checkout/worktree:

```bash
./gradlew :app:test --rerun-tasks
./gradlew build
./gradlew installDist
```

Gate: all tasks execute and pass; the distribution exists under
`app/build/install/todoist-caldav-sync`. The tests cover Todoist read/due-only write, CalDAV
REPORT/GET/PUT/DELETE and all-calendar UID search, Open-Meteo, Slack webhook/chat, the
OpenAI-compatible boundary, preview no-write, exact approvals, safe-only application,
idempotency/failure behavior, full-auto refusal, CLI help, and token-log redaction.

## 2. Isolated Todoist and calendar test

Create a dedicated Todoist project/label (for example `Planner Integration Test` + `schedule-test`)
and a dedicated CalDAV calendar (for example `Planner Test Output`). Do not point
`output_calendar` at a personal/work calendar. Add one separately configured read-only test calendar
with a few blockers so classification can be observed.

Create test tasks covering: no deadline, date-only deadline, timed due, native duration, duration
label, `@manual`, high/low priority, and one task that cannot fit. Create calendar events covering:
hard/soft/informational rules, all-day events, and an unknown calendar. Use test credentials with the
smallest permissions possible. Back up/export both isolated datasets.

Keep these controls:

```yaml
planner:
  mode: preview
  messaging: { enabled: false }
  ai: { enabled: false }
  weather: { enabled: false }
```

Run `capacity` then `preview` with explicit UTC instants. Gate: task count, calendar names,
classifications, capacity, planned intervals, deadline risks, diff, and unscheduled reasons match the
fixtures you created; all four state directories contain only expected local artifacts; Todoist due
and deadline fields and every calendar resource remain unchanged.

## 3. Isolated write gates

Make a backup/export immediately before each gate.

1. Set `mode: approval_required`, generate a new preview, record its plan id/version/hash, and first
   run apply without approval and with a deliberately wrong hash. Gate: durable refused receipts and
   zero remote writes.
2. Create an exact approval file for the stored plan and apply it. Gate: only planner-owned events
   appear in the test output calendar; each has the planner UID/ownership marker; Todoist `due_datetime`
   matches each block start; Todoist deadlines are byte-for-byte unchanged.
3. Rerun the same apply. Gate: idempotent/no-op results and no duplicate events or blind resend.
4. Generate a plan containing one ordinary and one protected/approval-required change. Run
   `apply-safe`. Gate: only the ordinary change is written; protected/frozen/manual changes are listed
   as withheld.
5. Test delete/reconcile only with a planner-owned test event. Gate: external or wrong-calendar UID
   collisions refuse; owned block metadata must match before DELETE.

Rollback: stop the process, retain receipts/logs, restore the test calendar export and Todoist due
values from the backup, and restore all four state directories as one snapshot. Never delete state
selectively to force a retry after an ambiguous provider success.

## 4. First production crawl / walk / run

### Crawl — preview

Back up/export production Todoist tasks and every configured calendar. Snapshot all planner state
directories (initially empty is fine). Use read-only CalDAV credentials where practical, keep
`mode: preview`, and keep Slack/AI/weather disabled. Run one narrow 24-hour capacity/preview, then a
three-day preview. Observe logs and state for at least one normal planning cycle.

Acceptance gate: configured calendar ownership/names are correct; unknown calendars are visible;
no remote writes occurred; plan output is stable on a repeat crawl; no credential appears in logs;
capacity and diffs are explainable.

### Walk — approval_required

Enable write permission only for the managed output calendar and due-time permission for the isolated
set of production tasks. Set `mode: approval_required`; generate a fresh plan after the mode change.
Exercise missing and stale approval refusals, then apply one exact approved small plan. Observe the
managed calendar, Todoist due/deadline fields, receipts, and collision checks for at least 24 hours.

Acceptance gate: every write is expected and traceable to a receipt; deadlines never change; rerun is
idempotent; backup restore instructions have been rehearsed.

### Run — apply_safe_changes

Only after the walk gates pass, set `mode: apply_safe_changes`. Start with a short horizon and review
every receipt daily. Protected, frozen, manual, drifted, and approval-required items must remain
withheld unless an exact approval/accepted decision is explicitly applied. Expand the horizon only
after several clean runs.

`fully_automated` is unavailable. It is intentionally refused with zero writes; do not configure it
as a rollout stage.

## Observation and rollback

Monitor provider HTTP failures/rate limits, plan churn, unknown calendar diagnostics, UID collisions,
partial calendar/Todoist outcomes, delivery `UNKNOWN/NEEDS_RECONCILIATION`, state filesystem space,
and deadline invariance. On any unexplained write or ambiguous success: stop further apply/delivery,
do not blindly retry, save logs/receipts/state, compare live resources to the last backup, restore
remote data if necessary, restore the matching state snapshot, return to `preview`, and diagnose
before resuming.

Production acceptance requires all automated and isolated gates, a successful preview crawl,
approval-required walk, safe-only run, verified backups, observed idempotent reruns, zero deadline
mutations, and an operator who can execute rollback without improvisation.
