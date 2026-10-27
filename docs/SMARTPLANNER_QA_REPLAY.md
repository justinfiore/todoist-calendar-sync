# SmartPlanner isolated QA replay

This is an operator checklist, **not a record of a completed live campaign or authorization to
write**. Use the companion [Google runbook](SMARTPLANNER_QA_RUNBOOK.md),
[optional-integration setup](OPTIONAL_INTEGRATIONS_QA_SETUP.md), and
[subscription authentication](AI_SUBSCRIPTION_AUTH.md) for underlying contracts. This replay selects:

- **Google Calendar API only** (`google_calendar_api`); no CalDAV QA or provider fallback.
- Open-Meteo at **40.7253341, -80.0984618**, **America/New_York**.
- The owner-designated Slack QA channel, app support for **both public and private channels**, and
  outbound **Socket Mode** only. The owner reinstalled the existing app with both history scopes and
  matching message events after the initial private-only manifest failed the channel readiness gate.
- **xAI subscription device OAuth** (`grok_build_subscription`, CLI provider `grok`), no OpenAI,
  API-key, vendor-cache, CLI-proxy, or cross-provider fallback.

Existing Google grants can be restored from Project Secrets. During setup on 2026-10-25, xAI device
login and the fixed remote catalog-status check succeeded; all four schemas, refresh/restart, and the
combined campaign remain unproven. The observed xAI HTTP 200 model catalog has a `data` list. Build
the reviewed catalog-parser fix before remote status; a parsing failure is not grounds to re-login or
switch providers. Historical setup results do not pass a fresh campaign's ledger.

## 1. Build, scope, and offline workspace

Do not read an existing private `.qa` directory as documentation input. For execution, only the
authorized operator accesses the new run's private files. Stop all other processes using the same
grants before restoring or refreshing them. Do not use shell tracing, environment dumps, or raw
credential/log dumps.

```bash
./gradlew :app:test
./gradlew installDist
BIN="$PWD/app/build/install/todoist-caldav-sync/bin/todoist-caldav-sync"
"$BIN" --help
scripts/smartplanner-qa.sh help
scripts/smartplanner-qa.sh contracts
```

Default Gradle tests are hermetic (fixtures, injected transports, localhost mocks), never a live
provider campaign. Record the reviewed commit, tool versions, test XML result counts and distribution
checksums. Do not infer a pass here from a prior run. PR #19's private Slack manifest generator is
merged upstream, but a local branch/installed distribution may not contain it until integrated/rebased
and rebuilt; the installed help/source must expose its options before using it below.

The helper interface below requires this revision, not an older installed checkout.
If the script or complete `.qa.example/planner-orb-qa.yaml` is missing, mark
workspace setup `blocked`; do not substitute a minimal, incomplete YAML.

```bash
RUN_ID=qa-YYYYMMDD-unique
AUTH_ROOT=/absolute/service-owned/path/outside-repo/qa-ai-auth
scripts/smartplanner-qa.sh init "$RUN_ID" --auth-root "$AUTH_ROOT" \
  --account DISPOSABLE_EMAIL --slack-channel QA_CHANNEL_ID \
  --slack-user ALLOWED_USER_ID --model OWNER_VALIDATED_XAI_MODEL
RUN="$PWD/.qa/runs/$RUN_ID"
CFG="$RUN/planner.yaml"
LOG="$RUN/auth-log4j.groovy"
# Restore auth explicitly in §2, then run offline preflight before any provider command.
```

Replace uppercase values privately; never publish the account, channel/user IDs, or auth path.
`--account` defaults to `GOOGLE_USERNAME` when present; other nonsecret options remain unresolved when
omitted. `init` and `preflight` are offline. Init copies the complete example, produces private
`planner.yaml`, `auth-log4j.groovy`, `campaign.json`, `cases.csv`, and directories `raw/`, `sanitized/`,
`before/`, `after/`, `receipts/`, `state/`. It records one current UTC range within the forecast horizon
in `campaign.json`. Use that same start and exclusive end throughout; never paste expired example
dates or silently regenerate the range mid-comparison. A later campaign needs a new run ID.

Initial state must be `preview`, with weather, AI, messaging, daemon, recurrence, and
`planner.calendarWriteback.enabled` disabled; no approval/apply-safe rules. Four planner state stores
must be isolated under the run, not production paths; external subscription auth must be outside the
repository and planner state. Preflight uses the actual config parsers and refuses unresolved
placeholders, missing environment/private files, unsafe state paths and toggles without showing
sensitive values. Missing auth before restoration is a stop, not permission to weaken preflight.
After each phase-specific config change review its private diff and parser results; an initial-safety
preflight may intentionally refuse later enabled/mutating phases. Do not bypass that refusal silently.

## 2. Explicit local credential restoration and rotation ownership

