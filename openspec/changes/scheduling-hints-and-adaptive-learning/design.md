# Design

## Context

See `proposal.md` for motivation and the three capability deltas for behavior. Source inspection used `77d8fbb301ffb271e0ba6f8f05da32011838d8f8`, which also matches `origin/master` fetched in this orb when creating the plan branch. The separate Deadline policy implementation is PR #21; this plan does not copy its changes or depend on recurrence-gated hardness surviving.

Existing extension points:

- `PlannerConfig.contextsFor` already matches any context label case-insensitively. `TaskContext` contains preferred windows and score adjustments, but no energy/focus levels or restrictive window mode.
- `PreferredWindow` parses day groups and local hours. Existing preference scoring uses overlap, not full containment; preserve that behavior for old contexts.
- `PlanScorer` combines priority, urgency, contexts, batching, and stability. `DeterministicScheduler` and `ProjectBatcher` build candidate units; the latter currently groups only within a project and compatible context signature. Cross-project learned grouping therefore needs changes at candidate generation, not just a new score term.
- `Task` has task/project identity, labels, duration, and recurring occurrence identity (`taskId`, `completedCount`). Task snapshots and `updated_at` do not establish who moved a task, and disappearing from open tasks does not establish completion.
- `Plan` already holds `PlanningExplanation` entries and `PlanStore` persists snapshots. Reuse these rather than generate explanations independently after scheduling.
- Application mappings and calendar writeback echo detection distinguish known planner writes. They do not prove every other change was human-authored.

## Goals / Non-Goals

**Goals:** Extend existing scheduling/configuration boundaries; make learning deterministic for fixed configuration, observations, and reference time; keep proposal explanations tied to the saved decisions; preserve provider and apply safety.

**Non-goals:** Task-description analysis, LLM-generated labels, estimating actual execution times from check-offs, changing Todoist priority/Deadline, changing task precedence, merging tasks, automatic Calendar application, modifying the legacy service, or accessing the runner. This change is planning only; any future implementation needs separate authorization.

## Decisions

### 1. Extend contexts and add separate personal profiles

Keep `planner.tasks.contexts` as the label classification source. Add optional energy/focus levels and a timing rule mode to its entries; reuse `match_labels` and local window syntax. Categories have no implicit labels. Keep existing contexts as preferences unless the new timing mode is explicitly selected. Resolve matching windows as a union and dimension levels as independent maxima, independent of label order.

Represent energy/focus availability in a new `planner.scheduling_profiles` section using the same day/local-window parser and planner timezone. Use low/medium/high levels; uncovered periods and tasks lacking a dimension are neutral for that dimension, not implicitly low. Reject contradictory overlapping profile levels instead of depending on config-map order. Validate profiles and hint fields before provider I/O. Preserve the current parser's overnight-window limits and document splitting an overnight profile at midnight rather than silently changing old window semantics.

Alternative: reserve morning/focus labels globally or parse descriptions. Rejected: the owner requires configurable label sets and labels-only interpretation in this change.

### 2. Separate feasibility, precedence, and placement preference

Retain calendar capacity, hard Deadline feasibility, and frozen/manual protections as non-negotiable. Keep the Deadline policy's task precedence independent of hint and learned scores. Restrictive timing windows require the task's full placement interval to fit a matching window; retain overlap scoring for legacy preferred windows.

Evaluate timing-conforming placement before Deadline-driven exceptions. If no conforming allocation can meet a native hard or soft Deadline but an otherwise eligible allocation can, permit out-of-window placement and save its reason. Never treat a high learned score as authorization for that exception. Check member durations, individual windows, and individual Deadlines inside every bundle. A unit that cannot meet those checks must be split or fall back to single-task placement.

Energy/focus mismatch is a bounded placement preference, not an exclusion. Apply learned terms only among feasible placement/grouping alternatives that preserve precedence; do not insert them into the task-order comparator. Candidate scoring needs an explicit boundary here because the existing scorer mixes urgency and placement terms.

Alternative: add one large learning bonus to the existing combined task/placement score. Rejected: it could accidentally reorder scarce-capacity allocation and defeat the owner's placement-only decision.

### 3. Use distinct collection and influence controls

Proposed configuration keys under `planner.learning`:

- `collect_observations: false`
- `use_learned_preferences: false`
- `evidence_threshold: 3`
- `evidence_half_life_days: 30`
- `missed_slot_grace_hours: 24`
- `task_to_project_weight_ratio: 2`

Both booleans default off. Collection-only mode records evidence without applying it. Influence-only mode reads retained evidence without collecting more. No learning-specific provider requests or observation writes occur when collection is off; existing unrelated planner reads continue normally. Both off avoids learning-state work beyond checking the configuration. Retained timestamps are never reset; compute decay at evaluation time, so turning switches off needs no background decay process.

Reject non-boolean switches, nonpositive thresholds/half-lives, negative grace durations, and task/project ratios that do not give task-level evidence greater weight. These key names and validation details are design choices, not new labels the owner must create.

### 4. Keep an occurrence-aware local evidence ledger

Add one versioned learning store following the project's atomic-write patterns. Record minimal facts: observation ID, task/project identity at observation time, occurrence identity, applied slot identity/start/end, normalized configured characteristics, outcome type, reference time, and attribution provenance. Do not retain task descriptions, credentials, or provider payload backups. Store stable observation identity, not just mutable aggregate counters, to deduplicate polling and restarts.

