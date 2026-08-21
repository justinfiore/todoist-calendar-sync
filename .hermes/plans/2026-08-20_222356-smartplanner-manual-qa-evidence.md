# SmartPlanner Phase 7 Manual QA and Evidence Plan

## Remaining Justin setup/access checklist (current)

### Nothing further is needed for Todoist and Google QA at this time

- [x] **Todoist QA credential:** `TODOIST_ACCESS_TOKEN` is securely staged in the ignored local `creds.txt`; I will create the disposable Todoist project, labels, tasks, and fixtures after the Google gateway implementation is approved.
- [x] **Google OAuth material:** the existing TodoistCalDavSync desktop OAuth client JSON plus legacy credential-store artifacts are securely staged in ignored local `.qa/secrets/` paths. No Bitwarden access, Google normal password, Google app password, or manual token copy is needed. The legacy credential is not accepted as normal planner auth because it may include broad calendar-management scope.
- [ ] **Google QA boundary confirmation and consent:** when implementation is complete, confirm that the account is the dedicated disposable agent-owned Google account and authorize a fresh normal event-only OAuth bootstrap. Separately authorize either validated import of the legacy credential into the QA-only token store or the explicit QA bootstrap before QA calendar provisioning. I will create all QA calendars and events.

### Still required only for optional Phase F Slack Socket Mode QA

- [ ] Provide a private QA Slack channel ID, the approved QA-user Slack ID(s), and the test SmartPlanner app's bot (`xoxb-`) and Socket Mode (`xapp-`) tokens through a secure local mechanism. If workspace policy prevents me from creating/installing the app or channel, create/install the test app from `conf/smartplanner-slack-app-manifest.example.yaml` and invite it to that private channel.

### Approval gates that remain intentionally manual

- [ ] Review and approve the implemented `add-google-calendar-gateway` change before I use any live Google API credential.
- [ ] Review the Phase C read-only evidence package before I enable any write-capable mode.
- [ ] Approve each separately scoped Phase E write/rollback case and any Phase F Slack approval case.

> **For Hermes:** Once Justin securely provides access to dedicated QA Todoist and Google accounts, provision the entire disposable provider fixture inventory in this plan. Preserve `preview` as the initial mode; do not use Justin’s personal Todoist projects or personal/work calendars. Do not begin any live provider call until the credential boundary and target accounts have been positively identified.

**Goal:** Produce repeatable, account-isolated proof that Phase 7 SmartPlanner behaves correctly with real Todoist, Google Calendar/CalDAV, and Slack Socket Mode integrations before any production write is attempted.

**Architecture:** QA proceeds through three trust levels: hermetic automated regression tests; real-provider tests against dedicated disposable Todoist/Google Calendar/Slack resources; and an optional, narrow production crawl/walk/run. A test runner records command output, redacted logs, exported before/after provider state, and screenshots/video. A generated static HTML report links every assertion to its evidence and clearly distinguishes "automated proof," "live isolated proof," "manual observation," and "not yet tested."

**Tech Stack:** Gradle/Spock/WireMock; installed `todoist-caldav-sync` distribution; Todoist REST API v1 for QA-resource inventory/provisioning and snapshots; a new Google Calendar API gateway with renewable OAuth 2.0 credentials for Google calendars; existing CalDAV adapter retained for non-Google providers; isolated Slack test channel and SmartPlanner test app; Playwright/browser capture (or an equivalent local browser recorder); static HTML/CSS/JavaScript; filesystem JSON state/receipts.

---

## Safety contract (non-negotiable)

- [ ] No QA configuration may reference Justin’s personal Todoist account, existing personal/work calendars, or production Slack channel.
- [ ] The CalDAV `output_calendar` must be a newly created disposable calendar named `SmartPlanner QA Output`; any other test calendars are input/blocker calendars only.
- [ ] Start with `planner.mode: preview`, with `daemon.enabled`, Slack, AI, and weather disabled. Preview must demonstrate zero Todoist/CalDAV writes before any write-capable test is enabled.
- [ ] Use separate, repo-local QA config and state under `.qa/` (gitignored); never put tokens/passwords in YAML, source, screenshots, HTML, or committed artifacts. Environment-variable names only.
- [ ] Take a provider export/screenshot and archive a copy of all four planner state directories before each write test. Never delete a receipt/state file to force a retry after an ambiguous outcome.
- [ ] Each write test must have a precise expected mutation, a before/after diff, and a cleanup/rollback step. Any unexplained mutation stops the test campaign and returns configuration to `preview`.
- [ ] `fully_automated` remains explicitly out of scope because the implementation is designed to refuse it with zero writes.
- [ ] Credential use is limited to a dedicated QA Todoist account and dedicated QA Google account. API tokens, OAuth refresh tokens, app passwords, and authorization headers must be redacted before any evidence is written.
- [ ] Until the `add-google-calendar-gateway` change is implemented and independently verified, Phase 7 must not connect to Google Calendar. The existing static CalDAV Basic/Bearer path is not an approved Google QA route.