The owner can populate **Project Secrets** from the current canonical files on the sole refresh-owner
machine. Stop its daemon/refresh activity first. These commands upload single-line **standard Base64
JSON**, unlike raw Todoist/Slack environment tokens. Paths below refer to the chosen source run and
external auth root, not vendor caches. There is no export-wrapper helper.

```bash
base64 -w0 "$RUN/secrets/google-oauth-client.json" | amp secrets set SMARTPLANNER_GOOGLE_OAUTH_CLIENT_JSON_B64 --secret --data-file - --project
base64 -w0 "$RUN/tokens/normal-event-only/credential.json" | amp secrets set SMARTPLANNER_GOOGLE_NORMAL_TOKEN_JSON_B64 --secret --data-file - --project
base64 -w0 "$RUN/tokens/qa-calendar-management/credential.json" | amp secrets set SMARTPLANNER_GOOGLE_QA_TOKEN_JSON_B64 --secret --data-file - --project
base64 -w0 "$AUTH_ROOT/providers/grok/credential.json" | amp secrets set SMARTPLANNER_XAI_SUBSCRIPTION_CREDENTIAL_JSON_B64 --secret --data-file - --project
```

Do not print the Base64 or JSON, put it into command arguments, or save it in evidence. Refresh the
orb environment with `amp orb restart-processes` if newly added secrets are not present; this restarts
orb processes and supervised services, so stop QA services and preserve the run checkpoint first,
and never do it during pending device authorization. Do not inspect secret values. In the new target
workspace explicitly run:

```bash
scripts/smartplanner-qa.sh restore-auth "$RUN_ID"
scripts/smartplanner-qa.sh preflight "$RUN_ID"
```

Restoration is environment-to-files, local-only, never an OAuth exchange. Targets are exactly the
three run-relative Google paths above and configured external
`auth_root/providers/grok/credential.json`. Private directories are 0700 and files 0600. Restoration
refuses **any existing credential target**, rather than overwriting it. If a target exists, stop and
resolve ownership/currentness; do not delete a newer credential merely to make restore succeed.

A Project Secret is a snapshot, not a synchronized token store. Never overwrite a newer rotating
refresh credential with an old snapshot, and never keep two active refresh owners. Before destroying
the sole-owner orb, refresh through the supported credential lifecycle and persist the resulting
canonical protected record to Project Secrets while other owners remain stopped. Remote status can
refresh an expired credential but is not a documented force-refresh switch; use an authorized
expiry/refresh lifecycle check, not hand-edited token JSON. Preserve current records if interrupted.

## 3. Staged live commands — not executed by this guide

Before **any remote write**, including fixtures, calendars, Slack messages/app installation,
writeback, recurrence or cleanup, obtain owner confirmation of the exact named disposable resources,
scope and expected mutation. Before apply additionally approve the exact plan/version/hash. Local
preflight is not a scope or live identity check. Maintain separate event-only normal and
calendar-management QA Google grants; stop on account or scope mismatch.

### Proposed disposable scope — approval required

Record actual returned IDs and ownership privately before execution. The proposed campaign is:

- One new Todoist project `SmartPlanner QA <RUN_ID>` with six synthetic ordinary tasks named
  `QA indoor`, `QA outdoor`, `QA deep-work`, `QA manual`, `QA frozen`, and `QA deadline`.
  Apply the dedicated `smartplanner-qa` eligibility label only to these fixtures, add `qa-outdoor`
  only to the outdoor task, and `manual` only to its control. Reuse labels only after checking their
  existing scope; never delete a pre-existing label during cleanup.
- Two Google calendars: `SmartPlanner QA Output` (managed output) and `SmartPlanner QA Blockers`
  (hard blocker). Existing same-name calendars are candidates, not proof of ownership. Review IDs,
  before inventories and prior campaign leftovers before reuse; never delete a reused calendar.
  Create one synthetic blocker event, then at most one managed event per ordinary task.
- The owner-designated Slack QA channel and allowed member only, at most 40 synthetic test
  messages/replies. No other channel, workspace-wide install change, or unsolicited user message.
- Four bounded xAI schema requests plus at most two combined feedback requests, using synthetic
  fixture content only and an explicitly catalogued model. Catalog access is not inference proof.
- A separate recurrence extension in the same project: eight tasks covering daily, weekly, monthly,
  yearly, completion-relative `every!`, explicit-time, floating/null-zone, and fixed-IANA-zone cases.
  Two native completions per task, with historical managed events retained until owned cleanup.
  This extension needs its own reviewed recurrence tuples and mutation approval before creation.
- Ordinary apply/writeback may change only the six ordinary fixtures' Due fields and their owned
  managed events. Recurrence lifecycle Deadline/marker transitions are confined to its eight
  separately approved fixtures. Each apply still needs approval of its exact generated plan/hash;
  approving this inventory does not pre-approve a later plan or changes to unrelated data.

