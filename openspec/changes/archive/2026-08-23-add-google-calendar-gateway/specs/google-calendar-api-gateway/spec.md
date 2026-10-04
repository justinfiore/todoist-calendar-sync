## ADDED Requirements

### Requirement: Google Calendar API provider implements calendar ports
The system SHALL provide a Google Calendar API gateway that implements `CalendarReadGateway` and `CalendarWriteGateway` for explicitly configured Google calendars, preserving configured calendar display names in domain events.

#### Scenario: Read a bounded time range from configured calendars
- **WHEN** SmartPlanner requests events for a positive time range using the Google provider
- **THEN** the gateway SHALL query every configured calendar with bounded pagination and return only events within the requested range as `CalendarEvent` values

#### Scenario: Find planner UID globally across configured Google calendars
- **WHEN** SmartPlanner looks up an event UID using the Google provider
- **THEN** the gateway SHALL search every configured Google calendar by the provider iCalendar UID as a collision barrier and by the writable private `plannerUid` extended property for planner-owned identity, and SHALL return null only if no configured calendar contains either matching identity

#### Scenario: Duplicate UID fails safely
- **WHEN** more than one configured Google calendar contains the same provider iCalendar UID or private planner UID
- **THEN** the gateway SHALL raise a classified collision error and SHALL not treat the UID as missing

### Requirement: Google event writes preserve managed ownership boundaries
The Google Calendar API gateway SHALL create, update, or delete events only in the configured managed output calendar and only when existing SmartPlanner ownership, planner UID, and expected block metadata checks pass.

#### Scenario: Owned planned event is created or updated
- **WHEN** an event targets the managed output calendar and contains valid planner UID and ownership metadata
- **THEN** the gateway SHALL create or update only the corresponding Google event, SHALL persist the deterministic planner UID in a private extended property, and SHALL not write the server-generated/read-only iCalendar UID

#### Scenario: Write outside managed output is refused
- **WHEN** an event targets any configured Google calendar other than the managed output calendar
- **THEN** the gateway SHALL refuse before sending a Google Calendar mutation request

#### Scenario: Delete requires live ownership revalidation
- **WHEN** deletion is requested for a planner event UID and expected block ID
- **THEN** the gateway SHALL reread the live event globally and SHALL delete only if it is in the managed output calendar and its ownership/block metadata match

#### Scenario: Indeterminate mutation is not blindly retried
- **WHEN** a create, update, or delete request times out or fails after dispatch such that remote outcome is unknown
- **THEN** the gateway SHALL classify the result as ambiguous and SHALL not retry the mutation automatically

### Requirement: QA-only Google calendar provisioning is explicit
The system SHALL provide an explicit QA-only operation or helper that lists and creates named Google calendars through the authenticated Google Calendar API using only the separate QA token store and SHALL not invoke that operation from normal capacity, preview, apply, apply-safe, deliver, feedback, or daemon flows.

#### Scenario: Provision isolated QA calendars
- **WHEN** the explicit QA provisioning operation runs after dedicated-account preflight
- **THEN** it SHALL create or reuse only the named QA calendars and persist their returned calendar IDs only in ignored local QA configuration/state

#### Scenario: Normal planning cannot provision calendars
- **WHEN** capacity, preview, apply, apply-safe, deliver, feedback, or planner-daemon runs
- **THEN** it SHALL not create, delete, or rename any Google calendar
