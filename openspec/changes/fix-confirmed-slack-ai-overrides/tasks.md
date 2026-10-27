# Tasks

## 1. Reproduce the public confirmation defect

- [x] 1.1 Replace the fabricated AI `exclude` result in `SmartPlannerDaemonSpec` with validator-issued duration/context fixtures and assert a pending 30-to-45-minute change, a real revised 45-minute interval, an unaffected 30-minute control and zero provider writes; run the targeted test on unchanged production code and retain the decisive failure privately.

## 2. Restore bounded suggestion authority after restart

- [x] 2.1 Add bounded canonical export/revalidation at the existing validator boundary and extend conversation pending state with exact content/plan/input/conversation/range identity; verify current-time restoration succeeds while tampered, unknown, expired and legacy untyped records fail through existing public confirmation tests.
- [x] 2.2 Wire the existing signed AI decision store and confirmation service into production orchestration with a private persistent service-owned key; verify restart keeps provenance, missing/corrupt retained key state refuses authority, unauthorized confirmation creates no usable decision and AI-disabled/one-shot suggestion behavior is unchanged.
- [x] 2.3 Document the pending-state compatibility rule, signing-state preservation and safe rejection behavior in `docs/LLM_INTEGRATION.md`; check that documented behavior matches the restoration/authorization tests and contains no credentials or private campaign values.

## 3. Apply confirmed changes to complete task copies

- [x] 3.1 Extend `ConfirmedOverrideApplier` to validate selected decisions against one original input and compose duration/context transformations atomically; extend `AiConfirmationAndOverrideSpec` to verify mixed sets, both types on one target, no valid-subset application and unchanged source/control tasks.
- [x] 3.2 Preserve every current `Task` field except the confirmed planning-only changes in the existing applier copy path; verify native Due/recurrence, Deadline, lifecycle metadata, description, timestamps and manual controls survive using asymmetric populated fixtures.
- [x] 3.3 Integrate the confirmed task copies into `ProductionPlannerOrchestrator` preview before deterministic scheduling, retaining the explicit fixed reviewed range and current weather pipeline; verify actual duration/context placement and plan explanations while provider inventories, source fixtures, old plans and permanent configuration remain unchanged.
- [x] 3.4 Document fixed-range confirmation and temporary-only effects in `docs/LLM_INTEGRATION.md`, and verify tests refuse changed tasks/events, rolled horizons, narrower unsupported ranges and expired confirmation without silently expanding scope.

## 4. Complete the Slack confirmation transition

- [x] 4.1 Remove the incompatible AI map translation and route valid pending bundles through exact confirmation to the production confirmed-preview path; make task/value/range summaries truthful and distinguish empty/rejected overrides, then make task 1.1 pass without bypassing schema validation.
- [x] 4.2 Keep pending/confirmed intent recoverable until the exact successor plan and delivery result are durable; verify public daemon scenarios for restart before confirmation, interruption, duplicate delivery after restart and uncertain publication produce no partial or duplicate successor authority.
- [x] 4.3 Extend existing daemon tests for actor/thread mismatch, stale plan/input, legacy pending records and combined duration/context behavior; retain existing regex exclusion/priority/freeze tests and verify their behavior is unchanged.
- [x] 4.4 Update the QA replay guide with an actual changed-duration/context acceptance gate, Firefox native Todoist observations, safe media capture, no-write controls and cleanup requirements; check documented cases correspond to executed hermetic assertions or explicitly future live evidence.

## 5. Verify and deliver the implementation before live replay

- [x] 5.1 Run focused AI/daemon/orchestrator contracts, `scripts/smartplanner-qa.sh contracts`, a fresh credential-free full build and distribution install; verify zero failures/errors/skips, strict OpenSpec validity and clean whitespace, recording actual totals rather than reusing earlier campaign numbers.
- [x] 5.2 Review and scan the tracked diff, commit only implementation/tests/docs and accurately completed task updates in a separate commit from the proposal, push `fix/xai-device-login-qa-plan`, and verify PR #20 head plus automatic exact-head CI/comment subscriptions; exclude private live harnesses, configs, credentials and evidence from Git.

## 6. Run a new isolated campaign with native Todoist evidence

- [x] 6.1 Initialize a fresh ignored campaign and verify account/resource ownership, current canonical credential records and before-inventories without overwriting prior runs or stale-restoring grants; verify Firefox remains signed into the intended Todoist QA account and Slack/Google UI access before fixtures.
- [x] 6.2 Replay ordinary baseline/weather, exact-approved apply/safe-only, guarded calendar writeback and idempotent repeats on the recorded disposable fixtures; verify Todoist/Google API diffs, protected fields and native Todoist/Google displayed Due/event alignment and task-link navigation with inspected sanitized captures.
- [x] 6.3 Replay bounded real grok-4.6/grok-4.7 subscription schema checks and real Slack + current Open-Meteo feedback/confirmation/restart; prove the revised proposal changes the target from 30 to 45 minutes and exercises a context override, while source duration/labels and provider inventories stay unchanged before separate apply. Keep credential-dependent integration tests private and capture inspected native Slack screenshots/recording.
- [x] 6.4 Complete the separately isolated disposable recurrence variants twice through native Todoist Firefox controls, not API completion substitutes; verify returned occurrence identities, protected recurrence/Deadline tuples, historical Google events and restart behavior with API assertions plus inspected native screenshots/recordings. Keep weather-disabled recurrence scope distinct from current-forecast integration evidence.
- [x] 6.5 Stop supervised QA services, disable optional mutation features, remove only recorded owned fixtures/messages/events and verify zero active leftovers and unchanged unrelated inventories; securely persist the newest canonical rotating Google/xAI records before retirement.
- [x] 6.6 Update the existing stable QA report and append a fresh sanitized case ledger/evidence package, preserving all earlier evidence; verify privacy scans/checksums/media review, distinguish synthetic/live API/native UI outcomes and every remaining gap, and report delivery, cleanup and owner actions in this thread and the source handoff thread.
