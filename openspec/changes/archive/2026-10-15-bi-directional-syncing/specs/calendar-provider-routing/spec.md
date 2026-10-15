# Spec Delta

## ADDED Requirements

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
