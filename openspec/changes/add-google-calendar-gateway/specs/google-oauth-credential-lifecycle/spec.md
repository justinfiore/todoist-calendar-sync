## ADDED Requirements

### Requirement: Renewable Google OAuth credentials
The system SHALL provide a Google OAuth 2.0 credential lifecycle for the Google Calendar API provider that loads OAuth client material from an ignored local secret reference, obtains and persists a refresh token through an explicit installed-app bootstrap, refreshes expired access credentials before Google API use, and never accepts a static access token as the durable Google credential.

#### Scenario: Bootstrap creates renewable credential state
- **WHEN** an operator explicitly invokes the documented Google OAuth bootstrap for the dedicated QA account and completes consent
- **THEN** the system SHALL persist refresh-capable credential state only in the configured private token store and SHALL report a redacted success result

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
