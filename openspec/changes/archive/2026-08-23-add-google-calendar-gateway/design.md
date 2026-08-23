## Context

`ProductionPlannerOrchestrator` currently constructs `CalDavHttpGateway` directly from `planner.integration.caldav`. That gateway performs CalDAV `REPORT`/`GET`/`PUT`/`DELETE` and supplies either Basic authentication or a static Bearer header. The original legacy sync contains a `GoogleAuthProvider` that can run an installed-app OAuth authorization flow and refresh a token, but it is not part of SmartPlanner composition and is coupled to legacy YAML shapes and local files.

The project already depends on Google API Client, Google OAuth Client Jetty, Google HTTP Client Jackson, and Google Calendar service libraries. Justin has an existing TodoistCalDavSync desktop OAuth client in his personal Google Cloud project; QA will reuse that client but authorize it only as the dedicated agent-owned Google account. The new implementation must not weaken account security or expose secrets in source, configuration, logs, reports, or commits.

## Goals / Non-Goals

**Goals:**

- Route Google calendars through a Google Calendar API gateway that implements existing `CalendarReadGateway` and `CalendarWriteGateway` contracts.
- Use renewable Google OAuth 2.0 credentials with a dedicated local secret/token store and a documented one-time installed-app bootstrap.
- Create/list isolated QA calendars through the Google Calendar API and retain returned IDs only in ignored QA configuration/state.
- Preserve global UID collision detection across every configured Google calendar, managed-output-only writes, live ownership verification before delete, and ambiguous-write handling.
- Keep the current CalDAV integration operational for non-Google providers.
- Prove the behavior with hermetic tests and WireMock API contracts before a real Google account is used.

**Non-Goals:**

- Migrating the legacy `legacy-sync` operation to the new gateway.
- Supporting service-account/domain-wide delegation, shared Google calendars, multiple Google accounts, or production Google rollout in this change.
- Storing credentials in tracked YAML, Git, report artifacts, or environment-dump logs.
- Changing Todoist behavior, deadline invariance, exact approval, safe-only, or fully-automated refusal semantics.

## Decisions

### 1. Add a provider-neutral configuration discriminator

Add a validated `planner.integration.calendar` provider section, with exactly one selected backend:

```yaml
planner:
  integration:
    calendar:
      provider: google_calendar_api # or caldav
      google_calendar_api:
        oauth_client_secret_file: .qa/secrets/google-oauth-client.json
        token_store_dir: .qa/secrets/google-oauth-tokens
        account_email: smartplanner-qa@example.com
        calendars:
          - name: SmartPlanner QA Output
            id: <Google calendar id, ignored QA config only>
            role: managed_output
          - name: SmartPlanner QA Blockers
            id: <Google calendar id, ignored QA config only>
            role: hard_blocker
```

The production-safe example must use placeholders and environment/file *references* only. Config validation SHALL reject mixed Google/CalDAV provider fields, duplicate names/IDs, inline secrets, an output calendar absent from the selected provider, or Google credentials/state paths outside the config directory without explicit safe resolution. Existing `planner.integration.caldav` behavior stays available when `provider: caldav` is chosen.

Rationale: explicit routing prevents an accidental fallback from intended Google OAuth to static CalDAV authentication and provides a stable home for Google IDs. The alternative—inferring provider from a URL or credential field—would be ambiguous and unsafe.

### 2. Build a SmartPlanner-specific OAuth credential provider

Create a narrow Google OAuth component that:

1. loads OAuth desktop-client JSON only from the configured ignored local file;
2. exposes an explicit `google-oauth-bootstrap` operation that binds only `127.0.0.1` on configurable `planner.integration.calendar.google_calendar_api.oauth_callback_port` (default `8787`), prints the one-time consent URL directly to the invoking terminal without persisting it to logs/receipts, and completes the local installed-app consent flow for the dedicated QA account;
3. exposes a normal `google-oauth-bootstrap` that requests only the event read/write scope required for normal Google Calendar planner operations, persists into the normal token store, and exits immediately after successful credential persistence;
4. exposes `google-oauth-bootstrap-qa` only as an explicit QA operation that requests the additional Google Calendar management scope required to create/list QA calendars, persists into a separate QA token store, and exits immediately after successful credential persistence;
5. refreshes before expiry and exposes only a short-lived authorized Google Calendar service/client to the gateway;
6. redacts client IDs, client secrets, authorization codes, access tokens, refresh tokens, and Bearer headers from every exception and log message.

