

# todoist-calendar-sync

SmartPlanner exposes capacity-aware planning through the existing main executable while retaining
the original sync as the default operation. Its primary production operation, `planner-daemon`, is a
long-running multi-horizon scheduler that publishes proposals and iterates on feedback through Slack
Socket Mode without an inbound port. Production planning uses real Todoist REST plus an explicitly
selected CalDAV or Google Calendar API adapter,
durable deterministic plans/conversations, exact approvals, safe-only application, optional
Open-Meteo, configurable regex feedback, and disabled-by-default bounded AI interpretation.
`fully_automated` remains unavailable and refuses writes.

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/justinfiore/todoist-calendar-sync)

Sync Todoist tasks into one or more CalDAV calendars.

Feature guides: [SmartPlanner configuration](docs/SMART_PLANNER_CONFIGURATION.md),
[end-to-end rollout/testing](docs/PLANNER_END_TO_END_TESTING.md),
[Slack](docs/SLACK_INTEGRATION.md), [LLM](docs/LLM_INTEGRATION.md), and
[weather](docs/WEATHER_INTEGRATION.md). The bounded suggestion and confirmation contracts are in
[AI Assistance](docs/AI_ASSISTANCE.md).

This app reads tasks from Todoist, filters them by labels/projects, routes each task to a calendar using rule matching, and creates/updates calendar events as `.ics` resources via CalDAV.

## What It Does

- Pulls Todoist tasks from the Todoist Sync API v1.
- Ignores tasks without due dates.
- Includes tasks when either of these is true:
  - task has at least one label from `todoist.labelsToInclude`
  - task is in a project listed in `todoist.projectsToInclude`
- Chooses a destination calendar using first-match rule logic in `caldav.rules`.
- Writes an event to the matched calendar and removes that same event UID from other configured calendars.
- Persists Todoist sync state in a `.state` file next to your config file.
- Supports CalDAV auth schemes:
  - `BASIC`
  - `GOOGLE_OAUTH2`

## Requirements

- Java 25 (the supported build and runtime baseline)
- Network access to:
  - `https://api.todoist.com`
  - your CalDAV server(s)
- A Todoist API token
- For Google OAuth mode:
  - `client_secret.json` in your config directory

## Project Layout

- `app/src/main/groovy/todoistcaldavsync/TodoistCalDavSync.groovy`: main sync program
- `app/src/main/groovy/todoistcaldavsync/GoogleAuthProvider.groovy`: helper to pre-authorize Google OAuth credentials
- `conf/todoist-calendar-sync.conf.example.yaml`: sample app config
- `conf/log4j.groovy`: sample logging config

## Build and Run

### 1) Build

```bash
./gradlew build
```

### 2) Create your config

```bash
cp conf/todoist-calendar-sync.conf.example.yaml conf/todoist-calendar-sync.conf.yaml
```

Edit the copied file with your real values.

### 3) Run the sync

```bash
./gradlew :app:run --args='-f conf/todoist-calendar-sync.conf.yaml -l conf/log4j.groovy'
```

### 4) Run one time vs looping

- If `syncIntervalMs > 0`: program runs forever and sleeps between sync runs.
- If `syncIntervalMs == 0`: program runs one sync and exits.

## Command-Line Interface

Main app:

```text
TodoistCalDavSync.groovy -f configFile -l log4j.groovy
```

Options:

- `-f <configFile>`: required YAML config file
- `-l <log4j.groovy>`: required Log4j Groovy config file
- `-h`: help

SmartPlanner uses the same entry point and adds `--operation`. Omitting it preserves `legacy-sync`.

Every command below also requires `-f CONFIG -l LOG_CONFIG`. Use the installed
`todoist-caldav-sync` launcher or the equivalent Gradle run command.

