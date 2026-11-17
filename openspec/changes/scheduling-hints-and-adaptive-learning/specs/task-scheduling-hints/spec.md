# Spec Delta

## Purpose

Match tasks to suitable local times using explicit label-based timing rules and independent energy and focus preferences, without compromising Deadline policy or calendar safety.

## ADDED Requirements

### Requirement: Scheduling categories use configurable any-of label sets
The system SHALL allow each scheduling category to define its meaning and a set of matching Todoist labels. Any one matching label SHALL activate that category. This capability SHALL use labels only, without task-description analysis or implicit reserved label names.

#### Scenario: Any listed label activates a category
- **WHEN** a focus category lists personal-coding, focus, and scouts as matching labels and a task has only scouts
- **THEN** the task SHALL receive that category's configured focus requirement

#### Scenario: Unconfigured labels and description text do not classify tasks
- **WHEN** a task's description mentions high focus but its labels match no configured category
- **THEN** that description SHALL NOT assign an energy, focus, or timing category

### Requirement: Timing categories define deadline-relaxable local windows
The system SHALL support configurable time-of-day categories such as morning-only and evening-only. Scheduling SHALL respect their configured windows normally, but SHALL permit an out-of-window proposal without separate permission when necessary to meet a hard or soft Deadline. This exception SHALL NOT authorize violating availability, frozen/manual placement protections, or hard Deadline feasibility.

#### Scenario: Timing rule can be satisfied before Deadline
- **WHEN** a task has a morning-only category and sufficient feasible morning capacity exists before its Deadline
- **THEN** the proposed placement SHALL respect the morning window rather than invoke a Deadline exception

#### Scenario: Soft Deadline requires a timing exception
- **WHEN** a task with a soft Deadline cannot fit before it in its configured morning window but can fit in otherwise eligible evening capacity
- **THEN** the planner SHALL permit an evening placement without requiring separate exception approval
- **AND** it SHALL record the timing exception for scheduling explanations

#### Scenario: Deadline exception does not create availability
- **WHEN** meeting a hard Deadline outside the task's timing window would also require overlapping a hard calendar block
- **THEN** the timing exception SHALL NOT make that blocked capacity eligible

### Requirement: Multiple timing categories offer alternative windows
When several timing categories match a task, the system SHALL treat their windows as alternatives rather than require their intersection. A placement satisfying any matching timing category SHALL satisfy the task's timing rule without a Deadline exception.

#### Scenario: Morning and evening both match
- **WHEN** a task matches morning and evening timing categories
- **THEN** either configured window SHALL be acceptable
- **AND** an afternoon placement outside both windows SHALL still require the normal Deadline-driven exception

### Requirement: Energy and focus are independent recommendations
The system SHALL support separate low, medium, and high energy and focus needs and corresponding configurable local time-of-day/day-of-week profiles. Matching SHALL influence placement preferences rather than impose feasibility constraints. A task SHALL be able to have both an energy need and a focus need.

#### Scenario: Energy and focus differ
- **WHEN** a task requires high energy and low focus and another requires low energy and high focus
- **THEN** the planner SHALL evaluate those needs independently against the configured profiles rather than treat both tasks as one effort category

#### Scenario: Weekday and weekend profiles differ
- **WHEN** configured focus is high on weekday mornings and high on weekend afternoons
- **THEN** matching SHALL use the profile for the candidate's local day and time

#### Scenario: Low-focus slot remains eligible
- **WHEN** a high-focus task has no high-focus slot available before its hard Deadline but an otherwise eligible low-focus slot fits
- **THEN** focus mismatch alone SHALL NOT prohibit placement in that slot

### Requirement: Highest matching energy and focus levels win independently
When multiple configured categories assign different levels to a task, the system SHALL select the highest matching level for energy and separately for focus, ordered low, medium, high. Classification SHALL NOT depend on label order or promote one dimension merely because the other is high.

#### Scenario: Conflicting levels resolve independently
- **WHEN** matching categories assign low and high focus and low and medium energy
- **THEN** the task SHALL be classified as high focus and medium energy, regardless of label order

### Requirement: Existing context preferences remain compatible
The system SHALL preserve existing label-based preferred-window configuration behavior when no new hint categories or profiles are configured and learning is disabled. Adding this capability SHALL NOT silently reinterpret existing labels as restrictive timing rules or energy/focus levels.

#### Scenario: Existing context-only installation
- **WHEN** configuration contains only previously supported context labels, preferred windows, bonuses, and penalties
- **THEN** those settings SHALL retain their existing preference meaning
