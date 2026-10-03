# Testing Slack, Weather, and AI integrations

This guide configures and tests SmartPlanner's optional integrations in an isolated environment. Test
them one at a time in this order:

1. establish a common `capacity`/`preview` baseline;
2. test Open-Meteo weather;
3. test standalone AI suggestions;
4. test Slack Socket Mode;
5. test combined Slack + AI feedback only after Slack and AI pass independently.

Use disposable Todoist tasks, a dedicated output calendar, a private Slack test channel, and separate
planner state directories. Do not start with production data.

## Safety boundary

`planner.mode: preview` prevents the normal `apply` operation from writing Todoist or the calendar. It
is **not** a global no-write switch:

- `capacity` reads providers and writes nothing remotely.
- `preview` reads providers and persists a local plan, but writes nothing remotely.
- Weather reads Open-Meteo and only changes proposal feasibility/scoring.
- `ai-suggest` sends bounded, redacted context to the configured LLM and persists audit metadata; AI
  output has no direct Todoist or calendar mutation authority.
- Slack testing writes messages/status to Slack and persists local delivery/conversation state.
- An explicit `apply-safe` command or Slack `apply_safe` action can write ordinary safe Todoist and
  calendar changes even when `planner.mode` is `preview`.
- If `planner.tasks.recurrence.enabled: true`, `planner-daemon` may update Todoist Deadline, labels,
  and lifecycle metadata independently of planner mode.
- `fully_automated` is unavailable and refuses writes.

For the initial integration tests, keep recurrence disabled, do not run `apply` or `apply-safe`, and do
not configure approval/apply-safe Slack phrases until the read-only interaction tests pass.

## Required material

| Integration | Material | Secret? | Where it comes from |
| --- | --- | --- | --- |
| Common planner | `TODOIST_ACCESS_TOKEN` and the selected calendar provider credentials | Yes | Existing SmartPlanner setup; use disposable QA accounts/data |
| Slack | `SLACK_BOT_TOKEN` (`xoxb-*`) | Yes | Slack app installation under **OAuth & Permissions** |
| Slack | `SLACK_APP_TOKEN` (`xapp-*`) | Yes | Slack app **Basic Information > App-Level Tokens**, with `connections:write` |
| Slack | Channel ID such as `C012...` | No | Copy the channel link/details and use its `C...` or `G...` ID |
| Slack | Allowed user IDs such as `U012...` | No | Slack profile menu: **Copy member ID** |
| Weather | Latitude, longitude, and IANA timezone | No | Coordinates for the location whose forecast should constrain tasks |
| AI | `OPENAI_API_KEY` (or the equivalent key for an approved compatible endpoint) | Yes | Provider's API-key dashboard |
| AI | HTTPS endpoint, exact hostname, and model ID | No | Provider documentation/account |

If running QA in an Amp orb, create Project Secrets with the exact names shown above. Store only raw
token/key values—no quotes, YAML, JSON, or Base64 encoding is needed. If running as a local service,
inject these environment variables through the service manager's protected environment/credential
facility. Never put secret values in YAML, Git, shell scripts, screenshots, Slack messages, or QA
evidence.

Verify presence without printing values:

```bash
test -n "${TODOIST_ACCESS_TOKEN:-}" && echo 'Todoist token present'
test -n "${SLACK_BOT_TOKEN:-}" && echo 'Slack bot token present'
test -n "${SLACK_APP_TOKEN:-}" && echo 'Slack app token present'
test -n "${OPENAI_API_KEY:-}" && echo 'AI key present'
```

## 1. Establish an isolated planner baseline

Copy `conf/todoist-planner.conf.example.yaml` to a private, ignored configuration file. Complete the
Todoist, calendar, availability, task, and four state-directory sections as described in
`SMART_PLANNER_CONFIGURATION.md`. Use state directories that no other installation uses.

Start with these controls:

```yaml
planner:
  mode: preview

  daemon:
    enabled: false

  tasks:
    scheduling_eligible_labels: [schedule]
    manual_label: manual
    recurrence:
      enabled: false

  messaging:
    enabled: false

  weather:
    enabled: false

  ai:
    enabled: false
```

Create a few synthetic Todoist tasks carrying only the labels needed by each test. Use a narrow UTC
range that includes configured availability:

