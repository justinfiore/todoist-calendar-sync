# Spec Delta

## MODIFIED Requirements

### Requirement: Provider routing preserves planner safety contracts
Provider selection SHALL not weaken preview no-write behavior, ordinary Todoist due-only/deadline-invariance behavior, exact Calendar approval requirements, safe-only withholding, managed-calendar ownership checks, global UID collision detection, or fully-automated refusal. Only a separately validated authorized Deadline-risk feedback reply SHALL permit its exact native Deadline or priority edit; it SHALL NOT authorize Calendar apply or broaden ordinary planning writes.

#### Scenario: Google provider runs preview
- **WHEN** SmartPlanner uses the Google Calendar API provider in preview mode
- **THEN** it SHALL perform only authenticated Google Calendar reads and local state persistence and SHALL send no Google Calendar mutations

#### Scenario: Google provider applies a guarded plan
- **WHEN** SmartPlanner applies a valid approved or safe-only plan using the Google provider
- **THEN** all existing approval and managed-calendar safety checks SHALL be evaluated before the gateway sends a Google Calendar mutation

#### Scenario: Authorized mitigation is independent of provider selection
- **WHEN** an authorized user submits a fully validated Deadline-risk mitigation reply for either configured Calendar provider
- **THEN** the app SHALL permit only the requested Todoist Deadline/priority edits through the guarded feedback path
- **AND** SHALL require existing approval and ownership checks before any subsequent Calendar application

#### Scenario: AI planning override remains preview-only
- **WHEN** an existing temporary duration or context override is interpreted or confirmed
- **THEN** it SHALL NOT acquire native Deadline/priority write authority from the risk-feedback exception
