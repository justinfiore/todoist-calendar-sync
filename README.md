

# todoist-calendar-sync

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/justinfiore/todoist-calendar-sync)

Sync Todoist tasks into one or more CalDAV calendars.

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

- Java 8+ (JVM compatible with this Gradle/Groovy build)
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