| Operation | Required/optional arguments | Behavior |
| --- | --- | --- |
| `legacy-sync` | no planner arguments | Runs the original Todoist-to-CalDAV sync/loop. This is the default when `--operation` is omitted. |
| `google-oauth-bootstrap` | Google provider OAuth references and expected account; calendar IDs are not required | Binds `127.0.0.1` on the configured callback port (default `8787`), requests normal event-only access, prints the one-time consent URL to the invoking terminal, persists only the normal refresh-capable token store, and exits without planning or provisioning. |
| `google-oauth-bootstrap-qa` | Google provider plus distinct `qa_token_store_dir`; calendar IDs are not required | Requests the separate calendar-management QA grant, persists only the QA token store, and exits without listing or creating calendars. |
| `google-oauth-import-legacy-qa` | `--confirm-legacy-qa-import --input-reference FILE` | Validates the explicitly referenced bounded legacy credential for the configured dedicated account and exact QA scope, imports it only to the QA token store, and exits. It cannot populate the normal store. |
| `google-qa-calendars-list` | `--confirm-dedicated-qa-account` | Uses only the QA credential, verifies the primary calendar matches the configured dedicated account, and prints the calendar inventory. |
| `google-qa-calendars-provision` | `--confirm-dedicated-qa-account --qa-calendar 'alias\|role\|name[;...]'` | Explicitly creates or exactly reuses named disposable calendars and persists returned IDs only under ignored `.qa/state/calendar-ids.json`. |
| `planner-daemon` | `planner.daemon.enabled: true`, configured planning runs, Slack Socket Mode credentials/channel | Primary long-running SmartPlanner lifecycle. Performs startup provider probes, schedules each configured horizon independently, publishes channel proposals, consumes thread feedback/commands, persists conversation state, and remains alive across contained cycle/provider/feedback failures. |
| `capacity` | `--range-start INSTANT --range-end INSTANT`; optional `--format markdown\|json` | Reads Todoist and the selected calendar provider and reports capacity for the half-open UTC interval. It makes no remote writes. |
| `preview` | `--range-start INSTANT --range-end INSTANT`; optional `--previous-plan-id ID` | Builds, renders, and locally persists a deterministic plan. It makes no Todoist or calendar-provider writes. |
| `apply` | `--plan-id ID`; optional `--approval FILE` | Applies a stored plan according to its configured safety mode. `approval_required` needs an exact approval file; `preview` and unavailable `fully_automated` refuse writes. |
| `apply-safe` | `--plan-id ID` | Applies only ordinary safe changes from a stored plan. Protected, frozen, manual, drifted, and approval-required changes remain withheld. |
| `deliver` | `--plan-id ID`; optional `--kind KIND` | Delivers one enabled message kind when `--kind` is supplied, or evaluates configured due schedules when it is omitted. The durable ledger prevents blind duplicate sends. |
| `feedback` | `--plan-id ID --feedback COMMAND --actor ID`; optional `--correlation-id ID --message-id ID` | Authorizes, parses, and persists structured feedback such as `approve`, `reject`, `apply-safe`, `request-changes`, `status`, or `help`. It never applies a plan. |
| `apply-decision` | `--plan-id ID --decision-id ID` | Explicitly revalidates and applies an accepted stored `APPROVE` or `APPLY_SAFE` decision. Rejected, stale, conflicting, or replayed decisions do not write. |
| `ai-suggest` | `--plan-id ID --ai-type TYPE --correlation-id ID`; optional `--feedback TEXT` | Requests a configured bounded AI suggestion. Allowed types are `task_suggestions`, `event_classification_suggestions`, `temporary_planning_overrides`, and `conversational_feedback_interpretation`. Output has no mutation authority. |

Examples:

