# Spec Delta

## ADDED Requirements

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
