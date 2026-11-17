# Proposal

## Why

SmartPlanner already supports label-based preferred windows, but cannot model separate energy and focus needs or adapt placement from observed outcomes. The owner wants understandable proposals that reflect explicit timing rules, personal energy/focus profiles, and cautiously learned task/project patterns.

## What Changes

- Preserve three distinct layers: explicit time-of-day rules; energy/focus preferences; and learned adjustments. Configure label meanings and any-of label sets per category; do not infer hints from descriptions or reserve implicit labels.
- Support configurable morning/evening-style timing windows; multiple matching timing categories are alternatives, not an intersection. Respect restrictive timing categories normally, but automatically permit exceptions when necessary to meet either hard or soft Deadlines. Never relax calendar availability, frozen/manual placement protections, or hard Deadline feasibility merely to satisfy a hint.
- Model energy and focus independently as low/medium/high task needs and local time/day-of-week profiles. The highest matching category level wins independently for each dimension. These are recommendations, not feasibility constraints.
- Add optional learning from repeated missed scheduled slots and identifiable human reschedules. Missed-slot evidence waits for a configurable grace period, defaulting to 24 hours after slot end, and remains tentative rather than proof of non-execution. Learn at task and project levels with a configurable task-to-project weighting ratio defaulting to 2:1. Exclude planner-authored and uncertain-authorship moves, including ambiguous recurrence/automation changes; do not interpret completion marking time as execution time.
- Provide separate switches for observation collection and learned scheduling, both off by default. Support collection without scheduling influence; collection off stops new observations. Once learned scheduling is enabled and sufficiently supported, it may influence proposals without separate approval for each learned preference.
- Retain existing evidence when either switch is disabled and keep its age-based decay running. Learned scheduling may use qualifying retained evidence while collection is off; re-enabling does not reset evidence ages.
- Gate learning on summed decayed evidence from distinct observations, with a configurable threshold defaulting to three fresh-observation equivalents. Decay evidence using a configurable half-life defaulting to 30 days; old patterns can fall below the gate. Keep evidence qualification separate from the task/project scheduling-weight ratio.
- Limit learning to placement-time and grouping/bundling choices, not task precedence, native priority, or Deadline urgency. Grouping may use both project identity and configured label-derived characteristics, including across projects, without merging native tasks. Supported learning may outweigh energy/focus preferences, not just break ties, but cannot override explicit timing categories; those retain the separately authorized hard/soft Deadline exception.
- Add a separate scheduling-explanation toggle, enabled by default. Append a concise rationale covering significant placement choices, compromises, and any learning that actually influenced the proposal; this also works with learning disabled.
- Preserve ordinary proposal/application authorization and Deadline policy. This change grants no new native Todoist edits or Calendar apply authority.

## Capabilities

### New Capabilities

- `task-scheduling-hints`: Configurable any-of label classification, deadline-relaxable timing windows, and independent energy/focus matching.
- `adaptive-scheduling-learning`: Attributed, deduplicated, decaying task/project evidence and bounded placement/grouping influence, including characteristic-based cross-project bundles.
- `scheduling-decision-explanations`: Optional, evidence-grounded explanations rendered from the saved proposal.

### Modified Capabilities

None. Existing provider/writeback safety requirements remain in force. Learning consumes attributable observations without changing writeback authorization.

## Impact

- Extend existing `PlannerConfig.tasks.contexts`, `TaskContext`/`PreferredWindow`, `PlanScorer`, and `DeterministicScheduler` rather than building an unrelated hint engine. Keep existing context-only configurations working.
- Extend stored plan evidence and `MessageRenderer`; connect observations to existing application mappings, recurrence occurrence identity, and writeback echo detection. Current task reads alone do not establish completion, authorship, or a full history.
- Add durable local learning state, deterministic time-controlled tests, configuration examples, and documentation. No live account data is needed to specify or test this behavior.
- Coordinate against the separate, unmerged `deadline-policy-and-risk-feedback` change (PR #21); its independent hard/soft policy is the intended compatibility target, not the older recurrence-gated policy in this checkout.
- Delivery is planning only: commit the completed artifacts on a new branch and open a PR as requested. No implementation, merge, deployment, runner access, service restart, feature enablement, or live provider mutation is authorized.
- Review draft: requirements reflect the owner's decisions. Design and tasks describe a future implementation; artifact existence is not implementation approval.

## Design Boundaries

- Move attribution must be supported by actual provenance; absence of a matching planner write is insufficient. If provider reads cannot establish authorship, exclude the move rather than guess. Missed slots require observed incompletion after the configured grace period; unavailable observations do not count.
- Exact bounded placement/grouping-score coefficients remain an implementation-design choice within the confirmed precedence protections. Defaults are three decayed fresh-observation equivalents, a 30-day half-life, and a configurable 2:1 task/project weighting ratio; learning must be capable of outweighing energy/focus preferences without changing task precedence.
