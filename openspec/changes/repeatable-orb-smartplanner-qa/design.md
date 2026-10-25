# Design

## Context

See `proposal.md` for scope and owner decisions. Existing assets include:

- `.qa.example/planner-google-qa.yaml`: a Google OAuth subset, not a complete planner profile.
- `.qa.example/calendar-fixture-inventory.yaml`: non-secret calendar roles and aliases.
- `scripts/recurrence-sync-contract.sh`: targeted hermetic Gradle tests; `--live` is intentionally refused.
- `docs/SMARTPLANNER_QA_RUNBOOK.md`, `docs/OPTIONAL_INTEGRATIONS_QA_SETUP.md`, and `docs/AI_SUBSCRIPTION_AUTH.md`: authorization gates, provider isolation, optional-provider checks, and device-login instructions.
- Installed-launcher tests, injected OAuth/HTTP tests, WireMock gateways, and daemon/conversation contracts already cover substantial behavior without live credentials.
- The October 1 and October 2 evidence packages report earlier live results and limitations; they must remain immutable.

The current secret presence check established only that a Todoist environment variable exists. It did not establish account identity or continued access to the previous campaign's Google resources. Slack credentials/channel/actor IDs and an entitled xAI model are not yet established. The Slack manifest generator is independently owned in thread T-01a17353-1e8d-7237-9ef6-6b56e3988367.

## Goals / Non-Goals

**Goals:** Make a fresh campaign reproducible from a clean checkout, separate offline checks from externally visible operations, and leave enough sanitized evidence to distinguish completed, failed, blocked, and untested cases.

**Non-Goals:** Change runtime scheduling/auth/provider contracts; test CalDAV or OpenAI subscription login; make real-account tests part of default Gradle; automate browser consent; bypass entitlement errors; or claim every gate is covered by live automation.

## Decisions

### One complete QA profile with safe initialization

Add a full Google-only template under `.qa.example/` rather than changing the production example's selected calendar provider or turning the existing bootstrap-only example into a misleading full config. Use the exact owner coordinates `40.7253341`, `-80.0984618` and `America/New_York`. Select xAI `grok_build_subscription` but keep AI, weather, messaging, daemon, recurrence, and calendar writeback disabled initially. Keep `planner.mode: preview`, explicit redaction/confirmation policies, and no approval/apply-safe feedback rule at initialization.

A small `scripts/smartplanner-qa.sh` entry point should initialize a new named `.qa/` workspace with restrictive permissions, expand only declared non-secret placeholders, and refuse existing destinations/symlink escapes. Keep configuration, four planner state directories, raw evidence, and the case ledger isolated per run. The xAI auth root must be an operator-selected absolute service-owned path outside the checkout and planner state, private to that campaign's refresh owner. Do not share personal Grok/Hermes caches or duplicate rotating credentials between runs.

Unfilled account/calendar/channel/actor/model inputs must be reported as unresolved rather than silently replaced. Templates must not invent an entitled model or treat project naming as proof of isolation. A structurally valid placeholder profile is not a successful live preflight.

### Default commands are offline; live stages are explicit

Provide help, initialization, local preflight, and a focused hermetic contract runner. Preflight validates config and intended safety toggles, paths/permissions/isolation, tools/installed launcher, and required environment-variable presence without printing secret values or constructing live gateways. Report structured safe outcomes where useful; do not dump config or environment.

Do not add a shell YAML parser or duplicate the application's full validation rules. Prefer the existing Groovy YAML/config loaders and installed-launcher test patterns when implementation needs structured inspection. Avoid adding a production CLI operation merely for a QA helper if a test/support entry point can use existing loaders.

Document explicit existing launcher commands for device login, local status, opt-in remote status, capacity/preview, and all four suggestion types. Device URL/code output goes only to the owner's interactive Terminal, never a captured log or committed evidence. After login and remote status pass, enable one integration at a time. Use supervised orb services (`amp orb service start`, or declared services when available) for daemon phases, not detached shells. Never have a general QA command silently log in, provision fixtures, post Slack messages, start a daemon, apply, or delete resources.

