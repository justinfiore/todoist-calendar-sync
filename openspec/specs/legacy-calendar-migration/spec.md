# legacy-calendar-migration Specification

## Purpose

Transfer calendar-event ownership from legacy sync to SmartPlanner through reviewed deletion and verified reconstruction from current Todoist schedules before any planning run.

## Requirements

### Requirement: Migration uses an explicit reviewed manifest
Migration SHALL default to read-only inventory/export. The manifest SHALL bind account, configured calendars, positively identified legacy task UIDs, provider resource IDs/versions, current task associations, complete restorable event payloads, routing snapshot, and exclusions. Deletion SHALL require separate explicit operator approval of that exact manifest and a verified backup.

#### Scenario: Calendar includes unrelated events
- **WHEN** inventory finds appointments, uncertain identities, or unmapped historical events alongside verified legacy projections
- **THEN** only positively identified reviewed legacy resources SHALL be deletion candidates; all exclusions SHALL be visible

#### Scenario: Resource changes after review
- **WHEN** a deletion candidate differs from its reviewed version
- **THEN** deletion SHALL stop for that resource and require reconciliation/review instead of deleting the newer edit

### Requirement: Deletion precedes reconstruction and planning
Migration SHALL require overlapping legacy writes stopped before deletion, all other calendar/task mutation loops held during the handoff, and approved legacy deletions verified absent before the first reconstruction write. First planning runs SHALL remain blocked until sync-only reconstruction parity is verified and the operator separately enables planning.

#### Scenario: Owner executes onboarding
- **WHEN** the manifest is approved and writer exclusivity is verified
- **THEN** migration SHALL delete and verify the approved legacy events, then allow sync-only reconstruction, then verify parity, then expose a separate planning-enable gate

#### Scenario: A deletion remains ambiguous
- **WHEN** one approved deletion cannot be verified
- **THEN** reconstruction/planning SHALL remain blocked and the migration SHALL report the uncertain resource

### Requirement: Reconstruction preserves Todoist's current schedule
Reconstruction SHALL create individual routed planner-owned events from fresh live Todoist Due and resolved duration without optimizing, batching, changing Todoist fields, or initializing lifecycle metadata. Date-only tasks SHALL preserve their local date; undated, ineligible and unroutable tasks SHALL be reported explicitly. Known unsynced calendar edits SHALL be reconciled or explicitly declined before deletion approval.

#### Scenario: Existing task is inconveniently scheduled
- **WHEN** Todoist schedules an eligible task outside working hours
- **THEN** reconstruction SHALL preserve that time and SHALL NOT move it to improve capacity

#### Scenario: Task changes during the gap
- **WHEN** Todoist Due changes after inventory and before reconstruction
- **THEN** reconstruction SHALL use and record the fresh live schedule, verify its event, and SHALL NOT restore inventory Due

### Requirement: Migration recovery preserves evidence and human edits
Migration SHALL persist per-resource deletion, reconstruction and verification outcomes so restart resumes safely. Unknown writes SHALL be reconciled before retry. Rollback SHALL be separately approved, ownership/version checked, and SHALL restore only reviewed missing legacy resources while removing only verified migration-created replacements and preserving subsequent human edits.

#### Scenario: Process crashes after deletion
- **WHEN** deletion completes but reconstruction is interrupted
- **THEN** restart SHALL retain the approved inventory and missing-event evidence, keep planning disabled, and offer resumable reconstruction or reviewed restoration

#### Scenario: Replacement edited before rollback
- **WHEN** a user edits a reconstructed event before rollback
- **THEN** rollback SHALL flag the drift and SHALL NOT delete that event or overwrite corresponding Todoist changes automatically

### Requirement: Operational authorization and credentials remain explicit
Creating the proposal or implementation SHALL NOT authorize production mutations, service enablement, credential copying, or migration execution. Rollout documentation SHALL require authorized runner-side verification of effective rules/account/IDs and isolated live acceptance before production approval.

#### Scenario: Orb lacks runner-local settings
- **WHEN** development cannot inspect the production runner's effective configuration
- **THEN** acceptance SHALL remain blocked on sanitized operator verification and SHALL NOT infer settings from examples or copy QA credentials
