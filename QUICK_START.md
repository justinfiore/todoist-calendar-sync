# Quick Start

This guide gets `todoist-calendar-sync` running with minimal setup.

For SmartPlanner, begin with the isolated test-service and crawl/walk/run procedure in
[docs/PLANNER_END_TO_END_TESTING.md](docs/PLANNER_END_TO_END_TESTING.md). Do not start with apply.

## 1) Prerequisites

- Java 25 (the supported build and runtime baseline)
- Todoist API token
- CalDAV calendar URL + credentials

## 2) Build

```bash
./gradlew build
```

## 3) Create your config

```bash
cp conf/todoist-calendar-sync.conf.example.yaml conf/todoist-calendar-sync.conf.yaml
```

Edit `conf/todoist-calendar-sync.conf.yaml`:

- Set `todoist.accessToken` (or use env var `TODOIST_ACCESS_TOKEN`).
- Set `todoist.labelsToInclude` (must be non-empty).
- Set `caldav.calendars[0].name` and `caldav.calendars[0].url`.
- Set auth under `caldav.default.auth` (usually `BASIC` with username/password).
- Add at least one rule under `caldav.rules` that routes tasks to your calendar name.

## 4) Minimal working config example

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

## 5) Run a one-time sync first

Set `syncIntervalMs: 0`, then run:

```bash
./gradlew :app:run --args='-f conf/todoist-calendar-sync.conf.yaml -l conf/log4j.groovy'
```

## 6) Switch to continuous sync

Set `syncIntervalMs` back to your preferred interval (for example `60000`) and run the same command.

## Optional: Use environment variables for secrets

```bash
export TODOIST_ACCESS_TOKEN='<token>'
export CALDAV_AUTH_BASICAUTH_PASSWORD='<password>'
```

- `TODOIST_ACCESS_TOKEN` overrides `todoist.accessToken`.
- `CALDAV_AUTH_BASICAUTH_PASSWORD` is used if BASIC auth password is omitted in YAML.

## Optional: Google OAuth2 for CalDAV

If using `GOOGLE_OAUTH2` auth:

- Put `client_secret.json` in `conf/` (or the same directory as your config file).
- Pre-authorize credentials:

```bash
./gradlew :app:run -PmainClass=todoistcaldavsync.GoogleAuthProvider --args='-f conf/todoist-calendar-sync.conf.yaml -l conf/log4j.groovy'
```

## Where to look when something fails

- Console logs from the run command.
- Rolling file log: `logs/todoist-ical-sync.log`.
- Full legacy-sync configuration and behavior reference: `README.md`.
- SmartPlanner configuration reference: `docs/SMART_PLANNER_CONFIGURATION.md`.

## SmartPlanner quick start

SmartPlanner production is a long-running daemon; `preview`, `capacity`, and other one-shot operations
remain rollout and diagnostic controls. Copy `conf/todoist-planner.conf.example.yaml`, set the explicit
Todoist endpoint, select exactly one `calendar.provider`, set that provider's references and managed
`output_calendar`, and configure four
state directories. Keep `planner.mode: preview`, `planner.daemon.enabled: false`,
`planner.messaging.enabled: false`, `planner.ai.enabled: false`, and `planner.weather.enabled: false`
for the first crawl.

```bash
export TODOIST_ACCESS_TOKEN='<isolated-test-token>'
export CALDAV_PLANNED_PASSWORD='<isolated-test-calendar-password>'

./gradlew installDist
./app/build/install/todoist-caldav-sync/bin/todoist-caldav-sync \
  -f conf/todoist-planner.conf.yaml -l conf/log4j.groovy \
  --operation preview \
  --range-start 2026-08-14T00:00:00Z \
  --range-end 2026-08-17T00:00:00Z
```

Preview performs Todoist/selected-calendar-provider reads and local plan persistence only. Inspect the emitted plan ID,
hash/diff, calendar classification, unscheduled tasks, and plan file before enabling a write-capable
mode.

### Optional: SmartPlanner Google Calendar API bootstrap

For Google, select `planner.integration.calendar.provider: google_calendar_api`, remove the CalDAV
provider section, and use ignored local references for the desktop OAuth client, normal event-only
token store, distinct QA calendar-management token store, and returned calendar IDs. Google account
passwords, app passwords, inline OAuth values, and static access tokens are not SmartPlanner Google
configuration. Keep `.qa/secrets/`, `.qa/tokens/`, and `.qa/state/` owner-private.