```bash
# Primary long-running SmartPlanner process (after isolated rollout validation)
export SLACK_BOT_TOKEN='xoxb-...'
export SLACK_APP_TOKEN='xapp-...'
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy --operation planner-daemon

# Read-only capacity report
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy --operation capacity \
  --range-start 2026-08-14T00:00:00Z --range-end 2026-08-17T00:00:00Z --format markdown

# Persist a preview, optionally comparing it with a known baseline
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy --operation preview \
  --range-start 2026-08-14T00:00:00Z --range-end 2026-08-17T00:00:00Z \
  --previous-plan-id PREVIOUS_PLAN_ID

# Apply an exactly approved plan, or apply only its safe subset
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation apply --plan-id PLAN_ID --approval approval.json
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation apply-safe --plan-id PLAN_ID

# Deliver a proposal now, or omit --kind to process due schedules
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation deliver --plan-id PLAN_ID --kind proposal

# Persist structured feedback, then explicitly apply its accepted decision
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy --operation feedback \
  --plan-id PLAN_ID --feedback 'approve PROPOSAL_ID PLAN_HASH' --actor ACTOR_ID \
  --correlation-id CORRELATION_ID --message-id MESSAGE_ID
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation apply-decision --plan-id PLAN_ID --decision-id DECISION_ID

# Request suggestion-only AI assistance
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy --operation ai-suggest \
  --plan-id PLAN_ID --ai-type task_suggestions --correlation-id CORRELATION_ID
```

### SmartPlanner Google OAuth and isolated QA setup

SmartPlanner requires `planner.integration.calendar.provider` to be exactly `caldav` or
`google_calendar_api`; it never infers a provider or falls back between them. The annotated planner
example keeps CalDAV active and contains a mutually exclusive commented Google block. Google uses an
ignored desktop OAuth client-file reference, separate normal and QA token-store references, a pinned
account email, provider-returned calendar IDs, and exactly one `managed_output` mapping matching
`planner.output_calendar`. Inline OAuth values, static durable access tokens, Google account
passwords, and app passwords are not accepted for the Google provider.

After implementation review and explicit authorization for live credential use, run a fresh normal
event-only bootstrap when normal Google planner operations are needed:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-bootstrap
```

This command allows the bootstrap-only configuration subset without calendar IDs, binds only
`127.0.0.1:<oauth_callback_port>` (default `8787`), prints the one-time consent URL only to that
terminal, persists only the normal token store, and exits. It does not construct the planner, list or
provision calendars, or start the daemon. For a browser on another machine, establish the tunnel
first and leave it running while completing consent:

```bash
ssh -N -L 8787:127.0.0.1:8787 hermes@<host>
```

Use the configured port on both sides, then open the printed URL in the local browser. The callback
returns through SSH; never copy an authorization code into Slack or a terminal.

The initially confirmed legacy broad credential may be validated/imported only into the isolated QA
calendar-management store. The input reference is a local bounded credential JSON file; do not print
or attach it:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-import-legacy-qa \
  --confirm-legacy-qa-import --input-reference .qa/secrets/legacy-qa-credential.json
```

The import validates the configured account and exact QA scope before writing, sends no Calendar API
request, never writes the normal token store, and exits. If validation fails, stop without
provisioning and use the separately authorized QA bootstrap only when an operator is available:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-bootstrap-qa
```

QA bootstrap uses the same loopback/tunnel behavior, writes only the distinct QA token store, and
exits without listing or creating calendars. After either successful QA credential path, explicitly
preflight and provision only the dedicated disposable account:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-qa-calendars-list --confirm-dedicated-qa-account

todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-qa-calendars-provision --confirm-dedicated-qa-account \
  --qa-calendar 'output|managed_output|SmartPlanner QA Output;blockers|hard_blocker|SmartPlanner QA Blockers'
```

Provisioning refuses an account whose primary calendar does not match `account_email`, reuses only an
exact unique name, and retains returned IDs only in ignored `.qa/state/calendar-ids.json`. Copy those
IDs into the ignored complete QA configuration; normal planner operations never provision calendars.
Keep `.qa/secrets/`, `.qa/tokens/`, and `.qa/state/` owner-private.