After execution, cleanup removes only recorded newly created resources and messages. Retain reused
calendars/labels and unrelated resources unchanged. The owner must approve this concrete scope before
any fixture creation, Slack posting, inference or mutation; until then those ledger rows are blocked.
The event-only normal Google grant intentionally cannot list calendars: use the management grant
for inventory and the normal grant for events on the approved returned calendar IDs.

For commands below, `START` and `END` are the exact values recorded in private `campaign.json`;
`PLAN_ID` is the freshly persisted plan ID. Record exits and safe summaries privately first. No
provider command below belongs in `contracts`, init, preflight or default Gradle execution.

### A. Google identity, inventories, and named provisioning

```bash
"$BIN" -f "$CFG" -l "$LOG" --operation google-qa-calendars-list \
  --confirm-dedicated-qa-account
# Only after owner approves these exact names/roles and the list/account matches:
"$BIN" -f "$CFG" -l "$LOG" --operation google-qa-calendars-provision \
  --confirm-dedicated-qa-account \
  --qa-calendar 'output|managed_output|SmartPlanner QA Output;blockers|hard_blocker|SmartPlanner QA Blockers'
```

Names are examples requiring explicit approval, not implicit ownership. Validate unique reuse versus
creation; put returned IDs privately into the complete config, exactly one managed output and the
matching `planner.output_calendar`. Keep the provisioning ID state inside the run. No ordinary
operation may accept provisioning flags. If grants cannot be restored, the distinct operator-only
commands are `google-oauth-bootstrap` (normal) and `google-oauth-bootstrap-qa` (management), each with
`-f "$CFG" -l "$LOG"`; see the Google runbook for loopback/tunnel setup. Never capture consent URLs.

Take normalized private Todoist and Google before-inventories, including unrelated-field invariants,
then create only approved fixtures (ordinary, protected/manual/frozen, outdoor, indoor, blocker).
Fixture creation itself writes and requires approval. Track owned IDs privately for later cleanup.

### B. Baseline and weather

```bash
"$BIN" -f "$CFG" -l "$LOG" --operation capacity --range-start "$START" --range-end "$END" --format json
"$BIN" -f "$CFG" -l "$LOG" --operation preview --range-start "$START" --range-end "$END"
```

Repeat with identical inputs; compare semantic plans/hashes, not creation timestamps. Require zero
Todoist/Google diffs. Enable only weather, exact coordinates/timezone above, `open_meteo`, fail-closed
fallback and an outdoor-only rule. Repeat over the same range/forecast, then disable weather as a
control. Only the outdoor task is constrained; the indoor control remains unaffected. Explain the
matched rule, forecast timestamps, placement/replacement or unscheduled reason. Live weather proves
current connectivity; deterministic rain/clear, stale/missing data, malformed/array mismatch and DST
cases come from hermetic fixtures, not an attempt to command the weather.

### C. xAI status, optional device login, and four strict schemas

Keep AI disabled until local and remote status pass. Existing successful login should normally be
restored, not repeated. New login is a separate owner-authorized browser interaction:

```bash
"$BIN" -f "$CFG" -l "$LOG" --operation ai-auth-status --ai-provider grok --json
# Only if a new device grant is actually needed; keep terminal/code/URL private:
"$BIN" -f "$CFG" -l "$LOG" --operation ai-auth-login --ai-provider grok --auth-flow device
"$BIN" -f "$CFG" -l "$LOG" --operation ai-auth-status --ai-provider grok --remote --json
```

Device verification is a **manual browser step** in the authorized account, not evidence to publish.
No callback tunnel or Grok/Hermes CLI is needed. Local status is offline; remote status is opt-in
network and may refresh. Record only safe status/model/entitlement outcomes. HTTP 200 catalog success
does not prove inference/schema success; 401/403/tier/protocol failure is `blocked` or `fail`, never a
reason to use an API key. Keep configured model explicit; do not guess a model from login success.

Enable only AI with `grok_build_subscription`, external `subscription.auth_root`, experimental protocol
acknowledgment, exact `api.x.ai` host allowlist, redaction and all four allowed types. No `secret_env`
or API key. For the grok-4.6/4.7 replay, use `max_response_bytes: 262144` (the xAI-specific default
when omitted). Existing explicit 65536-byte settings remain enforced and must be deliberately updated:
the bound counts the complete SSE stream, including encrypted reasoning and framing, not only JSON.
The gateway sends the configured `max_tokens` as xAI `max_output_tokens` and selects low reasoning
effort for these two verified model versions. Other model capabilities are not guessed; OpenAI SIWC
continues to omit its preview-unsupported fields. The token limit caps visible output, not reasoning.

Temporary overrides now receive the exact permitted range and request-time expiry constraints used by
local validation. Use a fresh plan; an old or expired plan is not a useful compatibility fixture. A
request outside the permitted range may correctly produce an empty bundle. For useful-output coverage,
also request a duration override within that range and independently check its target, value, binding,
range and expiry. Keep credential-dependent real-model tests private and ignored; normal automated
tests must require neither credentials nor network access. Generate a fresh preview and run each
command separately:

