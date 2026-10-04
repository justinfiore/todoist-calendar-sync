# Recurrence and Deadline Live QA Evidence — 2026-10-02

## Verdict

**PASS WITH LIMITATIONS — all application-owned OpenSpec/runbook provider-state gates passed; fixed-zone
completion, cursor rejection, UI, and assignment limitations are explicit below.**

The disposable Todoist and Google Calendar campaign exercised ten recurrence scenarios through three
scheduled occurrence histories and two native completions, incremental detection, crash/restart,
partial HTTP 429 recovery, drift edits, rollout/rollback, tombstone processing, automatic capture,
contention, initially-undated behavior, and destructive cleanup. Reviewer remediation subsequently
added a real fixed-IANA DST case and a non-empty property case. Final inventory is zero for every
campaign and remediation resource category.

Limitations and provider boundaries:

1. The orb browser had no authenticated Todoist or Google session. UI edits were performed through
   the providers' APIs against the same disposable resources, then verified by provider re-read; no
   rendered Todoist/Calendar screenshot or literal click-through is claimed. Link URLs and ownership
   metadata were verified in Google event payloads.
2. A manufactured invalid Todoist cursor was accepted by Todoist and returned items rather than a
   400. Cursor-loss bootstrap passed live; the rejection/reset branch is covered by a hermetic 400
   regression. This provider behavior is reported rather than called a live rejection pass.
3. A fixed `America/New_York` recurrence re-read correctly, SmartPlanner scheduled both sides of the
   2027 DST start after the live-discovered fix, and the documented explicit recurring-completion
   command preserved it. Todoist's simplified REST and Sync `close` operations instead reported
   success and removed the future fixed-zone Due. SmartPlanner does not complete Todoist tasks; this
   provider-operation limitation is retained as two failed observations, not treated as an
   application pass.
4. The disposable project exposed only the account owner. Creating the task with that collaborator's
   ID normalized `assignee_id` to null, so non-empty cross-account assignment was not testable.
   Description, label/project, comment, subtask, and reminder preservation all passed live.

## Identity and isolation

- Campaign: `20261002T161152Z-recurrence-deadline-935f7c84ac96`
- Initial reviewed implementation: `935f7c84ac96969e4491dd38d852f3617e6b3550`
- Live-discovered production fixes: `7bd3662` (`Fix live recurrence persistence and recovery boundaries`)
- Expanded regression contract: `7a9ed37` (`Codify live recurrence failures as contract regressions`)
- Reviewer-remediation fixed-zone production fix: `f00e8164a6bdb8452163a93bd89adab243300c4b`
  (`Preserve fixed-zone recurrence during apply`)
- Branch: `implement-deadline-recurrence-support`
- Base: `plan-deadline-recurrence-support`
- Java: Temurin 25.0.4.1; Gradle 9.6.1; OpenSpec 1.14.0; Node 26.10.0
- Private raw run, credentials, receipts, and unsanitized provider payloads remained under ignored
  `.qa`. Only inspected normalized assertions are copied here.
- The independent `2026-10-01-independent-google-todoist` directory was not edited. Its aggregate
  file-tree digest remained `a112aaaa9659c1eb983e6a0d02f7ead3d8c9786de991001441bf2fa0ca01f955`.

## Provider inventory and cleanup

Initial pre-cleanup inventory contained 18 active project tasks, two campaign calendars, one project, five
created labels, one managed-output event, and zero blocker events. Cleanup deleted the campaign
events/calendars, tasks, project, and labels; deletion deltas were polled. Final normalized inventory:

| Resource | Remaining |
| --- | ---: |
| Campaign calendars | 0 |
| Campaign projects | 0 |
| Created labels | 0 |
| Known active tasks | 0 |
| Tagged active tasks | 0 |

See `normalized/101-cleanup-preflight.json` and `normalized/103-final-cleanup-inventory.json`.
Each reviewer-remediation case was independently deleted and re-inventoried. The fixed-zone campaigns
and property campaign each reached zero known/tagged tasks, projects, and labels; the final aggregate
remediation inventory is also zero. See `normalized/105-fixed-timezone-cleanup.json`,
`normalized/107-property-preservation-cleanup.json`,
`normalized/111-fixed-timezone-completion-cleanup.json`,
`normalized/116-fixed-timezone-production-initial-cleanup.json`,
`normalized/121-fixed-timezone-production-retest-cleanup.json`, and
`normalized/108-review-remediation-final-inventory.json`.