Approval remains preview-first: run `capacity`, run `preview`, inspect the plan identity/hash/diff and
prove zero remote writes, then exercise missing/stale approval refusal before any exact approved or
safe-only write. On an unexplained or indeterminate mutation, stop, preserve logs/receipts/state,
reconcile live provider state, restore the matching Todoist/calendar export and all four planner state
directories together, and return to `preview`; never delete state to force a retry. To retire Google
access, stop the planner, revoke the app grant in the dedicated Google account, remove the ignored
normal and QA token stores plus client material, and select a fully configured `caldav` provider only
if that is the intended rollback. Revocation/removal invalidates local Google access but does not undo
calendar mutations, so restore provider backups separately.

No secret belongs in Slack: never paste Todoist tokens, Slack `xoxb-`/`xapp-` values, OAuth client
material, consent URLs, authorization codes, access/refresh tokens, credential documents, account
passwords, app passwords, or `Authorization` headers into a channel, thread, ticket, screenshot, log,
receipt, or committed evidence.

Google OAuth credential helper:

```text
GoogleAuthProvider.groovy -f configFile -l log4j.groovy
```

This scans your config for calendars using `GOOGLE_OAUTH2`, then launches the auth flow and stores credentials locally.

Run helper:

```bash
./gradlew :app:run -PmainClass=todoistcaldavsync.GoogleAuthProvider --args='-f conf/todoist-calendar-sync.conf.yaml -l conf/log4j.groovy'
```

## Configuration Reference

All config is YAML.

### Top-level

#### `dryRun` (boolean)

- Default example: `false`
- If `true`, logs intended writes/deletes but does not write calendar events or state.

#### `todoist` (object)

Contains Todoist auth and inclusion filters.

##### `todoist.accessToken` (string)

- Todoist API token.
- Can be overridden by env var `TODOIST_ACCESS_TOKEN`.
- Runtime precedence is:
  1. `TODOIST_ACCESS_TOKEN` (if set and non-empty)
  2. `todoist.accessToken`
- If neither is set, sync fails.

##### `todoist.labelsToInclude` (array of strings, required non-empty)

- Labels that make a task eligible for calendar sync.
- Must be a non-empty array.
- If empty/missing, app throws an error.

##### `todoist.projectsToInclude` (array of strings, optional)

- Additional inclusion path by Todoist project name.
- If missing/empty, treated as empty list.
- Exact project-name match is required.

#### `rateLimitMs` (integer)

- Milliseconds to sleep between CalDAV operations/retries.
- `0` means no added delay.
- Useful when CalDAV provider rate limits aggressively.

#### `syncIntervalMs` (integer)

- Milliseconds between sync runs.
- `0` means run once and exit.
- `> 0` means continuous loop.

#### `maxConnectionsPerHttpClient` (integer)

- Max HTTP connections per calendar HTTP client.
- Default in code when omitted: `10`.
- Example sets `500` to reduce connection starvation risk.

### `caldav` section

#### `caldav.default.auth` (object, optional)

Default auth used by calendars that do not define per-calendar `auth`.

##### `caldav.default.auth.scheme` (string)

Supported values:

- `BASIC`
- `GOOGLE_OAUTH2`

##### `caldav.default.auth.basicAuth.username` (string, required for `BASIC`)

Basic auth username.

##### `caldav.default.auth.basicAuth.password` (string, required for `BASIC`)

Basic auth password.

Can be supplied by env var `CALDAV_AUTH_BASICAUTH_PASSWORD` when config password is omitted.

##### `caldav.default.auth.google.username` (string, required for `GOOGLE_OAUTH2`)

Google account identifier used to fetch/store OAuth credentials.

#### `caldav.calendars` (array, required)

Each entry defines one destination calendar.

For each calendar item:

##### `name` (string, required)

- Logical calendar name.
- Used by `caldav.rules[].calendarName` routing.
- Must be non-empty.

##### `url` (string, required)

- Base CalDAV collection URL.
- Must be non-empty.

##### `auth` (object, optional)

- Per-calendar auth override.
- Same schema as `caldav.default.auth`.
- If omitted, falls back to `caldav.default.auth`.

##### `prefix` (string, optional)

- If set, prepended to event summary/title.
- Example: `"TD: "`.

