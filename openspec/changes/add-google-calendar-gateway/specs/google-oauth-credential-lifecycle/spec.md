## ADDED Requirements

### Requirement: Renewable Google OAuth credentials
The system SHALL provide a Google OAuth 2.0 credential lifecycle for the Google Calendar API provider that loads the existing TodoistCalDavSync desktop OAuth client material from an ignored local secret reference, obtains and persists a refresh token through an explicit installed-app bootstrap, refreshes expired access credentials before Google API use, and never accepts a static access token as the durable Google credential.

#### Scenario: Bootstrap creates renewable credential state
- **WHEN** an operator explicitly invokes the documented Google OAuth bootstrap for the dedicated QA account and completes consent
- **THEN** the system SHALL persist refresh-capable credential state only in the configured private token store and SHALL report a redacted success result

#### Scenario: Remote browser reaches loopback bootstrap through SSH tunnel
- **WHEN** an operator starts the documented SSH local-port forward to the configured callback port and opens the bootstrap consent URL in a browser on another machine
- **THEN** the system SHALL receive the callback only through its loopback listener and SHALL exchange the authorization code without requiring the code to be copied into Slack or a terminal

#### Scenario: Expired credential refreshes before an API call
- **WHEN** a Google Calendar operation requires credentials whose access token is expired or within the configured refresh window
- **THEN** the system SHALL refresh using the stored refresh token before sending the Calendar API request

#### Scenario: Refresh failure fails closed without a static fallback
- **WHEN** token refresh fails, is revoked, or returns a malformed response
- **THEN** the system SHALL classify the credential failure, SHALL not send a Calendar API mutation, and SHALL not substitute a static token or CalDAV credential

### Requirement: OAuth secrets are redacted and locally isolated
The system SHALL reject inline OAuth secrets, SHALL keep OAuth client and token-store paths out of tracked example configuration, and SHALL redact OAuth client secrets, authorization codes, access tokens, refresh tokens, and Authorization headers from exceptions, logs, receipts, and generated evidence.

#### Scenario: Invalid OAuth response contains a token-like value
- **WHEN** an OAuth provider error or malformed response includes token-like secret text
- **THEN** the surfaced error and persisted diagnostic SHALL contain only redacted text

#### Scenario: Inline OAuth secret appears in configuration
- **WHEN** configuration contains a raw OAuth client secret, authorization code, access token, or refresh token
- **THEN** configuration validation SHALL fail before any Google network client is constructed

### Requirement: OAuth bootstrap is explicit and loopback-only
The installed launcher SHALL expose `--operation google-oauth-bootstrap` only when the selected provider is `google_calendar_api`. It SHALL bind its callback receiver to `127.0.0.1` at configurable `oauth_callback_port` with default `8787`, SHALL print the one-time consent URL directly to the invoking terminal without persisting it to logs or receipts, and SHALL not start calendar provisioning or planner operations.

#### Scenario: Bootstrap refuses a non-Google provider
- **WHEN** `google-oauth-bootstrap` is invoked with `provider: caldav` or invalid provider configuration
- **THEN** the launcher SHALL exit before opening a listener or resolving OAuth client material

#### Scenario: Bootstrap uses only loopback
- **WHEN** `google-oauth-bootstrap` starts with valid Google provider configuration
- **THEN** its callback receiver SHALL bind only to `127.0.0.1` on the configured callback port

#### Scenario: Bootstrap precedes calendar provisioning
- **WHEN** `google-oauth-bootstrap` has valid Google OAuth configuration but no configured Google calendar IDs
- **THEN** it SHALL complete bootstrap validation and SHALL not require a managed output calendar or construct the calendar gateway
