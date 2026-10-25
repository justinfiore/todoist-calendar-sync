# Spec Delta

## Purpose

Provide repeatable, offline Slack app installation manifests before SmartPlanner provider configuration or credentials exist.

## ADDED Requirements

### Requirement: Credential-free manifest generation
The primary installed launcher SHALL support `--operation slack-app-manifest` without config, logging files, or provider credentials. It SHALL emit only importable YAML on stdout and SHALL NOT contact providers, register an app, start a daemon, or create state.

#### Scenario: Empty environment
- **WHEN** generation runs from an empty directory with a clean home and no provider environment variables
- **THEN** it exits zero with a SmartPlanner Socket Mode manifest and no state files or diagnostics

### Requirement: Explicit least-privilege customization
Generation SHALL default to SmartPlanner and `/smartplanner`, support app and command naming, and accept public, private, or both channel types. It SHALL pair channels:history with message.channels and groups:history with message.groups only for selected types, retain common bot scopes, and omit inbound Request URLs. App names SHALL be 1–35 characters with a derived Slack-safe lowercase bot name. New-app manifests SHALL use agent_view rather than assistant_view.

#### Scenario: Private QA channel
- **WHEN** generation selects private channels and a custom app name
- **THEN** the app name matches that name, the bot name is derived using lowercase safe characters, and the manifest contains groups:history and message.groups but not channels:history or message.channels

#### Scenario: Public defaults and mixed access
- **WHEN** generation uses defaults or explicitly selects both
- **THEN** defaults grant public history only, while both grants both matching history scopes and events

### Requirement: Invalid setup fails without provider activity
Generation SHALL reject invalid names, commands, channel types, and unrelated operation flags with exit code 2 and no manifest. Other operations SHALL reject manifest-only options.

#### Scenario: Invalid permission choice
- **WHEN** generation selects an unknown channel type
- **THEN** it exits 2 with a useful diagnostic and no YAML or provider activity