```bash
todoist-caldav-sync -f /private/path/planner-qa.yaml -l conf/log4j.groovy \
  --operation capacity \
  --range-start 2026-10-05T00:00:00Z --range-end 2026-10-08T00:00:00Z

todoist-caldav-sync -f /private/path/planner-qa.yaml -l conf/log4j.groovy \
  --operation preview \
  --range-start 2026-10-05T00:00:00Z --range-end 2026-10-08T00:00:00Z
```

Replace the example dates with a current future range. Save the resulting plan ID. Before enabling an
optional integration, verify that task count, calendar inventory, timezone, availability, and proposed
placements are expected and that Todoist/calendar inventories are unchanged.

## 2. Test Weather independently

Open-Meteo does not require an API key. Configure its explicit endpoint plus location policy:

```yaml
planner:
  integration:
    weather:
      base_url: https://api.open-meteo.com/v1/forecast
      timeout: PT10S
      max_response_bytes: 1048576

  weather:
    enabled: true
    provider: open_meteo
    latitude: 40.7128
    longitude: -74.0060
    timezone: America/New_York
    max_age: PT6H
    forecast_horizon_days: 7
    fallback: fail_closed
    suitability_bonus: 35
    task_rules:
      - name: outdoor-dry
        match_labels: [outdoor]
        require:
          precipitation_probability_max: 25
          precipitation_mm_max: 0.5
          wind_speed_kph_max: 25
        preferred:
          daylight: true
```

Replace coordinates/timezone with the real test location. Rules are ordered; the first rule whose
`match_labels` intersects a task's labels (case-insensitively) wins. Start with `fail_closed`, which
keeps a weather-sensitive task unscheduled if forecast data is unavailable, stale, or incomplete.

Create two otherwise comparable tasks inside the forecast horizon:

- one with `schedule` and `outdoor`;
- one with `schedule` and no weather label.

Run `preview` twice with Weather enabled, then once with `planner.weather.enabled: false` over the same
range and inputs. Pass the Weather gate only if:

- only the labeled task is weather-constrained;
- the explanation records the matched rule and relevant forecast/fallback outcome;
- repeated enabled previews are deterministic for the same retrieved forecast;
- disabling Weather returns the non-weather planning behavior;
- provider failure or stale/missing data fails closed for the labeled task without affecting the
  unlabeled task;
- no Todoist/calendar mutation occurs.

Because the public forecast changes over time, use recorded fixtures for deterministic rain/clear,
malformed response, stale data, array mismatch, and DST-fold regression tests. A live run proves
connectivity and current-provider compatibility, not every weather condition.

## 3. Test AI independently

### Create and stage the API key

For OpenAI, create a restricted project API key in the provider dashboard and make sure its project
may use the selected model and structured JSON output. Store the raw key in `OPENAI_API_KEY`. For
another OpenAI-compatible provider, use a separate environment-variable name and substitute that name
below.

Configure AI with an exact HTTPS host allowlist:

```yaml
planner:
  ai:
    enabled: true
    provider: openai_compatible
    endpoint: https://api.openai.com/v1/chat/completions
    model: gpt-5-mini
    secret_env: OPENAI_API_KEY
    allowed_hosts: [api.openai.com]
    connect_timeout: PT5S
    request_timeout: PT30S
    max_request_bytes: 65536
    max_response_bytes: 65536
    max_items: 100
    max_string_chars: 500
    max_tokens: 1200
    allowed_suggestion_types:
      - task_suggestions
      - event_classification_suggestions
      - temporary_planning_overrides
      - conversational_feedback_interpretation
    redaction_enabled: true
    require_confirmation: true
    safety:
      never_apply_changes_directly: true
      require_structured_output: true
      require_confirmation_for_policy_changes: true
      send_minimum_necessary_data: true
```

Keep the allowlist to the exact endpoint hostname. Redirects, non-HTTPS endpoints, tool/function calls,
oversized bodies, and schema-invalid responses are rejected.

### Run suggestion-only QA

AI requires an existing locally persisted plan. Generate a fresh `preview`, then run each enabled type
with a unique, non-secret correlation ID:

