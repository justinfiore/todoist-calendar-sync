# Tasks

## 1. Shared routing and configuration

- [ ] 1.1 Extract the pure inclusion/ordered destination evaluator from `TodoistCalDavSync`, use it in legacy and routed callers, and verify first-match, exact case/project names, AND/NOT, project-only inclusion and no-match through existing/extended routing tests.
- [ ] 1.2 Add validated routed managed destinations and routing fingerprints to planner/provider configuration without weakening single-output mode; verify invalid expressions, unknown/duplicate destinations, mixed providers/output models and no-network validation failures in configuration tests.
- [ ] 1.3 Separate projection eligibility from optimizer/lifecycle eligibility in the orchestrator and recurrence poll callback; verify manual/project-included tasks mirror without automatic scheduling/lifecycle mutation and excluded recurring tasks remain untouched.
- [ ] 1.4 Update example config and `docs/SMART_PLANNER_CONFIGURATION.md` for inclusion/routing/managed roles and sanitized seven-destination examples; verify examples parse without real IDs/secrets and single-output fixtures still load.

## 2. Recoverable projection state and provider boundaries

- [ ] 2.1 Version mappings and owned event metadata for destination, resource ID, ordered intervals, occurrence membership/revision and verified schedules; verify round-trip/state upgrade, historical identities, local-state-loss recovery and malformed/missing group metadata withholding.
- [ ] 2.2 Extend the application state store with durable immutable-intent sync operations and per-resource outcomes; verify restart after each checkpoint, atomic store replacement, overlapping-writer refusal and corruption fail-closed behavior.
- [ ] 2.3 Extend `ManagedCalendarWriteGateway` and both provider adapters to explicit managed destination sets and exact-resource conditional writes; verify allowed routed writes plus refusal for read-only calendars, stripped markers, wrong membership and stale ETags using WireMock/provider tests.
- [ ] 2.4 Implement journal-bound cross-destination relocation/replacement recovery without weakening ordinary duplicate-UID detection; verify ambiguous create/delete, exact transitional pairs, unrelated duplicates, restart and preservation of newer resource edits.
- [ ] 2.5 Document state versions, ownership/recovery boundaries and backup units alongside operational docs; verify old single-task fixtures recover and unsafe old group fixtures report actionable repair instead of adoption.

## 3. Independent single-task schedule synchronization

- [ ] 3.1 Confirm current official Todoist native-duration mutation semantics and implement the narrow scheduling write port; verify exact documented request payloads, native hour/minute normalization and readback while Deadline/content/labels/priority/completion remain unchanged.
- [ ] 3.2 Implement baseline-based single-task reconciliation around existing writeback logic, including end-only calendar edits, Todoist Due/duration changes, echoes and fresh initial projections; verify asymmetric edits on both sides and that an old proposal never supplies a mirrored time.
- [ ] 3.3 Preserve recurrence through shared guarded Due updates and verified duration steps; verify fixed/floating timezone, DST, incomplete tuple, native completion between reads, exactly-one intended-update repair and no datetime-only recurring POST.
- [ ] 3.4 Implement conflict/cancellation handling for both-sided edits, missing/equal timestamps, lost baselines, date-only/timed conversion, removed Due and deleted events; verify no destructive guesses or deletion/recreation loops and no writeback from historical occurrences.
- [ ] 3.5 Add `planner-sync`, read-only sync preview and independent daemon cadence with explicit forward/reverse/lifecycle/plan authorities and separate durable items inbox; verify sync with no planning schedules, cursor staging/restart, outage/backoff and no shared legacy cursor.
- [ ] 3.6 Update CLI help/config/runbook for explicit sync enablement, interval, status, conflict resolution and proposal-only versus read-only behavior; verify installed CLI and daemon tests show zero remote writes for capacity/preview and disabled mutation capabilities.

## 4. Group dragging, proportional resizing and splitting

- [ ] 4.1 Implement whole-group shift and combined move/resize from immutable verified intervals; verify asymmetric member starts/durations and fixed/floating recurring members across DST without changing task completion or Deadline.
- [ ] 4.2 Implement constrained proportional whole-minute allocation and native-duration writeback; verify [30,60] to 60 gives [20,40], [10,10] to 21 gives [11,10], [1,1,98] to 3 gives [1,1,1], and too-short/non-minute spans send no mutations.
- [ ] 4.3 Implement member edit/route-change split-out and maximal consecutive remaining runs; verify middle/edge/multiple edited members, one/zero remainder, new individual destinations and no changes to unaffected task Dues/durations.
- [ ] 4.4 Integrate per-member preflight, operation provenance and partial recovery for transforms/splits; verify failure after first member, ambiguous later write, concurrent completion/user edit, restart/echo replay and no double resize or self-induced split.
- [ ] 4.5 Document group editing and rounding examples, unsupported conversions and partial/conflict status in the user guide; verify a hermetic CLI/daemon scenario produces the documented task intervals and resulting calendar membership.
- [ ] 4.6 Implement verified member-departure reconciliation for native recurrence advancement, non-recurring completion/deletion, Due removal and inclusion/route loss independently of planning; verify asymmetric middle/edge/all-member departures, historical single-occurrence intervals without active-member coverage, unchanged residual Dues/durations, eligible dated successor projection from fresh Todoist and explicit undated/ineligible reporting. Verify partial feed absence/unexplained occurrence changes do not authorize retirement, concurrent calendar edits remain conflicts, and journal restart after each target creation/source retirement leaves no duplicate history/successors or missing residual projections.