```bash
"$BIN" -f "$CFG" -l "$LOG" --operation ai-suggest --plan-id "$PLAN_ID" \
  --ai-type task_suggestions --correlation-id "$RUN_ID-ai-task"
"$BIN" -f "$CFG" -l "$LOG" --operation ai-suggest --plan-id "$PLAN_ID" \
  --ai-type event_classification_suggestions --correlation-id "$RUN_ID-ai-event"
"$BIN" -f "$CFG" -l "$LOG" --operation ai-suggest --plan-id "$PLAN_ID" \
  --ai-type temporary_planning_overrides --correlation-id "$RUN_ID-ai-override" \
  --feedback 'Prefer tomorrow morning for the synthetic deep-work task'
"$BIN" -f "$CFG" -l "$LOG" --operation ai-suggest --plan-id "$PLAN_ID" \
  --ai-type conversational_feedback_interpretation --correlation-id "$RUN_ID-ai-feedback" \
  --feedback 'Please replan the synthetic deep-work task for tomorrow morning'
```

Require strict all-or-nothing schema/known-ID/plan-hash/input binding and metadata-only audit. A schema
failure is a failed case even if transport returns 200. Compare plan, config, application state and
provider inventories: model output must have **no direct write authority**. Test redaction with
synthetic canaries using hermetic capture assertions; never publish raw prompts or streams.

`ai-suggest` now emits its result JSON and exits **1 for rejection**, **0 for acceptance** (including
an accepted empty bundle); usage/configuration failures retain exit **2**. This is a shell compatibility
change: do not assume every emitted result exits zero. Run each case separately, or explicitly capture
the status in an `if` when using `set -e`; never use `|| true` to mark a failed case successful. Keep the
JSON and actual exit together in the private ledger. Do not automatically retry a rejected request.

Record these three outcomes separately:

1. **Transport success**: an available HTTP `statusCode` of 200 means the server replied successfully,
   not that extraction, schema validation or semantic binding succeeded.
2. **Acceptance**: `accepted: true` and exit 0 mean a validated bundle was returned. An empty
   `bundle.suggestions` is allowed and remains accepted, not a rejection.
3. **Useful nonempty output**: acceptance plus at least one suggestion is a separate QA observation;
   nonempty output alone does not establish usefulness, correctness or mutation authority.

Result compatibility fields remain `accepted`, `error` (`class`, safe `detail`), `audit`, and `bundle`
(`type`, `contentHash`, suggestion `id`/`type` summaries). Optional safe diagnostics in `error` and
`audit` include `reasonCode`, `statusCode`, `responseBytes`, `structuredTextBytes`, and
`responseTruncated`. Reason codes are application-defined, never raw exception/model text. Unknown
measurements are `null`, not zero. `responseBytes` counts **observed wire response bytes**, not the
extracted JSON text length. `responseTruncated: true` means the observation is incomplete and any byte
count is only a lower bound, not the full response size. `structuredTextBytes` counts the extracted
structured output's **UTF-8 bytes only if known**; it is separate from wire bytes, and may remain null
when extraction never completed. Zero denotes a known zero measurement. Review even metadata before
publishing; diagnostics do not change validation, providers, limits, retries or authority.

### D. Slack and supervised daemon

After integrating PR #19 and rebuilding, generate a seed manifest without config/log flags:

```bash
"$BIN" --operation slack-app-manifest --slack-app-name 'SmartPlanner' --slack-channel-types both
```

If installed help/source lacks this operation, stop and build the integrated revision first; do not
claim the current binary supports it. Manually import the generated manifest in Slack's app dashboard,
verify `groups:history` / `message.groups` and `channels:history` / `message.channels`, Socket
Mode/interactivity and no inbound Request
URL, create the `connections:write` app token, install to the approved workspace and invite the bot
only to the approved QA channel. When updating an existing app, preserve its branding and
Slack-generated flags; add missing scopes/events rather than resetting its configuration to the seed.
The app-manifest settings URL is stored outside Git as project environment variable
`SMARTPLANNER_SLACK_APP_MANIFEST_URL`. Store raw `SLACK_BOT_TOKEN` and `SLACK_APP_TOKEN` in protected
environment secrets, not Base64 JSON or YAML. Record neither identities nor tokens in UI evidence.

**Readiness gate:** authenticate the bot (`auth.test`), authenticate the app token
(`apps.connections.open`, discard its secret WebSocket URL), and read one message at most with
`conversations.history` in the exact configured channel. Record only success/error/scope names, never
the response bodies, messages, user profiles or socket URL. This does not post a message. Channel
metadata/user-profile APIs need additional read scopes that the minimal manifest intentionally omits;
do not request those scopes merely to run optional diagnostics.

