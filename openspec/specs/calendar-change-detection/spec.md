# calendar-change-detection Specification

## Purpose

Detect calendar-side changes for synced events without listing every event on every run, for both Google Calendar API and CalDAV.

## Requirements

### Requirement: Google Calendar changes use a per-calendar sync cursor
For the Google Calendar API provider, the system SHALL persist one sync cursor per configured calendar and SHALL request only events changed since that cursor. It SHALL persist the replacement cursor only after the returned changes have been processed. A missing cursor SHALL cause one full sync that establishes the cursor.

#### Scenario: Incremental Google poll
- **WHEN** a Google calendar has a stored sync cursor and the provider accepts it
- **THEN** the system SHALL request only changed and deleted events for that calendar and SHALL replace the stored cursor with the provider's new cursor after processing

#### Scenario: Google cursor is rejected
- **WHEN** the provider rejects a stored Google sync cursor as gone
- **THEN** the system SHALL discard that cursor, SHALL NOT apply partial incremental results, and SHALL establish a new cursor with a full sync before further writeback

### Requirement: CalDAV changes use sync-collection when advertised
For a CalDAV calendar that advertises collection synchronization, the system SHALL persist one sync token per calendar and SHALL request only member changes since that token. It SHALL persist the replacement token only after those changes have been processed.

#### Scenario: Server advertises sync-collection
- **WHEN** a CalDAV calendar advertises the sync-collection report and a stored token is accepted
- **THEN** the system SHALL retrieve added, changed, and removed members since that token and SHALL store the returned token only after processing

#### Scenario: Stored CalDAV token is invalid
- **WHEN** the server reports that a stored CalDAV sync token is invalid
- **THEN** the system SHALL discard that token and SHALL establish a new token with a full collection sync before further writeback

### Requirement: Unsupported CalDAV servers avoid a blind full reread
When a CalDAV server does not advertise collection synchronization, the system SHALL read the collection change tag first. It SHALL reread event data only when the tag is missing or differs from the last stored tag, and SHALL NOT treat an unchanged tag as a set of calendar edits.

#### Scenario: Collection tag is unchanged
- **WHEN** a CalDAV server has no sync-collection support and the collection change tag equals the stored tag
- **THEN** the system SHALL NOT download calendar event bodies for writeback on that run

#### Scenario: Collection tag changed
- **WHEN** a CalDAV server has no sync-collection support and the collection change tag differs from the stored tag or is absent
- **THEN** the system SHALL reread the calendar, compare each synced event with its stored modification stamp, and SHALL store the new tag only after that comparison completes

### Requirement: Change detection does not scan unrelated calendars
The system SHALL run calendar change detection only for calendars configured for the active sync or planner output. It SHALL NOT list events from calendars that are not configured.

#### Scenario: Multiple configured calendars
- **WHEN** change detection runs
- **THEN** each configured calendar SHALL be polled with its own cursor or tag, and an unconfigured calendar SHALL receive no list or report request