Only after implementation review and authorization for live credential use, normal event operations
use a fresh event-only bootstrap:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-bootstrap
```

It accepts Google OAuth configuration before calendar IDs exist, listens only on
`127.0.0.1:8787` by default, writes only the normal token store, and exits without planner,
provisioning, or daemon work. For a remote browser, first run
`ssh -N -L 8787:127.0.0.1:8787 hermes@<host>` (substitute the configured callback port), then open
the printed URL locally. Do not copy a callback code into Slack or a terminal.

The initial confirmed legacy credential is eligible only for the QA store:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-import-legacy-qa \
  --confirm-legacy-qa-import --input-reference .qa/secrets/legacy-qa-credential.json
```

It validates account and exact QA scope, writes neither the normal store nor calendar resources, and
exits. On validation failure, stop. With an operator available, the separate consent alternative is:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-bootstrap-qa
```

QA bootstrap writes only the QA token store and exits without provisioning. Then explicitly list and
provision the dedicated account:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-qa-calendars-list --confirm-dedicated-qa-account
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-qa-calendars-provision --confirm-dedicated-qa-account \
  --qa-calendar 'output|managed_output|SmartPlanner QA Output;blockers|hard_blocker|SmartPlanner QA Blockers'
```

Returned IDs are written only to ignored `.qa/state/calendar-ids.json`; add them to the ignored QA
config before normal planner operations. Never paste OAuth material, consent URLs/codes, tokens,
passwords, Slack secrets, or authorization headers into Slack, logs, screenshots, or evidence.

### SmartPlanner modes

Set `planner.mode` in `conf/todoist-planner.conf.yaml`, generate a new preview after changing it, and
use the matching procedure below. A stored plan retains the mode under which it was created.

| Mode | How to use it | Remote-write behavior |
| --- | --- | --- |
| `preview` | Run `capacity` and `preview` only. | Never writes Todoist or CalDAV. `apply` and `apply-safe` refuse. |
| `approval_required` | Preview, create an approval matching the stored plan ID, version, and full hash, then run `apply --plan-id ID --approval FILE`. | Writes only after exact approval; missing, stale, or mismatched approvals refuse. |
| `apply_safe_changes` | Preview, inspect the diff, then run `apply-safe --plan-id ID` (or `apply` for the stored mode). | Writes ordinary safe changes only; protected, frozen, manual, drifted, and approval-required changes are withheld. |
| `fully_automated` | Do not use. | Unavailable by design; all apply paths refuse with zero writes. |

#### Read capacity and create a preview

```bash
todoist-caldav-sync -f conf/todoist-planner.conf.yaml -l conf/log4j.groovy \
  --operation capacity --range-start 2026-08-14T00:00:00Z \
  --range-end 2026-08-17T00:00:00Z --format markdown

todoist-caldav-sync -f conf/todoist-planner.conf.yaml -l conf/log4j.groovy \
  --operation preview --range-start 2026-08-14T00:00:00Z \
  --range-end 2026-08-17T00:00:00Z
```

#### Apply in `approval_required` mode

Create an approval JSON or YAML file containing the exact stored plan identity and approval
metadata, then run:

```bash
todoist-caldav-sync -f conf/todoist-planner.conf.yaml -l conf/log4j.groovy \
  --operation apply --plan-id PLAN_ID --approval approval.json
```

First verify that omitting the approval and supplying a stale/mismatched approval both produce
refused receipts and zero remote writes. See the end-to-end guide for the approval fixture and gates.

#### Apply in `apply_safe_changes` mode

```bash
todoist-caldav-sync -f conf/todoist-planner.conf.yaml -l conf/log4j.groovy \
  --operation apply-safe --plan-id PLAN_ID
```

Inspect the receipt to confirm that only ordinary changes were applied and all protected changes were
withheld. Back up Todoist, the managed calendar, and all four SmartPlanner state directories together
before either write-capable mode.

If a write is unexplained or indeterminate, stop, preserve evidence, reconcile live state, restore the
matching provider exports and all four state directories together, and return to `preview`; do not
delete state and retry. To retire Google access, stop the process, revoke the app grant in the
dedicated Google account, remove both ignored token stores/client material, and separately restore any
calendar data because token revocation does not roll back provider mutations.

#### Start the long-running daemon

After the isolated preview/apply gates pass, import `conf/smartplanner-slack-app-manifest.example.yaml`, invite the
app to the configured channel, enable `planner.daemon` plus Slack `socket_mode`, and run:

```bash
export SLACK_BOT_TOKEN='xoxb-...'
export SLACK_APP_TOKEN='xapp-...'
todoist-caldav-sync -f conf/todoist-planner.conf.yaml -l conf/log4j.groovy \
  --operation planner-daemon
```

Use `/smartplanner plan daily`, `/smartplanner status`, or `/smartplanner help`. Proposals appear as
channel messages; approvals, rejection, and iterative feedback belong in each proposal thread. See
`docs/SLACK_INTEGRATION.md` for scopes, events, regex actions, bot status, and restart tests. The
complete one-shot command reference remains in `README.md`.
