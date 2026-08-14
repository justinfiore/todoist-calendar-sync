## Why

Planner Phases 1–6 provide deterministic scheduling, guarded application, weather awareness, Slack messaging and feedback, and bounded LLM assistance, but those capabilities are not yet composed into the existing `TodoistCalDavSync` production entry point. Operators also need hermetic provider-boundary coverage and an explicit, reversible crawl/walk/run procedure before any production data is changed.

## What Changes

- Add a production composition root and operations on the existing main application for live capacity reporting, deterministic preview generation, stored-plan application, safe-only application, Slack delivery/feedback, explicit decision application, and bounded LLM suggestions while preserving the legacy sync operation.
- Add explicit production configuration for Todoist, all relevant CalDAV calendars, durable planner state, feedback authorization, weather, Slack, and LLM integrations. Credentials remain environment-variable references; raw secrets are rejected.
- Enforce the supported safety progression: `preview` (no remote writes), `approval_required` (exact plan-bound approval), and `apply_safe_changes` (ordinary changes only; protected changes withheld). `fully_automated` remains unsupported and fails closed with zero writes.
- Add fixture-driven Spock integration tests and WireMock tests for every HTTP boundary: Todoist reads and due-time writes, CalDAV reads/UID lookup/managed writes/deletes, Open-Meteo, Slack webhook and chat API, and the OpenAI-compatible LLM endpoint.
- Add orchestration and CLI tests covering preview no-write behavior, exact approval binding, safe-only application, idempotency, partial/ambiguous provider outcomes, ownership and UID collision checks, unauthorized feedback, disabled integrations, and automatic-mode refusal.
- Add an end-to-end testing guide covering isolated test Todoist and calendar accounts, backups, fixtures, acceptance gates, rollback, and the first production crawl/walk/run progression.
- Expand the README, quick start, annotated example configuration, and configuration reference. Add and link feature-specific Slack, LLM, and Weather guides.

## Capabilities

### New Capabilities

- `planner-main-application-integration`: Production composition and CLI operations that expose Phases 1–6 through the existing application without weakening planner authority or legacy behavior.
- `planner-http-integration-testing`: Hermetic fixture and WireMock verification of all planner-related outbound HTTP contracts and failure semantics.
- `planner-operational-rollout`: Manual isolated-environment testing and reversible first-production-run procedures with explicit safety gates.
- `planner-feature-documentation`: Detailed configuration reference and linked Slack, LLM, and Weather feature guides.

### Modified Capabilities

None. This repository has no existing OpenSpec baseline capabilities; this change introduces the integration contracts as new capabilities.

## Impact

- Main entry point and CLI: `app/src/main/groovy/todoistcaldavsync/TodoistCalDavSync.groovy`, `planner/PlannerCli.groovy`, and a new production composition layer.
- Provider adapters: Todoist REST, CalDAV HTTP, Open-Meteo, Slack, and OpenAI-compatible LLM boundaries.
- Durable state: plan snapshots, application receipts/mappings, decisions, and delivery ledger directories; these must be backed up and restored as one consistent set.
- Tests: Spock, WireMock, checked-in JSON/YAML/ICS fixtures, CLI tests, and orchestration integration tests. No real provider calls are permitted in the automated suite.
- Documentation/configuration: `README.md`, `QUICK_START.md`, `conf/todoist-planner.conf.example.yaml`, configuration reference, end-to-end guide, and feature guides.
- Dependencies: existing WireMock test dependency is used; no production dependency should be added unless justified during implementation.
- Compatibility: the default `legacy-sync` operation and existing legacy configuration behavior remain available. Planner production operations require explicit planner integration configuration and fail closed when prerequisites are absent.