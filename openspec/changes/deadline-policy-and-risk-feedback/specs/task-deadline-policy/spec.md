# Spec Delta

## Purpose

Define consistent hard and soft treatment for native Todoist Deadlines independently of recurrence, while allocating limited scheduling capacity according to commitments, urgency, and remaining opportunity.

## ADDED Requirements

### Requirement: Deadline mode is explicit and independent of recurrence
The system SHALL classify native Deadlines using `default_deadline_mode`, defaulting to `hard` and accepting `hard` or `soft`. Recurrence enablement and whether a task recurs SHALL NOT change classification. Optional configured `hard_label` and `soft_label` SHALL override the default, and both SHALL default to no matching label.

#### Scenario: Default configuration
- **WHEN** deadline-mode and label settings are omitted
- **THEN** native Deadlines SHALL be hard for recurring and non-recurring tasks with recurrence support either enabled or disabled
- **AND** labels literally named `hard` or `soft-deadline` SHALL have no special meaning without configuration

#### Scenario: Soft default and explicit labels
- **WHEN** the default is `soft`, `hard_label` is configured as `firm`, and `soft_label` is configured as `flexible`
- **THEN** unlabeled and `flexible` tasks SHALL receive the same soft treatment
- **AND** a task carrying `firm` SHALL receive hard treatment in either recurrence mode

#### Scenario: Both labels match
- **WHEN** a task has both configured hard and soft labels
- **THEN** hard treatment SHALL win and the proposal SHALL identify the label conflict

#### Scenario: No native Deadline
- **WHEN** a task has a Due date or matching label but no native Deadline
- **THEN** deadline policy SHALL NOT manufacture a Deadline or treat Due as its substitute

### Requirement: Deadline windows are separately configurable
The system SHALL expose separate hard-urgency, soft-urgency, hard-warning, and soft-warning windows, each defaulting to seven local calendar days. Changing a window SHALL NOT implicitly change another. Scheduling SHALL preserve the existing local-date interpretation of native Deadline rather than using a fixed-hour approximation across DST.

#### Scenario: Warning and urgency differ
- **WHEN** hard warning is configured to fourteen days and soft urgency remains at its default
- **THEN** hard-risk reporting SHALL use fourteen local days and soft urgency SHALL use seven
- **AND** hard urgency and soft warning SHALL remain at their configured or default values

### Requirement: Hard deadlines constrain completion and protect needed capacity
The scheduler SHALL reject completion after an effective hard Deadline and give approaching hard commitments strong urgency. It SHALL protect capacity needed to meet feasible hard Deadlines from displacement by soft work. It SHALL preserve manual and frozen placements and report infeasible hard commitments rather than silently scheduling them late.

#### Scenario: Soft work would consume the last hard opportunity
- **WHEN** a soft task competes for the only feasible interval in which a hard task can finish on time
- **THEN** the scheduler SHALL protect that interval for the hard commitment
- **AND** SHALL leave the soft task eligible for another interval, including after its target

#### Scenario: Hard work can safely wait
- **WHEN** a soft task can run first and the plan still has feasible capacity to complete all otherwise feasible hard commitments on time
- **THEN** the scheduler SHALL allow the urgent soft task to precede hard work rather than imposing unconditional hard-first ordering

#### Scenario: Protected placements leave insufficient hard capacity
- **WHEN** manual or frozen placements prevent a hard task from fitting before Deadline
- **THEN** the planner SHALL preserve those placements and report the hard scheduling risk

### Requirement: Soft urgency reflects time and capacity pressure
Soft Deadline SHALL remain a flexible target with increasing urgency as it approaches. Scarcity of all remaining feasible capacity before that target SHALL increase urgency further, accounting for task duration and competing work, not just free time immediately before Deadline. Late placement SHALL remain feasible; overdue soft tasks SHALL retain elevated urgency until completed or their Deadline changes.

#### Scenario: Equal dates but different remaining opportunity
- **WHEN** otherwise comparable soft tasks have equal time to Deadline but one has less usable pre-Deadline capacity relative to its duration and competing work
- **THEN** that task SHALL receive greater capacity-pressure urgency
- **AND** this SHALL NOT waive hard-capacity, manual, frozen, or ordinary feasibility protections

#### Scenario: Target is missed
- **WHEN** a soft task cannot finish until after Deadline
- **THEN** its late placement SHALL remain a valid candidate and its urgency SHALL NOT drop merely because the target passed

### Requirement: Approaching deadlines outrank tasks without Deadline across priority levels
Within its urgency window, even a user-facing P4 soft-deadline task SHALL gain sufficient precedence to outrank P1, P2, and P3 tasks without Deadline, regardless of their Due dates, subject to protected capacity and placements. Urgency SHALL NOT itself mutate native Todoist priority. Hard urgency SHALL be stronger while preserving the hard-capacity policy.

#### Scenario: No-Deadline controls have Due dates
- **WHEN** an approaching soft P4 competes for movable feasible capacity with P1, P2, or P3 tasks that have Due dates but no Deadline
- **THEN** the soft P4 SHALL have greater scheduling precedence than each control
- **AND** native priority values SHALL remain unchanged

#### Scenario: Controls have neither date
- **WHEN** the same competing P1–P3 tasks have neither Due nor Deadline
- **THEN** the approaching soft P4 SHALL still have greater precedence

### Requirement: Deadline interpretation does not grant lifecycle write authority
Classification, urgency, and warning generation SHALL NOT themselves rewrite native Deadline, priority, or Due, enable recurrence management, or authorize Calendar writes. Recurrence advancement SHALL retain its separate guarded lifecycle. Explicit risk-feedback task edits SHALL use the independently authorized feedback contract.

#### Scenario: Deadline-only task is previewed
- **WHEN** a two-hour P2 task has native Deadline but no Due and the planner previews either effective mode
- **THEN** the planner SHALL evaluate that Deadline without creating Due or sending task/Calendar mutations
