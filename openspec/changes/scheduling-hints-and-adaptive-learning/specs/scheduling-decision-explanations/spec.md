# Spec Delta

## Purpose

Make schedule proposals understandable through concise explanations of actual placement decisions, including explicit preferences, Deadline compromises, and any learned influence.

## ADDED Requirements

### Requirement: Scheduling explanations have an independent default-enabled toggle
The system SHALL provide an explain-scheduling-decisions configuration toggle, enabled by default and independent of learning. When enabled, proposals SHALL include a concise closing rationale for significant scheduling decisions even when learning is disabled.

#### Scenario: Explain ordinary scheduling without learning
- **WHEN** explanations are enabled and learning is disabled
- **THEN** the proposal SHALL explain significant decisions from the active scheduling factors without claiming learned influence

#### Scenario: Disable explanation without disabling learning
- **WHEN** explanations are disabled and learning is enabled
- **THEN** learning SHALL remain eligible to influence the proposal
- **AND** the explanatory closing section SHALL be omitted without suppressing required risk warnings or approval controls

### Requirement: Explanations reflect the actual saved proposal
The system SHALL ground explanations in the saved proposal's decision evidence rather than invent retrospective reasons or recompute against newer live state. When learning or a Deadline-driven timing exception influenced placement, enabled explanations SHALL identify that influence and distinguish observed patterns from known execution behavior.

#### Scenario: Learned project pattern influences placement
- **WHEN** supported project evidence actually contributes to selecting an evening slot and explanations are enabled
- **THEN** the closing rationale SHALL identify the project-level pattern and its influence without asserting that completion marking time proves evening execution

#### Scenario: Learned grouping crosses project boundaries
- **WHEN** supported learning contributes to grouping tasks across projects by configured label-derived characteristics and explanations are enabled
- **THEN** the closing rationale SHALL identify the grouping rationale without claiming the tasks belong to one project

#### Scenario: Deadline overrides morning timing
- **WHEN** the proposal places a morning-only task in an evening slot to meet a hard or soft Deadline and explanations are enabled
- **THEN** the closing rationale SHALL identify the timing exception and Deadline motivation

#### Scenario: Re-render stored proposal after learning changes
- **WHEN** the same saved proposal is rendered after newer evidence has changed the learning state
- **THEN** its explanation SHALL still describe the evidence and decisions used for that saved proposal