Use explicit UTC ranges derived from the current campaign, within Open-Meteo's horizon; record them once and reuse them for comparisons. Do not copy expired dates from earlier evidence. If multiple previews need a stable forecast, record a sanitized forecast fixture and distinguish replay proof from live forecast compatibility.

### Reuse tests and add regressions at public boundaries

The contract runner should select existing Google/Todoist gateways, xAI credential/OAuth/Responses, Slack surface/conversation/confirmation, weather evaluator/scheduler, recurrence, and writeback tests; use full Gradle separately for final verification. Reuse Spock and existing fixtures, not a new testing framework.

Add helper tests through its public CLI for initialization/no-overwrite, private paths, unresolved inputs, and prevention of live actions from default commands. Use asymmetric weather/confirmation inputs if extending existing integration tests: only the outdoor task should be constrained, and unmatched natural language must not replan or mutate until an authorized current-plan confirmation. Do not add one test per private helper or duplicate proven tests simply to increase coverage counts.

### A single campaign ledger links evidence to gates

Add a concise campaign replay section/file referenced from existing runbooks, with case IDs for baseline, independent weather, independent xAI, independent Slack, combined confirmation, scoped apply, writeback, recurrence/UI checks, restart/recovery, and cleanup. Mark CalDAV and OpenAI auth out of scope. Each case records status (not run/pass/fail/blocked), expected/actual result, command exit, revision/config hash, range/plan/correlation identities, and references to sanitized evidence. Manual UI observations remain manual, not inferred from API diffs.

Initialize the ledger without claiming any case passed. Reuse previous evidence conventions for before/after normalized provider inventories, receipts, state snapshots, cleanup totals, and checksums. Keep raw material ignored. Scan sanitized exports before publishing; hashing a secret-bearing file is not sanitization. Exclude tokens, device/consent material, account identifiers, exact private credential paths, and raw LLM streams from retained evidence. Do not provide a blanket exporter that copies private auth/config/state wholesale.

The owner-provided forecast coordinates are configuration inputs, not OAuth secrets. Keep credentials in Amp project secrets or protected stores; document project-secret refresh with `amp orb restart-processes` before launching the new supervised process. Warn that refresh restarts orb processes/services.

### Credential recovery recorded during setup

On 2026-10-25, the owner authorized reusing Google OAuth material from thread
T-01a0f73a-f885-7207-b9a2-ad0d46997c77. Only the desktop-client JSON and two canonical
credential files were transferred into ignored private local storage, not the full historical archive.
Both grants refreshed through the application's OAuth lifecycle and passed Google's token-info
account-subject, expected-account, granted-scope, and client-audience checks. This is refresh/reuse
proof, not a fresh browser-consent/bootstrap test, and performed no Calendar resource mutations.

The following Amp project secrets now hold single-line standard base64 of the complete JSON files:

| Secret | Restored local role |
| --- | --- |
| `SMARTPLANNER_GOOGLE_OAUTH_CLIENT_JSON_B64` | Desktop OAuth client JSON |
| `SMARTPLANNER_GOOGLE_NORMAL_TOKEN_JSON_B64` | Normal event-only credential store |
| `SMARTPLANNER_GOOGLE_QA_TOKEN_JSON_B64` | Separate QA calendar-management credential store |

Base64 is encoding, not encryption; these must remain secrets. Future setup must decode privately,
validate identities/scopes, refuse accidental overwrite of a newer local credential, and preserve
normal/QA separation. Save refreshed local records back explicitly after stopping their refresh owner;
never assume the original environment snapshot updates when a local token rotates. Avoid restarting
orb processes while a device authorization is pending. xAI device authorization is still pending and
no xAI credential secret has been created at this setup checkpoint.

