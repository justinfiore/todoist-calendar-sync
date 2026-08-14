## 1. Preserve and assess the integration branch

- [x] 1.1 Confirm `feat/planner-main-integration` is based exactly on `feat/planner-phase-6` and record the base commit in the implementation/PR description.
- [x] 1.2 Inventory the existing uncommitted integration implementation, tests, fixtures, configuration, and documentation; preserve all unrelated work and map each file to these specs.
- [x] 1.3 Run the existing focused and full test suites before further edits and record every baseline failure without forcing planner state changes.

## 2. Production composition and configuration

- [x] 2.1 Complete `ProductionIntegrationConfig` parsing and validation for Todoist endpoint/token env name, CalDAV calendars/auth env names, weather transport controls, feedback actor allowlist, and all four durable state paths.
- [x] 2.2 Add validation tests for required values, unique calendar names, managed output calendar membership, absolute HTTPS production endpoints, positive limits/timeouts, relative state-path resolution, and actionable secret-free errors.
- [x] 2.3 Reject inline production tokens, passwords, API keys, and secrets while retaining clearly test-only dependency-injection seams that cannot be selected by production YAML.
- [x] 2.4 Complete the production composition root so disabled Weather, Slack, and AI adapters are not constructed or called and deterministic services receive only their narrow ports.
- [ ] 2.5 Ensure plans, applications/mappings, decisions, and deliveries use separate configured paths and preserve unknown/reconciliation-required outcomes across process restarts.

## 3. Existing main application integration

- [x] 3.1 Complete main CLI dispatch for `legacy-sync`, `capacity`, `preview`, `apply`, `apply-safe`, `deliver`, `feedback`, `apply-decision`, and `ai-suggest` with operation-specific required arguments and stable exit codes.
- [ ] 3.2 Preserve the legacy operation as the default and add regression coverage showing it does not require planner integration configuration.
- [x] 3.3 Verify `capacity` and `preview` accept explicit valid bounds, normalize live tasks/events, classify availability, optionally obtain weather, reuse an explicit/latest prior plan, and persist an immutable plan snapshot.
- [x] 3.4 Verify preview and capacity issue zero Todoist/CalDAV writes and do not call disabled optional providers.
- [x] 3.5 Complete exact approval loading and application with plan ID/version/hash validation and itemized durable refusal/success receipts.
- [ ] 3.6 Complete `apply-safe` so ordinary changes can apply while frozen, manually moved, drifted, protected, and approval-required changes are withheld and itemized.
- [x] 3.7 Prove `fully_automated` is refused by both apply paths with a durable refusal and zero remote writes.
- [x] 3.8 Complete explicit Slack delivery, feedback capture, decision application, and AI suggestion operations without collapsing their authority boundaries.
- [ ] 3.9 Add CLI tests for help, every operation, missing/invalid options, disabled integrations, unknown operations, output shape, secret redaction, and zero-write failure paths.

## 4. Todoist and CalDAV production adapters

- [x] 4.1 Complete Todoist REST task/project pagination, cursor/limit bounds, bearer auth, response-size/timeout controls, project-name enrichment, normalization fields, and classified errors.
- [x] 4.2 Restrict Todoist writes to due datetime only, refuse deadline writes before transport, and define safe read retry versus non-blind write retry/reconciliation semantics.
- [x] 4.3 Complete CalDAV range REPORT parsing with configured calendar-name preservation, all-day/timed event handling, and bounded XML/ICS responses.
- [x] 4.4 Complete all-calendar UID REPORT plus live-resource GET, and reject duplicate, cross-calendar, unmanaged, or drifted resources.
- [x] 4.5 Restrict PUT and DELETE to deterministic planner-owned resources on the managed calendar and require live UID/ownership/block metadata checks before deletion.
- [ ] 4.6 Record partial or ambiguous Todoist/CalDAV outcomes as reconciliation-required and prevent blind duplicate requests on rerun.
- [ ] 4.7 Add adapter tests proving credentials and sensitive response content do not appear in logs, exceptions, receipts, or snapshots.

## 5. Fixture and WireMock HTTP integration tests

