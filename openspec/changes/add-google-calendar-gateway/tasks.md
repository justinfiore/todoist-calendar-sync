## 1. Establish the Google provider configuration boundary

- [x] 1.1 Write failing `ProductionIntegrationConfigSpec` cases for explicit `calendar.provider`, provider-specific required fields, mixed-provider rejection, duplicate Google calendar IDs/names, managed-output mapping, secret-reference validation, bootstrap-only Google configuration without calendar IDs, and zero provider construction on invalid config.
- [x] 1.2 Extend `ProductionIntegrationConfig` with a validated immutable calendar-provider model that selects exactly `caldav` or `google_calendar_api` and preserves existing CalDAV configuration semantics.
- [x] 1.3 Update `ProductionPlannerOrchestrator` composition to instantiate only the selected gateway and preserve the existing `CalendarReadGateway` / `CalendarWriteGateway` ports plus `ManagedCalendarWriteGateway` enforcement.
- [x] 1.4 Run the focused configuration/composition specs and verify the new cases fail before implementation and pass after it.

## 2. Implement secure renewable Google OAuth credentials

- [x] 2.1 Write failing unit tests for ignored-file loading of Justin's existing TodoistCalDavSync desktop OAuth client JSON, injected clock/token-store expiry decisions, successful refresh, revoked/invalid refresh, malformed response, atomic token persistence, token/header redaction, legacy-scope validation, normal-store rejection of legacy broad credentials, initial confirmed legacy QA-store import, and no-Google-request/no-token-write failure behavior.
- [x] 2.2 Introduce a narrow Google OAuth client-material loader that resolves only configured local secret-file references, rejects inline secret fields, and produces secret-free validation errors.
- [x] 2.3 Introduce a Google OAuth token-store abstraction with private local-file implementation and in-memory fake; enforce atomic writes and owner-only file permissions where the host supports them.
- [x] 2.4 Implement distinct normal event-only and QA calendar-management OAuth scope sets/token stores around the existing compatible Google client libraries, with refresh-before-use behavior and no normal-token overwrite by QA consent.
- [x] 2.5 Add `--operation google-oauth-bootstrap`, require `provider: google_calendar_api`, bind a configurable callback receiver to `127.0.0.1` only (default `8787`), request event-only scope, print the one-time consent URL only to the invoking terminal (not logs/receipts), persist only the normal token store, and exit without planner/provisioning work.
- [x] 2.6 Add `--operation google-oauth-bootstrap-qa`, require `provider: google_calendar_api`, bind loopback-only, request the separate calendar-management QA scope, persist only the QA token store, and exit without planner/provisioning work.
- [x] 2.7 Add deterministic noninteractive bootstrap seams plus tests for fixed/configured loopback port, normal-vs-QA scope/token-store separation, non-Google refusal before listener/secret resolution, SSH-tunneled callback completion, and all hermetic tests avoiding browser/network calls; leave real consent as an explicit post-review operator operation.
- [x] 2.8 Document reuse of the already staged local OAuth client JSON, initial confirmed legacy QA-store import, fresh normal event-only bootstrap, later manual exercise of QA bootstrap, ignored `.qa/secrets/` permissions, both bootstrap launcher commands and exit behavior, remote-browser SSH forwarding, and the prohibition on pasting codes/tokens/passwords into Slack in `README.md` and `QUICK_START.md`.
- [x] 2.9 Run focused OAuth/redaction tests and inspect test output to confirm no representative secret reaches errors or reports.

## 3. Implement Google Calendar API read semantics

- [x] 3.1 Build WireMock stubs from the official Google Calendar API documentation and write contract tests for event-list request parameters, page-token traversal, bounded page/result behavior, configured calendar-name retention, timed/all-day conversion, and malformed/oversized responses.
- [x] 3.2 Add `GoogleCalendarApiGateway` implementing `CalendarReadGateway`, with injectable authorized service/HTTP client, clock, limits, and exception classifier seams.
- [x] 3.3 Implement `fetchEvents` using the Google Calendar API across every configured calendar, explicit range bounds, single-event expansion, bounded pagination, and domain conversion.
- [x] 3.4 Write and implement global `findEventByUid` tests/behavior for absent, one matching event, API failure, and duplicate provider-iCalUID/private-plannerUID matches across configured calendars; Google `iCalUID` remains read-only and planner identity uses private extended properties; duplicate/missing lookup ambiguity must fail closed.
- [x] 3.5 Run focused gateway read/WireMock tests and verify the adapter does not issue mutations during reads.

## 4. Implement guarded Google event writes and reconciliation behavior

- [x] 4.1 Write WireMock tests proving create/update request shapes (including no write of read-only `iCalUID` and private planner UID persistence), managed-output-only enforcement, planner UID/ownership checks, live ownership/block revalidation before delete, and static field conversion to/from Google events.
- [x] 4.2 Implement `upsertEvent` with global provider iCalendar-UID collision and private planner-UID lookup, deterministic create-or-update behavior, and no mutation outside the configured managed output calendar.
- [x] 4.3 Implement `deleteOwnedEvent` using global live lookup, managed-output/ownership/block checks, and the live Google provider event ID.
- [x] 4.4 Add classified handling/tests for 401/403, 404, 409, 429, 5xx, timeout/interruption, malformed response, and post-dispatch indeterminate mutation; prove ambiguous mutations are never automatically retried.
- [x] 4.5 Exercise existing planner apply/idempotency/approval/safe-only tests with the new adapter seam and add regressions for deadline invariance and no cross-calendar writes.

## 5. Add explicit QA calendar provisioning

- [x] 5.1 Write failing WireMock tests from the official Google Calendar API documentation for an explicit QA-only provision/list operation: separate QA-token-store requirement, dedicated-account preflight, create-or-reuse named QA calendars, returned-ID persistence limited to ignored QA state, and refusal from normal planner operations.
- [x] 5.2 Add a narrow Google calendar-provisioning service/command that uses the authorized Google gateway only when explicitly invoked and never from `capacity`, `preview`, `apply`, `apply-safe`, or `planner-daemon`.
- [x] 5.3 Add a safe ignored `.qa/` configuration template and fixture inventory documenting aliases, output/blocker calendars, client-secret/token-store references, and no secret values.
- [x] 5.4 Run provisioning tests and verify normal planner operation dispatch cannot reach the provisioning service.

## 6. Documentation, examples, and review-ready validation

- [x] 6.1 Update `conf/todoist-planner.conf.example.yaml` and `docs/SMART_PLANNER_CONFIGURATION.md` with explicit Google-vs-CalDAV provider routing, OAuth secret references, calendar IDs, startup errors, and Google-specific safety boundaries.
- [x] 6.2 Update `README.md`, `QUICK_START.md`, `docs/PLANNER_END_TO_END_TESTING.md`, and the QA runbook with normal bootstrap exit behavior, separately scoped QA bootstrap/QA calendar provisioning, preview-first gates, evidence requirements, and rollback/token-revocation procedure.
- [x] 6.3 Add regression coverage that parses the example configuration and asserts it contains no inline Google secrets or normal-password/app-password guidance for the Google provider.
- [x] 6.4 Run focused new specs, `./gradlew :app:test --rerun-tasks`, `./gradlew build`, `./gradlew installDist`, installed launcher help, `openspec validate add-google-calendar-gateway --strict`, `git diff --check`, and a secret scan of tracked files.
- [x] 6.5 Review the complete diff against the three new OpenSpec specs; commit implementation/tests/docs separately from this proposal artifact commit, then wait for Justin’s approval before any live Google authentication, Google Cloud OAuth-client creation, or QA calendar mutation.