The legacy `GoogleAuthProvider` is reference material only. The new component may reuse the existing OAuth client JSON but must not silently reuse the legacy config shape, generic datastore name, or token location. The legacy helper requested both event and broad calendar-management scopes, so its stored credential SHALL never be imported into the normal event-only token store. The intended QA client JSON and legacy datastore artifacts from Justin's existing TodoistCalDavSync desktop OAuth app are already staged in ignored local QA paths and are never copied into the repository. The alternative of passing a static access token through `token_env` is rejected because it expires and cannot support a long-running daemon.

The installed launcher command contracts are:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-bootstrap

# Explicit QA elevation; it is not used by normal planner operations.
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-bootstrap-qa
```

Each bootstrap command must refuse unless `provider: google_calendar_api` is selected, listen only on loopback, and exit with a redacted result after persisting refresh-capable credentials. Normal bootstrap validates the Google provider, OAuth client-file reference, normal token-store reference, account email, and callback port; it deliberately does not require calendar IDs. QA bootstrap additionally requires the separate QA token-store reference but also precedes calendar provisioning. The token stores are distinct, so granting the QA-only calendar-management scope does not overwrite or broaden the normal planner credential. For a browser on another machine, the documented path is `ssh -N -L 8787:127.0.0.1:8787 hermes@<host>` before opening the consent URL; the authorization-code callback travels through that SSH tunnel and is never pasted into Slack or a terminal.

### 3. Implement Google Calendar API semantics behind existing ports

`GoogleCalendarApiGateway` will translate API `Event` resources into `CalendarEvent` domain objects and implement:

- `fetchEvents(rangeStart, rangeEnd)`: list events from every configured calendar using bounded pagination, single-event expansion, and explicit time range; preserve configured calendar display names.
- `findEventByUid(uid)`: query every configured calendar by `iCalUID` as a provider-level collision barrier and by `privateExtendedProperty=plannerUid=<uid>` for the writable planner-owned identity; return null when absent and refuse with a classified collision error when more than one accessible event matches. Google Event `iCalUID` is server-generated/read-only and is never written by SmartPlanner.
- `upsertEvent(event)`: refuse any calendar other than the configured managed output; require existing planner UID/ownership metadata, then create or update only the matching owned event. Persist the deterministic planner UID in `extendedProperties.private.plannerUid`; use the live Google event ID for update/delete and retain global provider `iCalUID` collision checks rather than attempting to write an iCalUID.
- `deleteOwnedEvent(uid, expectedBlockId)`: re-read globally, require managed-calendar ownership and matching block metadata, then delete by the live provider event ID.
- QA provisioning helper/port: list and create explicitly named disposable calendars; it is not called by normal planner capacity/preview/apply operations.

Google errors will be normalized into a new typed gateway exception. Timeouts, interrupted/indeterminate create/update/delete requests, malformed responses, rate limits, authentication failures, and duplicate UID results must retain the current safety posture: never blind-retry an ambiguous mutation and never treat a failed global lookup as no collision.

### 4. Preserve composition and safety decorators

`ProductionPlannerOrchestrator` selects the gateway via validated provider routing and continues to expose it through the existing read/write ports. `ManagedCalendarWriteGateway` remains the final write decorator. The gateway will additionally enforce managed-output boundaries itself, so both composition and adapter boundaries reject a cross-calendar write.

### 5. Test at transport boundaries without live Google credentials

Use WireMock to emulate the official Google Calendar API HTTP contract, with request paths, methods, query parameters, pagination fields, response fields, and error semantics cross-checked against the Google Calendar API documentation at implementation time. Cover:

- OAuth token refresh success, invalid grant, malformed/oversized response, and redaction;
- event-only OAuth bootstrap/refresh; QA-scope OAuth bootstrap/refresh into a separate store; calendar-list/create only through QA service; event list pagination, provider `iCalUID` collision lookup plus private `plannerUid` lookup across calendars, create/update/delete request shapes that never write read-only `iCalUID`, 401/403/404/409/429/5xx responses, and timeouts;
- global UID collision, managed-only write, live ownership/block recheck before delete, and ambiguous mutation classification;
- provider-routing/config validation and no construction of CalDAV when Google is selected (and vice versa).

Use fake/in-memory OAuth token stores and clocks so expiry/refresh tests do not require browser consent or real time.

## Risks / Trade-offs

- **OAuth desktop consent needs interactive login/2FA** → first attempt the audited import/validation of the already staged dedicated-account credential after implementation review. If Google requires re-consent, obtain Justin's separate authorization before interactive login/2FA; reuse the existing TodoistCalDavSync desktop OAuth client and authorize it only as that dedicated account.
- **Google API client dependency drift** → pin/test a mutually compatible dependency set and inspect Gradle resolution before code composition.
- **API event semantics differ from iCalendar** → isolate conversion in one adapter and test timed, all-day, recurrence-instance, missing-end, and metadata cases.
- **Event update creates duplicate or overwrites external data** → global provider `iCalUID` collision lookup, private planner-UID lookup, ownership marker checks, managed-output checks, and live reread before delete.
- **Refresh token or OAuth errors leak** → centralized redaction and tests that assert raw secret substrings are absent from thrown/loggable messages.
- **Calendar provisioning becomes an accidental production operation** → require QA-scoped credentials in a separate token store and expose provisioning only through an explicit QA-only command/helper with a dedicated-account preflight; normal planner operations cannot call it.

## Migration Plan

1. Commit and review this OpenSpec artifact set; do not connect a Google account.
2. Implement configuration/provider routing and hermetic validation tests.
3. Implement OAuth lifecycle with fake stores/clocks and tests.
4. Implement the Google Calendar gateway, conversion, and WireMock contracts.
5. Add normal event-only OAuth bootstrap, separately scoped QA OAuth bootstrap, explicit QA-only calendar provisioning commands, and README/QUICK_START documentation.
6. Run focused tests, full `:app:test --rerun-tasks`, `build`, `installDist`, installed launcher help, OpenSpec validation, and security review.
7. After implementation, validate/import the already staged legacy credential into the separate QA token store first; Justin has confirmed it was generated today for the dedicated agent-owned Google account. If validation unexpectedly fails, stop before live Google access and report the precise failure. Run a fresh normal event-only bootstrap only when normal non-QA planner operation is needed. Later, with Justin available, exercise and retain evidence for the separately scoped `google-oauth-bootstrap-qa` flow. After successful QA credential import, provision QA calendars and resume the isolated QA plan without further credential setup; existing preview/write approval gates remain mandatory.
8. Roll back by selecting `provider: caldav` for non-Google deployments and removing ignored local Google OAuth/token material; no tracked migration of live calendar data is required.

## Resolved Decisions

- Google Calendar uses a dedicated Google Calendar API gateway and renewable OAuth 2.0; it does not use SmartPlanner's static CalDAV Basic/Bearer path.
- Existing ignored OAuth client material will be reused after implementation review, without creating a new Google Cloud client. Justin confirmed the staged legacy credential was generated today for the dedicated agent-owned Google account; validate/import it initially only into the QA token store. Normal event-only operation requires fresh consent. Later exercise `google-oauth-bootstrap-qa` with Justin available and retain its evidence.
- OAuth bootstrap is a documented `google-oauth-bootstrap` operation bound only to loopback port `8787` by default; remote-browser consent uses an SSH loopback tunnel.
- `google-oauth-bootstrap` exits after persisting normal event-only credentials. `google-oauth-bootstrap-qa` is separately invoked, exits after persisting a distinct QA-only calendar-management credential, and is the only bootstrap path that may grant calendar-provisioning scope.
- The CalDAV adapter remains supported for non-Google providers.
- The change is implementation-planning only; no live Google authentication, Google Cloud app creation, or provider mutation occurs before Justin reviews and authorizes implementation.
