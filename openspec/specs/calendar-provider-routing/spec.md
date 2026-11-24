# calendar-provider-routing Specification

## Purpose
Fail-closed SmartPlanner calendar routing: select exactly `google_calendar_api` or `caldav`, construct only that provider, and preserve existing preview, approval, ownership, and deadline-invariance safety contracts.
## Requirements
### Requirement: Calendar provider selection is explicit and fail-closed
The system SHALL require an explicit validated calendar provider selection for production SmartPlanner composition and SHALL construct exactly the selected Google Calendar API or CalDAV gateway without implicit fallback. Configuration SHALL select either the compatible single managed output or explicit routed managed destinations, never ambiguous overlapping output models.

#### Scenario: Google provider configuration is selected
- **WHEN** configuration selects `google_calendar_api` and satisfies its credential, account, calendar-ID, and single-output or routed-managed-destination requirements
- **THEN** production composition SHALL construct the Google OAuth credential lifecycle and Google Calendar API gateway without constructing the CalDAV gateway

#### Scenario: CalDAV provider configuration is selected
- **WHEN** configuration selects `caldav` and satisfies its endpoint, authentication, and single-output or routed-managed-destination requirements
- **THEN** production composition SHALL preserve compatible CalDAV behavior without constructing Google OAuth or Google Calendar API clients

#### Scenario: Mixed or incomplete provider configuration is rejected
- **WHEN** configuration mixes Google and CalDAV provider fields, selects an unknown provider, duplicates configured calendar names/IDs, mixes conflicting output models, or omits a required managed destination mapping
- **THEN** validation SHALL fail before any provider credential is resolved or network request is sent

#### Scenario: OAuth bootstrap validates the pre-provisioning subset
- **WHEN** the `google-oauth-bootstrap` operation is invoked with Google provider, OAuth client-file, token-store, account-email, and callback-port configuration but no Google calendar IDs
- **THEN** operation-specific validation SHALL allow bootstrap while normal capacity, preview, apply, sync, and daemon operations SHALL continue to require complete selected-output mappings

#### Scenario: Existing single-output configuration
- **WHEN** an operator supplies valid existing configuration without routed sync enabled
- **THEN** the selected provider SHALL retain its single-output ownership boundary and SHALL NOT acquire authority over other configured calendars

### Requirement: Provider routing preserves planner safety contracts
Provider selection SHALL preserve preview no-write behavior, exact approval, safe-only withholding, fully-automated refusal, managed-event ownership and UID collision checks. Ordinary schedule writes SHALL change only authorized Due/duration fields; validated lifecycle and authorized Deadline-edit operations SHALL retain their separately guarded exceptions. Explicit sync authority SHALL NOT grant authority to apply a new plan.

#### Scenario: Google provider runs preview
- **WHEN** SmartPlanner invokes preview using the Google Calendar API provider
- **THEN** that operation SHALL perform only authenticated reads and local persistence, with no calendar, Todoist schedule, duration, or lifecycle mutation

#### Scenario: Google provider applies a guarded plan
- **WHEN** SmartPlanner applies a valid approved or safe-only plan using the Google provider
- **THEN** approval, fresh-input, destination and managed-ownership checks SHALL precede every dependent provider mutation

#### Scenario: Independently enabled sync runs
- **WHEN** the daemon's sync capability is explicitly enabled alongside proposal-only planning
- **THEN** sync SHALL mirror current schedules under its own visible authority and SHALL NOT apply an unapproved proposal

#### Scenario: Lifecycle writes are requested
- **WHEN** initialization, managed occurrence advancement or an authorized Deadline edit requests fields beyond Due/duration
- **THEN** provider composition SHALL require the existing operation-specific authorization, task eligibility, live preconditions and verification; sync-only reconstruction SHALL NOT perform those writes

### Requirement: Both providers support calendar-to-Todoist writeback
Selecting `google_calendar_api` or `caldav` SHALL provide change detection and enabled calendar-to-Todoist schedule writeback through exactly that provider. Routed SmartPlanner SHALL support current single-task and verified focus-group Due/duration edits on explicitly managed destinations. Existing legacy writeback SHALL retain its due-only contract without implicit provider fallback.

#### Scenario: Google provider writeback
- **WHEN** configuration selects `google_calendar_api` and a valid owned current event move or resize is detected
- **THEN** writeback SHALL use the Google Calendar API gateway and SHALL NOT construct the CalDAV gateway

#### Scenario: CalDAV provider writeback
- **WHEN** configuration selects `caldav` and a valid owned current planner event move or resize is detected
- **THEN** writeback SHALL use the CalDAV gateway and SHALL NOT construct Google OAuth or Google Calendar API clients

#### Scenario: Preview stays read-only on either provider
- **WHEN** SmartPlanner invokes preview with either selected provider
- **THEN** the operation SHALL send no provider mutation and no Todoist Due, duration or lifecycle update
