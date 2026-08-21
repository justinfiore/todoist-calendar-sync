## Why

SmartPlanner currently composes a direct CalDAV adapter that accepts only Basic or a static Bearer token. That path cannot renew Google OAuth credentials and cannot provision Google calendars, so it is not a safe or durable basis for the isolated Google QA campaign.

## What Changes

- Add a Google Calendar API implementation of SmartPlanner's existing calendar read/write ports.
- Add a renewable Google OAuth 2.0 credential lifecycle for the dedicated Google QA account, reusing Justin's existing TodoistCalDavSync desktop OAuth client for QA, with no raw credentials committed or logged.
- Add explicit configuration-driven calendar-provider selection so Google Calendar API and non-Google CalDAV remain separate, fail-closed integration paths.
- Add Google Calendar API calendar discovery/provisioning support for the disposable `SmartPlanner QA Output` and test-input calendars.
- Preserve all existing planner ownership, collision, approval, due-date, ambiguity, and preview-no-write safety controls.
- Add hermetic OAuth/Google Calendar HTTP-contract tests plus configuration, documentation, and QA-runbook updates. Live Google testing remains blocked until Justin reviews and approves implementation.

## Capabilities

### New Capabilities

- `google-calendar-api-gateway`: OAuth-authenticated Google Calendar API adapter for SmartPlanner calendar reads, global UID collision lookup, managed event writes/deletes, and isolated QA calendar provisioning.
- `google-oauth-credential-lifecycle`: Renewable, local-secret Google OAuth bootstrap, storage, refresh, redaction, and fail-closed credential handling.
- `calendar-provider-routing`: Explicit validated selection between the Google Calendar API gateway and existing non-Google CalDAV gateway.

### Modified Capabilities

None. The repository has no baseline OpenSpec capability specs; the active `planner-integration` change is a completed integrated feature plan rather than an archived baseline spec.

## Impact

- Runtime/configuration: `ProductionIntegrationConfig`, `ProductionPlannerOrchestrator`, planner example configuration, and QA configuration/runbook.
- Adapters: new Google OAuth and Google Calendar API gateway classes implementing `CalendarReadGateway` and `CalendarWriteGateway`; existing `CalDavHttpGateway` remains supported for non-Google providers.
- Security: Justin's existing desktop OAuth-client JSON and legacy credential-store artifacts are already staged in ignored local QA paths; a separate SmartPlanner QA token store holds only the dedicated account's credentials. No Google normal password, app password, static long-lived access token, or secrets appear in YAML/logs/evidence.
- Tests: Spock unit tests and WireMock Google OAuth/Calendar API contracts, including refresh, pagination, ownership, collision, ambiguous writes, and no cross-calendar mutation.
- Dependencies: consolidate the already-present Google client, OAuth, HTTP, and Calendar service dependencies at mutually compatible versions.
