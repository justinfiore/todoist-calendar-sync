# Tasks

All tasks below are future implementation work. Preparing this checklist does not authorize implementing it, enabling features, or accessing live providers or the Nextcloud runner.

## 1. Configuration and label-derived hints

- [ ] 1.1 Extend `PlannerConfig` contexts with optional timing mode and energy/focus levels, add personal profiles and independent learning/explanation settings, and verify defaults plus invalid-value rejection in `PlannerConfigSpec` before provider access.
- [ ] 1.2 Resolve any-of category labels, union timing windows, and independent maximum energy/focus levels; verify label-order independence, unrelated labels, and high-focus/medium-energy asymmetric cases through public task classification or scheduling behavior.
- [ ] 1.3 Implement local weekday/time profile matching and neutral uncovered dimensions; verify different weekday/weekend profiles, DST boundaries, invalid contradictory overlaps, and preservation of existing context-only configurations.
- [ ] 1.4 Document label mappings, profile semantics, all defaults, and four collection/influence switch combinations in configuration examples and `docs/SMART_PLANNER_CONFIGURATION.md`; verify examples parse and both learning switches default off while explanations default on.

## 2. Timing feasibility and placement scoring

- [ ] 2.1 Integrate full-interval timing-category checks into `DeterministicScheduler`, preserving legacy preferred-window overlap behavior; verify morning-or-evening acceptance, rejection outside both without a Deadline exception, and a task that starts inside but ends outside a required window.
- [ ] 2.2 Add hard/soft Deadline-driven timing relaxation with saved exception reasons; verify in-window-first behavior and out-of-window rescue for each mode without admitting hard calendar conflicts, frozen/manual violations, or sacrificing hard Deadline feasibility.
- [ ] 2.3 Add independent energy/focus placement terms without changing Deadline/priority task precedence; verify competing morning/evening placements with different energy/focus profiles and a hard-Deadline control where low suitability cannot prevent feasible placement.
- [ ] 2.4 Update configuration documentation with timing-rule versus preference examples and Deadline exceptions; verify the examples correspond to the scheduler scenarios rather than describing timing rules as unconditionally hard.

## 3. Durable observations and attribution

- [ ] 3.1 Add the versioned local learning ledger using existing atomic-state patterns; verify deduplication across repeated polls, redelivery, reconstructed store instances, and persisted observation/occurrence identity without descriptions or credential material.
- [ ] 3.2 Collect tentative misses only for actual applied task occurrences after configured slot-end grace; verify before/after the 24-hour boundary, late completion within grace, recurring advancement, vanished tasks, unavailable reads, and no evidence from unapproved previews or collection-disabled periods.
- [ ] 3.3 Connect positively attributable human moves to a supported read-only production provenance source; verify real source-shaped fixtures, exclusion of planner receipts/echoes, recurrence advancement, other automation, and unknown authorship. Document unavailable source coverage explicitly and leave this task incomplete if only synthetic attribution is implemented.
- [ ] 3.4 Wire collection separately from influence in production orchestration; verify all four switch combinations, zero learning-specific reads/writes with collection off, retained evidence on disable/re-enable, unchanged original timestamps, and ordinary preview/provider safety.
- [ ] 3.5 Document the evidence source, tentative meaning of missed slots, 24-hour configurable grace, privacy scope, and authorship limitations; verify no explanation claims a check-off time proves execution time or an unknown move proves human preference.

## 4. Qualified placement and grouping influence

- [ ] 4.1 Implement fixed-reference-time half-life decay and per-pattern task/project gates before applying scheduling weights; verify weight one at age zero, half at 30 days, three aged observations totaling 1.5, threshold boundaries, and a 2:1 multiplier unable to bypass qualification.
- [ ] 4.2 Combine bounded qualified task/project placement preferences with configurable default 2:1 weighting; verify conflicting equally supported patterns, decayed evidence falling below the gate, supported learning outweighing an energy/focus preference, and no native priority/Deadline changes.
- [ ] 4.3 Extend `ProjectBatcher`/candidate construction for qualified project and label-characteristic grouping across projects, auditing single-project assumptions in `SchedulingUnit` and renderers; verify preserved member identities, projects, durations, timing, buffers, and single-task fallback.
- [ ] 4.4 Verify grouping actually responds to qualified evidence using grouped/ungrouped controls and scarce asymmetric capacity; assert that learning cannot crowd out higher-precedence work, relax timing on its own, or turn a planner-authored arrangement into self-supporting evidence.
- [ ] 4.5 Document decayed threshold equivalents, the 30-day half-life, 2:1 task/project ratio, and placement/grouping-only influence; verify documented calculations and defaults against clock-controlled regression scenarios.

## 5. Saved-plan explanations and production integration

- [ ] 5.1 Persist meaningful timing/profile/learning/grouping decision evidence in `PlanningExplanation` and `PlanStore`, integrating relevant inputs with plan identity; verify snapshot round-trip, old-plan compatibility without fabricated reasons, and stable explanations after live learning state changes.
- [ ] 5.2 Append a concise closing rationale in `MessageRenderer` controlled by the default-enabled explanation toggle; verify learning-off explanations, actual learned contributions, cross-project grouping, Deadline timing exceptions, and toggle-off omission without losing risk warnings or approval controls. Document the output and toggle alongside the implementation.
- [ ] 5.3 Exercise the production daemon path with hermetic Todoist/calendar fixtures and a restart: observe an applied slot, collect/deduplicate evidence, cross the decayed gate, produce an influenced saved proposal, and assert its actual messaging payload and unchanged apply authorization. Include collection-only and uncertain-authorship controls rather than testing only injected scoring helpers.
- [ ] 5.4 Run targeted planner suites, the full hermetic Gradle suite, and `openspec validate scheduling-hints-and-adaptive-learning --strict`; report exact results and any provenance limitations. Resolve compatibility with the independent Deadline-policy change before claiming completion; do not use live providers, enable the runner's SmartPlanner, restart services, or deploy as validation.
