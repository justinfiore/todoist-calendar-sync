## 1. Establish the Google provider configuration boundary

- [ ] 1.1 Write failing `ProductionIntegrationConfigSpec` cases for explicit `calendar.provider`, provider-specific required fields, mixed-provider rejection, duplicate Google calendar IDs/names, managed-output mapping, secret-reference validation, bootstrap-only Google configuration without calendar IDs, and zero provider construction on invalid config.
- [ ] 1.2 Extend `ProductionIntegrationConfig` with a validated immutable calendar-provider model that selects exactly `caldav` or `google_calendar_api` and preserves existing CalDAV configuration semantics.
- [ ] 1.3 Update `ProductionPlannerOrchestrator` composition to instantiate only the selected gateway and preserve the existing `CalendarReadGateway` / `CalendarWriteGateway` ports plus `ManagedCalendarWriteGateway` enforcement.
- [ ] 1.4 Run the focused configuration/composition specs and verify the new cases fail before implementation and pass after it.

## 2. Implement secure renewable Google OAuth credentials

- [ ] 2.1 Write failing unit tests for ignored-file loading of Justin's existing TodoistCalDavSync desktop OAuth client JSON, injected clock/token-store expiry decisions, successful refresh, revoked/invalid refresh, malformed response, atomic token persistence, and token/header redaction.
- [ ] 2.2 Introduce a narrow Google OAuth client-material loader that resolves only configured local secret-file references, rejects inline secret fields, and produces secret-free validation errors.
- [ ] 2.3 Introduce a Google OAuth token-store abstraction with private local-file implementation and in-memory fake; enforce atomic writes and owner-only file permissions where the host supports them.
- [ ] 2.4 Implement the credential provider/bootstrap service around the existing compatible Google client libraries, requesting the exact Calendar scopes required by gateway reads/writes and QA calendar provisioning, with refresh-before-use behavior.
- [ ] 2.5 Add `--operation google-oauth-bootstrap`, require `provider: google_calendar_api`, bind a configurable callback receiver to `127.0.0.1` only (default port `8787`), print the one-time consent URL only to the invoking terminal (not logs/receipts), and exit after refresh-capable token persistence without running planner/provisioning work.
- [ ] 2.6 Add deterministic noninteractive bootstrap seams plus tests for fixed/configured loopback port, non-Google refusal before listener/secret resolution, SSH-tunneled callback completion, and all hermetic tests avoiding browser/network calls; leave real consent as an explicit post-review operator operation.
- [ ] 2.7 Document Bitwarden retrieval of the existing OAuth client JSON to ignored `.qa/secrets/google-oauth-client.json`, owner-only permissions, the bootstrap launcher command, remote-browser SSH forwarding, and the prohibition on pasting codes/tokens/passwords into Slack.
- [ ] 2.8 Run focused OAuth/redaction tests and inspect test output to confirm no representative secret reaches errors or reports.

## 3. Implement Google Calendar API read semantics

- [ ] 3.1 Write WireMock tests for Google Calendar event-list request parameters, page-token traversal, bounded page/result behavior, configured calendar-name retention, timed/all-day conversion, and malformed/oversized responses.
- [ ] 3.2 Add `GoogleCalendarApiGateway` implementing `CalendarReadGateway`, with injectable authorized service/HTTP client, clock, limits, and exception classifier seams.
- [ ] 3.3 Implement `fetchEvents` using the Google Calendar API across every configured calendar, explicit range bounds, single-event expansion, bounded pagination, and domain conversion.
- [ ] 3.4 Write and implement global `findEventByUid` tests/behavior for absent, one matching event, API failure, and duplicate UID across configured calendars; duplicate/missing lookup ambiguity must fail closed.
- [ ] 3.5 Run focused gateway read/WireMock tests and verify the adapter does not issue mutations during reads.

## 4. Implement guarded Google event writes and reconciliation behavior

- [ ] 4.1 Write WireMock tests proving create/update request shapes, managed-output-only enforcement, planner UID/ownership checks, live ownership/block revalidation before delete, and static field conversion to/from Google events.
- [ ] 4.2 Implement `upsertEvent` with global iCalendar UID lookup, deterministic create-or-update behavior, and no mutation outside the configured managed output calendar.
- [ ] 4.3 Implement `deleteOwnedEvent` using global live lookup, managed-output/ownership/block checks, and the live Google provider event ID.
- [ ] 4.4 Add classified handling/tests for 401/403, 404, 409, 429, 5xx, timeout/interruption, malformed response, and post-dispatch indeterminate mutation; prove ambiguous mutations are never automatically retried.
- [ ] 4.5 Exercise existing planner apply/idempotency/approval/safe-only tests with the new adapter seam and add regressions for deadline invariance and no cross-calendar writes.

## 5. Add explicit QA calendar provisioning

- [ ] 5.1 Write failing tests for an explicit QA-only provision/list operation: dedicated-account preflight, create-or-reuse named QA calendars, returned-ID persistence limited to ignored QA state, and refusal from normal planner operations.
- [ ] 5.2 Add a narrow Google calendar-provisioning service/command that uses the authorized Google gateway only when explicitly invoked and never from `capacity`, `preview`, `apply`, `apply-safe`, or `planner-daemon`.
- [ ] 5.3 Add a safe ignored `.qa/` configuration template and fixture inventory documenting aliases, output/blocker calendars, client-secret/token-store references, and no secret values.
- [ ] 5.4 Run provisioning tests and verify normal planner operation dispatch cannot reach the provisioning service.

## 6. Documentation, examples, and review-ready validation

- [ ] 6.1 Update `conf/todoist-planner.conf.example.yaml` and `docs/SMART_PLANNER_CONFIGURATION.md` with explicit Google-vs-CalDAV provider routing, OAuth secret references, calendar IDs, startup errors, and Google-specific safety boundaries.
- [ ] 6.2 Update `docs/PLANNER_END_TO_END_TESTING.md`, `QUICK_START.md`, and the QA runbook with the post-review dedicated-account OAuth bootstrap, Bitwarden CLI boundary, QA calendar provisioning, preview-first gates, evidence requirements, and rollback/token-revocation procedure.
- [ ] 6.3 Add regression coverage that parses the example configuration and asserts it contains no inline Google secrets or normal-password/app-password guidance for the Google provider.
- [ ] 6.4 Run focused new specs, `./gradlew :app:test --rerun-tasks`, `./gradlew build`, `./gradlew installDist`, installed launcher help, `openspec validate add-google-calendar-gateway --strict`, `git diff --check`, and a secret scan of tracked files.
- [ ] 6.5 Review the complete diff against the three new OpenSpec specs; commit implementation/tests/docs separately from this proposal artifact commit, then wait for Justin’s approval before any live Google authentication, Google Cloud OAuth-client creation, or QA calendar mutation.