Initially on 2026-10-25 both tokens authenticated, but history on the supplied channel returned `missing_scope`,
`needed: channels:history`; the installed grant had `groups:history`, not `channels:history`. This
blocks a campaign until resolved. Resolve with the owner: use a private target compatible with the
current manifest, or explicitly select public-channel support, regenerate with
`--slack-channel-types public` (or `both` if both are intended), import/update the manifest in the
Slack dashboard and **reinstall/re-authorize** the app. The manifest must include the matching
`message.channels` event, not only the history scope. Preserve the existing app identity/slash command
when updating; do not silently install a second app. If Slack issues a new bot token, update its
Project Secret privately and refresh the orb environment before repeating the read-only gate.
Changing an existing channel's privacy is an owner decision, not an automated workaround.

The owner subsequently reinstalled the existing app with **both** scope/event pairs. Rechecking
`auth.test`, QA-channel `conversations.history`, and `apps.connections.open` all succeeded with the
existing saved tokens; no token replacement or test message was needed. This passes credential/channel
readiness only, not slash-command delivery, thread interactions, inference, or the combined campaign.

Initially disable AI/weather again to isolate Slack; leave recurrence/writeback off. Enable messaging
and daemon, configure the QA destination, explicit allowed actor, one named `daily` planning run,
help/status/replan rules only, `run_on_startup: false`, and safe scheduling intervals. No approval or
apply-safe phrase. Use an orb-supervised service (command contains paths, never secrets):

```bash
amp orb service start smartplanner-qa --command "$BIN -f $CFG -l $LOG --operation planner-daemon"
amp orb service status smartplanner-qa
# After the restart/persistence checks or on any stop condition:
amp orb service stop smartplanner-qa
```

Use space-free absolute paths for this command or quote them for the service shell. Do not use
`nohup`, `&`, or tmux for daemon survival. Service logs are private until sanitized; do not paste them.
Restart with the same start command and all four state directories intact, never with a blank state.

**Manual Slack UI:** `/smartplanner help`, `/smartplanner status`, `/smartplanner plan daily`, mention
`@SmartPlanner plan daily`, then `replan prefer mornings` in its proposal thread. Observe prompt
acknowledgment, root proposal, working-status clear, same-thread linked iteration and zero scheduling
writes. Try denied actor, channel-root feedback, another channel, unknown/stale thread, and bot input.
Duplicate provider-event identity is a synthetic replay check, not two manually typed messages.
Restart and resume the same thread; verify durable correlation/deduplication and graceful shutdown.

Only after independent Slack and AI pass, enable xAI and send unmatched synthetic natural-language
feedback in the active thread. Expect a deterministic summary and exact configured confirmation within
15 minutes before replan. Denied actor/root/other channel, stale or expired identity, wrong plan/hash,
unknown task, out-of-range and duplicate confirmations must produce no applicable action. Use fixture
clocks/events for deterministic expiry/replay rather than misrepresenting manual observation as proof.

**Confirmed-override acceptance gate:** use a fresh disposable 30-minute target and a distinct
30-minute control with sufficient capacity. Request a 45-minute target and a configured context label.
Record the actual accepted types/values privately, not just a successful inference or iteration count.
Before confirmation, require iteration 1, an honest target/value/fixed-range summary and unchanged
provider inventories. Restart with the pending signed state intact; confirm in that exact thread.
Require an actual **45-minute target interval**, unchanged 30-minute control, context-sensitive placement,
temporary-change explanations and unchanged source duration/labels/Deadline/Due before separate apply.
Recheck full inventories, reviewed-range containment, successor linkage and duplicate-envelope behavior.
A revised proposal that still schedules 30 minutes fails this gate even if the LLM accepted its schema.
Hermetic daemon/applier tests cover composition, restart, altered/legacy state and refusal boundaries;
they do not pass this real Slack/xAI/Open-Meteo case. Preserve the signing key with the decision state;
never regenerate missing retained provenance to make a replay pass.

For the fresh campaign, use the owner's existing **signed-in Firefox Todoist** session; do not replace
its profile or copy credentials. Cross-check the intended QA account privately before fixtures. Observe
owned task Due values, protected fields and navigation from the Google event's task link. For recurrence,
complete each approved disposable variant twice using native Todoist controls, with API snapshots of
occurrence identity, recurrence/Deadline tuple and historical Google events before/after each action and
after restart. API completion is a separate case, not a substitute for native Todoist completion.
Inspect captured UI state and the full interaction recording, cropping/redacting account chrome,
emails, resource/member IDs and unrelated tasks before publication. Never publish raw prompts, model
responses or pending envelopes. Verify owned-only cleanup and unrelated inventories after these cases;
retain every earlier campaign's evidence and label remaining blocked cases explicitly.

### E. Separately approved mutations, writeback, and recurrence

**Preview is not a global no-write switch.** Explicit `apply-safe`, enabled recurrence lifecycle and
enabled daemon writeback can independently write; Slack always writes messages/state. Standalone
`capacity`/`preview` stay remotely read-only even with writeback enabled. Keep mutation features off
until their named fixture inventory, before-snapshot and exact expected changes are approved.