## Live matrix results

- **Recurrence preservation:** the original ten scenarios covered daily, weekly/date-only,
  weekday-specific, monthly, yearly, strict-relative `every!`, explicit-time, rich weekday, and
  existing-Deadline behavior. Their attempted REST timezone parameter normalized to floating
  `timezone:null`; it is a floating civil-time control, not fixed-zone evidence. Reviewer remediation
  separately proved a non-null `America/New_York` Due and explicit recurring completion across the
  2027 DST start, preserving 03:30 civil time while UTC changed from 08:30Z to 07:30Z. The production
  planner retained two distinct owned Calendar events at `03:30-05:00` and `03:30-04:00`, with stable
  Todoist links and ownership metadata.
- **Occurrence advancement:** all ten cases advanced natively twice on the same active Todoist task
  ID with `completed_count` 0→1→2 and preserved tuples. Incremental cycle-1 detection was 28 seconds,
  within the nominal 0–5 minute gate.
- **Calendar history:** first, second, and third schedules produced 10, 20, and 30 total managed events;
  each case had one, two, then three distinct owned event IDs, aligned Todoist/Google times, and stable
  ID-only Todoist links. A task rename retained all three links.
- **Restart/recovery:** a staged six-item pending inbox was replayed after process reconstruction
  without refetch; all active markers and Deadlines reached count 2. Cursor-state deletion rebuilt from
  Todoist and immediately caught up. A deletion tombstone appeared in Sync and was excluded from the
  active inventory.
- **Drift:** recurrence, same-count Due, Deadline, `hard`, recurrence removal, sentinel removal, and
  removed/malformed/duplicate/unsupported markers were all preserved and classified fail-closed.
- **Onboarding:** legacy shadow observation, labelled onboarding, request-label-last behavior,
  existing Deadline, post-cutoff automatic capture, initially undated/planner Due, and later genuine
  user Due were exercised. Unlabelled legacy tasks received no Deadline write.
- **Deadline scheduling:** after removing historical managed events from disposable capacity, urgent
  hard work preceded ordinary high priority, a soft-overdue task remained schedulable after its date,
  and infeasible hard work was explicitly reported.
- **Rollback:** recurrence-disabled preview had byte-identical provider-state hash and zero writes;
  forward polling reconciled before re-enable while 30 historical managed events remained intact.
- **Cleanup:** `all_zero=true` after provider deletion and tombstone processing.
- **Non-empty properties:** one comment, one subtask, one reminder, exact human description, label,
  project, and task identity survived a civil-time recurrence move and native completion exactly
  once. The owner-only assignee normalized to null, so collaboration/assignment is limited rather
  than passed.

`case-index.csv` maps every runbook gate to retained evidence and limitations.

## Honest failure and retest chronology

1. **Present-null timezone rejected.** Initial onboarding treated Todoist's explicit `timezone:null`
   as incomplete. `TodoistDue` now distinguishes presence from value and serializes explicit null.
   Unit and WireMock request-shape regressions pass.
2. **Multi-item onboarding restart failed.** A local `marker` variable shadowed the marker factory, and
   staged request-label cleanup had no restart finalization. Naming and `onboarding_finalized` recovery
   were fixed; durable replay completed all staged items.
3. **Persisted preview lost recurrence authority.** Plan schema v2 omitted Due/Deadline/marker/count
   fields, so apply reloaded tasks as non-recurring and used REST `due_datetime`. Schema v3 now
   round-trips lifecycle state; save/load/apply regressions pass.
4. **UTC replacement changed floating recurrence semantics.** Todoist normalized recurrence when sent
   a `Z` datetime. Recurring apply now sends civil time in the Due/planner zone while preserving the
   provider tuple. Live controls and exact Sync JSON regressions pass.
5. **HTTP 429 after marker commit blocked resume.** The same plan rejected its own already-written
   marker. Apply now accepts exact original-or-target marker/Due states, writes only the missing side
   with stable IDs, and reuses the owned Calendar event. The initial retry had 10 errors/zero writes;
   the repaired retry completed six missing writes and restored two-event history for all ten cases.
6. **Automatic capture used the wrong creation key.** Todoist Sync emits `added_at`, while the parser
   handled only `created_at`; the first automatic task was classified `legacy_pending`. The alias fix
   retest produced `initial_user_due`, Deadline, sentinel, and marker.
