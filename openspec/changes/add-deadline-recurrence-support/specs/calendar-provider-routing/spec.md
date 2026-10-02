# Spec Delta

## MODIFIED Requirements

### Requirement: Provider routing preserves planner safety contracts
Provider selection SHALL not weaken preview no-write behavior, exact approval requirements, safe-only withholding, managed-calendar ownership checks, global UID collision detection, or fully-automated refusal. Todoist Deadline SHALL remain invariant except for validated lifecycle initialization, label-approved legacy conversion, and managed occurrence advancement; those writes SHALL obey the lifecycle's preflight, verification, and recovery contracts.

#### Scenario: Google provider runs preview
- **WHEN** SmartPlanner uses the Google Calendar API provider in preview mode
- **THEN** it SHALL perform only authenticated Google Calendar reads and local state persistence and SHALL send no Google Calendar mutations

#### Scenario: Google provider applies a guarded plan
- **WHEN** SmartPlanner applies a valid approved or safe-only plan using the Google provider
- **THEN** all existing approval and managed-calendar safety checks SHALL be evaluated before the gateway sends a Google Calendar mutation

#### Scenario: Ordinary planner path encounters a Deadline
- **WHEN** any path other than validated recurrence-lifecycle initialization or occurrence advancement schedules a Todoist task
- **THEN** it SHALL preserve Todoist Deadline unchanged

#### Scenario: Validated lifecycle initializes or advances a Deadline
- **WHEN** new-task policy or the legacy onboarding label permits a recurrence-lifecycle operation and all live preconditions match
- **THEN** the selected provider SHALL permit only the validated Deadline mutation required to initialize or advance that occurrence
- **AND** SHALL preserve all other provider, approval, ownership, and apply safety checks

#### Scenario: Lifecycle Deadline precondition does not match
- **WHEN** a Deadline write is requested without valid opt-in, matching occurrence identity, or verified live preconditions
- **THEN** provider routing SHALL reject the mutation before any Todoist or Calendar write
