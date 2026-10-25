# Design

## Context

The production entry point requires `-f` and `-l` before operation dispatch. The existing YAML example already describes the Socket Mode bot, assistant status capability, and slash command. See proposal.md for motivation.

## Goals / Non-Goals

**Goals:** Generate standalone installation YAML using the installed launcher in an empty working directory and home, without credentials or configuration.

**Non-Goals:** Registering/installing Slack apps, obtaining tokens, inspecting destinations remotely, or changing daemon behavior.

## Decisions

- Dispatch `slack-app-manifest` before config/logging validation. It never constructs an orchestrator, authentication service, or messaging surface.
- Package the existing `conf` example via `processResources` instead of duplicating a manifest in code or relying on the current working directory.
- Render with the existing YAML library to safely quote customized names. Use stdout and shell redirection rather than adding output-file semantics.
- Expose `--slack-app-name`, `--slack-command`, and `--slack-channel-types public|private|both`. Defaults preserve SmartPlanner, `/smartplanner`, and public-only access. Private-only replaces public history/event permissions; both explicitly grants both. Keep common scopes and outbound-only settings unchanged.
- Refuse unrelated operation flags during generation and manifest-only flags during other operations, so setup mistakes do not silently start providers.
- Slack's current manifest reference limits app names to 35 characters and bot names to a narrower alphabet. Derive a lowercase safe bot name, falling back to `smartplanner` when no usable characters remain. Update the shared template to `agent_view` because new apps cannot import the old `assistant_view`. This changes installation material, not daemon messaging behavior.

## Risks / Trade-offs

- Offline generation cannot prove Slack accepts a workspace's app policies → document owner import/review; do not contact Slack during generation.
- App and command labels must agree with runtime configuration → document matching `planner.messaging.app_name` and `command`.