7. **Contention initially had no capacity.** Retained historical managed events correctly consumed the
   campaign window. After explicitly clearing those already-evidenced disposable events, the isolated
   contention retest passed all soft/hard assertions. This was fixture isolation, not a scheduler fix.
8. **Reviewer remediation corrected two overclaims.** The original `timezone-attempt` was floating,
   and the selected property samples had empty comments/reminders/subtasks. A documented Sync Due
   update produced a real `America/New_York` recurrence. Simplified REST and Sync `close` calls then
   removed that future fixed-zone Due despite success responses; both failures are retained. The
   explicit recurring-completion command passed across DST. A separate non-empty property case passed
   for description, label/project, comment, subtask, and reminder before/after movement and completion;
   single-account assignment remained unsupported.
9. **Production scheduling floated fixed-zone recurrence.** The first real planner apply sent a civil
   datetime for a Due whose timezone was `America/New_York`; Todoist normalized it to `timezone:null`,
   the postcondition failed, and Calendar mutation was correctly withheld. Fixed-zone recurrence now
   sends a UTC `Z` date plus the unchanged IANA timezone, while floating recurrence still sends civil
   time plus explicit null. The first live retest created one owned event with fixed timezone intact.
   An evidence-checker comparison then falsely rejected Google's equivalent `03:30-05:00` against
   `08:30Z`; instant normalization fixed the checker. After native completion and incremental polling,
   the second planner apply retained two distinct owned events at `03:30` on both sides of DST.

The initial failures remain represented by `68-cycle-1-apply-retry-summary.json`,
`89-automatic-capture.json`, and `95-contention-preview.json`. Reviewer-remediation failures are in
`104-fixed-timezone-dst.json` and `109-fixed-timezone-dst-native-close-retest.json`; the successful
explicit completion is `110-fixed-timezone-dst-explicit-completion-retest.json`. The production failure
and checker failure are retained in `113-fixed-timezone-production-scheduling-initial-failure.json`
and `117-fixed-timezone-production-scheduling-assertion-normalization-failure.json`; successful
production retests are `118-fixed-timezone-production-scheduling.json` through
`120-fixed-timezone-production-scheduling.json`. No failed observation was rewritten as a pass.

## Regression mapping for every discovered defect

| Defect | Owning-boundary test | Crossing-boundary test |
| --- | --- | --- |
| Present-but-null timezone | `RecurrenceLifecycleSpec: recurring Due preserves Todoist floating timezone as an explicit null field` | `TodoistSyncGatewayWireMockSpec: civil-time Sync item_update preserves an explicitly null floating recurrence tuple` |
| Multi-item onboarding shadowing/restart | `RecurrenceLifecycleSpec: first observation with onboarding request commits marker then removes request label` | `RecurrenceLifecycleSpec: restart after staged first-observation onboarding removes request label idempotently` plus persisted inbox reconstruction test |
| Civil-time Sync shape | `RecurrencePlanApplierSpec: floating recurrence receives planner-zone civil time and preserves explicit null timezone` | Same WireMock test asserts the exact five-field JSON request |
| Plan lifecycle/count persistence | `PlanStoreSpec: round-trip preserves recurrence lifecycle fields required by apply` | `RecurrencePlanApplierSpec: two native occurrences use distinct UIDs and retain the historical event` saves, reloads, and applies |
| Provider-normalized fingerprint | `RecurrenceLifecycleSpec: provider-normalized date and timezone alias retain fingerprint while zone drift does not` | Civil-time WireMock test plus live timezone controls |
| Partial 429 resume | `TodoistSyncGatewayWireMockSpec: HTTP 429 mutation is surfaced after one request without blind retry` | `RecurrencePlanApplierSpec: retry after marker success and rate-limited Due write completes without duplicate event` asserts one marker write, two Due attempts, one event |
| Pending inbox restart | `RecurrenceLifecycleSpec: dedicated items cursor checkpoints response and replays inbox before acknowledgement` | `RecurrenceLifecycleSpec: items poller replays a staged delta after processor failure without refetching` reconstructs store and poller |
| Sync `added_at` onboarding | `RecurrenceLifecycleSpec: Todoist Sync added_at drives automatic post-cutoff initialization` | `ProductionPlannerOrchestratorIntegrationSpec: incremental added_at item onboards automatically while tombstone is excluded from active processing`; WireMock also preserves the field |
| Tombstone exclusion | Cursor persistence retains tombstones until acknowledgement | The orchestrator integration proves no inventory fetch, task re-read, or lifecycle write for the tombstone; WireMock preserves `is_deleted` |
| Fixed-IANA scheduling normalized to floating | `RecurrencePlanApplierSpec: fixed-zone recurrence receives UTC instant and preserves IANA timezone authority` | `TodoistSyncGatewayWireMockSpec: fixed-zone Sync item_update sends UTC date with non-null IANA timezone`; occurrence-history integration now expects UTC `Z` for fixed-zone Due |

