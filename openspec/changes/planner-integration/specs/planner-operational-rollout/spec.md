## ADDED Requirements

### Requirement: Manual testing begins with isolated provider data
The end-to-end guide SHALL direct the operator to create dedicated test Todoist projects/labels and dedicated test calendars, use least-privilege credentials, create representative tasks/events, and export or back up the isolated data before enabling writes.

#### Scenario: Isolated test environment is prepared
- **WHEN** an operator follows the initial setup
- **THEN** production task lists and calendars SHALL not be configured as the output targets and the test dataset SHALL cover due/deadline variants, duration sources, manual exclusions, priorities, blockers, informational events, all-day events, unknown calendars, and unschedulable work

### Requirement: Test-account validation has explicit read and write gates
The guide SHALL specify commands, expected artifacts, remote-system observations, and pass/fail criteria for capacity, preview, missing/stale approval refusal, exact approval, idempotent rerun, safe-only application, managed deletion/reconciliation, and optional integrations.

#### Scenario: Isolated preview gate
- **WHEN** the operator runs capacity and preview in `preview` mode
- **THEN** the guide SHALL require validation of normalized inputs, classification, capacity, schedule, risk/diff/unscheduled explanations, local state, and verified zero Todoist/CalDAV writes

#### Scenario: Isolated approval gate
- **WHEN** the operator tests `approval_required`
- **THEN** the guide SHALL first require missing and incorrect approval refusals and then an exact small approval whose resulting managed events, due datetimes, invariant deadlines, and receipts are inspected

#### Scenario: Isolated safe-only gate
- **WHEN** the operator tests `apply_safe_changes`
- **THEN** the guide SHALL require a mixed plan and verify that only ordinary changes apply while protected changes are listed as withheld

### Requirement: First production use follows crawl, walk, run
The production procedure SHALL advance through `preview`, `approval_required`, and `apply_safe_changes` only after explicit acceptance gates. It SHALL state that `fully_automated` is unavailable and not a rollout stage.

#### Scenario: Crawl uses preview
- **WHEN** production testing begins
- **THEN** the operator SHALL back up provider data and all planner state, disable optional integrations, use a narrow horizon, verify calendar ownership and unknown-calendar diagnostics, repeat the preview for stability, and confirm zero remote writes

#### Scenario: Walk uses exact approval
- **WHEN** crawl gates pass
- **THEN** the operator SHALL grant minimal write permissions, generate a fresh `approval_required` plan, exercise refusal cases, apply one small exact approval, and observe receipts, deadline invariance, idempotency, and live resources before proceeding

#### Scenario: Run uses safe-only application
- **WHEN** walk gates pass without unexplained outcomes
- **THEN** the operator SHALL use `apply_safe_changes` with a short horizon, review receipts daily, keep protected changes withheld, and expand scope only after multiple clean runs

#### Scenario: Fully automated mode is not used
- **WHEN** the operator considers an automatic stage
- **THEN** the guide SHALL explain that `fully_automated` intentionally fails closed and SHALL not instruct the operator to enable it

### Requirement: Rollback and reconciliation are executable
The guide SHALL identify backup scope, stop conditions, evidence retention, remote comparison/restoration, four-store snapshot restoration, return-to-preview procedure, and the prohibition on selective state deletion or blind retry after ambiguous success.

#### Scenario: Unexplained or ambiguous write occurs
- **WHEN** a provider result cannot be reconciled immediately or an unexpected mutation is observed
- **THEN** the operator SHALL stop apply/delivery, retain logs/receipts/state, compare live resources to backup, restore remote data and the matching complete state snapshot as needed, and resume only in `preview` after diagnosis

### Requirement: Production advancement is checklist-gated
Each stage SHALL provide checkboxes for prerequisites, commands, observations, acceptance criteria, rollback readiness, and an explicit decision to continue or stop.

#### Scenario: A gate is incomplete
- **WHEN** any checklist item or expected observation fails
- **THEN** the operator SHALL remain at or return to the current safer mode and SHALL not advance to the next stage