## External API references

- Todoist API v1: <https://developer.todoist.com/api/v1/>. Use this as the authoritative source for dedicated Todoist QA project, label, task, and snapshot operations.
- Google Calendar API overview: <https://developers.google.com/workspace/calendar/api/guides/overview>. This is useful background and may be used by an external QA-provisioning helper only if separately implemented; it is **not** the Phase 7 planner authentication contract.

## Superseded Phase 7 Google Calendar authentication finding and approved direction

Repository inspection establishes the following current limitation for `feat/planner-main-integration`:

- `ProductionPlannerOrchestrator` composes `CalDavHttpGateway`, not the Google Calendar REST API client.
- `ProductionIntegrationConfig` requires every configured calendar to have an HTTPS collection URL and accepts only `auth.type: basic` (`username` + `password_env`) or `auth.type: bearer`/`oauth2` (`token_env`).
- `CalDavHttpGateway` sends either HTTP Basic authentication or a static `Authorization: Bearer …` header. It has no Google OAuth authorization-code flow, no refresh-token handling, and no Calendar API client-secret consumption.
- The older `GoogleAuthProvider`/`client_secret.json` code is for the legacy sync path, not the Phase 7 planner composition. It must not be used as evidence that SmartPlanner can refresh Google OAuth credentials.

**Approved decision:** implement `add-google-calendar-gateway` before live Google QA. The new gateway will use Google Calendar API OAuth 2.0 with durable refresh-token support, create/list the disposable QA calendars through the Google API, and satisfy the existing calendar read/write ports. The existing CalDAV adapter remains available for non-Google CalDAV providers. No Google app password, static OAuth access token, or legacy `GoogleAuthProvider` setup is needed for the SmartPlanner Google path.

## Current implementation baseline to preserve

The live branch is `feat/planner-main-integration` at `09ad541` (`test(planner): verify oversized write barriers end to end`). Existing repository material already defines much of the contract:

- `docs/PLANNER_END_TO_END_TESTING.md` defines isolated Todoist/calendar gates, Slack Socket Mode checks, and crawl/walk/run production rollout.
- `docs/SLACK_INTEGRATION.md` defines Socket Mode setup, command/thread behavior, durable event recovery, and working-status evidence.
- `conf/todoist-planner.conf.example.yaml` provides safe preview defaults, four distinct state stores, mode controls, and daemon/Slack settings.
- `conf/smartplanner-slack-app-manifest.example.yaml` creates the default SmartPlanner test Slack app.
- The supplied Todoist API v1 documentation governs Todoist QA provisioning/snapshot calls. Google Calendar API documentation and the `add-google-calendar-gateway` OpenSpec change will govern the Google execution/authentication path once implemented.
- Existing test coverage includes WireMock provider boundaries and `SmartPlannerDaemonSpec`; it is necessary but not sufficient proof of actual Todoist/Google/Slack credentials, permissions, server semantics, or browser-visible Slack behavior.

## Artifacts to create during execution

All live-test material stays ignored from Git unless Justin explicitly elects to publish a fully redacted sample report.