The named tests live in:

- `app/src/test/groovy/todoistcaldavsync/planner/recurrence/RecurrenceLifecycleSpec.groovy`
- `app/src/test/groovy/todoistcaldavsync/planner/adapters/TodoistSyncGatewayWireMockSpec.groovy`
- `app/src/test/groovy/todoistcaldavsync/planner/apply/RecurrencePlanApplierSpec.groovy`
- `app/src/test/groovy/todoistcaldavsync/planner/state/PlanStoreSpec.groovy`
- `app/src/test/groovy/todoistcaldavsync/planner/ProductionPlannerOrchestratorIntegrationSpec.groovy`

The restart case is strongest at the persisted poller boundary rather than WireMock because the defect
is process reconstruction over local durable state, not HTTP parsing. The 429 case is split deliberately:
WireMock proves one transport attempt and classification; the apply integration proves stateful resume
and Calendar idempotency.

## Automated verification

- Focused `bash scripts/recurrence-sync-contract.sh`: **82/82 passed**, 0 failures/errors/skips.
- Full `./gradlew --no-daemon -Dorg.gradle.jvmargs='-Xmx256m -Xms64m -XX:MaxMetaspaceSize=192m' -Dorg.gradle.workers.max=1 test`: **963/963 passed**, 0 failures/errors/skips, `BUILD SUCCESSFUL in 1m 36s`.
- `npx -y @fission-ai/openspec@1.14.0 validate add-deadline-recurrence-support --strict`: valid.
- `git diff --check`: passed.

Three full-suite attempts were terminated without a result after stale Gradle daemons and test workers
exhausted the 2 GiB orb and stopped making progress: two in the initial campaign and one during review
remediation. After stopping daemons and constraining the single-use daemon heap, the unchanged full
suite completed successfully. These were infrastructure stalls, not failing test assertions.

## Documentation handoff map

Files changed for the source thread's independent documentation audit:

- `README.md`
- `conf/todoist-planner.conf.example.yaml`
- `docs/SMART_PLANNER_CONFIGURATION.md`
- `docs/SMARTPLANNER_QA_RUNBOOK.md`
- `openspec/changes/add-deadline-recurrence-support/tasks.md`
- this new `docs/qa/evidence/2026-10-02-recurrence-deadline/` package

Behavior mapping:

- recurrence onboarding and voice/mobile capture → README, configuration guide, config example, QA runbook;
- soft/hard Deadline semantics and priority/soon-window behavior → README and configuration guide;
- five-minute incremental polling, durable inbox, cursor recovery, tombstones → configuration guide and QA runbook;
- occurrence-aware Calendar history, ownership, reconstruction, and ID-only links → README and configuration guide;
- staged rollout, preview rollback, and forward reconciliation → configuration guide and QA runbook;
- disposable live matrix, stop conditions, evidence, redaction, and cleanup → QA runbook and this package;
- live-discovered null-timezone, civil/fixed-zone time, schema-v3 persistence, partial-retry, inbox-replay,
  `added_at`, and tombstone invariants → QA runbook “Provider-normalization and recovery invariants”
  and this report's failure/regression sections.

This package reports the implementation state; it does not claim the independent final documentation
review assigned to the source thread.

## Reproduction and package integrity

`command-status-index.txt` records the sanitized command sequence and statuses. Verify all tracked
evidence with:

```bash
cd docs/qa/evidence/2026-10-02-recurrence-deadline
sha256sum -c evidence-manifest.sha256
```

The redaction scan covers only this committed package and tracked change set; it does not inspect or
publish ignored credentials. See `redaction-scan.txt` for patterns and result.
