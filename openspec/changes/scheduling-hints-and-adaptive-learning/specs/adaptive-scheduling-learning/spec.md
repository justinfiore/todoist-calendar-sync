# Spec Delta

## Purpose

Improve proposed placement using repeated, attributable scheduling outcomes at task and project levels while avoiding false execution-time assumptions and self-reinforcing planner feedback.

## ADDED Requirements

### Requirement: Collection and learned scheduling are separately opt-in
The system SHALL provide independent collection and learned-scheduling switches, both defaulting to disabled. Collection off SHALL stop new learning observations; learned scheduling off SHALL prevent learned influence. Neither switch SHALL grant additional native Todoist write or Calendar apply authority.

#### Scenario: Defaults collect nothing and use no learning
- **WHEN** both switches are omitted
- **THEN** the planner SHALL collect no new learning observations and SHALL apply no learned placement influence
- **AND** configured timing, energy/focus preferences, and explanations SHALL remain independently usable

#### Scenario: Collect without influencing proposals
- **WHEN** collection is enabled and learned scheduling is disabled
- **THEN** eligible observations SHALL be collected
- **AND** learned preferences SHALL NOT influence placement

#### Scenario: Learned scheduling disabled with existing evidence
- **WHEN** learned scheduling is disabled and stored learned preferences exist
- **THEN** those preferences SHALL NOT influence placement

#### Scenario: Learning enabled with sufficient evidence
- **WHEN** learned scheduling is enabled and a learned preference meets the configured repetition threshold
- **THEN** it SHALL be eligible to influence the next proposal without a separate learned-preference approval step
- **AND** ordinary plan application safeguards SHALL remain unchanged

### Requirement: Disabling collection retains evidence and does not pause decay
Turning collection or learned scheduling off SHALL NOT erase existing evidence or pause its age-based decay. With collection off and learned scheduling on, retained qualifying evidence SHALL remain eligible to influence proposals. Re-enabling either switch SHALL NOT reset evidence ages or count observations again.

#### Scenario: Stop collecting but continue using evidence
- **WHEN** collection is turned off while learned scheduling remains on
- **THEN** no new learning observations SHALL be collected
- **AND** retained qualifying evidence SHALL remain usable with its normally decayed influence

#### Scenario: Re-enable after both switches were off
- **WHEN** learned scheduling is re-enabled after both switches were off for 30 days
- **THEN** retained observations SHALL retain their original identities and timestamps
- **AND** their influence SHALL reflect elapsed time rather than resume from a paused age

### Requirement: Learning affects placement and grouping rather than task precedence
Learned influence SHALL be limited to placement times and grouping under existing priority and Deadline precedence. It SHALL NOT change task precedence, native priority, or Deadline urgency. Learned placement or grouping preferences SHALL NOT displace a higher-precedence task merely to improve a lower-precedence task's placement or bundle.

#### Scenario: Lower-precedence task prefers a scarce morning slot
- **WHEN** learned evidence favors morning placement for a lower-precedence task and the slot is needed to schedule a higher-precedence task
- **THEN** learning SHALL NOT give the lower-precedence task that capacity at the expense of the higher-precedence task

#### Scenario: Same task has several eligible times
- **WHEN** a task can fit at several times without violating existing precedence or feasibility protections
- **THEN** learning SHALL be allowed to influence the chosen time without changing native priority or Deadline

### Requirement: Learned grouping considers projects and label-derived characteristics
Supported learning SHALL be allowed to favor adjacent or bundled placements based on project identity and configured label-derived task characteristics, including across projects. Bundling SHALL preserve separate task identities, durations, and application safeguards. It SHALL NOT infer characteristics from task descriptions or treat a cross-project bundle as a shared project identity.

#### Scenario: Related tasks across projects
- **WHEN** tasks in different projects share configured bookkeeping or low-focus characteristics and qualifying evidence favors grouping them
- **THEN** learning SHALL be allowed to favor placing them together while preserving precedence, timing rules, and feasible Deadline commitments
- **AND** each task SHALL retain its own identity and duration rather than become one merged Todoist task

#### Scenario: Related tasks within one project
- **WHEN** qualifying task or project evidence favors bundling related tasks in the same project
- **THEN** grouping SHALL be eligible to influence their placement without changing native priority or Deadline

#### Scenario: Learned bundle would destroy a hard commitment
- **WHEN** qualified evidence favors a bundle but an ungrouped allocation preserves a hard task's only feasible capacity and the bundle would consume it
- **THEN** the planner SHALL split or decline the learned bundle and retain the feasible hard commitment
- **AND** reservation SHALL evaluate member timing and split fallback consistently with actual placement

### Requirement: Supported learning can outweigh soft preferences but not timing rules
Sufficiently supported learning SHALL be allowed to outweigh configured energy and focus preferences, rather than only break ties. Learning alone SHALL NOT relax an explicit timing category. Deadline-driven timing exceptions SHALL remain available under the separate timing policy.

#### Scenario: Learning outweighs a high-focus morning preference
- **WHEN** supported evidence favors evening placement despite a morning high-focus profile and no timing rule prohibits evening placement
- **THEN** learned influence SHALL be allowed to select the evening slot

#### Scenario: Learning alone cannot break a morning-only rule
- **WHEN** supported learning favors evening placement but a morning-only category applies and no Deadline-driven timing exception is necessary
- **THEN** the planner SHALL retain the timing rule rather than invoke an exception from learning alone

