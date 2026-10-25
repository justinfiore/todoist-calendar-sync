# Tasks

## 1. Complete isolated QA profile and initialization

- [x] 1.1 Add a full secret-free `.qa.example/` Google-only planner profile using owner coordinates 40.7253341, -80.0984618 and America/New_York, xAI subscription selection, external auth-root placeholder, explicit safety policies, and disabled integrations/recurrence/writeback; verify rendered non-secret inputs load through existing configuration parsers without a CalDAV section or API-key fields.
- [x] 1.2 Add `scripts/smartplanner-qa.sh` help and initialization for new private ignored run workspaces and a not-run case ledger; verify public-command tests cover no-overwrite, unsafe/symlink destinations, private permissions, isolated state, exact coordinates, unresolved run inputs, and zero provider/auth activity.
- [x] 1.3 Document initialization and owner decisions in a replay guide linked from the existing QA runbooks; verify commands against helper help and preserve the bootstrap-only Google example and previous evidence packages unchanged.

## 2. Local preflight and repeatable hermetic checks

- [x] 2.1 Implement local preflight using existing configuration loaders rather than shell YAML parsing, checking tools/launcher, safety controls, unresolved non-secret inputs, selected provider, auth/state isolation, and credential-variable presence without values; verify tests reject unsafe settings and missing inputs without networking or leaking sentinel secrets.
- [x] 2.2 Add a contract-runner mode selecting existing Google/Todoist, weather, Slack/conversation/confirmation, xAI auth/Responses/installed launcher, recurrence, and writeback tests; verify the selected suites execute without real credentials and report their real counts, exits, and failures.
- [x] 2.3 Extend existing public-boundary tests only where a concrete gap is found in weather-label isolation, xAI suggestion safety, or current-plan authorized confirmation; verify plausible wrong behavior fails using asymmetric cases, and document which contracts are automated versus live-only.
- [x] 2.4 Document the distinction between initialization success, unresolved/failed preflight, hermetic passes, and live readiness; verify examples with temporary fake homes and no credentials, including an invocation from outside the checkout.

## 3. Live replay instructions and evidence templates

- [x] 3.1 Document xAI device login in the orb's interactive Terminal while AI remains disabled, local then opt-in remote status, entitled-model selection, four suggestion schemas, refresh/restart/cancellation, and logout limitations; verify every command against installed help without performing live auth or provider calls.
- [x] 3.2 Add ledger cases and evidence templates for current baseline, weather, xAI, Slack, combined confirmed feedback, scoped apply, writeback, recurrence/native UI limits, restart/recovery, and owned-resource cleanup; verify new ledgers claim no pass and explicitly exclude CalDAV/OpenAI authentication.
- [x] 3.3 Document stage-specific approvals, current UTC ranges within the weather horizon, provider/state before-after comparisons, ambiguous-result stop conditions, and zero-leftover cleanup; verify every write-capable path including apply-safe, recurrence, and writeback has an explicit gate.
- [x] 3.4 Document supervised orb daemon startup, project-secret refresh/restart effects, and secure evidence handling; verify instructions never capture device codes, consent output, raw prompts/responses, credentials, or private identity/config dumps and that sanitized evidence has a scan/checksum procedure.
- [x] 3.5 Incorporate the separately delivered Slack manifest generator's actual installed command into replay instructions once available; verify that command emits the private-channel permissions without credentials and keep generator implementation in its owning branch.

## 4. Combined verification and handoff

- [x] 4.1 Run helper/targeted contracts, full Gradle tests, build, and installed-distribution checks with bounded orb resources; record real counts/failures and confirm no real credentials or live providers were used.
- [x] 4.2 Run strict OpenSpec validation, shell syntax checks, diff/secret checks, and documentation command/link checks; verify earlier evidence remains byte-identical and report the exact local delivery state plus outstanding live inputs/authorizations.

Live execution and publication of a campaign evidence package are follow-on authorization-gated work, not a prerequisite to marking the reusable tooling implemented. Do not check old live-QA tasks complete based on this tooling alone.
