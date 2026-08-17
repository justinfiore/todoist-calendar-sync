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
Todoist/CalDAV endpoints, credential environment-variable names, managed `output_calendar`, and four
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

Preview performs Todoist/CalDAV reads and local plan persistence only. Inspect the emitted plan ID,
hash/diff, calendar classification, unscheduled tasks, and plan file before enabling a write-capable
mode.

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