```bash
todoist-caldav-sync -f /private/path/planner-qa.yaml -l conf/log4j.groovy \
  --operation ai-suggest --plan-id PLAN_ID \
  --ai-type task_suggestions --correlation-id ai-task-001

todoist-caldav-sync -f /private/path/planner-qa.yaml -l conf/log4j.groovy \
  --operation ai-suggest --plan-id PLAN_ID \
  --ai-type event_classification_suggestions --correlation-id ai-event-001

todoist-caldav-sync -f /private/path/planner-qa.yaml -l conf/log4j.groovy \
  --operation ai-suggest --plan-id PLAN_ID \
  --ai-type temporary_planning_overrides --correlation-id ai-override-001 \
  --feedback 'Prefer tomorrow morning for the deep-work task'

todoist-caldav-sync -f /private/path/planner-qa.yaml -l conf/log4j.groovy \
  --operation ai-suggest --plan-id PLAN_ID \
  --ai-type conversational_feedback_interpretation --correlation-id ai-feedback-001 \
  --feedback 'Please replan this for tomorrow morning'
```

The final two types are required only if they will be used with Slack. A valid provider reply must
still pass the repository's strict schema and identity checks. Pass the standalone AI gate only if:

- a valid structured response is accepted and bound to the exact plan/hash/input hash;
- suggestions do not change the stored plan, Todoist, calendar, configuration, or application state;
- audit data contains metadata/hashes, not raw prompts, responses, or credentials;
- sensitive fixture text is redacted before transmission;
- a stale plan identity, unallowlisted host, malformed/extra JSON, unknown ID, or provider error fails
  closed;
- rate-limit information is surfaced without a blind automatic retry.

Some negative cases require a controlled OpenAI-compatible mock because a live provider cannot be
instructed reliably to return malformed envelopes. Treat those mock tests as required complements to
the live connectivity test.

## 4. Test Slack independently

### Create the Slack app

1. In the Slack API app dashboard, create an app **from an app manifest** and import
   `conf/smartplanner-slack-app-manifest.example.yaml`.
2. Keep Socket Mode and interactivity enabled. Do not configure a public Request URL.
3. Under **Basic Information > App-Level Tokens**, create a token with `connections:write`; store its
   `xapp-*` value as `SLACK_APP_TOKEN`.
4. Under **OAuth & Permissions**, install the app to the test workspace; store the bot's `xoxb-*`
   token as `SLACK_BOT_TOKEN`.
5. Invite `@SmartPlanner` to a private test channel and copy that channel's ID.
6. Copy the member IDs of the people authorized to issue commands/feedback.

The supplied manifest requests:

- bot scopes: `app_mentions:read`, `assistant:write`, `channels:history`, `chat:write`, `commands`;
- events: `app_mention`, `message.channels`;
- slash command: `/smartplanner`;
- Socket Mode and interactivity.

For a private test channel, add `groups:history` to the bot scopes and `message.groups` to event
subscriptions, then reinstall the app if Slack requires it. Do not add broader scopes.

### Configure a non-applying first pass

Enable only help, status, and replan feedback initially. Leaving approval/apply-safe rules absent
prevents those phrases from matching deterministic write actions:

```yaml
planner:
  mode: preview

  tasks:
    recurrence:
      enabled: false

  daemon:
    enabled: true
    startup_connectivity_check: true
    shutdown_timeout: PT20S
    planning_runs:
      - name: daily
        horizon: P3D
        interval: PT6H
        initial_delay: PT0S
        run_on_startup: false

  messaging:
    enabled: true
    provider: slack
    slack_mode: socket_mode
    destination: C0123456789
    app_name: SmartPlanner
    command: /smartplanner
    bot_token_env: SLACK_BOT_TOKEN
    app_token_env: SLACK_APP_TOKEN
    enabled_kinds: [proposal]
    working_status: "is working on your request…"
    loading_messages:
      - "is planning…"
      - "is checking Todoist and calendar capacity…"

  integration:
    feedback:
      allowed_actors: [U0123456789]
      rules:
        - name: replan
          pattern: "(?i)^\\s*(replan|try again)(?:\\s+(?<feedback>.*))?$"
          action: replan
        - name: status
          pattern: "(?i)^\\s*status\\s*$"
          action: status
        - name: help
          pattern: "(?i)^\\s*help\\s*$"
          action: help
```

