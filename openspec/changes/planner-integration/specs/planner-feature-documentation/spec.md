## ADDED Requirements

### Requirement: README and quick start present daemon-first SmartPlanner
The README and quick start SHALL identify `planner-daemon` as the primary SmartPlanner production lifecycle, explain that it remains running, preserve `legacy-sync` and one-shot controls, summarize multiple horizons and threaded messaging, and link every detailed guide with valid relative paths.

#### Scenario: Operator finds the production entry point
- **WHEN** an operator reads the SmartPlanner introduction
- **THEN** they SHALL find the daemon command, readiness/fatal behavior, safe default, config template, Slack App setup, commands, state locations, and crawl/walk/run guide without searching source code

### Requirement: Configuration reference covers every daemon contract
The annotated YAML and SmartPlanner configuration guide SHALL document every daemon lifecycle, planning-run, horizon/interval, retry, startup probe, shutdown, concurrency/coalescing, Slack Socket Mode, app name/command/channel/token, status, regex rule, temporary override, authorization, LLM confirmation, and state key including type/default/bounds/interactions/failures.

#### Scenario: Operator configures multiple horizons
- **WHEN** the operator copies documented daily/weekly/medium examples
- **THEN** the configuration SHALL parse, each run SHALL have independent horizon/interval behavior, and invalid duplicates/durations SHALL have documented fail-fast errors

#### Scenario: Unsafe messaging config is supplied
- **WHEN** daemon mode is configured with webhook-only Slack, inline tokens, missing app/bot token references, invalid regexes, or no receive-capable channel
- **THEN** docs SHALL state the startup rejection and exact remediation

### Requirement: Slack guide is complete for Socket Mode and conversations
The Slack guide SHALL use authoritative Slack documentation links and cover manifest creation, default/custom app naming, `connections:write` app token, bot token/scopes/events, Socket Mode/no public Request URL, command setup, configured channel, parent proposals, thread replies/iterations, actor authorization, dedupe/reconnect, working status via `assistant.threads.setStatus`, rate limits, testing, troubleshooting, and disable/rollback.

#### Scenario: Operator creates the default app
- **WHEN** the operator registers the supplied manifest without editing names
- **THEN** the Slack app and bot SHALL be named SmartPlanner and expose `/smartplanner`

#### Scenario: Operator tests commands and threads
- **WHEN** setup is complete in an isolated channel
- **THEN** the guide SHALL provide commands and pass/fail observations for plan/replan/status/help, proposal parent identity, thread-only feedback, multiple iterations, status set/clear, unauthorized messages, and no inbound listener

### Requirement: LLM guide covers conversational feedback safely
The LLM guide SHALL document optional unmatched-feedback interpretation, bounded/redacted thread context, strict action/override schemas, confirmation wording, exact plan binding, expiry, audit, zero direct mutation authority, provider failure behavior, and disable steps.

#### Scenario: Natural-language feedback is tested
- **WHEN** an operator enables AI in an isolated thread and posts unmatched feedback
- **THEN** the guide SHALL require a confirmation summary, zero replan/apply before deterministic confirmation, bounded new iteration afterward, and unchanged Todoist source/config

### Requirement: End-to-end guide validates a supervised service
The end-to-end guide SHALL include service foreground/system supervision examples, startup probes/readiness, scheduled and Slack-triggered runs, status/health, non-fatal retry, fatal termination, restart recovery, graceful shutdown, backups, observations, acceptance gates, and rollback.

#### Scenario: Daemon smoke test is followed
- **WHEN** an operator executes the documented isolated smoke procedure
- **THEN** it SHALL verify the process stays alive across at least two triggers and one handled failure, persists conversations/dedupe, accepts thread feedback, and shuts down cleanly

### Requirement: Examples and app artifacts are reproducible and secret-safe
Every command, manifest, YAML, environment variable, Gradle task, launcher option, scope, event, and API method SHALL match implementation. Examples SHALL use fake placeholders, tracked files SHALL contain no production/personal secrets, and documentation SHALL distinguish automated from live verification.

#### Scenario: Documentation verification runs
- **WHEN** implementation is complete
- **THEN** maintainers SHALL verify Markdown links, YAML/manifest parsing, CLI help, Slack command names/scopes/events, config coverage, source URLs, and tracked-file cleanliness
