# Spec Delta

## Purpose

Make schema-supported AI planning overrides produce observable, temporary changes in a revised proposal only after durable, exact human confirmation, without granting the model provider-write authority.

## ADDED Requirements

### Requirement: Schema-supported overrides survive the confirmation boundary

SmartPlanner SHALL retain validated duration and context-label suggestions without silently dropping their values or targets. A nonempty supported suggestion SHALL produce a bounded confirmation summary describing the actual proposed change. A rejected or empty override result SHALL be distinguished from a pending actionable override.

#### Scenario: Duration estimate awaits confirmation
- **WHEN** an authorized proposal-thread request produces a validated 45-minute duration suggestion for a currently 30-minute task
- **THEN** the confirmation summary SHALL identify the duration change, and the current plan, source tasks and scheduling providers SHALL remain unchanged until confirmation

#### Scenario: Override generation is rejected or empty
- **WHEN** feedback interpretation requests a replan but the associated override generation is rejected or contains no suggestions
- **THEN** the reply SHALL state that no task override is ready and SHALL NOT imply that confirmation will apply the requested duration or context change

### Requirement: Pending suggestions remain bounded and verifiable after restart

Pending override state SHALL bind the exact suggestion content, targets, plan version/hash, planning input, conversation and permitted time range. Restart SHALL restore authority only through validation at the current time. Missing, altered, malformed, legacy untyped or expired AI pending state SHALL require a fresh suggestion rather than be treated as an authorized map.

#### Scenario: Restart occurs before confirmation
- **WHEN** the daemon restarts with a valid pending suggestion and unchanged bound inputs
- **THEN** an authorized exact confirmation SHALL remain able to produce its intended revised proposal without another LLM request

#### Scenario: Pending state cannot establish its identity
- **WHEN** restored state is tampered with, lacks its required validated identity, or has expired
- **THEN** confirmation SHALL be refused with a safe explanation and zero new proposal or provider writes

### Requirement: Confirmation authorizes only the exact current suggestion

An authorized actor's deterministic confirmation SHALL authorize only the displayed pending suggestion set in its current proposal thread. SmartPlanner SHALL verify current plan/version/hash, task and event inputs, target identities, expiry and range before use. A new proposal SHALL require all selected overrides to be valid; none SHALL be applied partially.

#### Scenario: Input changes while confirmation is pending
- **WHEN** a target task, planning-relevant event, bound plan identity or permitted range changes before confirmation
- **THEN** the stale confirmation SHALL produce no revised proposal or provider writes and SHALL request fresh review

#### Scenario: One suggestion in a set is invalid
- **WHEN** a confirmation names multiple suggestions and any selected suggestion is expired, unknown, conflicting or out of bounds
- **THEN** the entire requested override set SHALL be refused rather than applying its valid subset

### Requirement: Confirmed overrides affect temporary planning input

A valid confirmed duration override SHALL change the target's effective duration in the new proposal. A valid context-label override SHALL affect context-sensitive planning for that target. Unaffected tasks, source data and prior plans SHALL remain unchanged; combined valid overrides SHALL compose deterministically without dropping protected task fields.

#### Scenario: Duration change has an observable schedule effect
- **WHEN** a 30-to-45-minute override is confirmed in a fixture with sufficient capacity and an unaffected 30-minute control
- **THEN** the revised proposal SHALL contain a 45-minute target interval, retain the control's 30-minute duration, and explain the temporary change without rewriting either source task

#### Scenario: Context change has an observable planning effect
- **WHEN** a context-label override is confirmed for a task with a corresponding configured context window
- **THEN** the revised proposal SHALL reflect that context in its task input and configured placement behavior, while the source task's labels remain unchanged

#### Scenario: Task copies preserve protected fields
- **WHEN** a confirmed override targets a task carrying recurrence, native Due, Deadline, lifecycle metadata or manual controls
- **THEN** those fields SHALL remain equal in the transformed input except for the explicitly confirmed effective duration or added planning-only context label

### Requirement: Override authority cannot extend its reviewed time scope

The revised planning scope SHALL remain within every selected override's validated range and expiry. Confirmation SHALL NOT silently roll the horizon forward, stretch a suggestion's range, change its targets or carry it into later proposal identities. A suggestion that cannot cover the reviewed scope SHALL be refused and require a new review.

#### Scenario: Confirmation arrives later than suggestion generation
- **WHEN** the configured rolling horizon would now end after the confirmed override's permitted end
- **THEN** planning SHALL use the explicitly reviewed fixed scope if still valid, or refuse it; it SHALL NOT extend the override to the new horizon

### Requirement: Confirmation replay and interruption do not create duplicate authority

Confirmation decisions and the resulting proposal lineage SHALL remain durable across interruption and restart. Repeated delivery of a confirmation SHALL NOT create another effective override or duplicate successor proposal. An uncertain delivery or persistence outcome SHALL preserve reconciliation state instead of blindly restarting generation.

#### Scenario: Confirmation is redelivered after restart
- **WHEN** the same event or equivalent already-resolved confirmation is delivered again after a successful revised proposal
- **THEN** SmartPlanner SHALL retain the existing decision and successor identity without another applicable override, duplicate proposal or provider write

### Requirement: Existing non-AI authority boundaries remain intact

AI override confirmation SHALL generate a preview only, not an apply approval. Existing regex priority, exclusion and freeze behavior SHALL retain its own validation. AI suggestions SHALL NOT modify permanent configuration, source duration/labels, ordinary Deadlines, recurrence tuples or remote scheduling resources before a separately authorized apply.

#### Scenario: Confirmed AI override is previewed
- **WHEN** an authorized duration or context suggestion is confirmed
- **THEN** a new proposal SHALL be persisted and posted while Todoist and Calendar provider inventories remain equal to their pre-confirmation values

#### Scenario: Deterministic exclusion feedback is used
- **WHEN** an authorized configured regex rule supplies an existing valid exclusion or priority override
- **THEN** its established behavior SHALL continue without pretending that this is a schema-supported AI override
