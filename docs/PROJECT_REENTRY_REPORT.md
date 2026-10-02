# Project Re-entry Report

**Assessment date:** October 1, 2026
**Repository:** `justinfiore/todoist-calendar-sync`
**Default branch assessed:** `master` at [`42d33c3`](https://github.com/justinfiore/todoist-calendar-sync/commit/42d33c3)

> **Historical assessment.** This report intentionally preserves the October 1 branch/PR and risk
> snapshot. The recurring-Due finding below was subsequently addressed by
> [PR #13](https://github.com/justinfiore/todoist-calendar-sync/pull/13) and the disposable campaign in
> `docs/qa/evidence/2026-10-02-recurrence-deadline/`. Other Slack, Weather, LLM, CalDAV, legacy-sync,
> and production-rollout findings remain open unless a later artifact explicitly closes them. Use
> current operational guides—not this snapshot—for present recurrence behavior.

## Executive summary

The project is not currently at a production-ready SmartPlanner release.

- `master` contains the legacy Todoist-to-CalDAV synchronizer and its Java 25 / Gradle 9 / Groovy 5 upgrade. It does **not** contain SmartPlanner.
- SmartPlanner has a seven-PR stacked path ending at PR #11, plus the duplicate aggregate PR #10. Draft PR [#12](https://github.com/justinfiore/todoist-calendar-sync/pull/12) now exposes the complete `qa-google-calendar` descendant directly against `master` as the proposed consolidated review and merge surface.
- The supplied August 25 report and newly recovered primary artifact bundle establish that the core SmartPlanner Google Calendar API + Todoist workflow was exercised live against disposable accounts. Raw receipts and provider snapshots support preview/refusal no-write behavior, one exact approved write, deadline preservation, idempotent replay, apply-safe withholding/application, due restoration, calendar-event cleanup, empty final local state, and fully-automated refusal on the calendar side.
- An August 30 offline audit reports 891 passing tests across 54 suites, including 361 integration-focused Slack/daemon/AI/Weather tests, plus successful build, distribution, CLI, and OpenSpec checks at [`94525b7`](https://github.com/justinfiore/todoist-calendar-sync/commit/94525b7). Only a documentation commit followed that audited revision on the remote QA branch.
- The first supplied archive contains only reports and checksum manifests. The later `qa-from-hermes.tar.gz` archive contains 135 primary files, including raw/redacted command streams, provider snapshots, receipts, plans, and state snapshots. It materially raises confidence in the core campaign, but it does not identify the tested commit, retain command invocations or exit statuses, or include native UI/JUnit evidence. Its final Todoist export still contains both fixtures with restored due values, so the earlier claim of proven task/project deletion is not supported by the recovered artifacts.
- The recovered primary bundle improperly includes reusable Google OAuth refresh credentials, OAuth client material, and unredacted OAuth consent URLs. The bundle must not be committed or reused as sanitized evidence. Under explicit owner instruction, the clean QA thread staged its one Desktop client plus canonical isolated normal/QA token stores for this campaign; revoke those archived grants after the campaign, and rotate the Desktop client too if this transmission falls outside its accepted exposure boundary.
- A new independent campaign from reviewed PR base [`939edef`](https://github.com/justinfiore/todoist-calendar-sync/commit/939edef), tested at local setup/OpenSpec commit `9b3bebf`, passed all 12 gates. It includes 299/299 scoped tests, stable zero-write previews, missing/wrong-hash and both fully-automated refusals, one exact approved apply, idempotent replay, mixed apply-safe behavior, complete provider rollback, complete fixture deletion, and restoration of all four planner state directories. PR #12's Java 25 GitHub check and the earlier independent 891/891 full run also pass.
- Slack Socket Mode, Weather/Open-Meteo, LLM/OpenAI-compatible behavior, combined Slack+AI feedback, CalDAV fallback, and native UI evidence were explicitly excluded from the live campaign and remain unfinished.
- Amp history contains ten earlier project threads, all about the legacy synchronizer. They contain useful pre-fix production logs but no successful post-fix end-to-end run for the final migration, pool-reset, or 403-backoff implementations. No separate SmartPlanner or manual-QA Amp thread was found.
- Static review found several issues to fix before live QA: destructive handling of Todoist recurring dates, false Slack startup readiness, incorrect Slack conversation state after unsuccessful applies, a mismatch between documented and wired AI confirmation paths, unusable `ai-suggest` output, and malformed Open-Meteo daily arrays bypassing configured weather fallback.

**Recommended posture:** use draft PR #12 as the single review path. The non-recurring Google Calendar/Todoist core gate is now independently passed, but do not merge or deploy yet: first fix the recurring-Date data-loss path and the identified integration issues. Slack/Weather/AI/CalDAV QA remains a later release gate.

## What is actually on each branch

```text
master
  └─ PR #4  Phase 1: capacity diagnostics
      └─ PR #5  Phase 2: deterministic preview scheduler
          └─ PR #6  Phase 3: approved apply
              └─ PR #7  Phase 4: weather
                  └─ PR #8  Phase 5: Slack
                      └─ PR #9  Phase 6: AI assistance
                          └─ PR #11 production daemon/integration
                              └─ qa-google-calendar (25 additional commits after this report/evidence delivery; PR #12)

PR #12 targets qa-google-calendar directly at master and contains the entire chain above.

PR #10 points the same Phase 6 commit as PR #9 directly at master.
It is an aggregate alternative, not another implementation phase.
```

`qa-google-calendar` is a strict descendant of PR #11: after this report/evidence delivery it is 25 commits ahead and zero behind. It adds explicit calendar-provider routing, a Google Calendar API gateway, separate normal/QA OAuth stores, QA calendar provisioning, extensive hermetic tests, architecture documentation, the manual QA runbook, orb/OpenSpec setup, this report, and the independent QA evidence package. It does not need rebasing onto PR #11 at its current tips.

## Outstanding pull requests

There are nine open PRs and no open GitHub issues.

| PR | Scope | Current evidence | Assessment |
| --- | --- | --- | --- |
| [#4](https://github.com/justinfiore/todoist-calendar-sync/pull/4) | Phase 1 capacity diagnostics | Approved; CI passed Aug 7 | Ready for final refresh/merge sequencing, but old result |
| [#5](https://github.com/justinfiore/todoist-calendar-sync/pull/5) | Phase 2 scheduler | No review or branch-tip check | Needs review and current CI |
| [#6](https://github.com/justinfiore/todoist-calendar-sync/pull/6) | Phase 3 apply | No review or branch-tip check | High-risk mutation code; needs review and current CI |
| [#7](https://github.com/justinfiore/todoist-calendar-sync/pull/7) | Phase 4 weather | No review or branch-tip check | Needs weather fix, review, and QA |
| [#8](https://github.com/justinfiore/todoist-calendar-sync/pull/8) | Phase 5 Slack | No review or branch-tip check | Needs review and live Slack QA |
| [#9](https://github.com/justinfiore/todoist-calendar-sync/pull/9) | Phase 6 AI | Same head as #10 | Review as the stack layer or close in favor of #10—not both |
| [#10](https://github.com/justinfiore/todoist-calendar-sync/pull/10) | Aggregate Phases 1–6 to master | CI passed Aug 12; 120 files / 38k additions | Duplicate route to the same head as #9 |
| [#11](https://github.com/justinfiore/todoist-calendar-sync/pull/11) | Production daemon and provider composition | No GitHub CI; descendant branch has a reported 891-test offline pass; formal `CHANGES_REQUESTED` | All 15 review threads are resolved, but owner re-review and new fixes are needed |
| [#12](https://github.com/justinfiore/todoist-calendar-sync/pull/12) | Consolidated SmartPlanner + Google Calendar QA branch to `master` | Draft; contains every #4–#11 head plus 25 Google/QA/setup/report commits after this delivery; Java 25 CI passes and independent core live QA passes | Preferred canonical review path; keep draft until critical-finding disposition complete |

The CI workflow only runs for pull requests whose base is `master`. That explains why most stacked PRs and PR #11 have no checks. It also creates a verification blind spot. Change the workflow to run on pull requests to any branch, or ensure each PR is retargeted to `master` and rerun before merge.

### Merge strategy

Do not merge the old stack and PR #12 independently. Every open PR head from #4 through #11 is an ancestor of `qa-google-calendar`; #9 and #10 also resolve to the same commit.

At this age and stage, use PR #12 as the canonical merge candidate while preserving its 34-commit phase history for review. Keep #4–#11 open temporarily as narrower historical review surfaces. If #12 is accepted, close #4–#11 as superseded rather than merging them individually. This removes retarget/CI churn without collapsing the implementation into one opaque commit.

## Amp thread history audit

The initial Amp audit found this re-entry assessment and ten legacy-sync threads from February 2026. Broad searches for `SmartPlanner`, `manual QA`, `planner-main-integration`, `qa-google-calendar`, and the repository name found no older SmartPlanner QA thread. A new dedicated [independent QA thread](https://ampcode.com/threads/T-01a0f73a-f885-7207-b9a2-ad0d46997c77) now owns the clean rerun described below.

### Legacy thread groups

| Thread group | What landed | What the threads actually proved | Unfinished work still relevant |
| --- | --- | --- | --- |
| [Connection-pool exhaustion](https://ampcode.com/threads/T-019c1af1-e8bd-721e-ade8-120f907b3ab8), [pool-swap errors](https://ampcode.com/threads/T-019c1b0a-6e35-7048-bdf6-66382b5b876a), and [staged pool mitigation](https://ampcode.com/threads/T-019c1b18-5c08-71dd-a92e-45abf0b6724b) | Proactive pool monitoring/reset and client reconstruction, ultimately represented by [`b1d5e3f`](https://github.com/justinfiore/todoist-calendar-sync/commit/b1d5e3f) plus later edits | Intermediate live logs exposed authentication and stale-client bugs; some compilation passed with tests explicitly skipped | No final live run proved reset→DELETE→PUT recovery or stable leased counts. Documentation still contradicts code on reset interval, configurability, pool size, and synchronous behavior. |
| [HTTPBuilder incompatibility](https://ampcode.com/threads/T-019c8238-dc14-713d-baf1-0f7e8f4693d1) | Replacement of an obsolete Groovy HTTP library; the modern branch now uses `TodoistHttpClient` | Java 8 build passed, but Gradle reported no tests at the time | No post-fix live Todoist sync occurred in that thread. This is partly superseded by the later Java 25 client and WireMock baseline. |
| [Todoist v1 migration](https://ampcode.com/threads/T-019c8578-c29d-70c2-82e4-8d081918a323), [filtered migration](https://ampcode.com/threads/T-019c8ceb-1a45-775d-91d8-2fdc3038ef54), [progress reporting](https://ampcode.com/threads/T-019c8d0c-7004-750a-9691-2bcfbdf3c363), [migration loop](https://ampcode.com/threads/T-019c8d16-eb9b-7219-8c3e-a9c926edfbb1), and [sync-token loop](https://ampcode.com/threads/T-019c8d30-6e3e-732b-a681-5e91c35649bc) | v1 endpoint/ID migration, filtering, progress logs, and earlier persistence of `v1Migrated`; all corresponding commits are now ancestors of `master` | Real pre-fix logs demonstrated 410 responses, 12-hour migration behavior, repeated deletion, and a failing 403. Most verification was static or `build -x test`. | No final controlled migration proved old-ID deletion, new-ID recreation, one-time state transition, and subsequent incremental sync. Partial mapping/deletion failures can still be logged and swallowed while migration is marked complete. |
| [403 reset workaround](https://ampcode.com/threads/T-019c8d30-6e3e-732b-a681-5e91c35649bc) and [403 backoff follow-up](https://ampcode.com/threads/T-019c8d83-c288-7671-be9b-efbba53dffd2) | Final code uses PUT/DELETE backoff rather than resetting specifically on 403 | Live intermediate logs showed valid-looking events receiving 403 and disproved the first `getHttpStatus()` implementation. Final code compiled with tests skipped. | No live evidence shows the final backoff recovering. Every message containing `403` is treated as rate limiting without checking Google's response reason; terminal DELETE failure is logged and swallowed. |

### Legacy gaps visible in current `master`

The Java 25 upgrade later added five focused unit cases and three WireMock scenarios. They establish a useful baseline for task filtering/routing, one successful Todoist→CalDAV sync, dry-run no-write behavior, and Todoist API failure isolation. They do **not** cover the migration state machine, ID-mapping batch failure, 403 classification/backoff, connection-pool replacement, retry timing, or terminal DELETE semantics.

Current source inspection also found security/operational cleanup that should precede any credential-backed QA:

- `TodoistCalDavSync.sync()` logs the full Todoist access token at info level.
- It logs complete Todoist item JSON at info level and complete iCalendar payloads at debug level, which can expose task and calendar content.
- One historical Amp thread reported that a real Todoist token appeared in local tool output. No tracked credential file remains, but rotation/revocation was never documented; any credential from that period should be treated as compromised if it is still valid.
- `POOL_RESET_STRATEGY.md` promises a 60-second, configurable, asynchronous reset; current code hard-codes a 50% threshold, throttles at two seconds despite a “once per minute” comment, and resets synchronously.

These are not evidence that the current legacy sync is broken, but they prevent treating it as recently proven. Remove secret logging, reconcile the documentation, add focused regressions, and include one controlled legacy smoke sync in re-entry QA.

## QA evidence audit

### Supplied artifact integrity and limitations

Two archives are now available:

- `todoist-caldav-sync-qa.tar.gz`, SHA-256 `da60e269a7db336c900ef8bf35d6936b35378f8703850843fdf3432abea6dafd`, contains 19 Markdown files and two internally valid checksum manifests. It is the narrative handoff bundle.
- `qa-from-hermes.tar.gz`, SHA-256 `d5510fa1d6fea480f8d954c8f04e8641456d160871ebd9315f4e4c1238c986a1`, contains 135 files under `.qa`: 22 raw provider JSON snapshots, 44 raw stdout/stderr streams, 36 paired redacted streams plus manual refusal captures, plans, application receipts, approvals, configuration, and before/final state directories.

The second archive is primary behavioral evidence, but it has material limitations:

- `campaign-manifest.json` records fixture IDs/tag/time, not a Git revision, build identity, command list, expected results, or exit statuses. The artifacts therefore prove observed provider/state transitions but cannot independently bind them to `94525b7` or another exact binary.
- `.qa/runs/` and `.qa/reports/` are empty. There is no runner manifest, transcript with commands/statuses, JUnit/OpenSpec output, native UI screenshot, or video.
- Preview plans are stable in placement, not byte-identical: the first proposes an `add`; the second preserves the same interval as a frozen `keep` based on prior-plan state.
- The bundle has no final Todoist export after fixture deletion. Its latest Todoist snapshot still contains both QA tasks with `due: null` and unchanged deadlines. Due rollback is proven; task/project deletion is not.
- Fully-automated refusal has a zero-write receipt and byte-identical Google before/after exports, but no Todoist before/after pair dedicated to that refusal.
- The archive contains the normal and QA `credential.json` stores, a legacy credential, the OAuth client JSON, and four raw OAuth bootstrap outputs containing consent URLs. These files violate the runbook's evidence boundary even though the redacted planner streams themselves contain no obvious token/client-secret patterns.

Confidence is therefore split more precisely:

- **High artifact confidence:** exact provider snapshots and receipt/state contents described below.
- **Moderate provenance confidence:** attribution to the reported branch revision and command exit behavior.
- **Report-level only:** the 891-test totals, OpenSpec results, UI observations, and final Todoist fixture deletion.

### Completed live Google Calendar API + Todoist campaign

The August 25 report plus recovered artifacts establish a credential-backed API/CLI campaign against disposable accounts:

1. Dedicated-account inventory and provisioning of exactly one managed-output and one hard-blocker calendar.
2. Disposable Todoist fixture creation with a deadline and no due time.
3. Capacity plus two previews placed the same task in the same 30-minute interval. Byte-identical Todoist and Google snapshots before/after preview prove zero provider writes.
4. Missing-approval and wrong-hash receipts each report `skipped_unapproved` and zero writes; paired Todoist and Google snapshots are byte-identical.
5. One exact approved apply reports one write. The after snapshots show exactly one owned Google event, Todoist due equal to the event start, and byte-identical deadline data.
6. Replay reports `skipped_idempotent` with zero writes and both provider items skipped idempotently.
7. Apply-safe reports one protected move as `skipped_unapproved` and one ordinary addition as applied. Provider snapshots show the original protected event/due unchanged, one new owned event, the ordinary Todoist due set to its block start, and unchanged deadlines for both tasks.
8. Cleanup evidence shows no remaining output/blocker events, both Todoist due values restored to null, and all four final planner state directories empty. It does **not** prove Todoist task/project deletion because both fixtures remain in the latest Todoist export.
9. Manual captures show the dedicated-account confirmation gate and normal-operation rejection of QA provisioning flags. The fully-automated receipt reports zero writes, and Google before/after snapshots are byte-identical; dedicated Todoist comparison evidence is absent.
10. Separate token stores contain an event-only normal grant and a broader QA calendar-management grant. Bootstrap artifacts record timeout/state-mismatch failures before success. A later inventory attempt failed on a wrong returned scope set, followed by successful live inventories after the OAuth alias fixes in [`4da3cce`](https://github.com/justinfiore/todoist-calendar-sync/commit/4da3cce) and [`8f9407d`](https://github.com/justinfiore/todoist-calendar-sync/commit/8f9407d). The archive itself does not bind these runs to an exact commit.

The OAuth commits and campaign code are present on `origin/qa-google-calendar`. The later offline audit says it ran at [`94525b7`](https://github.com/justinfiore/todoist-calendar-sync/commit/94525b7); the only later remote commit, [`939edef`](https://github.com/justinfiore/todoist-calendar-sync/commit/939edef), changes documentation rather than application or test code. That revision attribution remains report-level because it is absent from the primary bundle.

### Completed offline QA

The August 30 report records:

- 891 tests across 54 suites, with zero failures, errors, or skips;
- 361 integration-focused Slack/daemon/feedback/AI/Weather tests across 16 suites;
- successful `build`, `installDist`, and installed launcher `--help`;
- four strict OpenSpec validations passing;
- architecture link/fence validation and a tracked-file secret-signature scan.

These are stronger and newer than the checks historically visible on most stacked PRs. They remain report-level evidence because neither archive includes the generated Gradle/OpenSpec outputs.

### Fresh independent rerun status

The clean QA thread checked out `origin/qa-google-calendar` at exact reviewed base [`939edef`](https://github.com/justinfiore/todoist-calendar-sync/commit/939edef). Its initial full hermetic gate passed 891/891 tests, build, distribution, installed launcher contract, tracked-secret/evidence scans, and 4/4 strict OpenSpec validations. The final live campaign tested local commit `9b3bebf43bfe2d02d6a241d8dd52391bc4b1a76b` and passed all 12 campaign gates:

1. 299/299 scoped Google/Todoist/planning/apply tests passed with zero failures, errors, or skips.
2. Dedicated-account preflight and separated event-only versus QA calendar-management OAuth boundaries passed.
3. Capacity reported 420 usable minutes for 30 task minutes without writes; repeated previews retained the same 13:00–13:30Z block and produced zero provider writes.
4. Missing approval, wrong plan hash, fully-automated apply, and fully-automated apply-safe were each refused with `writeCount=0` and dedicated provider comparisons.
5. One exact approved apply changed the non-recurring Todoist due to the block start, preserved its native deadline byte-for-byte, and created exactly one owned calendar event.
6. Replay returned `skipped_idempotent` with zero writes and no duplicate event.
7. Apply-safe withheld the protected move on both providers, applied only the ordinary addition, left the blocker calendar unchanged, and preserved both native deadlines.
8. Cleanup ownership-checked and deleted two output events plus one run blocker, restored both Todoist dues before deleting two tasks/four labels/one project, restored all four planner state directories together, and returned Todoist task/label/project inventory plus Google event ranges/calendar inventory to preflight.

The final normalized Todoist, Google output, and Google blocker snapshots are byte-identical to their preflight/baseline counterparts. Native provider UI captures were unavailable because orb Chrome was not authenticated; API exports, diffs, receipts, ownership metadata, and final snapshots are the authoritative provider evidence.

The ignored fresh evidence tree contains 372 checksummed files at `.qa/runs/20261001T120759Z-live-9b3bebf43bfe`. Its secret-free distributable is `manual-qa-report-package.tar.gz`, SHA-256 `afbefb40293d5704cfea7e2876a698cd17615fc958b3aac47e642ca91f19aa28`. The package's result matrix, campaign manifest, cleanup assertions, redaction result, exact apply receipt, normalized provider snapshots, screenshots, and four-second walkthrough support the PASS. Two evidence-quality caveats remain:

- The first combined Gradle command exited 1 because task-specific `--tests` options were also applied to `installDist`; the thread corrected this by running the same scoped tests and `installDist` separately, both of which passed. This is a captured command-composition error, not a test or build failure.
- The packaged `report/evidence-manifest.sha256` retains its original `.qa/runs/.../report/` path prefix, so direct verification from the extracted package root fails. Removing that prefix verifies all 134 distributable entries. Fix the packager before the next campaign so verification is portable without path rewriting.

The thread made two unpushed local commits that are not part of PR #12:

- `02a44e5`: Java 25 plus pinned OpenSpec 1.14.0 orb setup, verified in login and non-login shells.
- `9b3bebf`: OpenSpec configuration and six generated Amp skills, committed after review, strict validation, `doctor`, and skill reload.

Both commits must be reviewed and transferred separately if wanted.

### Open finding from the completed campaign

Apply-safe behaved correctly at item level, but the aggregate application receipt reported `partial` when the protected item was intentionally withheld and the ordinary item succeeded. The report classifies this as low severity and open for product review. It does not indicate a wrong provider mutation, but the status may mislead operators and overlaps with the daemon's broader result-state reporting problem.

### Explicitly unfinished live QA

- Slack Socket Mode app installation/scopes, daemon startup/readiness, commands, thread behavior, status UI, restart recovery, duplicate/stale/unauthorized handling, and live message idempotency.
- Live Open-Meteo fetch and tagged weather-sensitive preview comparison, including explicit fallback observations.
- At least one bounded live OpenAI-compatible request for supported suggestion schemas.
- Combined Slack+AI unmatched-feedback interpretation, confirmation, expiry/staleness, and no-write-before-confirmation proof.
- Live CalDAV fallback compatibility. The completed calendar campaign used the Google Calendar API gateway.
- Native Todoist/Google UI screenshots. The independent report UI and walkthrough were captured and inspected, but the orb browser was not authenticated to provider UIs; API reconciliation is authoritative.
- Recurring Todoist Date/Due behavior. No recurring fixture entered either campaign; a separate post-fix live safety case is required.
- Legacy synchronizer migration/pool/403 post-fix validation described in the Amp thread audit.

### Current confidence matrix

| Area | Automated evidence | Live/manual evidence | Current verdict |
| --- | --- | --- | --- |
| Legacy synchronizer | Focused tests and Aug 7 master CI | Historical production issues imply use | Existing behavior has a baseline, but should be smoke-tested before release |
| Capacity and deterministic scheduling | Full 891-test run plus 299 scoped tests | Repeated stable preview and zero-write provider comparisons | Validated for the campaign's non-recurring fixture and exact reviewed/tested revisions |
| Todoist SmartPlanner reads | WireMock/fixture tests | Fresh live disposable account with preflight/final inventory | Validated for non-recurring campaign scope |
| Todoist due-only writes/deadline preservation | Apply and failure-path tests | Fresh exact-apply/replay/apply-safe receipts and provider snapshots; full due restore and fixture deletion | Strong evidence for non-recurring tasks; recurring dates remain unsafe and untested |
| CalDAV SmartPlanner reads/writes | WireMock and ownership tests | None | Unverified |
| Google Calendar API/OAuth | Extensive tests; OAuth remediation | Fresh scope-separated preflight, owned writes, replay, apply-safe, cleanup, and restored inventories | Validated for campaign scope; archived grants should now be revoked |
| Weather/Open-Meteo | Reported focused suite pass using fixtures/injected HTTP | None | Live QA unfinished; known malformed-daily-response gap |
| Slack delivery and Socket Mode | Reported focused suite pass using fakes/injected transports | None | Live QA unfinished; known readiness/result-state gaps |
| LLM/OpenAI-compatible | Reported focused suite pass using local/injected transport | None | Live QA unfinished; production wiring/docs mismatch |
| Approval/refusal/idempotency/rollback | Automated state-machine tests | Fresh exact-revision receipts, command statuses, dedicated provider comparisons, full cleanup, and empty restored state | Validated for non-recurring Google/Todoist campaign scope |

## Critical technical findings

These are based on direct static review of PR #11, not merely missing QA. The reported 361-test integration subset is meaningful but does not negate code-visible gaps that its scenarios did not detect.

### 1. Planning a recurring Todoist task can destroy its recurrence — high

Todoist distinguishes **Date/Due** from **Deadline**. [Deadlines](https://www.todoist.com/help/todoist/features/introduction-to-deadlines-in-todoist-uMqbSLM6U) are date-only, one-time fixed cutoffs and cannot recur. Date/Due supports daily, monthly, yearly, and other recurrence through the natural-language `due.string` expression; `due.is_recurring` identifies such tasks and `due.date` is only the current occurrence. The [API reference](https://developer.todoist.com/api/v1/#tag/Due-dates) explicitly states that constructing a due value from a fixed date/datetime does not allow recurring due dates.

SmartPlanner currently discards both `due.is_recurring` and `due.string` while normalizing a task. Apply then sends only a fixed `due_datetime` equal to the selected block start. Todoist's product documentation warns that replacing the recurring date with a new fixed date loses recurrence. Blocks and persisted mappings are also identified from the stable Todoist task ID rather than a recurrence occurrence, so successive occurrences are not distinct in the current model. The completed live campaign used only non-recurring fixtures, so its correct due writes and deadline preservation do not cover this case.

**Required fix:** do not send the current fixed `due_datetime` mutation for recurring tasks. First preserve recurrence metadata in the domain model and model an occurrence identity, then choose and document one of two safe product policies: schedule only the calendar block while leaving the recurring Todoist Date untouched, or exclude recurring tasks from planning that would mutate Date. Todoist documents a UI action that reschedules only the next occurrence, but the public task-update API exposes no equivalent next-occurrence flag; its special recurring completion operation advances the recurrence only by completing the task, which is not a scheduling substitute. Do not claim per-occurrence API rescheduling until a suitable authoritative operation has been identified and tested. Add a disposable live recurring-task case proving preview/apply/rollback never removes or changes its recurrence expression and that successive occurrences do not overwrite calendar history unintentionally.

### 2. Slack can report daemon startup before Socket Mode is connected — high

`SlackSocketModeMessagingSurface.start()` initiates `startAsync()` and marks the surface connected immediately. The daemon then schedules work, and the launcher reports startup. A bad app token or asynchronous connection failure can therefore produce an apparently running daemon with no inbound approval/control channel. Later status checks probe the SDK, but initial readiness is not awaited.

**Required fix:** wait for bounded Socket Mode readiness and verify bot authentication/channel access before declaring startup ready or publishing initial work.

### 3. Slack records unsuccessful apply outcomes as successful — high

The apply service can return applied, partial, no-op, rejected, or error results. `SmartPlannerDaemon` currently advances the conversation to `APPLIED` or `SAFE_CHANGES_APPLIED` after any returned result. Preview refusal, protected-only no-op, partial writes, and provider errors can therefore close a conversation with a false success message.

**Required fix:** map every structured apply result to truthful, retry/reconciliation-aware conversation states. Add daemon-level tests for refusal, no-op, partial, ambiguous, and error results.

### 4. Documented AI confirmation controls are not the daemon's wired path — high

The AI guide describes signed `AiSuggestionDecisionStore` records, planning-input hash verification, and `ConfirmedOverrideApplier`. Those classes are covered by tests but are not composed into the production daemon. The daemon uses a separate unsigned conversation-state map and directly merges confirmed overrides. The guide also says daemon/CLI wiring is absent even though it now exists.

**Required fix:** either wire the signed path end-to-end with configured key management, or deliberately remove it and document/test the simpler production contract. Do not leave security claims stronger than the wired implementation.

### 5. `ai-suggest` does not output the suggestions — high

The service produces validated suggestion objects, but the CLI emits only suggestion ID and Java class name. It omits target, action, values, rationale, and proposed fields, preventing meaningful operator review.

**Required fix:** serialize a safe explicit representation for every suggestion type and snapshot-test the installed CLI output.

### 6. Malformed Open-Meteo daily arrays can bypass weather fallback — high

Hourly arrays receive length validation; daily sunrise/sunset arrays are indexed without equivalent checks. A short daily array can raise an unclassified index exception. The production orchestrator only converts `WeatherGatewayException` into an unavailable forecast, so configured `fail_open` or `fail_closed` behavior can be bypassed by malformed provider data.

**Required fix:** validate all daily arrays and normalize every provider parse failure. Exercise both fallback policies through the production orchestrator.

### 7. Weather horizon and example configuration need correction — medium

The Open-Meteo request asks for `forecast_days` capped at 16 and does not send the configured range's explicit start/end, while daemon horizons permit up to 90 days. Longer or non-current ranges silently lack coverage. The example also references availability calendars that are not present in its integration calendar list, so they can never be fetched.

**Required fix:** make unsupported weather horizon coverage explicit/fail-safe, and make the example's configured calendar inventory internally consistent.

## What is strong enough to preserve

- The planner core is deterministic and separated from provider mutation.
- Calendar ownership markers, deterministic IDs, global collision checks, and managed-calendar restrictions are thoughtfully designed.
- Todoist mutation is intentionally limited to due datetime, with deadline preservation checks. That boundary is strong for non-recurring tasks but unsafe for recurring Date values until recurrence is modeled and protected.
- Ambiguous write outcomes have durable reconciliation barriers and good failure-path tests.
- Slack delivery/event deduplication, state locking, bounded provider responses, secret indirection, redirect restrictions, LLM redaction, strict schemas, and duplicate-key rejection are stronger than typical first implementations.
- Documentation explicitly distinguishes hermetic tests from live QA rather than falsely claiming integration success.
- The supplied campaign demonstrates that the Google Calendar API/Todoist ownership, approval, write, idempotency, deadline, and rollback boundaries work together in the tested isolated scenario.

These strengths justify continuing the implementation rather than restarting it. They do not remove the need for current CI, code fixes, or the remaining provider-backed QA.

## Recommended re-entry sequence

### 1. Re-establish a green exact-tip baseline

1. Use draft PR #12 / `qa-google-calendar` as the continuation point; preserve its phase history because it contains every #4–#11 head.
2. Use the clean 891-test/OpenSpec pass, successful PR #12 Java 25 check at `939edef`, and 12/12 independent campaign at tested commit `9b3bebf` as the baseline. Retain the earlier `94525b7` result only as corroborating history.
3. The QA thread's orb setup and generated OpenSpec skill/config commits are now transferred to PR #12 as reviewable descendants; rerun the full gate after any application-source fixes.
4. Broaden CI triggers if stacked/integration PRs will continue to be used. PR #12 itself now has a successful exact-tip GitHub check because it targets `master`.

### 2. Fix known issues before more live integration QA

Address the six high-severity SmartPlanner findings above and the weather/config inconsistencies. Treat recurring Todoist tasks as ineligible for Date mutation until a recurrence-safe policy is implemented and proven. Decide whether intentional apply-safe withholding should report `partial`. Also remove legacy token/content logging and add focused migration, 403, and pool-reset regressions. Add tests at the production composition/daemon boundary, not only isolated unit tests. Re-run the entire suite after fixes.

### 3. Resolve the branch and review queue

1. Review the consolidated diff in PR #12, using the old phase PRs as historical/narrower context where useful.
2. Reconcile PR #11's formal change request and all new findings against PR #12's descendant code.
3. After PR #12 is accepted, close #4–#11 as superseded; do not merge both routes.

### 4. Execute isolated provider QA in safety order

The fresh independent Google/Todoist resource campaign is complete. Slack, Weather, LLM, combined Slack+AI, CalDAV, recurring Todoist tasks, and production rollout were intentionally out of scope and remain later gates:

1. **Independent core rerun — complete:** OAuth isolation, inventory/provisioning, capacity/preview, missing/wrong-hash refusals, one exact apply, replay, apply-safe, both fully-automated refusals, rollback, cleanup, command statuses, checksums, provider diffs, state restoration, and final fixture deletion all passed. Recurrence was deliberately excluded and remains a separate post-fix case.
2. **Legacy smoke:** with disposable resources and after removing secret logging, run one bounded legacy sync and a second incremental cycle. Confirm no duplicate events, no replayed migration, successful delete/update behavior, persisted sync state, and no secret/content leakage at normal log levels.
3. **Weather first:** use controlled outdoor/indoor fixtures plus a live Open-Meteo observation; cover disabled/unlabelled baselines, usable forecast, malformed/stale/missing data, `fail_closed`, explicit `fail_open`, repeatability, and zero provider writes.
4. **LLM second:** use an approved low-quota endpoint with minimized synthetic data; cover each supported schema where feasible, valid structured output, redaction/bounds, identity binding, no direct mutation, confirmation refusal cases, and local negative mocks.
5. **Slack last:** test real Socket Mode readiness, commands, proposal threads, working status, replan, unauthorized/stale/duplicate inputs, restart recovery, truthful apply outcomes, contained provider failure, graceful shutdown, and no inbound listener in a private QA channel.
6. **Combined Slack+AI:** test unmatched feedback interpretation, displayed confirmation, expiry/staleness, deterministic follow-up, and proof of no write before confirmation.
7. **Evidence:** retain a secret-free manifest, redacted command logs, receipts, provider exports, state snapshots, JUnit/OpenSpec outputs, cleanup proof, and checksums. Never archive token stores, OAuth client JSON, consent URLs, authorization codes, or raw provider credentials. Native UI screenshots remain optional unless visual behavior itself becomes disputed.

### 5. Roll out conservatively

Only after the isolated evidence is reviewed:

- **Crawl:** 24-hour then three-day production preview, integrations optional and writes disabled.
- **Walk:** one exact approval-required change with observation and rehearsed restore.
- **Run:** short-horizon `apply_safe_changes`, daily receipt review, then gradual expansion.

`fully_automated` should remain refused. Stop on any unexplained mutation, ambiguous result, identity mismatch, deadline change, or incomplete evidence.

## Verification performed for this report

- Fetched full Git history and every remote branch/PR head.
- Inspected all open PR metadata, reviews, comments, changed files, and checks.
- Confirmed PR #9 and #10 have the same head commit.
- Confirmed all PR #11 review threads are resolved while its formal review decision remains `CHANGES_REQUESTED`.
- Confirmed the pre-delivery `qa-google-calendar` was 21 commits ahead and zero behind PR #11; this report/evidence delivery brings it to 25 ahead.
- Audited implementation, tests, configs, runbooks, OpenSpec tasks, and commit messages across PR #11 and `qa-google-calendar`.
- Searched all indexed Amp project threads plus broad SmartPlanner/QA keywords, read all ten prior project threads, and reconciled their outputs and unfinished claims against current `master` history and source.
- Downloaded and inventoried both supplied QA archives. Verified the narrative archive's SHA-256 and both internal checksum manifests. Safely extracted the primary Hermes archive under owner-private permissions; recorded its SHA-256; audited 135 files without printing credential values; parsed receipts/plans; compared Todoist and Google snapshots; inspected OAuth scope separation and failure/success sequence; and checked final state/cleanup contents.
- Confirmed PR #12 is mergeable and its exact-tip Java 25 GitHub check passes; PR #11 itself remains unchecked because its base is not `master`.
- Commissioned a clean independent QA thread at exact base `939edef`; it passed the full 891-test gate, build/distribution/launcher checks, secret/redaction scans, and 4/4 strict OpenSpec validation, followed by all 12 live Google/Todoist campaign gates with 299/299 scoped tests.
- Downloaded the final secret-free QA package, verified its SHA-256, safely extracted it, inspected the machine-readable results/manifest, exact apply receipt, command statuses, cleanup/redaction assertions, and normalized provider snapshots; independently confirmed byte-identical final provider baselines and visually inspected representative report captures. After normalizing the package manifest's stale source prefix, all 134 distributable entries verify.