#### `caldav.rules` (array, required for routing)

Ordered rule list. First matching rule wins.

Each rule item:

##### `calendarName` (string)

Destination calendar name. Must match an entry in `caldav.calendars[].name`.

##### `rule` (string)

Rule expression syntax supported by code:

- `AND` for conjunction
- `NOT <token>` for negation
- label tokens (plain): `foo`
- project-name token prefix: `p:<Exact Project Name>`

Examples:

- `cal AND foo`
- `NOT foo AND bar`
- `NOT foo AND p:ABC Company`

Notes:

- There is no explicit `OR` operator. Use multiple rules pointing to the same `calendarName`.
- Matching is exact string match for labels and project names.

## Environment Variables

- `TODOIST_ACCESS_TOKEN`: overrides `todoist.accessToken`
- `CALDAV_AUTH_BASICAUTH_PASSWORD`: fallback password for BASIC auth

## OAuth Files and Token Storage (Google OAuth)

When using `GOOGLE_OAUTH2`:

- Place `client_secret.json` in the same directory as your YAML config.
- Credentials are stored in that same directory via Google `FileDataStoreFactory` (data store named `user`).
- Tokens are refreshed automatically in request interceptor when expired.

## Generated State File

A state YAML file is written beside the config file.

- Name is derived from config filename by replacing `.conf` with `.state`.
- Example:
  - config: `todoist-calendar-sync.conf.yaml`
  - state: `todoist-calendar-sync.state.yaml`

State currently tracks values like:

- `syncToken` (Todoist incremental sync token)
- `v1Migrated` (Todoist v9 -> v1 UID migration flag)

If state file parsing fails, app logs an error and continues with empty state.

## Event Mapping Details

- Event UID is deterministic from `todoistUserId-taskId` (Base32 encoded).
- Event summary is Todoist task content (plus optional calendar `prefix`).
- Event description includes project, labels, and mapped priority.
- Due-date behavior:
  - due datetime -> used directly
  - due date-only -> treated as all-day and forced to start at 09:00 local time
- Duration behavior:
  - If Todoist duration exists, uses that (`minute` or `hour`)
  - Else checks for short `t*` labels (`t1`..`t9`, `t0`, `t30` etc.)
  - Else defaults to 30 minutes

## Logging

Sample logging config is `conf/log4j.groovy`:

- Console appender and rolling file appender
- File path: `logs/todoist-ical-sync.log`
- Root logger at `debug`

Run with a different log config by passing another file to `-l`.

## Troubleshooting

### Tasks not appearing in calendars

- Verify tasks have due dates.
- Verify labels/project inclusion filters.
- Verify routing rules match labels/project names exactly.
- Check logs for `Couldn't find a calendar for item`.

### BASIC auth failures

- Confirm username/password values.
- If relying on env var password, ensure `CALDAV_AUTH_BASICAUTH_PASSWORD` is exported in the shell running the app.

### Google OAuth failures

- Ensure `client_secret.json` exists in config directory.
- Run the `GoogleAuthProvider` helper to pre-authorize users.
- Check token refresh logs.

### Connection pool / hanging requests

- Increase `maxConnectionsPerHttpClient`.
- Tune `rateLimitMs`.
- See:
  - `CONNECTION_LEAK_ANALYSIS.md`
  - `POOL_RESET_STRATEGY.md`

## Example Minimal Config

```yaml
dryRun: false
rateLimitMs: 10
syncIntervalMs: 60000
maxConnectionsPerHttpClient: 200

todoist:
  accessToken: <TODOIST_ACCESS_TOKEN>
  labelsToInclude: [cal]
  projectsToInclude: []

caldav:
  default:
    auth:
      scheme: BASIC
      basicAuth:
        username: <BASIC_AUTH_USERNAME>
        password: <BASIC_AUTH_PASSWORD>

  calendars:
    - name: Home
      url: https://example.com/caldav/user/home
      prefix: "TD: "

  rules:
    - calendarName: Home
      rule: cal
```
