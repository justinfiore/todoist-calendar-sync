# Spec Delta

## Purpose

Defines secure authentication lifecycle behavior for using eligible ChatGPT and Grok subscription
sessions without placing renewable credentials in planner configuration or sharing personal CLI state.

## ADDED Requirements

### Requirement: Subscription authentication is explicit and isolated
The system SHALL keep each subscription provider disabled unless its profile is explicitly selected, and SHALL isolate its credentials from API-key auth, other providers, normal vendor CLI homes, and planner state stores.

#### Scenario: Existing API-key configuration is used
- **WHEN** an operator uses the existing `openai_compatible` profile with an environment credential
- **THEN** subscription authentication SHALL not run, no vendor executable SHALL be required, and existing behavior SHALL remain unchanged

#### Scenario: Subscription profile is selected without credentials
- **WHEN** `codex_subscription` or `grok_build_subscription` is selected and its dedicated store is empty
- **THEN** AI suggestion and daemon startup validation SHALL fail closed with a secret-free instruction to run the matching login operation

#### Scenario: Two subscription profiles are configured
- **WHEN** Codex and Grok Build subscription profiles coexist
- **THEN** each SHALL use a distinct canonical credential directory, lock, provider identity, and status without reading or overwriting the other

### Requirement: The launcher provides bounded AI authentication operations
The installed launcher SHALL expose `ai-auth-login`, `ai-auth-status`, and `ai-auth-logout` for an explicitly selected configured AI provider, and each operation SHALL exit without starting legacy sync, planning, Slack, or provider mutation.

#### Scenario: Headless login is requested
- **WHEN** the operator requests Codex device login before a CLI-issued grant has passed the official SIWC endpoint capability probe
- **THEN** the launcher SHALL fail closed and direct the operator to complete official browser login locally and securely transfer the protected record to the self-hosted host

#### Scenario: Browser login is requested explicitly
- **WHEN** the operator selects OpenAI `--auth-flow browser`
- **THEN** the launcher SHALL use dynamic-agent registration, fresh state/nonce/PKCE, an exact `127.0.0.1` loopback callback, ID-token/JWKS validation, and granted-scope validation and SHALL not silently fall back to device, API-key, or another account authentication

#### Scenario: Grok client registration is not authorized
- **WHEN** a Grok authentication operation is requested before xAI authorizes third-party reuse of the source-visible CLI registration and direct public Responses entitlement
- **THEN** the launcher SHALL fail closed before device authorization and report that exact compatibility gate

#### Scenario: Authentication operation receives planner-only arguments
- **WHEN** login, status, or logout receives plan, approval, range, feedback, or QA-provisioning arguments
- **THEN** the launcher SHALL reject the invocation before constructing planner or remote mutation services

#### Scenario: Unsupported provider is selected
- **WHEN** an authentication operation names an API-key profile or unknown provider
- **THEN** the launcher SHALL fail with the allowed subscription provider names and SHALL execute no external process

### Requirement: Login is exact and bounded
Login SHALL use only a documented provider flow with pinned HTTPS hosts, bounded messages, fresh anti-forgery values, and a dedicated protected store. An optional vendor-CLI import SHALL execute only after a capability probe proves its grant on the official endpoint, and then SHALL use a canonical binary without a shell, a minimal environment, and a dedicated temporary home.

#### Scenario: Configured executable is missing or incompatible
- **WHEN** the executable cannot be resolved as a regular non-symlink file or its reported version is outside the tested range
- **THEN** login SHALL fail before displaying a login code or reading a credential file

#### Scenario: Vendor login times out or is cancelled
- **WHEN** the device flow exceeds its configured deadline, the user denies it, or the process is interrupted
- **THEN** the launcher SHALL terminate the child process group, remove temporary credentials, preserve the prior valid profile credential, and return a classified nonzero result

#### Scenario: Vendor writes an unexpected credential shape
- **WHEN** login exits successfully but the resulting file is missing, malformed, provider-mismatched, over size, symlinked, or permission-unsafe
- **THEN** the launcher SHALL refuse import, remove the temporary home, preserve the prior credential, and report no token or identity content

