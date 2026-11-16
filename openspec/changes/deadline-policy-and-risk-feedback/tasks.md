# Tasks

All tasks are initially unchecked because this commit delivers planning only. The owner authorized implementation in a separate orb using Grok 4.7 medium, followed by verification, commit/push, and a PR on the same branch. Do not access the runner, restart services, enable SmartPlanner/recurrence, mutate live accounts, merge, or deploy.

## 1. Explicit Deadline Configuration and Classification

- [x] 1.1 Add recurrence-independent default mode and optional hard/soft label settings in `PlannerConfig`, with both labels disabled by default; extend `PlannerConfigSpec` to verify hard default, soft selection, both recurrence states, blank labels, invalid modes, case matching, and identical-label rejection, and update the relevant config example/docs alongside the parser.
- [x] 1.2 Add independent hard/soft urgency and warning windows defaulting to seven local days, remove recurrence-policy fallbacks, and provide migration errors for old explicit nested policy keys; verify custom windows do not alter each other, invalid windows are rejected, and documented migration examples parse successfully without changing runtime configuration.
- [x] 1.3 Unify effective-policy use across scorer, scheduler, clipping, retained blocks, focus members, and explanations; extend existing scheduling specs to verify configured labels override either default, both labels resolve hard with a conflict note, default-soft and label-soft are equivalent, and Due-only tasks do not acquire Deadline semantics.

## 2. Scheduling Precedence and Capacity Pressure

- [x] 2.1 Implement hard-capacity preservation while allowing earlier soft work when hard commitments can still fit; extend `DeterministicSchedulerSpec` with asymmetric last-slot and safely-deferrable-hard cases plus mixed hard/soft focus members, verifying no late hard completion, no unconditional distant-hard-first ordering, and preserved manual/frozen blocks; document the policy with those examples.
- [x] 2.2 Implement increasing soft date urgency plus remaining-capacity pressure that accounts for duration, task eligibility, and competing work without double-counting intervals; verify independently derived constrained-versus-roomy fixtures produce different scheduling precedence and overdue soft tasks retain elevated urgency, and document the bounded scarcity estimate and unknown-coverage behavior.
- [x] 2.3 Enforce approaching soft P4 precedence over each P1–P3 no-Deadline control, both with and without Due; verify actual contested-capacity scheduler outcomes rather than score positivity alone, unchanged native priorities, and hard-needed capacity protections using existing scheduling fixture/spec patterns.
- [x] 2.4 Cover independent local-day urgency boundaries, exact end-of-Deadline-day completion, DST transitions, and stale effective-policy changes in scheduler/plan identity tests; verify persisted proposals and approvals cannot silently reuse a changed mode/label/window policy, and document the civil-date behavior.

## 3. General Risk Inventory and Slack Tables

- [x] 3.1 Derive risk from unscheduled or incompletely/late scheduled tasks using per-task member completion, with independent hard/soft warning windows and explicit beyond-horizon uncertainty; extend renderer/service specs with on-time, late, unscheduled, overdue, out-of-window, mixed recurring/non-recurring, and focus-member controls, and document the difference between risk and proven impossibility.
- [x] 3.2 Persist stable proposal/thread-bound warning numbers and task context through existing plan/conversation state; verify restart restores exact mappings, a later proposal cannot remap old numbers, and warning rows retain task/project/Deadline/mode/P1–P4 data; update the feedback documentation with the mapping lifetime.
- [x] 3.3 Add structured native Slack table payloads and task hyperlinks through the actual messaging adapter, preserving thread identity, text fallback, and stable-number chunking; verify injectable HTTP payloads for all required columns, long/untrusted titles, duplicate task names across projects, escaped content, and provider size limits without dropping warnings, and document compact numbered reply examples.

## 4. Bounded Natural-Language Mitigation Replies

- [x] 4.1 Extend the existing application LLM boundary with typed complete-action or clarification results for risk feedback, without widening existing temporary override authority; add schema/context tests for allowed task numbers, exact resolved dates, user-facing priorities, unknown fields/targets, unavailable/malformed responses, and unauthorized/thread-mismatched input; document the new directly authorized reply boundary.
- [x] 4.2 Route concise multi-instruction replies with task number anywhere in its instruction line, fuzzy/absolute dates, and current-Deadline-relative delays through that interpreter; verify controlled LLM fixtures for “1 elevate to P2”, “change deadline to Dec 5 for 2”, and “3 push back two weeks”, preserving timezone/reference context and native P1–P4/API mapping without requiring rigid syntax.
- [x] 4.3 Implement same-thread clarification when any instruction is unresolved or conflicting, keeping the whole batch non-mutating until every instruction resolves; verify a mixed clear/ambiguous reply produces a Slack question and zero Todoist writes, and clarification continuation preserves proposal mapping and prior date reference rather than treating the answer as unrelated feedback; document the interaction.

## 5. Guarded Native Task Edits and Recovery

- [x] 5.1 Add the narrow authorized-reply executor and gateway support for exact Deadline/priority edits, preflighting every requested action before the first write with no second confirmation; use mocked HTTP integration tests to verify native date/priority payloads, current-state checks, unauthorized/stale refusal, unchanged unrelated fields, and no authority granted by warning generation or ordinary preview; document the write exception.
- [x] 5.2 Keep authorized recurring Deadline edits consistent with lifecycle metadata while preserving Due/recurrence/completion state; extend recurrence/application integration specs to verify the next lifecycle poll accepts the acknowledged edit without accidental advancement, field loss, or completion/reopen, and document how these edits differ from ordinary calendar writeback and lifecycle advancement.
- [x] 5.3 Persist action receipts and deduplicate Slack events across restart, reconciling rejected/ambiguous provider outcomes without blind replay or automatic rollback; verify a relative delay is applied once, one completed action survives a later action failure, stale concurrent edits are not overwritten, and Slack reports verified/failed/unknown results accurately; document partial-batch behavior.
- [x] 5.4 After verified task edits, re-read state and create a fresh proposal without applying Calendar changes; verify the existing Calendar approval/ownership checks and confirmed temporary AI override behavior remain unchanged, including both Calendar-provider compositions where existing tests support them.

## 6. Combined Verification and Delivery

- [x] 6.1 Run targeted configuration, scheduling, messaging, LLM-validation, recurrence, persistence, and mocked-HTTP integration checks, then the repository's broader hermetic test suite and `openspec validate deadline-policy-and-risk-feedback --strict`; report exact decisive output, unresolved failures, and synthetic Slack payload/interaction evidence without live accounts or runner access.
- [x] 6.2 Reconcile implementation docs/examples and requirement coverage with the final behavior, mark only verified tasks complete, commit and push implementation on `plan/explicit-soft-deadline-support`, and open a PR; verify the PR includes migration notes and test evidence, subscribe to exact pushed-head CI and PR comments/reviews, and report the PR/thread links without merging or deploying.