Replace the IDs. Patterns are ordered Java regular expressions with full-string matching; first match
wins. An empty `allowed_actors` list denies everyone.

Start the daemon in the foreground under the same process/service environment that contains the
tokens:

```bash
todoist-caldav-sync -f /private/path/planner-qa.yaml -l conf/log4j.groovy \
  --operation planner-daemon
```

Exercise both slash commands and mentions:

- `/smartplanner help` and `/smartplanner status`;
- `/smartplanner plan daily` and `@SmartPlanner plan daily`;
- reply `replan prefer mornings` in the resulting proposal thread;
- send the same reply at channel root, from an unauthorized user, and in another channel;
- restart the daemon, then continue in the existing proposal thread;
- send a duplicate event if the test harness can replay one;
- terminate with SIGTERM and verify graceful shutdown.

Pass the Slack gate only if:

- Socket Mode connects outbound without an inbound listener;
- commands acknowledge promptly and a proposal appears as a channel-root message;
- replan publishes the next iteration in the same thread without provider scheduling writes;
- working status appears during work and clears afterward;
- unauthorized, root, unrelated-channel, bot, stale, unknown-thread, and duplicate events do not
  trigger planning mutations;
- restart restores thread/plan correlation and event deduplication;
- Slack/API failures remain contained and the daemon continues;
- logs/state/evidence contain no `xoxb-*`, `xapp-*`, API key, authorization header, or webhook URL.

Only after that gate passes should you add `approve` or `apply_safe` rules. Test those against
disposable fixtures with before/after provider inventories; an accepted action must apply at most once,
and stale, duplicate, mismatched, or unauthorized feedback must write nothing.

## 5. Test combined Slack + AI feedback

Keep the Slack configuration, enable AI, and ensure these two types are allowlisted:

```yaml
planner:
  ai:
    enabled: true
    allowed_suggestion_types:
      - temporary_planning_overrides
      - conversational_feedback_interpretation
```

In an active proposal thread, send an unmatched natural-language reply such as “Could you move the
deep-work task to tomorrow morning?” SmartPlanner may ask the LLM to interpret the feedback and create
bounded temporary overrides. It must then post a deterministic confirmation summary. An authorized
user must respond with the configured deterministic confirmation phrase within 15 minutes before any
replan/apply action occurs.

Pass the combined gate only if:

- unmatched text invokes AI only in the correct active proposal thread;
- the resulting suggestion references only known tasks/events and remains within configured bounds;
- no action occurs before deterministic confirmation;
- unauthorized, stale, expired, unknown-task, out-of-range, plan-mismatched, and duplicate
  confirmations fail closed;
- a confirmed replan creates a linked iteration in the same Slack thread;
- provider or schema failure produces a safe user-visible failure and the daemon stays alive;
- no model output directly calls Todoist, calendar, Slack, configuration, or application gateways.

Do not introduce an approval/apply-safe rule during this phase unless the test explicitly includes
authorized disposable provider mutation and exact before/after inventories.

## Evidence, cleanup, and rollback

For every phase, record:

- commit/config revision, operation, range, plan ID, and correlation IDs;
- redacted command output and logs;
- provider inventories before and after;
- expected versus actual behavior for each positive and negative case;
- cleanup results and any limitation that prevented a case from running.

Do not capture environment dumps, secret files, authorization headers, raw LLM prompts/responses,
OAuth URLs, Slack tokens, API keys, or personal account identifiers. Scan evidence for common token and
credential patterns before committing it.

Rollback is configuration-first:

1. stop `planner-daemon`;
2. set `planner.messaging.enabled`, `planner.weather.enabled`, and `planner.ai.enabled` to `false`;
3. set `planner.daemon.enabled: false`;
4. keep recurrence disabled unless its separate rollout has been completed;
5. restart only the intended operation (`legacy-sync`, or one-shot `capacity`/`preview`);
6. revoke Slack/OpenAI test tokens if exposed or no longer needed;
7. delete only owned QA messages/tasks/events and preserve the four state directories together if
   evidence or restart analysis is still needed.

See `SLACK_INTEGRATION.md`, `WEATHER_INTEGRATION.md`, `LLM_INTEGRATION.md`, `AI_ASSISTANCE.md`, and
`PLANNER_END_TO_END_TESTING.md` for the underlying contracts and broader acceptance matrices.
