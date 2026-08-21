## Context

`ProductionPlannerOrchestrator` currently constructs `CalDavHttpGateway` directly from `planner.integration.caldav`. That gateway performs CalDAV `REPORT`/`GET`/`PUT`/`DELETE` and supplies either Basic authentication or a static Bearer header. The original legacy sync contains a `GoogleAuthProvider` that can run an installed-app OAuth authorization flow and refresh a token, but it is not part of SmartPlanner composition and is coupled to legacy YAML shapes and local files.

The project already depends on Google API Client, Google OAuth Client Jetty, Google HTTP Client Jackson, and Google Calendar service libraries. The new implementation must use a dedicated Google QA account only, without weakening account security or exposing secrets in source, configuration, logs, reports, or commits.

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
2. obtains the authorization URL and completes the local installed-app consent flow for the dedicated QA account;
3. requests the minimum Calendar scope required by the gateway, including calendar management for QA provisioning;
4. persists refresh/access/expiry data in a private local token store, atomically and with owner-only permissions where supported;
5. refreshes before expiry and exposes only a short-lived authorized Google Calendar service/client to the gateway;
6. redacts client IDs, client secrets, authorization codes, access tokens, refresh tokens, and Bearer headers from every exception and log message.

The legacy `GoogleAuthProvider` is reference material only. The new component must not silently reuse its legacy config shape, generic datastore name, or token location. The alternative of passing a static access token through `token_env` is rejected because it expires and cannot support a long-running daemon.

### 3. Implement Google Calendar API semantics behind existing ports

`GoogleCalendarApiGateway` will translate API `Event` resources into `CalendarEvent` domain objects and implement:

- `fetchEvents(rangeStart, rangeEnd)`: list events from every configured calendar using bounded pagination, single-event expansion, and explicit time range; preserve configured calendar display names.
- `findEventByUid(uid)`: query every configured calendar by `iCalUID`; return null when absent and refuse with a classified collision error when more than one accessible event has that UID.
- `upsertEvent(event)`: refuse any calendar other than the configured managed output; require existing planner UID/ownership metadata, then create or update only the matching owned event. Use the Google event `iCalUID` for collision-safe lookup rather than assuming the provider event ID is stable.
- `deleteOwnedEvent(uid, expectedBlockId)`: re-read globally, require managed-calendar ownership and matching block metadata, then delete by the live provider event ID.
- QA provisioning helper/port: list and create explicitly named disposable calendars; it is not called by normal planner capacity/preview/apply operations.

Google errors will be normalized into a new typed gateway exception. Timeouts, interrupted/indeterminate create/update/delete requests, malformed responses, rate limits, authentication failures, and duplicate UID results must retain the current safety posture: never blind-retry an ambiguous mutation and never treat a failed global lookup as no collision.

### 4. Preserve composition and safety decorators

`ProductionPlannerOrchestrator` selects the gateway via validated provider routing and continues to expose it through the existing read/write ports. `ManagedCalendarWriteGateway` remains the final write decorator. The gateway will additionally enforce managed-output boundaries itself, so both composition and adapter boundaries reject a cross-calendar write.

### 5. Test at transport boundaries without live Google credentials

Use WireMock to emulate:

- OAuth token refresh success, invalid grant, malformed/oversized response, and redaction;
- Calendar list/create, event list pagination, `iCalUID` lookup across calendars, create/update/delete request shapes, 401/403/404/409/429/5xx responses, and timeouts;
- global UID collision, managed-only write, live ownership/block recheck before delete, and ambiguous mutation classification;
- provider-routing/config validation and no construction of CalDAV when Google is selected (and vice versa).

Use fake/in-memory OAuth token stores and clocks so expiry/refresh tests do not require browser consent or real time.

## Risks / Trade-offs

- **OAuth desktop consent needs interactive login/2FA** → defer it until after implementation review; use only the dedicated Google account and Bitwarden CLI access provided by Justin.
- **Google API client dependency drift** → pin/test a mutually compatible dependency set and inspect Gradle resolution before code composition.
- **API event semantics differ from iCalendar** → isolate conversion in one adapter and test timed, all-day, recurrence-instance, missing-end, and metadata cases.
- **Event update creates duplicate or overwrites external data** → global `iCalUID` lookup, ownership marker checks, managed-output checks, and live reread before delete.
- **Refresh token or OAuth errors leak** → centralized redaction and tests that assert raw secret substrings are absent from thrown/loggable messages.
- **Calendar provisioning becomes an accidental production operation** → expose it only through an explicit QA-only command/helper with a dedicated-account preflight; normal planner operations cannot call it.

## Migration Plan

1. Commit and review this OpenSpec artifact set; do not connect a Google account.
2. Implement configuration/provider routing and hermetic validation tests.
3. Implement OAuth lifecycle with fake stores/clocks and tests.
4. Implement the Google Calendar gateway, conversion, and WireMock contracts.
5. Add explicit QA-only provisioning/bootstrap commands and documentation.
6. Run focused tests, full `:app:test --rerun-tasks`, `build`, `installDist`, installed launcher help, OpenSpec validation, and security review.
7. After Justin approves implementation, use only the dedicated account to create OAuth client/consent, provision QA calendars, and resume the isolated QA plan.
8. Roll back by selecting `provider: caldav` for non-Google deployments and removing ignored local Google OAuth/token material; no tracked migration of live calendar data is required.

## Resolved Decisions

- Google Calendar uses a dedicated Google Calendar API gateway and renewable OAuth 2.0; it does not use SmartPlanner's static CalDAV Basic/Bearer path.
- OAuth/2FA setup will use the dedicated agent-owned Google account and Bitwarden CLI access after implementation review.
- The CalDAV adapter remains supported for non-Google providers.
- The change is implementation-planning only; no live Google authentication, Google Cloud app creation, or provider mutation occurs before Justin reviews and authorizes implementation.