### Requirement: Task evidence has greater influence than project evidence
The system SHALL learn from both task-specific and project-level observations. A configurable task-to-project weighting ratio SHALL default to 2:1, with task-specific learning carrying greater weight. Project evidence SHALL be associated with the observed task's project rather than inferred from shared labels alone.

#### Scenario: Task and project patterns disagree
- **WHEN** equally supported and equally recent task and project evidence favor different periods
- **THEN** under the default ratio the task-specific contribution SHALL have twice the weight of the project contribution

#### Scenario: Labels do not substitute for project identity
- **WHEN** two tasks share a focus label but belong to different projects
- **THEN** the shared label alone SHALL NOT pool their project-level learning

### Requirement: Learning uses attributed outcomes rather than completion-time guesses
The system SHALL support repeated missed scheduled slots and identifiable human reschedules as learning evidence. It SHALL exclude planner-authored moves, their echoes, and moves with uncertain authorship. Completion marking time alone SHALL NOT establish when work was performed. A change not recognized as planner-authored SHALL NOT by itself be classified as human-authored.

#### Scenario: Planner-authored move returns on the next poll
- **WHEN** a task or calendar change matches a recorded planner-authored move
- **THEN** it SHALL NOT count as human rescheduling evidence

#### Scenario: Move could be another automation
- **WHEN** the planner sees a changed task time but cannot confidently attribute it to the human rather than another automation or recurrence advancement
- **THEN** the move SHALL be excluded from learning rather than admitted with weaker confidence

#### Scenario: Human authorizes a native Deadline or priority edit
- **WHEN** an authorized Slack reply changes a native Deadline or priority without identifying a human move of a scheduled execution interval
- **THEN** neither that edit nor its lifecycle acknowledgement SHALL count as human time-of-day or grouping preference evidence

#### Scenario: Authorized edit produces a successor schedule
- **WHEN** the planner generates or applies a successor schedule after an authorized Deadline or priority edit
- **THEN** the generated placements SHALL NOT count as human rescheduling or positive grouping evidence
- **AND** an actually applied slot MAY later qualify for a distinct missed-slot observation under the normal occurrence, grace, and collection rules

#### Scenario: Completion is marked at night
- **WHEN** a task is marked complete at night without evidence of its execution interval
- **THEN** learning SHALL NOT infer that night was its successful execution period

#### Scenario: Applied slot is missed repeatedly
- **WHEN** distinct scheduled slots are observed as missed under the agreed observation rules
- **THEN** those observations SHALL be eligible evidence rather than treating repeated polls of one missed slot as separate misses

### Requirement: Missed-slot evidence waits for a configurable grace period
The system SHALL count an applied scheduled slot as missed only when its task occurrence is observed still incomplete after a configurable grace period following the slot end, defaulting to 24 hours. Such evidence SHALL represent tentative failure to complete as scheduled, not proof of non-execution. Missing or unavailable observations SHALL NOT establish incompletion.

#### Scenario: Late check-off within grace period
- **WHEN** a task occurrence is marked complete before its slot end plus the configured grace period
- **THEN** that slot SHALL NOT count as missed merely because the task was incomplete at slot end

#### Scenario: Still incomplete after default grace period
- **WHEN** the same task occurrence is observed incomplete after 24 hours have elapsed since its applied slot ended
- **THEN** the slot SHALL be eligible as one tentative missed-slot observation under the default grace period

#### Scenario: Provider unavailable
- **WHEN** the planner cannot establish the task occurrence's completion state after the grace period
- **THEN** it SHALL NOT manufacture a missed-slot observation from the failed read

### Requirement: Repetition is configurable and deduplicated
The system SHALL gate each learned pattern on the sum of decayed weights of distinct qualifying observations, defaulting to three fresh-observation equivalents. A fresh observation SHALL contribute one equivalent before decay. Task/project scheduling weights SHALL NOT multiply evidence toward this gate. Repeated reads, redelivery, and restarts SHALL NOT multiply an observation.

#### Scenario: Below and at threshold
- **WHEN** the threshold is three and a pattern's summed decayed evidence weight is below three
- **THEN** that pattern SHALL NOT influence placement
- **AND WHEN** distinct qualifying observations bring its decayed evidence weight to at least three
- **THEN** the repetition gate SHALL permit that pattern's influence

#### Scenario: Old evidence falls below the gate
- **WHEN** three observations with total fresh weight three age by one 30-day default half-life without new observations
- **THEN** their combined weight SHALL be 1.5 and SHALL no longer satisfy the default threshold of three

#### Scenario: Task weighting does not bypass repetition
- **WHEN** a task pattern has decayed evidence weight two and the default task/project scheduling ratio is 2:1
- **THEN** the task pattern SHALL remain below the default repetition threshold rather than qualify with weight four

#### Scenario: Same event survives restart
- **WHEN** the same observation is encountered before and after a daemon restart
- **THEN** it SHALL count once rather than twice

### Requirement: Older evidence loses influence with configurable half-life
The system SHALL decay evidence influence with age using a configurable half-life defaulting to 30 days. With equal source strength, newer evidence SHALL contribute more than older evidence. Passing time alone SHALL NOT create new observations.

#### Scenario: Default half-life
- **WHEN** one qualifying observation is evaluated now and again 30 days later with no other changes
- **THEN** its decayed influence SHALL be half its original influence
- **AND** its identity SHALL remain a single observation
