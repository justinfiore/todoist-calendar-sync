# Slack Socket Mode integration

SmartPlanner uses Slack Socket Mode for its long-running Messaging Surface. Socket Mode opens an
outbound WebSocket from SmartPlanner to Slack, so the operator does **not** expose a public inbound
port or Request URL. Provider callbacks are acknowledged only after bounded local queue admission.
When the queue is saturated, event callbacks return non-success so Socket Mode can redeliver; slash
commands receive an explicit busy response and can be retried by the operator.

Authoritative references:

- [Using Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/)
- [Java Slack SDK Socket Mode](https://docs.slack.dev/tools/java-slack-sdk/guides/socket-mode/)
- [`assistant.threads.setStatus`](https://docs.slack.dev/reference/methods/assistant.threads.setStatus/)
- [Slash commands](https://docs.slack.dev/interactivity/implementing-slash-commands/)

## Register the app

1. Copy/import `conf/smartplanner-slack-app-manifest.example.yaml` when creating a Slack App.
2. The default registered app and bot display name is **SmartPlanner**. To rename it, edit both
   `display_information.name` and `features.bot_user.display_name` before importing/updating the
   manifest. Set the same operator-facing label in `planner.messaging.app_name`.
3. Keep Socket Mode and interactivity enabled. No inbound Request URL is needed.
4. Install the app to the workspace and invite it to the configured channel.
5. Create an app-level token with `connections:write` and store the `xapp-*` value only in the
   environment variable named by `app_token_env`.
6. Store the bot `xoxb-*` token only in the environment variable named by `bot_token_env`.

The example manifest requests `app_mentions:read`, `assistant:write`, `channels:history`, `chat:write`,
and `commands`; it subscribes to `app_mention` and `message.channels`. For a private destination,
add `groups:history` and `message.groups`. Do not grant private-channel scopes unless needed.

## Configure SmartPlanner

```yaml
planner:
  daemon:
    enabled: true
    startup_connectivity_check: true
    shutdown_timeout: PT20S
    planning_runs:
      - name: daily
        horizon: P3D
        interval: PT6H
        initial_delay: PT0S
        run_on_startup: true
      - name: weekly
        horizon: P7D
        interval: P1D
        initial_delay: PT5M
        run_on_startup: true

  messaging:
    enabled: true
    provider: slack
    slack_mode: socket_mode
    destination: C0123456789
    app_name: SmartPlanner
    command: /smartplanner
    bot_token_env: SLACK_BOT_TOKEN
    app_token_env: SLACK_APP_TOKEN
    working_status: "is working on your request…"
    loading_messages:
      - "is planning…"
      - "is checking Todoist and calendar capacity…"

  integration:
    feedback:
      allowed_actors: [U0123456789]
      rules:
        - name: approve
          pattern: "(?i)^\\s*(yes|approve|looks good)\\s*$"
          action: approve
        - name: reject
          pattern: "(?i)^\\s*(no|reject)(?:\\s+(?<reason>.*))?$"
          action: reject
        - name: replan
          pattern: "(?i)^\\s*(replan|try again)(?:\\s+(?<feedback>.*))?$"
          action: replan
```

Patterns are ordered Java regular expressions and use full-string matching; the first match wins.
Invalid patterns fail configuration validation. Unknown actors fail closed. Inline token fields are
rejected.

## Commands and conversations

The default slash command is `/smartplanner`:

- `/smartplanner plan [run-name]` initiates one configured horizon immediately; omitting the name initiates all configured runs.
- `/smartplanner replan run-name [feedback]` creates a linked iteration in that run's active proposal thread.
- `/smartplanner status` reports daemon run state.
- `/smartplanner help` prints command and thread-feedback help.

`@SmartPlanner plan daily`, `replan daily prefer mornings`, `status`, and `help` are also accepted through app mentions. Slash-command
callbacks are acknowledged before planning begins. Because slash commands have no parent message,
SmartPlanner posts a command receipt and uses its thread for working status.

Each plan proposal is a new message in `planner.messaging.destination`. Feedback for that proposal is
accepted only in its Slack thread. A replan reply publishes iteration 2, 3, and later in the **same**
thread while retaining the exact previous/current plan and proposal identities. Unrelated channels,
root messages, bot messages, unauthorized actors, duplicate Slack events, and stale/unknown threads
cannot apply changes.

Regex actions are `acknowledge`, `approve`, `reject`, `replan`, `apply_safe`, `status`, and `help`.
Rules may include deterministic conversation-scoped `horizon`, `priority_overrides`,
`exclude_task_ids`, and `freeze_task_ids`. If AI and
`conversational_feedback_interpretation`/`temporary_planning_overrides` are explicitly enabled, an
unmatched bounded thread reply may be interpreted into validated structured feedback and temporary
planning overrides. The suggestion is persisted against the exact current plan and expires after 15
minutes. SmartPlanner posts a confirmation summary and performs no replan/apply until an authorized
follow-up matches the configured deterministic action phrase. Stale, expired, mismatched, unknown-task,
or out-of-range overrides fail closed. The LLM has no direct mutation port.

## Working status

For Slack-originated work, SmartPlanner calls `assistant.threads.setStatus` with the channel, thread,
status, and optional loading messages. Slack expires a status after two minutes and clears it when the
app replies; SmartPlanner also clears it explicitly. Status failures are logged and non-fatal so an
unsupported workspace/app configuration does not terminate planning or bypass safety.

## Lifecycle and failure behavior

Start the primary production process with:

```bash
export SLACK_BOT_TOKEN='xoxb-...'
export SLACK_APP_TOKEN='xapp-...'
todoist-caldav-sync -f conf/todoist-planner.conf.yaml -l conf/log4j.groovy \
  --operation planner-daemon
```

Startup configuration errors and failed Todoist/CalDAV connectivity checks terminate startup before
Socket Mode scheduling. After startup, a failed planning cycle, Slack send/status failure, malformed or
unauthorized feedback, or LLM failure is contained and the scheduler remains alive for the next run.
SIGTERM/SIGINT stops new work, waits up to `shutdown_timeout`, closes Socket Mode, and exits.

Conversation correlation and event deduplication are atomically persisted below the configured
`deliveries_dir`. Inbound events move through durable `PENDING`/`PROCESSING`/`COMPLETED` states,
failed callback work is retried locally, and interrupted payloads are recovered after restart. Revised
proposals block the prior plan identity before the new Slack message is exposed. Socket readiness is
reported from the live SDK connection probe rather than process-start state. Restart restores proposal
threads and exact plan identity. Back up all four state directories together.

## Acceptance test

Use isolated Todoist, CalDAV, and Slack data. Verify:

1. Startup performs read-only Todoist and CalDAV probes and opens no inbound port.
2. `plan daily` and `plan weekly` produce channel-root proposals with different configured horizons.
3. A thread replan produces a revised proposal in the same thread and no remote writes.
4. Unauthorized, duplicate, bot, unrelated-channel, root, stale, and unknown-thread feedback writes
   nothing.
5. Exact approval applies once through the normal safety gates; reject/acknowledge write nothing.
6. Kill/restart before feedback; the same thread still resolves to the exact proposal.
7. Status appears while Slack-requested work runs and is cleared after the reply.
8. Logs and state contain no `xoxb-*`, `xapp-*`, webhook URL, or other credential.