Collect missed-slot evidence only for actual applied slots, never proposed-but-unapproved ones. An unchanged task occurrence observed still incomplete after slot end plus grace is eligible. Completion before grace excludes the miss; missing tasks, failed reads, and unresolvable recurrence transitions are unknown, not misses. Use recurring occurrence advancement to avoid attaching the old slot's miss to the new occurrence. Do not backfill observations from periods when collection was disabled simply because the task is still overdue on re-enablement.

For moves, require positive provenance identifying a human action plus source/target placement. Known application receipts and writeback echoes exclude planner-origin changes; native recurrence transitions are a separate class. Unknown authorship is excluded, not given a weak weight. Existing REST task timestamps alone cannot support the human-move path. The implementation must connect a supported read-only provenance source when available and document its actual coverage; it must not claim that excluding known planner writes proves authorship. If no supported source supplies provenance, that class remains unavailable and the limitation must be reported before claiming implementation complete.

Use a move to support a tentative placement preference toward the chosen period and away from the abandoned period, not proof of execution there. Misses provide negative evidence about a period. Completion marking time is never positive evidence of an execution period. Cross-project grouping uses configured characteristics plus qualifying task/project evidence; do not introduce an unapproved global label-level evidence pool.

### 5. Gate on decayed evidence before applying task/project weights

For an observation with age in days \(a\) and configured half-life \(h\), compute \(w(a)=2^{-a/h}\). Each distinct fresh observation starts at weight one. Gate each pattern at each task/project scope using the sum of these weights against the threshold; apply the task/project multiplier only after qualification. At the default settings, three observations aged 30 days total 1.5 and no longer qualify.

Keep directional support for competing patterns rather than turning every observation into an accumulating positive bonus. Normalize qualifying task and project contributions separately so a large project history cannot win merely by volume, then combine using the configurable default 2:1 ratio. Bound total learning placement influence; select coefficients capable of outweighing an energy/focus preference while never outranking feasibility/precedence. Store the chosen score components and evidence snapshot with the plan. Exact scale is an implementation detail to validate using competing-placement fixtures, not a product tuning questionnaire.

Older evidence is retained while switches are off and continues to age. This first change does not introduce destructive automatic pruning or a new reset command.

### 6. Extend grouping candidates without fabricating a shared project

Extend the current batching path to consider both project-related and characteristic-related candidates when learned scheduling is enabled and qualifying grouping evidence exists. Consider cross-project candidates by configured context/characteristic signatures rather than descriptions. Keep project identity on each member; audit `SchedulingUnit` and renderer assumptions that currently describe one project. Respect the existing batching enablement, duration limits, buffers, and single-task fallback.

A grouping preference chooses adjacency/packaging among safe placement alternatives; it does not consume capacity needed by higher-precedence work. Compare against an ungrouped control and retain member-level intervals for apply. Grouping observations must describe actual applied arrangements or positively attributed human rearrangements, not preferences the planner only proposed. Merely grouping tasks once does not create evidence supporting its own decision.

### 7. Render a concise deterministic explanation from saved evidence

Add `planner.messaging.explain_scheduling_decisions: true`, independent of both learning switches. Populate `PlanningExplanation` entries during scheduling with significant factors: timing compliance/exception, energy/focus suitability, qualified task/project learning, and learned grouping. Persist evidence reference time, applicable configuration identity, and contribution summaries as part of the plan snapshot; integrate schedule-affecting inputs with existing plan identity/approval checks.

`MessageRenderer` appends a concise closing paragraph from these entries. Do not call an LLM just to rationalize the schedule. When the toggle is off, omit only that explanatory section, not risk warnings, conflicts, or approval information. Re-rendering a saved plan must not consult today's learning state to explain yesterday's choices. Existing generic score-only diagnostics are not sufficient to claim that a learned pattern actually influenced a decision.

## Risks / Trade-offs

- Sparse or ambiguous provider evidence → Exclude unknowns, deduplicate actual observations, and explain tentative missed-slot evidence honestly. A missed task is not proof that the time of day caused the miss.
- A 24-hour grace can span recurrence advancement → Key slots to occurrences; do not use a new occurrence's open state as evidence that the previous occurrence was missed.
- Decayed threshold of three is stricter than three lifetime events → Document that several separated observations may be needed; use fixed-clock boundary tests, not approximate lifetime counts.
- Bundles can hide precedence changes → Assert protected tasks still fit and compare grouped versus single-member alternatives under scarce asymmetric capacity.
- Personal profiles can conflict or leave gaps → Validate contradictions, use neutral uncovered periods, and preserve planner-timezone semantics including DST.
- Completion/activity provenance may be unavailable → Do not replace real provenance with synthetic fields in tests and call the feature production-ready. Report source limitations and retain conservative behavior.

## Migration Plan

This plan adds no new implicit label interpretation and leaves collection/influence off by default. Existing context-only scheduling remains compatible; the new explanation section is on by default and can be disabled separately. Read old plan snapshots without inventing learning reasons and write new evidence fields with explicit schema compatibility.

Implement against the resolved Deadline-policy dependency, not the older recurrence-gated classifier. Review configuration examples before enabling either learning switch. Rollback at configuration level disables learned influence and collection without deleting evidence; reverting a future binary must preserve old-plan loading compatibility. No merge, rollout, runner changes, or service actions are part of preparing this plan.
