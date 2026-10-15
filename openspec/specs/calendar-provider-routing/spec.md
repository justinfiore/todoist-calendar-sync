# calendar-provider-routing Specification

## Purpose
Fail-closed SmartPlanner calendar routing: select exactly `google_calendar_api` or `caldav`, construct only that provider, and preserve existing preview, approval, ownership, and deadline-invariance safety contracts.
## Requirements
### Requirement: Calendar provider selection is explicit and fail-closed
The system SHALL require an explicit validated calendar provider selection for production SmartPlanner composition and SHALL construct exactly the selected Google Calendar API or CalDAV gateway without implicit fallback.

#### Scenario: Google provider configuration is selected
- **WHEN** configuration selects `google_calendar_api` and satisfies its credential, account, calendar-ID, and managed-output requirements
- **THEN** production composition SHALL construct the Google OAuth credential lifecycle and Google Calendar API gateway without constructing the CalDAV gateway

#### Scenario: CalDAV provider configuration is selected
- **WHEN** configuration selects `caldav` and satisfies its existing endpoint and authentication requirements
- **THEN** production composition SHALL preserve existing CalDAV behavior without constructing Google OAuth or Google Calendar API clients

#### Scenario: Mixed or incomplete provider configuration is rejected
- **WHEN** configuration mixes Google and CalDAV provider fields, selects an unknown provider, duplicates configured calendar names/IDs, or omits the selected provider's managed output mapping
- **THEN** validation SHALL fail before any provider credential is resolved or network request is sent

#### Scenario: OAuth bootstrap validates the pre-provisioning subset
- **WHEN** the `google-oauth-bootstrap` operation is invoked with Google provider, OAuth client-file, token-store, account-email, and callback-port configuration but no Google calendar IDs
- **THEN** operation-specific validation SHALL allow bootstrap while normal capacity, preview, apply, and daemon operations SHALL continue to require a complete managed-output calendar mapping

### Requirement: Provider routing preserves planner safety contracts
Provider selection SHALL not weaken preview no-write behavior, Todoist due-only/deadline-invariance behavior, exact approval requirements, safe-only withholding, managed-calendar ownership checks, global UID collision detection, or fully-automated refusal.

#### Scenario: Google provider runs preview
- **WHEN** SmartPlanner uses the Google Calendar API provider in preview mode
- **THEN** it SHALL perform only authenticated Google Calendar reads and local state persistence and SHALL send no Google Calendar mutations

#### Scenario: Google provider applies a guarded plan
- **WHEN** SmartPlanner applies a valid approved or safe-only plan using the Google provider
- **THEN** all existing approval and managed-calendar safety checks SHALL be evaluated before the gateway sends a Google Calendar mutation

### Requirement: Both providers support calendar-to-Todoist writeback
Selecting `google_calendar_api` or `caldav` SHALL provide calendar change detection and calendar-to-Todoist due writeback for that provider. Provider selection SHALL remain explicit and fail-closed, and SHALL NOT add a fallback from one provider to the other.

#### Scenario: Google provider writeback
- **WHEN** configuration selects `google_calendar_api` and a newer owned calendar move is detected
- **THEN** writeback SHALL use the Google Calendar API gateway and SHALL NOT construct the CalDAV gateway

#### Scenario: CalDAV provider writeback
- **WHEN** configuration selects `caldav` and a newer synced calendar move is detected
- **THEN** writeback SHALL use the CalDAV gateway and SHALL NOT construct Google OAuth or Google Calendar API clients

#### Scenario: Preview stays read-only on either provider
- **WHEN** SmartPlanner preview runs with either selected provider
- **THEN** change detection MAY read the selected provider and SHALL send no provider mutation and no Todoist due update
