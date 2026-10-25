# Proposal

## Why

Slack setup currently requires hand-editing a static manifest before credentials exist. An offline generation operation will make initial installation and private-channel QA repeatable without starting providers.

## What Changes

- Add credential-free `slack-app-manifest` operation emitting importable YAML to stdout.
- Allow app naming, slash-command naming, and public/private/both channel permissions with least-privilege defaults.
- Package the existing example as the source template and document generation followed by owner-controlled Slack import.

## Capabilities

### New Capabilities

- `slack-app-manifest`: Offline Slack installation manifest generation.

### Modified Capabilities

None.

## Impact

Primary CLI dispatcher, packaged resources, Slack setup documentation, and focused launcher tests. No new dependencies, provider behavior changes, Slack registration, or installation.
