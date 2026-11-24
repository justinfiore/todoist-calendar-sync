# Spec Delta

## MODIFIED Requirements

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
