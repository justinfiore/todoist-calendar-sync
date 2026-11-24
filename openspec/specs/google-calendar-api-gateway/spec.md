# google-calendar-api-gateway Specification

## Purpose
Google Calendar API adapter for SmartPlanner calendar reads, global UID collision lookup, managed-output-only writes/deletes, and explicit QA-only calendar provisioning.
## Requirements
### Requirement: Google Calendar API provider implements calendar ports
The system SHALL implement calendar read/write ports for explicitly configured Google calendars, preserve display names, and perform global identity lookup across those configured calendars. Ordinary lookup SHALL reject duplicate logical identities; relocation recovery SHALL use exact recorded source/target resources without treating arbitrary duplicates as owned.

#### Scenario: Read a bounded time range from configured calendars
- **WHEN** SmartPlanner requests events for a positive time range using the Google provider
- **THEN** the gateway SHALL query every configured calendar with bounded pagination and return only events within that range as domain events

#### Scenario: Find planner UID globally across configured Google calendars
- **WHEN** SmartPlanner looks up an event UID using the Google provider
- **THEN** the gateway SHALL search every configured calendar by provider iCalendar UID and writable private planner UID and return null only if neither identity matches

#### Scenario: Duplicate UID fails safely
- **WHEN** several resources match an identity and they are not the exact pair of an unresolved recorded relocation
- **THEN** the gateway SHALL raise a collision error and SHALL NOT adopt, overwrite, or treat them as missing

### Requirement: Google event writes preserve managed ownership boundaries
The Google Calendar API gateway SHALL mutate events only within explicitly managed destinations and only with verified planner ownership, identity, occurrence and membership. Single-output configurations SHALL retain their original boundary. A recorded relocation SHALL be the only exception permitting the same logical planner identity temporarily in two managed destinations; unrelated UID collisions SHALL remain errors.

#### Scenario: Owned planned event is created or updated
- **WHEN** an event targets its validated managed destination with valid planner identity and ownership metadata
- **THEN** the gateway SHALL create/update only that resource, persist deterministic planner UID in writable private metadata, and SHALL NOT write server-generated/read-only iCalendar UID

#### Scenario: Write outside managed output is refused
- **WHEN** an event targets a read-only calendar, an unconfigured destination, or a destination inconsistent with its validated route
- **THEN** the gateway SHALL refuse before sending a Google Calendar mutation

#### Scenario: Delete requires live ownership revalidation
- **WHEN** deletion is requested for a planner identity and expected block/membership revision
- **THEN** the gateway SHALL reread the exact live resource and delete only when managed destination, ownership and expected metadata/version match

#### Scenario: Indeterminate mutation is not blindly retried
- **WHEN** a create, update, delete or relocation request has an unknown remote outcome
- **THEN** the gateway SHALL report ambiguity and require live reconciliation before another mutation

#### Scenario: Routed relocation partially completes
- **WHEN** a persisted relocation has a verified old source resource and a verified replacement in the target calendar
- **THEN** reconciliation SHALL recognize only those recorded resource IDs as an authorized transitional pair and remove the old one only after ownership/version revalidation

### Requirement: QA-only Google calendar provisioning is explicit
The system SHALL provide an explicit QA-only operation or helper that lists and creates named Google calendars through the authenticated Google Calendar API using only the separate QA token store and SHALL not invoke that operation from normal capacity, preview, apply, apply-safe, deliver, feedback, or daemon flows.

#### Scenario: Provision isolated QA calendars
- **WHEN** the explicit QA provisioning operation runs after dedicated-account preflight
- **THEN** it SHALL create or reuse only the named QA calendars and persist their returned calendar IDs only in ignored local QA configuration/state

#### Scenario: Normal planning cannot provision calendars
- **WHEN** capacity, preview, apply, apply-safe, deliver, feedback, or planner-daemon runs
- **THEN** it SHALL not create, delete, or rename any Google calendar

### Requirement: Google event reads expose last modification
The Google Calendar API gateway SHALL include the provider event last-modification time on every event it returns for range reads, UID lookup, and incremental change reads. A resource that omits that time SHALL still be returned, with the modification time marked absent.

#### Scenario: Event resource includes updated
- **WHEN** the gateway reads a Google event whose resource has a last-modification timestamp
- **THEN** the returned event SHALL carry that timestamp for conflict comparison

#### Scenario: Event resource omits updated
- **WHEN** the gateway reads a Google event whose resource has no last-modification timestamp
- **THEN** the returned event SHALL be usable for existing planning reads and SHALL expose the modification time as absent

### Requirement: Google gateway can list changes since a cursor
The Google Calendar API gateway SHALL list changed and deleted events for one configured calendar since a supplied sync cursor, using bounded pagination, and SHALL return the replacement cursor from the final page. It SHALL keep the existing bounded time-range read unchanged.

#### Scenario: Cursor page completes
- **WHEN** a caller requests changes for one configured calendar since a valid cursor
- **THEN** the gateway SHALL follow provider pages without a time-range filter and SHALL return the changed events plus the replacement cursor from the last page

#### Scenario: Cursor is gone
- **WHEN** the provider responds that the supplied cursor is gone
- **THEN** the gateway SHALL surface that rejection without substituting a partial page or a time-bounded list as if it were the incremental result