**Later setup checkpoint (2026-10-25):** xAI device login succeeded and its complete protected JSON
record is saved as `SMARTPLANNER_XAI_SUBSCRIPTION_CREDENTIAL_JSON_B64`. The public catalog returned
HTTP 200 with a `data` list; a separately committed parser regression fix makes opt-in remote status
report `ready`/entitlement available. Catalog model visibility does not prove Responses compatibility.
Both Google grants refreshed again through the existing service, and remote token-info checks matched
account subject/email, client audience and required scopes. The QA grant listed four calendars, including
the two candidate QA names; the normal event-only grant read events successfully on both candidates.
Their sampled event lists were empty, not proof of calendar ownership. Updated canonical Google
records were saved back to Project Secrets. Open-Meteo returned 72 aligned hourly samples in the
selected timezone, and the Todoist project read succeeded. No Calendar/Todoist resources were changed.
The orb environment was refreshed with no QA services running. Initial Slack history access required
`channels:history`, which the private-only installed manifest lacked. The owner updated and reinstalled
the existing app, preserving its identity/branding and adding public-channel scope/event support while
retaining private support. Bot authentication, history access on the exact QA channel, and app-token
Socket Mode authentication then all passed with the existing saved tokens; no messages were posted.
The app-manifest dashboard URL is a non-secret project environment variable
`SMARTPLANNER_SLACK_APP_MANIFEST_URL`, not tracked configuration.

The private run workspace passes offline preflight, with canonical Google records moved rather than
copied and `grok-4.3` selected from the observed catalog as an unproven inference candidate. The
replay guide defines the disposable fixture scope; model inference, Slack feedback, native UI and
exact-plan mutation approvals remain distinct campaign gates. The owner requested that the prepared
branch be committed/pushed and execution start in a new **medium-sized orb**. That execution orb
becomes the sole credential-refresh owner; this source orb must not refresh or start its QA daemon
after handoff.

Preparation verification: the contract helper passed 848 tests in 49 suites; full `:app:build
:app:installDist` passed 1,121 tests in 70 suites, with zero failures/errors/skips. The installed
manifest generator emitted both public/private scope-event pairs without configuration or credentials.
Helper regression tests use fake credentials and isolated homes, not real providers. Existing
weather/confirmation/suggestion tests already exercise the asymmetric isolation and authorization
contracts, so no duplicate tests were added for those boundaries. Historical evidence and the partial
Google bootstrap example remain byte-identical. Live readiness checks above are separate from these
hermetic results and do not complete any live campaign ledger row.

### Authorization and cleanup stay resource-scoped

Document the approved account/project/calendar/channel inventory before remote actions. Separate authorization for bounded xAI subscription requests, Slack test messages, fixture creation, scoped Todoist/Calendar writes, and cleanup. Preview is not a global no-write switch: `apply-safe`, recurrence lifecycle, and newly enabled writeback require their own gates. Unknown outcomes stop dependent actions until provider reconciliation, not blind retry.

Cleanup uses only resources created and recorded by this campaign, with before/after inventories and zero-leftover assertions. Do not introduce a generic recursive remote cleanup script. xAI logout removes the local credential; account-side revocation remains a manual step where available.

## Risks / Trade-offs

- [xAI tier/protocol incompatibility] → Treat login/status/inference as separate live gates, report actual failures, and never substitute API-key billing or another provider.
- [Accidental activity against personal data] → Account/resource verification and explicit stage approval precede network/write stages; token presence alone cannot pass identity checks.
- [Parallel Slack generator branch] → Reference its documented installed command only after it is available; do not invent an interface or edit files owned by that thread.
- [False completeness from existing tests] → Preserve live/manual/fixture distinctions and unchecked cases; earlier evidence does not prove the current revision.
- [Overbuilt QA framework] → Keep one helper entry point, existing Gradle contracts, simple templates/ledger, and existing runbooks. Add automation only where it can assert a real boundary safely.

## Migration Plan

Existing production configuration and evidence stay unchanged. Add tooling and validate it entirely with temporary fake homes/resources first. Prepare an ignored campaign only after those checks; authenticate while AI remains disabled; then progress through approved live gates. Rollback stops the supervised daemon and disables optional integrations, recurrence, and writeback while preserving state for reconciliation. Logout/cleanup follow the recorded owned-resource inventory rather than deleting shared state.

## Open Questions

- Which entitled xAI model does the disposable QA subscription expose? Record the result from the separately authorized remote-status/inference gate rather than guessing.
- What are the dedicated account/calendar IDs, Slack channel/member IDs, and approved disposable fixture inventory? These are run inputs, not architectural decisions.