| Artifact | Proposed path | Purpose / acceptance content |
| --- | --- | --- |
| QA runbook | `docs/SMARTPLANNER_QA_RUNBOOK.md` | Operator-neutral, checkbox-based instructions derived from this plan; includes safe setup, command lines, expected results, stop conditions, cleanup, and rollback. |
| Test config template | `.qa/smartplanner-qa.yaml.example` | Safe isolated resource names and env-var references; no values/secrets. |
| Local secrets/config | `.qa/smartplanner-qa.yaml`, `.qa/qa.env` | Ignored; mode starts as `preview`; contains test-account references only. |
| Fixture inventory | `.qa/fixtures/README.md` | Exact test tasks/events/Slack messages to create, expected IDs redacted or aliased. |
| Per-case evidence | `.qa/runs/<run-id>/<case-id>/` | Command transcript, sanitized logs, planner receipts/state snapshots, exports/normalized JSON, screenshots, optional video, and `result.json`. |
| Provider-state snapshots | `.qa/runs/<run-id>/snapshots/{before,after}/` | Todoist task/project export, Google Calendar/CalDAV event export, and Slack message/thread transcript for exact before/after comparison. |
| Evidence manifest | `.qa/runs/<run-id>/manifest.json` | SHA-256 hashes, timestamps, test-case ID, configuration fingerprint with secrets removed, verdict, evidence paths, and cleanup status. |
| Human-readable proof report | `.qa/reports/<run-id>/index.html` | Static HTML evidence page: test matrix, assertion/result, explanation, thumbnails linking full-sized images/video, redacted snippets, state/provider diffs, and known limitations. |
| HTML report assets | `.qa/reports/<run-id>/assets/` | Copied CSS/JS/images/video; report remains portable when its full directory is copied. |
| Final redacted bundle | `docs/qa-evidence/<run-id>/` or an external private share | Only after Justin reviews it; contains no credential, personal task, calendar, Slack user, or sensitive content. |

## Evidence standards

Every test case is assigned a stable identifier, e.g. `LIVE-TOD-01`.

- **Machine assertion:** saved command exit code plus a narrowly scoped script/assertion over real outputs, receipts, or exported state.
- **Provider proof:** before/after normalized diff showing only the intended fields/resources changed (or proving no change).
- **Visual proof:** at least one screenshot of the native Todoist, Google Calendar, or Slack UI when the behavior is user-visible.
- **Narrative proof:** the report explains what was attempted, expected, observed, why it proves the requirement, and any residual ambiguity.
- **Reproducibility:** evidence manifest records branch commit, distribution build identity, test config fingerprint (with secrets removed), test data aliases, and a UTC timestamp.
- **Evidence integrity:** hashes are calculated for captured files; the report must label captured artifacts rather than presenting generated text as a screenshot.

## Test matrix and execution order

### Phase A — Establish a repeatable hermetic baseline

**Objective:** Confirm the branch has not regressed before real credentials are introduced.

1. [ ] Record clean worktree, exact HEAD, Java/Gradle versions, and installed-distribution version/help output.
2. [ ] Run targeted suites: `SmartPlannerDaemonSpec`, `ProductionHttpGatewaysWireMockSpec`, `ExternalProviderWireMockSpec`, `OpenAiWireMockBoundarySpec`, Slack gateway tests, and relevant plan-application tests.
3. [ ] Run `./gradlew :app:test --rerun-tasks`, `./gradlew build`, and `./gradlew installDist`.
4. [ ] Parse JUnit XML rather than relying only on Gradle’s exit status; record suite/test/failure/error/skipped totals and preserve the full Gradle report tree.
5. [ ] Run an installed-launcher help/config-validation smoke check that does not construct live providers.
6. [ ] Add the results as `AUTO-*` rows in the HTML report, clearly labelled as fixture/WireMock proof only.

**Gate:** all automated checks pass; no live provider host is contacted; no QA account exists in any test output.

### Phase B — Provision and validate disposable resources

**Objective:** Prove the test boundary is isolated before SmartPlanner is allowed to read anything.

1. [ ] Positively identify the dedicated Todoist account and confirm the dedicated Google account’s email/username from securely staged nonsecret configuration; record only redacted aliases/fingerprints. Stop if either account appears to contain non-QA data or cannot be unambiguously identified.
2. [ ] Confirm the `add-google-calendar-gateway` implementation has passed its hermetic OAuth, token-refresh, Calendar API pagination, calendar-provisioning, event read/write, ownership, and no-cross-calendar-write tests before staging any Google credential.
3. [ ] Using the new gateway’s documented OAuth validation/bootstrap, authenticate only to the dedicated Google QA account using the already staged ignored local OAuth client/credential material; record only aliases/fingerprints in `.qa/` artifacts.
4. [ ] Create and document aliases for the `SmartPlanner QA` Todoist project, planner labels, the `SmartPlanner QA Output` and `SmartPlanner QA Blockers` Google calendars, any input/availability calendar required by the test matrix, Slack test app/channel, and authorized test Slack user.
5. [ ] Create the additional QA calendars through the Google Calendar API gateway, persist their returned calendar IDs only in ignored `.qa/` configuration, and configure explicit provider routing so only `SmartPlanner QA Output` is writable.
6. [ ] Seed all disposable Todoist and Google fixture data listed below, assigning each created resource a stable QA alias and preserving its returned provider ID only in ignored local state.
7. [ ] Verify that the QA config resolves to those aliases only. Add a preflight guard that refuses execution unless `output_calendar == SmartPlanner QA Output`, destination is the QA Slack channel ID, and account identifiers match the approved test inventory.
8. [ ] Capture initial exports and browser screenshots. Review them with Justin before starting tests that could write.
9. [ ] Run configuration validation and read-only Todoist/CalDAV probes. Capture the result and provider-facing credential/permission errors, if any, with secrets redacted.

