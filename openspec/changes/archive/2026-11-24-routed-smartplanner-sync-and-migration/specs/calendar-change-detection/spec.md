# Spec Delta

## MODIFIED Requirements

### Requirement: Change detection does not scan unrelated calendars
Change detection SHALL poll only explicitly configured legacy calendars or SmartPlanner managed destinations using independent cursor/tag state. Routed manual schedule synchronization SHALL run independently of planning cadence. Configured read-only blocker calendars SHALL remain planning inputs, not writeback targets; unconfigured calendars SHALL receive no list/report requests.

#### Scenario: Multiple configured calendars
- **WHEN** routed change detection runs
- **THEN** each managed destination SHALL be polled with its own cursor/tag and an unconfigured calendar SHALL receive no request

#### Scenario: No planning run is scheduled
- **WHEN** independent sync is enabled and its interval elapses
- **THEN** managed calendar changes SHALL be reconciled without generating or applying a plan

#### Scenario: One destination is unavailable
- **WHEN** a destination poll fails before its changes are safely staged or processed
- **THEN** its cursor SHALL NOT advance, its health/conflict status SHALL be visible, and affected cross-destination operations SHALL wait for reconciliation
