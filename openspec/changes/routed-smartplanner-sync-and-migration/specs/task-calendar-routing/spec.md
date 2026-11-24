# Spec Delta

## Purpose

Route task calendar projections to explicitly managed destinations using the owner's ordered label/project rules, independently of calendar availability classification.

## ADDED Requirements

### Requirement: Task inclusion and destination selection reuse legacy semantics
Routed SmartPlanner SHALL use explicit label-or-project inclusion followed by the first matching ordered destination rule. Label/project matching SHALL retain legacy case sensitivity, exact project names, AND and NOT behavior, and repeated-rule OR behavior. A task without an inclusion match or destination SHALL be reported and withheld from routed writes.

#### Scenario: Project inclusion without an inclusion label
- **WHEN** a task belongs to an included project, lacks included labels, and matches a destination rule
- **THEN** it SHALL remain eligible for projection to that destination

#### Scenario: Several rules match
- **WHEN** a task matches two destination rules
- **THEN** only the first matching rule SHALL determine its destination

#### Scenario: Candidate has no Due yet
- **WHEN** an included routable task lacks Due
- **THEN** sync-only SHALL report it as unscheduled without creating an event, while planning SHALL be allowed to propose a schedule under the separate planner eligibility policy

#### Scenario: Rule does not match
- **WHEN** an included task matches no rule
- **THEN** it SHALL be visibly unroutable and SHALL NOT fall back to an arbitrary output calendar

### Requirement: Projection eligibility is separate from planning eligibility
An included routable dated task SHALL be eligible for current-schedule projection even when its manual label or planner-label policy excludes automatic scheduling. Planner/lifecycle mutations SHALL remain restricted to their own explicitly eligible scope; matching projection inclusion alone SHALL NOT authorize them.

#### Scenario: Manual task is included by project
- **WHEN** a manual dated task belongs to an included project and resolves to a destination
- **THEN** sync SHALL project its current schedule while planning and lifecycle initialization SHALL leave it untouched

### Requirement: Routing configuration is explicit and validated
Every rule destination SHALL map to exactly one explicitly managed calendar in the selected provider. Invalid expressions, unknown destinations, duplicate names/IDs, or ambiguous single-output/routed configuration SHALL fail before network access. Existing single-output configuration SHALL retain its behavior when routed mode is absent.

#### Scenario: Owner imports effective routing
- **WHEN** reviewed rules target J&J, Todoist, Jack, Evan, Emily, Colin, and Kids
- **THEN** each destination SHALL require an explicit provider mapping, and the names alone SHALL NOT imply rules or credentials

#### Scenario: Missing destination mapping
- **WHEN** a rule references an unmapped calendar
- **THEN** startup SHALL fail without resolving credentials or creating an event

### Requirement: Membership and placement are destination aware
Each owned projection SHALL record its destination and occurrence membership. Focus groups SHALL contain only tasks with the same resolved destination as well as existing batching compatibility. Routing changes SHALL preserve schedule and use recoverable ownership-checked relocation, never mutation of unrelated events.

#### Scenario: Same project with different routes
- **WHEN** otherwise compatible tasks resolve to different calendars
- **THEN** planning SHALL NOT combine them into one focus event

#### Scenario: Task route changes
- **WHEN** an owned active task changes labels/project and resolves to another managed destination
- **THEN** sync SHALL relocate its projection without selecting a new work time and SHALL retain recoverable evidence until the old projection is verified removed

#### Scenario: Task loses inclusion
- **WHEN** an active task becomes ineligible or unroutable
- **THEN** sync SHALL retire its verified owned active projection with a receipt, preserve the Todoist task and historical occurrence events, and report the reason

### Requirement: Availability roles do not grant calendar ownership
Routing SHALL NOT determine whether a whole calendar is free or busy. Planning SHALL reconcile owned current projections with live task placements, preserve unrelated blockers and manual placements, and avoid counting a replacement projection twice. Unknown or unavailable required calendar data SHALL NOT be treated as free capacity.

#### Scenario: Managed destination also contains appointments
- **WHEN** a routed calendar contains both owned task events and unrelated appointments
- **THEN** only verified task projections SHALL receive task-aware reconciliation; appointments SHALL retain their configured capacity roles

#### Scenario: Calendar cannot be read
- **WHEN** a required destination/blocker calendar read fails
- **THEN** affected planning/application SHALL be withheld rather than assuming an empty calendar