**Gate:** preflight refuses production-like resources; initial snapshots are complete; configuration validates; Todoist and CalDAV probes succeed without write requests.

### Phase C — Real provider read-only behavior (`preview`)

**Objective:** Validate the most important safe path with live APIs and real calendar semantics.

| Case | Live procedure | Required proof |
| --- | --- | --- |
| `LIVE-PRE-01` | Run `capacity` over an explicit, narrow UTC interval. | Task/event counts and calendar names match the prepared inventory; Google Calendar screenshot and normalized CalDAV output agree; no Todoist/CalDAV diff. |
| `LIVE-PRE-02` | Run `preview` with the same interval. | Persisted plan has explainable slots, scheduled blocks, deadline risks, and unscheduled reasons; all four state dirs have expected local-only artifacts; provider diff is empty. |
| `LIVE-PRE-03` | Run the identical preview again. | Stable/reused baseline or explainable deterministic result; no provider diff; screenshot/report shows plan comparison. |
| `LIVE-PRE-04` | Introduce an unknown/read-only calendar blocker and repeat preview. | It is visible/classified under configured fallback; it is never treated as writable free time. |
| `LIVE-PRE-05` | Check Todoist due and deadline fields before/after. | Both are byte-for-byte unchanged in preview. |

**Gate:** every provider diff is empty and the generated plan agrees with the test inventory. This is the first artifact package Justin reviews.

### Phase D — Real provider negative write barriers

**Objective:** Prove SmartPlanner refuses unsafe requests before permitting the smallest intended write.

1. [ ] `LIVE-GATE-01`: switch only to `approval_required`, create a fresh preview, attempt apply with no approval.
2. [ ] `LIVE-GATE-02`: attempt apply with a tampered/wrong-hash approval.
3. [ ] `LIVE-GATE-03`: attempt a stale approval after a changed/revised plan.
4. [ ] `LIVE-GATE-04`: use a `fully_automated` config and prove both apply paths refuse with zero provider writes.
5. [ ] `LIVE-GATE-05`: point one controlled test at a non-owned/wrong-calendar resource and verify collision/delete refusal.

**Required proof for every case:** refused receipt with reason; zero Todoist and CalDAV provider diff; no unmanaged Google event; no deadline change; screenshots of the relevant native UI state.

**Gate:** all refusal cases are demonstrably no-write. A single unexpected provider mutation stops the campaign.

### Phase E — Minimal real write, idempotency, and rollback

**Objective:** Validate exactly one expected, reversible approved change in disposable accounts.

1. [ ] Export/snapshot all test resources and state together immediately before the test.
2. [ ] `LIVE-WRITE-01`: use an exact approval for one small plan containing one ordinary eligible task/block.
3. [ ] Verify Todoist changes only the due datetime (not deadline) and Google creates only the planner-owned event in `SmartPlanner QA Output` with expected UID/ownership metadata.
4. [ ] `LIVE-WRITE-02`: rerun the same application; prove idempotent/no-op behavior—no duplicate Todoist update/event and no blind resend.
5. [ ] `LIVE-WRITE-03`: create a mixed plan containing ordinary and protected/manual/frozen/approval-required items; run `apply-safe`; prove only ordinary change(s) are applied and withheld items are recorded.
6. [ ] `LIVE-WRITE-04`: perform the documented rollback from captured state/provider export. Prove the dedicated task/calendar returns to its pre-test state and document any provider operation that must be restored manually.