For ordinary apply, set `approval_required`, regenerate preview, and verify unapproved and stale/wrong
approval attempts refuse with zero writes. Prepare private approval JSON/YAML with a unique `id`,
current `planId`, `planVersion`, `planHash` from the actual reviewed plan (not a hand-edited plan),
and the real authorization's `approvedAt` and `approvedBy`. Then, only after owner confirmation of
that exact plan:

```bash
"$BIN" -f "$CFG" -l "$LOG" --operation apply --plan-id "$PLAN_ID"
"$BIN" -f "$CFG" -l "$LOG" --operation apply --plan-id "$PLAN_ID" --approval "$RUN/wrong-approval.json"
# The next command writes; owner-approved exact approval file only:
"$BIN" -f "$CFG" -l "$LOG" --operation apply --plan-id "$PLAN_ID" --approval "$RUN/approval.json"
# Separate safe-only case/approval, using its own reviewed mixed safe/protected plan:
"$BIN" -f "$CFG" -l "$LOG" --operation apply-safe --plan-id "$SAFE_PLAN_ID"
```

Require only expected owned managed-calendar changes and ordinary Todoist Due changes; ordinary
Deadlines and unrelated fields remain unchanged. Safe-only must withhold protected/frozen/manual
changes. Repeat only a **known resolved** successful apply to prove idempotency; never retry an
ambiguous outcome. Slack approval/apply-safe rules are added only for their separately authorized
mutation case, not for the earlier confirmation/replan gate.

There is **no standalone writeback or recurrence CLI operation**. For writeback, separately enable
`planner.calendarWriteback.enabled: true`, manually move one approved owned single-task Google event,
then run the supervised daemon's planning tick. Expect newer calendar timestamp to update only Due,
adopt the moved baseline and suppress echoes, including after restart; unowned/multi-task events,
tied/missing clocks and newer Todoist edits must not be adopted. Disable again immediately. Snapshot
the cursor/echo state and verify Deadline/labels/description unchanged.