## 5. Planning and apply integration

- [ ] 5.1 Partition `ProjectBatcher` by route and preserve current sync placements as planning inputs; verify same-project/different-destination tasks cannot share a block and sync-only never chooses new grouping or work times.
- [ ] 5.2 Reconcile availability for replaceable task projections, retained manual/frozen placements and unrelated appointments; verify no self-block/double counting, genuine blocker preservation and withheld planning on required calendar failure.
- [ ] 5.3 Bind source Due/native-effective duration, route fingerprint, occurrence and membership revision into plan persistence/hash/preflight; verify manual/routing/group edits after approval reject before dependent writes, with successful reapply still idempotent through its own verified operation evidence.
- [ ] 5.4 Wire routed `PlanApplier` through the same journals/ownership/provenance as sync; verify a fresh exact approved plan can supersede reconciled manual placement, proposal alone cannot, and safe-only/protected/fully-automated policies remain unchanged.
- [ ] 5.5 Update planning/Slack documentation to explain synced manual intent, stale proposal refusal and fresh-plan authorization; verify fake-surface integration covers manual sync between proposal and approval and correctly reports the stale result.

## 6. Reviewed deletion-first onboarding and rollback

- [ ] 6.1 Implement read-only migration inventory/export with derived legacy UID/task evidence, full restorable resource payloads, versions, routing/account fingerprints and explicit exclusions; verify unrelated/uncertain events are excluded and exports contain no credentials.
- [ ] 6.2 Add manifest-bound deletion authorization and handoff gates proving overlapping legacy writes stopped and other mutation loops held; verify stale manifest/account/resource versions, missing backups and active overlapping writers refuse deletion.
- [ ] 6.3 Implement a narrow conditional legacy-resource deletion capability and persisted migration stages; verify partial/ambiguous deletion reconciliation, restart, and zero reconstruction writes until every approved deletion is verified absent.
- [ ] 6.4 Implement reconstruction from fresh Todoist Due/resolved duration as individual routed events with all Todoist/lifecycle/planning writes disabled; verify outside-hours times, date-only dates, absent Due, unrouteable tasks and task edits during the deletion gap.
- [ ] 6.5 Implement parity verification and separate normal-sync/first-planning release gates; verify exact destination/start/end/membership, no surviving approved legacy IDs/duplicates, unchanged Todoist fields, and blocked release for any unexplained mismatch.
- [ ] 6.6 Implement resumable reviewed rollback using retained payloads and exact created-resource/version evidence; verify crash after deletion, partial reconstruction, replacement human edits and no blind restoration of Todoist Due/completed counts or stale local cursors.
- [ ] 6.7 Extend the end-to-end runbook with dry-run/review commands, maintenance-window/writer-stop checks, deletion/reconstruction ordering, recovery, rollback and separately authorized live gates; verify the full sequence hermetically without production services or credentials.

## 7. Combined acceptance and delivery evidence

- [ ] 7.1 Run the full hermetic routed lifecycle scenario from legacy inventory/deletion through sync reconstruction, first approved group plan, drag, resize, split, route move and native recurrence advancement within a mixed group; include completion/deletion, Due/inclusion loss and interrupted departure recovery without planning, and retain sanitized provider/task/state evidence showing exclusive ownership, separate historical/current membership and exact schedules.
- [ ] 7.2 Run focused suites then `./gradlew :app:test --rerun-tasks`, `./gradlew build` and `./gradlew installDist`; inspect decisive test/build results and exercise installed sync/migration help and hermetic operations without live credentials.
- [ ] 7.3 Reconcile this change's deltas with active recurrence/planner-integration safety requirements, run strict OpenSpec validation and whitespace/secret checks, and verify no earlier live-acceptance box was marked complete without evidence.
- [ ] 7.4 After separate authorization and isolated account setup, execute documented live Google/Todoist group-edit and migration/recovery acceptance; retain sanitized results, or leave this task unchecked with exact missing inputs. Do not copy QA credentials or mutate production to satisfy this gate.
- [ ] 7.5 Before production rollout, obtain sanitized runner-side confirmation of effective routing/inclusion/prefixes, account/IDs/scopes, timezone and writer exclusivity plus explicit manifest deletion and service-change approvals; retain the approved gate evidence or leave this task unchecked. Implementation completion alone does not authorize execution.