**Gate:** every actual mutation matches the receipt and normalized diff; no deadline mutation occurs; rerun is idempotent; rollback has been exercised, not merely described.

### Phase F — Slack Socket Mode live integration (still isolated Todoist/Google)

**Objective:** Prove browser/user-visible messaging workflow without exposing an inbound port or using a production Slack channel.

1. [ ] Start `planner-daemon` only after `preview`/provider-probe gates pass. Capture process/network evidence that it uses outbound Socket Mode and does not listen on a public inbound port.
2. [ ] `LIVE-SLACK-01`: invoke `/smartplanner plan daily` and `/smartplanner plan weekly`; capture channel-root proposals and verify their configured horizons.
3. [ ] `LIVE-SLACK-02`: issue `status` and `help`; screenshot response and status information.
4. [ ] `LIVE-SLACK-03`: use a configured thread `replan` response; capture Slack working status, revised iteration in the same thread, receipt correlation, and zero provider write in preview mode.
5. [ ] `LIVE-SLACK-04`: test acknowledgement/rejection; prove zero Todoist/Calendar writes.
6. [ ] `LIVE-SLACK-05`: test exact authorized approval only after Phase E is passing; prove one corresponding expected apply and no second apply after duplicate event/redelivery.
7. [ ] `LIVE-SLACK-06`: post bot, unauthorized-user, root-message, unrelated-channel, duplicate, stale, and unknown-thread inputs; prove no writes and correct safe response/ignore behavior.
8. [ ] `LIVE-SLACK-07`: restart daemon between proposal and reply; prove durable thread-to-plan correlation still works.
9. [ ] `LIVE-SLACK-08`: simulate a contained cycle/provider failure using a controlled unavailable test endpoint; prove process remains alive, exposes bounded backoff, and resumes on restored endpoint; then exercise graceful SIGTERM shutdown.

**Evidence:** browser screenshots plus a short screen recording for command → working-status → proposal → threaded replan; redacted daemon logs; Slack thread export; receipts/state; before/after provider snapshots.

**Gate:** all accepted messages are correlated to the intended test thread; all unauthorized/replayed cases fail closed; no token appears in any artifact.

### Phase G — Report review and optional production rollout

1. [ ] Generate and validate the static HTML report locally. Ensure links work when the entire report directory is copied, thumbnail assets exist, and every matrix row links to its evidence.
2. [ ] Provide Justin the report bundle and concise exceptions list. Justin reviews the isolated-account result before authorizing production crawl.
3. [ ] If authorized, run production **Crawl** only: one 24-hour then three-day `preview`, Slack/AI/weather disabled, read-only credentials when practical, explicit backups, zero write diffs.
4. [ ] After Justin approves crawl evidence, run **Walk**: `approval_required`, a single exact small plan, 24-hour observation, and a rehearsed restore.
5. [ ] After Justin approves walk evidence, run **Run**: `apply_safe_changes` with a short horizon, daily receipt review, then gradually expand horizons after several clean runs.

**Production stop condition:** unexpected mutation, ambiguous provider write, authorization/configuration mismatch, unexplained plan churn, missed deadline invariance, or incomplete evidence triggers immediate stop, state/log preservation, return to `preview`, and diagnosis. No auto-retry to make an ambiguous write "go away."

## What Justin needs to do manually

### One-time access setup

- [ ] **Todoist:** create or designate a standalone disposable QA Todoist account for SmartPlanner (not linked to Justin’s main account) and securely stage its API token on this host—not in Slack or a repository file. I will create the `SmartPlanner QA` project, labels, tasks, and all other test fixtures.
- [ ] **Google Calendar — dedicated boundary:** confirm that the already staged legacy OAuth credential represents the dedicated agent-owned Google account containing no personal/work calendars. I will reuse its existing TodoistCalDavSync desktop OAuth client/material, validate or import the OAuth state through the implemented gateway, and create every QA calendar/event fixture after implementation review/approval.
- [ ] **Google Calendar — confirm the boundary:** tell me the account is approved as the disposable Google boundary. I will create `SmartPlanner QA Output`, `SmartPlanner QA Blockers`, and every other QA calendar/event fixture using the new OAuth-authenticated Google Calendar API gateway, retaining IDs only in ignored `.qa/` configuration.
- [ ] **Slack (only for Phase F):** securely stage the test-app bot and Socket Mode tokens and provide the QA channel ID plus authorized QA Slack user ID(s). I will configure the local integration and create the test messages. If workspace permissions require Justin to create/install the app or channel, that is the only remaining manual Slack setup; Phases A–E do not depend on it.
- [ ] **Secure secrets:** Todoist and Google material are already staged locally. Only `SLACK_BOT_TOKEN` and `SLACK_APP_TOKEN` remain to be securely staged when Slack testing begins. Do not paste secret values into Slack, plans, issue comments, commits, screenshots, or video.
- [ ] **Boundary authorization:** confirm that the supplied Todoist and Google accounts are the approved disposable boundary. I will derive and store resource IDs/URLs only in ignored `.qa/` configuration after preflight confirms that boundary.

