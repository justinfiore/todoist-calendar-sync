# Repeatable orb SmartPlanner QA

## Why

SmartPlanner's live QA evidence and optional-integration instructions exist, but replaying the campaign still requires manually assembling a complete configuration and reconciling several runbooks. Preserve the owner's selected environment and codify safe setup, verification, and evidence collection before the next live campaign.

## What Changes

- Add a reusable, secret-free Google Calendar QA profile and helper commands that initialize an isolated ignored workspace without overwriting an existing run.
- Record the 2026-10-25 owner decisions: Google Calendar API only (no separate CalDAV campaign), Open-Meteo at the owner-supplied coordinates 40.7253341, -80.0984618 in the ZIP 16066 area (America/New_York), and xAI subscription OAuth via device flow in the orb. These exact coordinates supersede the earlier ZIP-center approximation.
- Keep optional integrations disabled initially; select `grok_build_subscription`, with no API-key or OpenAI fallback, and a dedicated protected auth root outside planner state and the repository.
- Reuse and organize existing hermetic contracts for Google/Todoist, weather, Slack, xAI OAuth/inference, daemon/confirmation, recurrence, and writeback; add tests only for newly introduced QA-helper behavior or demonstrated missing public contracts.
- Provide explicit stages for local initialization/preflight, separately authorized live reads/auth, suggestion-only checks, Slack/combined feedback, scoped writes, restart/recovery, and owned-resource cleanup.
- Define repeatable evidence capture and verification without recording credentials, device codes, raw model prompts/responses, or provider identities. Preserve previous committed evidence unchanged.
- Integrate the Slack manifest-generation command after its separately owned branch/PR is available; do not duplicate its implementation here.

## Capabilities

### New Capabilities

None. This is QA tooling, configuration examples, tests, and operator documentation, not a change to application behavior. `skip_specs: true` declares that boundary.

### Modified Capabilities

None. Existing provider, authentication, confirmation, recurrence, and writeback contracts remain unchanged.

## Impact

Expected changes are under `.qa.example/`, `scripts/`, `docs/`, and focused helper/configuration tests under `app/src/test/`. Reuse the existing Gradle/Spock and installed-distribution workflows; introduce no new runtime framework. The existing `.qa.example/planner-google-qa.yaml` is a partial bootstrap example, not a complete runnable planner profile. Existing `scripts/recurrence-sync-contract.sh` remains a hermetic contract runner and refuses live mode.

No credential inspection, live login, remote fixture creation, deployment, provider mutation, or deletion is authorized by this proposal. Account identity, selected entitled xAI model, Slack channel/actor IDs, and disposable resource inventories remain operator-supplied or live-verified inputs. Never treat the presence of `TODOIST_ACCESS_TOKEN` as proof that it belongs to a disposable account.