### Requirement: Credential ownership is singular and durable
The system SHALL normalize successful login into one versioned SmartPlanner-owned store using owner-only permissions, atomic replacement, process/file locking, and one refresh owner; it SHALL not continue sharing a renewable cache with the vendor CLI.

#### Scenario: Fresh login succeeds
- **WHEN** the vendor login produces valid access, refresh, provider, expiry, and account/workspace binding data
- **THEN** the system SHALL atomically persist only the required normalized fields, remove the temporary vendor cache, and mark SmartPlanner as refresh owner

#### Scenario: Existing personal vendor login exists
- **WHEN** the user's default Codex or Grok home already contains credentials
- **THEN** login SHALL leave that home byte-for-byte unchanged and use the configured SmartPlanner-specific home instead

#### Scenario: Credential replacement is interrupted
- **WHEN** the process stops before atomic replacement completes
- **THEN** restart SHALL expose either the complete prior record or complete new record, never a partial credential

#### Scenario: Concurrent refresh is attempted
- **WHEN** two SmartPlanner processes try to refresh the same profile
- **THEN** one SHALL hold the profile lock and the other SHALL reload the resulting record rather than replay the prior refresh token

### Requirement: Status is useful without disclosing identity or secrets
`ai-auth-status` SHALL support human and JSON output and report provider, configured binary/version, storage safety, authentication mode, expiry state, refresh ownership, and local/optional remote readiness without printing credentials or personal identifiers.

#### Scenario: Local status is requested
- **WHEN** the operator runs status without a remote-check flag
- **THEN** the operation SHALL perform no provider request or refresh and SHALL classify the profile as ready, expiring, expired, absent, unsafe, incompatible, or malformed

#### Scenario: Remote status is requested
- **WHEN** the operator explicitly requests remote status
- **THEN** the operation MAY refresh under the profile lock and perform one bounded read-only entitlement probe, SHALL report the provider plan only as a non-identifying class when available, and SHALL perform no inference

#### Scenario: Status encounters sensitive malformed content
- **WHEN** credential parsing fails and the file contains token-like, email, account, or provider response data
- **THEN** command output, logs, and exceptions SHALL identify only the provider and safe error class

### Requirement: Refresh and logout fail closed
The system SHALL refresh only through the profile's versioned provider contract, preserve rotated refresh tokens atomically, classify permanent versus transient failures, and provide explicit local and provider logout semantics.

#### Scenario: Access token approaches expiry
- **WHEN** a subscription request begins inside the configured early-refresh window
- **THEN** the system SHALL refresh once under lock, persist any rotated token before use, and send only the new access token to the matching provider

#### Scenario: Refresh is permanently rejected
- **WHEN** the provider reports revoked, reused, expired, wrong-client, or wrong-account credentials
- **THEN** the profile SHALL become `reauthentication_required`, no inference SHALL occur, and no API-key or alternate-profile fallback SHALL be attempted

#### Scenario: Logout is requested
- **WHEN** the operator runs `ai-auth-logout` for a dedicated subscription profile
- **THEN** the system SHALL best-effort revoke through the matching provider contract, remove the local credential even if revocation is unavailable, and leave personal vendor homes and other profiles unchanged

### Requirement: Authentication material is excluded from observability and evidence
Access tokens, refresh tokens, ID tokens, device codes after display, credential JSON, authorization responses, and personal account/workspace identifiers SHALL be excluded from logs, normal output, audit receipts, screenshots, committed fixtures, and error details.

#### Scenario: Login and refresh diagnostics are enabled
- **WHEN** debug logging or a provider failure occurs
- **THEN** diagnostics SHALL contain bounded operation/provider/error metadata only and SHALL not contain request bodies, headers, token claims, verification codes, or credential paths with personal data

#### Scenario: QA evidence is packaged
- **WHEN** authentication QA artifacts are prepared for commit
- **THEN** an automated secret/identity scan SHALL pass and raw vendor homes, token stores, consent URLs, and unredacted process streams SHALL remain ignored