For recurrence, separately enable `planner.tasks.recurrence.enabled` only after reviewing the full
[native recurrence campaign](SMARTPLANNER_QA_RUNBOOK.md#h-native-recurrence-and-deadline-campaign-new-evidence-only).
Use supervised daemon item polling and exact approved apply plans; complete tasks **natively in
Todoist UI**, not REST/Sync `close` shortcuts. Cover daily/weekly/monthly/yearly, `every!`, explicit-time,
floating/null-zone and fixed-IANA-zone tuples, two cycles and restart. Require preserved expression,
flag, language, timezone; only authorized lifecycle Deadline/marker/label transitions; distinct new
occurrence identity and historical events retained. Stop on drift, incomplete tuple, same-count user
edit, removed sentinel/marker or completion racing apply. Fault/cursor-loss/state-loss injection belongs
in hermetic regressions unless separately approved against disposable live data. Disable recurrence
and writeback again before cleanup.

## 4. Case-to-test and evidence ledger

Every row starts **`not_run`** in `cases.csv`. Use only `not_run`, `pass`, `fail`, `blocked`; a hermetic
pass never fills a live/manual cell. Record case ID, proof kind (`synthetic`, `live_api`, `manual_ui`),
expected/actual, exit/status, reviewed revision/config digest, UTC range, private inventory/receipt
references, sanitized evidence references, approval reference and cleanup result. Keep identities and
resource-ID mappings private. Missing authorization, model compatibility or helper support is
`blocked` with a reason, not a pass. Add individual rows for the variants grouped below.

Test paths below are relative to `app/src/test/groovy/todoistcaldavsync/`; each named feature exists in
the indicated Spock spec. Run the full specs, not only the representative feature. This is a map of
contracts to replay obligations, not a claim that tests cover every live condition.

| Case / expectation | Existing hermetic spec and exact representative feature | Live/manual counterpart |
| --- | --- | --- |
| AUTH: isolated protected grants, single rotating owner | `planner/ai/SubscriptionCredentialAndOAuthSpec.groovy`: “refresh is serialized reread under lock and persists one rotating token before either caller returns”; “private store round trips versioned credentials with owner-only files and provider isolation” | §2 restore/refusal/mode checks; authorized refresh and restart. Helper restore needs its own contract tests; these specs do not prove shell restore. |
| XAI: offline local status, strict catalog, no fallback | `planner/ai/SubscriptionCredentialAndOAuthSpec.groovy`: “local status performs zero network and terminal refresh errors require reauthentication”; “xAI remote status validates the public data catalog without exposing credentials”; `planner/ai/AiSubscriptionFailClosedSpec.groovy`: “subscription config rejects relative paths unknown fields and API key crossover” | §3C local/remote status, tier/model outcome; catalog fix must be built. |
| GOOGLE: explicit account/provisioning gate | `TodoistCalDavSyncQaProvisioningSpec.groovy`: “QA operations refuse absent dedicated-account confirmation before service construction”; “normal planner operations cannot reach QA provisioning factory” | §3A list/account/scope check, owner-approved names, returned IDs, no unexpected resources. |
| BASE: deterministic preview, no mutations | `planner/scheduling/SchedulerFixtureIntegrationSpec.groovy`: “capacity fixtures produce deterministic preview plan with unscheduled reasons”; `planner/apply/PlanApplierSpec.groovy`: “preview mode never writes calendar or Todoist” | §3B repeated baseline; normalized before/after provider diff zero. |
| WEATHER: outdoor restriction, indoor control | `planner/scheduling/WeatherSchedulerSpec.groovy`: “rain invalidates deck painting and selects feasible indoor replacement in same slot”; “non-outdoor tasks identical with weather disabled vs enabled benign forecast” | §3B exact location/current forecast, rule/timestamp explanation; live weather is not forced-rain proof. |
| WEATHER-NEG: stale, missing, malformed, DST | `planner/scheduling/WeatherEvaluatorSpec.groovy`: “stale forecast fail-closed blocks; fail-open allows”; `planner/adapters/OpenMeteoWeatherGatewaySpec.groovy`: “hourly optional array shorter or longer than time is SCHEMA with field expected actual”; `planner/scheduling/WeatherSchedulerSpec.groovy`: “DST fall folds evaluated as distinct weather hours for outdoor task” | Synthetic deterministic negatives; live API connectivity separately recorded. |
| AI-4: all four strict schemas and redaction | `planner/ai/LlmSchemaAndAssistanceSpec.groovy`: “fixture trial validates all four versioned schema/model contracts”; “strict validator rejects malformed unsafe and cross-context output without partial results”; “context builder allowlists fields redacts sensitive strings and truncates deterministically” | §3C four separate inference rows; invalid IDs/hash/extra JSON/expired range fail closed, no raw payload evidence. |
| AI-STREAM: no tools/direct writes, errors safe | `planner/ai/SubscriptionResponsesGatewaySpec.groovy`: “gateway emits official store-false streaming history contract with no tools and accepts only completed strict output”; “SSE parser rejects tool activity explicit failures incomplete and interrupted streams” | §3C metadata-only audits, unchanged plan/config/provider state; synthetic tool/error/interrupt cases. |
| SLACK: denied context, duplicates, restart | `planner/SmartPlannerDaemonSpec.groovy`: “ignores bots unrelated channels root feedback and duplicate or unauthorized events”; “publishes configured horizons, replans in the same thread, and applies exact approval after restart” | §3D manual UI and separate synthetic duplicate replay; no scheduling writes in initial phase. |
| CONFIRM: exact actor/identity/expiry, no direct AI apply | `planner/ai/AiConfirmationAndOverrideSpec.groovy`: “stale wrong expired unauthorized replay and conflict never produce another applicable override”; `planner/SmartPlannerDaemonSpec.groovy`: “LLM feedback requires deterministic confirmation before a replan and never writes providers”; “legacy untyped AI pending overrides are retained for inspection but confer no authority” | §3D authorized confirmation plus denied actor/root/other-channel/duplicate/stale cases; fixture clock for deterministic expiry. |
| APPLY: exact approval, safe/protected separation | `planner/apply/PlanApplierSpec.groovy`: “missing approval causes zero writes”; “tampered plan hash approval is rejected”; “applySafeChanges on approval_required plan applies safe only; receipt effective mode; plan not mutated” | §3E unapproved/wrong-hash refusals, one exact approved apply and separate safe-only mixed plan. |
| OWNERSHIP: managed Google only, no ordinary Deadline | `planner/apply/PlanApplierGoogleCalendarApiGatewaySpec.groovy`: “approved apply writes only the managed Google calendar and never mutates Todoist deadlines”; `planner/adapters/GoogleCalendarApiGatewayWireMockSpec.groovy`: “upsert refuses cross-calendar, missing ownership, and mismatched live block before mutation” | §3E inspect allowed output, invariants and global collision controls; never create collisions in unrelated data. |
| RECOVERY: ambiguous writes, no blind retry | `planner/apply/PlanApplierSpec.groovy`: “ambiguous Todoist write is durably reconciled from live state without resend”; “ambiguous calendar PUT is reconciled from live owned event without resend”; `planner/messaging/MessagingServiceSpec.groovy`: “provider success then final ledger failure yields UNKNOWN and retry does not resend” | Stop, preserve all stores and receipts, inspect live postcondition before reconciliation; synthetic injection unless separately authorized. |
| WRITEBACK: guarded Due-only, cursor/echo | `planner/SmartPlannerDaemonSpec.groovy`: “daemon adopts a newer owned move before planning and never previews the old slot back”; `planner/writeback/CalendarWritebackDecisionSpec.groovy`: “different times with missing or tied timestamps are recorded and skipped”; `planner/writeback/PlannerCalendarWritebackServiceSpec.groovy`: “recurring planner writeback uses one guarded repair and never falls back to a datetime POST” | §3E separately approved native calendar move, daemon/restart, negative candidates and unchanged non-Due fields. |
| RECURRENCE: native cycles, drift/restart, historical identity | `planner/recurrence/RecurrenceLifecycleSpec.groovy`: “managed recurrence advances Deadline for two native #kind cycles without interpreting expression”; “items poller replays a staged delta after processor failure without refetching”; `planner/apply/RecurrencePlanApplierSpec.groovy`: “two native occurrences use distinct UIDs and retain the historical event”; “#race between planning and apply fails before Todoist or Calendar mutation” | §3E and full runbook campaign; native UI completion/time alignment screenshots, protected-field diffs. |
| CLEANUP: zero owned leftovers, evidence safe | Ownership/refusal specs above constrain application deletion; no automated test proves a live campaign cleaned up | §5 before/after resource inventory and owner-reviewed removal totals; scan/hash verification. |

## 5. Stop, reconcile, clean up, and publish evidence

Stop on account/scope mismatch, unapproved target, missing before snapshot, credential leakage,
unexpected Deadline/recurrence drift, unknown mutation outcome or cleanup discrepancy. Stop the
supervised daemon, disable optional features/recurrence/writeback and return to preview. Preserve the
private before/after exports, receipts and **all four** state stores together. An ambiguous result is
not an invitation to reset state or repeat a command: inspect live owned resource/postcondition and
stable command identity, reconcile only the missing side, then seek approval to proceed.

Cleanup is a separately approved **manual/API** action; the launcher has no general QA-delete command.
Delete only IDs in the private owned inventory, checking ownership and current state first. Remove
owned events/messages/tasks/subtasks/project/labels and disposable calendars as authorized, never
the primary calendar or unrelated resources. Poll deletion deltas where relevant and compare final
Todoist, Google and Slack inventories with the before inventory. Require **zero owned leftovers** and
explicit per-resource totals; nonzero or unverified cleanup means campaign incomplete. Revocation does
not undo data writes. Retire disposable grants only after current rotating records are safely persisted
or deliberate revocation is approved; xAI `ai-auth-logout --ai-provider grok --json` (with config/log
flags) removes the local record, not a guaranteed remote revocation. Use account settings if available.

Keep `raw/`, `before/`, `after/`, `receipts/`, `state/`, private config and auth **private**. Create
allowlisted summaries in `sanitized/` manually; never recursively copy these directories into it.
Publish only synthetic labels, counts/diffs, safe classes, UTC timestamps, plan/config digests,
redacted UI crops and outcome references. Exclude credentials, identities/emails/account/workspace/
channel/user IDs, codes, consent/verification URLs, secret-bearing paths, raw prompts/responses/streams.
Metadata-only output still requires review. Do not include hashes of raw secret files as evidence.

Run the following **only on the reviewed sanitized directory** (GNU tools). A pattern scan is a guard,
not proof that every identity or arbitrary credential format was removed. Never display matches:

```bash
set -o pipefail
EVIDENCE="$RUN/sanitized"
test -d "$EVIDENCE" || exit 1
# No links or credential/config filenames; investigate privately if this fails.
test -z "$(find "$EVIDENCE" -type l -print -quit)" || exit 1
test -z "$(find "$EVIDENCE" -type f \( -iname '*credential*' -o -iname '*token*' -o -name 'planner.yaml' -o -name 'auth-log4j.groovy' \) -print -quit)" || exit 1
rg --hidden --no-ignore -a -i -q '(xox[baprs]-|xapp-|Bearer[[:space:]]|Authorization:|access_token|refresh_token|client_secret|id_token|device_code|user_code|verification_uri|consent|https://[^[:space:]]*(oauth|authorize)|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,})' "$EVIDENCE"
scan_status=$?
# rg 1 means no matches; 0 means quarantine/re-sanitize, 2 means scan failure.
test "$scan_status" -eq 1 || exit 1
# Manually review text and redacted image/video pixels before hashing.
( cd "$EVIDENCE" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum > SHA256SUMS )
( cd "$EVIDENCE" && sha256sum --check SHA256SUMS )
```

The scan intentionally runs without `set -e` so the expected rg exit 1 can be examined; if your shell
uses errexit, capture it in an `if` instead. Review media for IDs, browser URL bars, consent/code panels
and token-bearing terminals; text scanning cannot inspect pixels. Verify the manifest again after
transfer and ensure no unlisted files were added (regenerate/compare the sorted file inventory).
Record scan outcome, manual redaction review, checksum verification, per-case statuses and zero-leftover
totals. Only this reviewed sanitized package is publishable; raw private state is never a deliverable.