### During execution

- [ ] Review the initial resource screenshots/exports and approve the isolated test boundary.
- [ ] Review the Phase C preview report before I change the mode from `preview`.
- [ ] Approve each separately scoped write gate (Phase E and Slack approval test) after reading its expected mutation and rollback description.
- [ ] Review the final isolated-environment evidence report and explicitly authorize, defer, or reject production Crawl. Production Walk and Run each require separate approval.

## Files likely to change when the plan is executed

- Create: `docs/SMARTPLANNER_QA_RUNBOOK.md`
- Create: `.qa/smartplanner-qa.yaml.example`
- Create/modify: `.gitignore` (to exclude `.qa/` secrets, live captures, state, and exports while allowing safe templates)
- Create: QA capture/normalization/report-generator scripts under `scripts/qa/` (exact language chosen after environment inspection)
- Create: `.qa/fixtures/README.md`
- Create: `.qa/runs/` and `.qa/reports/` locally, ignored
- Modify: `docs/PLANNER_END_TO_END_TESTING.md` only if execution reveals a real gap or ambiguity; preserve its safety requirements.
- Modify: tests under `app/src/test/groovy/todoistcaldavsync/planner/` only if live QA identifies a reproducible product defect; add a regression test first, then fix under normal review gates.

## Verification of the QA system itself

- [ ] Validate that the capture helper redacts tokens (`xoxb-`, `xapp-`, Todoist bearer values, CalDAV credentials, query secrets) before files enter the report tree.
- [ ] Deliberately run one known failing assertion against copied/sanitized sample data to prove the report marks failure and blocks an overall pass.
- [ ] Verify evidence hashes and all HTML references in a copied report directory.
- [ ] Inspect the report in a browser at desktop and mobile width; confirm screenshots, video controls, tables, and explanations render without external/private dependencies.
- [ ] Run report generation twice on the same captured inputs; compare the evidence manifest to prove deterministic ordering and detect missing artifacts.

## Risks, decisions, and open questions

1. **Google Calendar test access:** Current Phase 7 CalDAV authentication is insufficient for durable Google OAuth. Live Google QA is blocked until the reviewed `add-google-calendar-gateway` change provides renewable OAuth and API-based calendar provisioning.
2. **Todoist test tenancy:** the supplied dedicated Todoist account is the required safety boundary. I will create the project, labels, and every task fixture after preflight; a separate project in Justin’s main account is not an equivalent boundary.
3. **Slack workspace permissions:** app creation/install and Socket Mode may require a Slack administrator. If blocked, Phase F waits; Phases A–E can still prove Todoist/Calendar functionality.
4. **Video capture:** browser automation can capture screenshots reliably. A short screen recording should be included for the Slack interaction if the installed browser/host supports it; otherwise the report will transparently use sequenced screenshots plus timestamped logs rather than claiming a video exists.
5. **Provider export APIs:** API/CalDAV snapshots will be normalized to remove credentials and volatile timestamps. Native UI screenshots complement, but do not replace, machine-comparable state exports.

## Completion definition

QA is complete for the isolated environment only when:

- all Phase A automated checks are recorded as passing;
- all Phase C read-only cases prove zero remote writes;
- all Phase D refusal barriers prove zero remote writes;
- Phase E proves one exact approved write, safe-only behavior, idempotent rerun, deadline invariance, and rehearsed rollback;
- Phase F proves Socket Mode command/thread/restart/replay behavior and status visibility, if Slack setup is available;
- the report is portable, redacted, hash-manifested, and reviewed by Justin;
- any failures are documented as failures with preserved evidence and associated regression issue/test—not silently omitted.
