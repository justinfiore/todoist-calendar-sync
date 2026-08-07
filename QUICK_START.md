# Quick Start

This guide gets `todoist-calendar-sync` running with minimal setup.

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
- Full configuration and behavior reference: `README.md`.