- [ ] 5.1 Organize checked-in fake JSON/YAML/XML/ICS fixtures for Todoist pages/projects/errors, CalDAV events/multistatus/collisions, weather conditions, Slack responses, and all allowed LLM schemas.
- [ ] 5.2 Complete Todoist WireMock tests for exact paths/queries/headers/bodies, pagination, project lookup, due-only write, no deadline transport, retryable read, rate limit, malformed/oversized response, timeout, auth failure, and write ambiguity.
- [ ] 5.3 Complete CalDAV WireMock tests for range REPORT, UID REPORT on every calendar, GET, PUT, DELETE, auth modes, calendar-name retention, ownership/collision/drift refusal, malformed XML/ICS, limits, timeout, provider errors, and ambiguous writes.
- [ ] 5.4 Complete Open-Meteo WireMock tests for exact query parameters, clear/unsuitable/DST fixtures, stale/malformed/oversized/timeout failures, and configured fail-open/fail-closed behavior.
- [ ] 5.5 Complete Slack webhook and chat API WireMock tests for destination/auth/body shape, provider rejection, rate limits, timeout, duplicate suppression, and unknown delivery reconciliation.
- [ ] 5.6 Complete OpenAI-compatible WireMock tests for HTTPS/host controls, auth, redacted bounded context, deterministic request settings, strict output schema, every allowed suggestion type, malformed/duplicate-key/oversized/timeout responses, redirect refusal, and zero mutation.
- [ ] 5.7 Add a test-level network guard or equivalent assertion ensuring automated tests cannot contact configured real provider hosts and require no live credentials.

## 6. End-to-end orchestration integration tests

- [x] 6.1 Build a reusable fixture-backed integration harness with a fixed clock, temporary four-store state root, deterministic IDs/hashes, and injected provider gateways.
- [x] 6.2 Test live-capacity and repeated-preview flow, previous-plan stability, plan persistence/reload, explainable unscheduled work, and zero remote writes.
- [ ] 6.3 Test `approval_required` missing/stale/tampered/exact approvals, feedback capture without apply, explicit decision apply, receipt persistence, and idempotent rerun.
- [ ] 6.4 Test `apply_safe_changes` with mixed ordinary, frozen, manual, drifted, collision, and approval-required blocks and assert exact writes/withheld statuses.
- [ ] 6.5 Test partial calendar/Todoist outcomes, process restart, reconciliation-required state, and absence of blind resend.
- [ ] 6.6 Test Slack delivery/feedback and LLM suggestion flows through the composition root while asserting independent authority boundaries.
- [x] 6.7 Test `fully_automated` refusal end to end with zero Todoist and calendar mutations.

## 7. Operator and feature documentation

- [x] 7.1 Update README and quick start with planner overview, legacy compatibility, safety mode table, command examples, safe defaults, and links to every detailed guide.
- [x] 7.2 Complete `conf/todoist-planner.conf.example.yaml` and `docs/PLANNER_CONFIGURATION.md` for every supported key, allowed value, default, validation rule, environment-variable reference, state path, and cross-feature interaction.
- [x] 7.3 Complete `docs/SLACK_INTEGRATION.md` with webhook/chat setup, least permissions, destinations, kinds/schedules, durable delivery states, feedback actor authorization, explicit decision apply, testing, troubleshooting, disable, and rollback.
- [x] 7.4 Complete `docs/LLM_INTEGRATION.md` with provider/endpoint/model/host/secret controls, bounds, redaction, schemas/types, confirmation and no-mutation authority, testing, troubleshooting, audit, and disable instructions.
- [x] 7.5 Complete `docs/WEATHER_INTEGRATION.md` with endpoint/location/timezone/horizon/staleness/limits, suitability rules, fail-open/fail-closed behavior, deterministic explanations, tests, troubleshooting, and disable instructions.
- [x] 7.6 Rewrite `docs/PLANNER_END_TO_END_TESTING.md` as checkbox gates for isolated Todoist/calendar setup, representative data, backups, preview, refusal/exact approval, idempotency, safe-only application, optional-provider tests, observations, and rollback.
- [x] 7.7 Document the first production rollout exactly as crawl=`preview`, walk=`approval_required`, run=`apply_safe_changes`, with short horizons and acceptance gates; state that `fully_automated` is unavailable and must not be enabled.
- [ ] 7.8 Verify all documentation links, CLI commands, paths, option names, examples, safe defaults, and absence of production/personal secrets or stale “library-only” Phase limitations.

## 8. Verification and review readiness

- [x] 8.1 Run focused WireMock, orchestration, CLI, legacy regression, and configuration test classes and resolve every failure.
- [x] 8.2 Run `./gradlew :app:test --rerun-tasks`, verify JUnit XML/HTML contain individual test results, and inspect failures rather than relying on artifacts alone.
- [ ] 8.3 Run `./gradlew build` and `./gradlew installDist`; exercise installed-launcher help plus a hermetic preview smoke test and verify zero remote writes.
- [ ] 8.4 Review the full branch diff for authority-boundary regressions, credential leakage, unintended deadline mutation, undocumented configuration, and accidental inclusion of generated state/build output.
- [ ] 8.5 Validate the OpenSpec change, confirm every requirement has test/doc coverage, and prepare a detailed commit/PR description against Phase 6 without merging or approving it.