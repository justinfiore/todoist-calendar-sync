# Design

## Context

This replaces the original `explicit-soft-deadline-support` plan after requirements discussion in `https://ampcode.com/threads/T-01a1e092-c30a-768c-b385-3b1f8ae07ec0`. The owner approved the generalized policy and requested a separate Grok 4.7 medium orb implementation, commit/push, and PR. This checkout originally matched the exact source baseline `77d8fbb301ffb271e0ba6f8f05da32011838d8f8`; no runner or current-remote-state claims follow from that fact.

Observed at that baseline:

- `scheduling/PlanScorer.isHard()` and `DeterministicScheduler.constraintDeadline()` duplicate recurrence-gated classification. Recurrence disabled means every Deadline is hard; enabled means configured `hard_label` selects hard treatment for any task. Soft lateness already has a capped penalty, but that is not the requested general capacity-aware policy.
- `DeterministicScheduler` initially sorts units by earliest Deadline then native priority. Frozen/manual retention, member-level focus feasibility, clipping, scoring, and explanations all matter; editing only urgency weights cannot enforce the scheduling contract.
- `config/PlannerConfig.parseRecurrence()` owns existing `hard_label` (default `hard`) and `hard_deadline_soon_days` (default 5). Native `domain/Task` already carries labels, project context, priority, Due, Deadline date, and Deadline instant. Date-only Deadline maps to exclusive next local midnight.
- `messaging/MessageRenderer` renders proposal summaries and separate risk alerts. Existing risk alerts filter `plan.unscheduled` and `riskDeadlineDays`, so late scheduled soft tasks are not covered by that mechanism alone.
- `adapters/SlackMessagingGateway` builds header/section blocks from escaped/truncated text. Native table blocks require a structured rendering path, not a Markdown pipe table inserted into that text. Socket Mode uses the same `chat.postMessage` payload route and carries thread metadata.
- `ai/LlmContracts` and `LlmSchemaValidator` restrict existing conversational interpretations to plan actions. `AiSuggestionDecisionStore` and `ConfirmedOverrideApplier` bind exact confirmed suggestions; duration/context overrides are temporary and preview-only. The new directly authorized risk-edit path must not silently expand those existing contracts.
- `adapters/TodoistGateway` separates read/write/lifecycle interfaces. Existing Deadline operations do not by themselves provide a validated native-priority/reply executor. Recurring marker state records Deadline and generation, so an authorized Deadline edit needs coordinated marker reconciliation, not an untracked date write that freezes the task on its next poll.

Read `docs/SMART_PLANNER_CONFIGURATION.md` (Native recurrence and Deadline lifecycle), the unarchived recurrence/deadline change, and confirmed-AI-overrides change. Their historical gate/preview assumptions are distinguished here from the newly approved policy. Calendar-to-Todoist writeback remains due-only; this feature is not a new Calendar-originated Deadline writeback.

## Goals / Non-Goals

**Goals:** one effective hard/soft policy for all native Deadlines; protect commitments without starving urgent flexible targets; actionable low-effort Slack feedback; narrowly authorized native edits with durable evidence.

**Non-goals:** no live accounts, runner edits, service restarts, deployment, SmartPlanner/recurrence enablement, task backup export, or automatic Deadline/priority mutation based on a warning. No changes to native recurrence expressions, occurrence-completion semantics, or Calendar apply authorization. No unrestricted model tools or general-purpose Todoist editor.

## Decisions

### 1. Explicit configuration replaces the recurrence-policy switch

Use the existing `planner.tasks` namespace for `default_deadline_mode`, `hard_label`, `soft_label`, `hard_deadline_soon_days`, and `soft_deadline_soon_days`; use `planner.messaging` for `hard_deadline_warning_days` and `soft_deadline_warning_days`. This keeps task policy separate from delivery and avoids another enable gate. The default mode is hard; both labels default to null/no match; four windows independently default to 7.

Implementation choices: normalize blank optional labels to no match; use existing case-insensitive label matching; reject unknown modes, nonpositive/noninteger windows, and identical nonempty hard/soft label settings with actionable validation. A task carrying both distinct labels is valid but effectively hard and gets a conflict explanation. These parser details are design choices, not additional owner requirements.

Remove implicit recurrence fallbacks from policy consumers. For explicitly supplied old nested `recurrence.hard_label` or `recurrence.hard_deadline_soon_days`, fail validation with migration guidance instead of silently ignoring the operator's choice or retaining two policy authorities. Update tracked examples/tests accordingly. Existing standalone capacity-risk configuration retains its own meaning; the new proposal warnings do not depend on its daily-trigger/kind settings.

Apply the same classification with recurrence on or off and for recurring or ordinary tasks: configured hard match → hard; else configured soft match → soft; else default mode. No Deadline means no classification cutoff or fabricated date. Default-soft and label-selected soft share urgency/risk behavior. This intentionally replaces the earlier idea that only soft-labeled tasks receive enhanced treatment.

### 2. Preserve hard feasibility before optimizing soft urgency

Keep one effective-policy source of truth used by scoring, clipping, retained placements, per-member focus validation, and unscheduled reasons. Use an existing shared owner where practical; add only a small coherent policy helper if needed by both scheduler and reporting. Keep native Deadline available as a target even when no hard cutoff is returned.

Preserve frozen/manual blocks first. Establish a feasible hard-commitment allocation before accepting competing soft work. Permit a soft task to take an earlier slot only if hard commitments can still be placed on time, potentially moving unprotected hard placements later. Reject a proposed displacement when preserving hard feasibility cannot be established; do not claim a globally optimal solver or a guarantee when hard demand already exceeds capacity. Report the remaining hard conflict and let feasible soft work use otherwise available capacity.

Compute soft urgency from time-to-target plus pressure on remaining eligible pre-Deadline capacity, considering task duration, overlapping context/availability restrictions, and competing work. Count usable time without double-counting overlapping intervals; do not measure only the last free interval near Deadline. Read-only capacity assessment may need coverage through an approaching Deadline even if the posted proposal ends earlier; if coverage is unavailable, retain an explicit unknown rather than inventing free time or impossibility.

Use a monotonic time component and a bounded monotonic scarcity component. Exact constants are implementation choices, but comparisons are contractual: less remaining time raises time urgency; less feasible slack raises pressure; an approaching soft P4 beats P1–P3 no-Deadline work in movable capacity; hard-needed capacity remains protected. Keep overdue soft urgency elevated rather than allowing the existing late-penalty path to erase it. Break otherwise equivalent choices deterministically by target date, native priority, then stable task identity.

Use planner-zone civil dates for window membership: Deadline date on or before local today plus the configured days is in-window, with overdue dates included. Preserve existing end-of-Deadline-day completion semantics. Test local-midnight and DST boundaries independently; don't compare fixed 24-hour multiples for calendar-day policy.

### 3. Derive risk from the actual proposal, not a new forecast guarantee

Within the applicable independent warning window, a task is at risk if unscheduled or not fully scheduled to finish by Deadline. For focus blocks, use the task's member interval(s), not only the block's outer end. For any supported split placement, assess complete duration and final member completion, not the first fragment. An unscheduled task whose Deadline exceeds the posted proposal horizon still appears, with “on-time coverage unverified; proposal ends before Deadline.” Distinguish that from an explicitly late placement or an infeasible hard cutoff.

Store effective policy, risk reason, proposal-bound number mapping, and relevant config identity in existing plan/conversation structures as needed. Bind task numbers and effective policy to proposal version/hash and planning inputs so a setting/label change cannot reuse an approval for another policy. Number the complete warning inventory deterministically and persist it; never regenerate mappings from the latest task sort when a reply arrives. Pagination/chunking must keep numbering stable across the proposal's Slack messages.

### 4. Use native Slack tables with linked tasks

Render columns `#`, linked task name, project, Deadline local date, hard/soft, and displayed priority. Add risk explanation/conflict notes near the table or in an extra column as space permits. Include a compact prompt offering a Deadline change or priority increase and example numbered replies. Preserve an accessible text fallback with equivalent task context, while the `chat.postMessage` blocks carry the real table.

Slack's official table reference confirms `table` blocks support rich-text cells with hyperlinks through `chat.postMessage`: `https://docs.slack.dev/reference/block-kit/blocks/table-block/` (read during planning). Verify current provider limits during implementation; chunk within limits without changing identifiers or dropping warning rows. Escape untrusted task/project content while preserving structured hyperlinks. Do not add Slack account access to prove payload rendering; use the existing injectable transport and synthetic data.

### 5. Interpret numbered replies into actions or clarification

Reuse the configured application LLM gateway, authentication, bounded context, schema validation, and Slack authorization/thread routing. Do not hard-code Grok as the application's interpretation model: Grok 4.7 medium is the requested implementation agent, not a new production-provider requirement.

The new interpretation contract has mutually exclusive outcomes: a complete set of typed native task edits, or a clarification question. An edit names the bound task/number and either an exact local Deadline date or a user-facing priority level. The code supplies the allowed task mapping, current values, actor/thread/proposal identity, timezone, reply reference time, and allowed fields. The LLM gets no write gateway or execution tools.

Support task number at the start or elsewhere in the same instruction line, multiple lines or concise separators, fuzzy dates such as “next Friday” or “12/5,” explicit dates, and delays such as “push back two weeks.” Resolve genuinely clear fuzzy input rather than demanding ISO dates. Use planner-zone reply time for relative-to-today expressions and the bound current Deadline for a relative Deadline extension. If a year/locale/date or target cannot be resolved confidently, return clarification rather than a guessed write. Preserve the original reply and resolved reference context through clarification so a later response does not silently shift an already resolved date.

An ambiguous, missing, duplicate-conflicting, unsupported, schema-invalid, or unauthorized instruction prevents every write in that reply. Post the clarification in the same proposal thread and allow the authorized user to clarify the outstanding intent. Model unavailability yields a safe failure/clarification path, never a regex fallback that grants broader write authority.

### 6. Execute narrowly authorized task edits without a second confirmation

The owner's explicit decision is that an unambiguous authorized reply itself authorizes these native edits; do not introduce a second confirmation. This is separate from still-confirmed temporary AI planning overrides. Preflight the complete interpreted set against bound task identities and current live state before the first write. Refuse concurrent changes, removed/completed targets, stale proposal mapping, invalid dates, or unsupported field changes; request refreshed review instead of overwriting newer intent.

Execute only the validated fields. Translate displayed P1/P2/P3/P4 to Todoist API 4/3/2/1. A Deadline edit preserves Due, native recurrence tuple, labels, human text, completion count, and other task properties; coordinate only the lifecycle metadata needed to acknowledge the authorized Deadline edit without corruption or unintended occurrence advancement. Reuse stable command identity and live verification patterns, not blind REST retries. A priority edit must preserve Deadline and recurrence state.

Persist actor, event/proposal/task identity, interpreted value, preconditions, execution state, and verification evidence using existing durable stores. Deduplicate Slack redelivery and restart recovery; a relative two-week delay must not be applied twice. Interpretation/preflight is all-or-nothing, but remote multi-task writes are not atomic. On rejection or uncertain outcome, retain receipts, reconcile the exact command, report actual verified/failed/unknown actions, and do not automatically roll back completed writes or repeat them. After verified changes, re-read tasks and produce a fresh proposal; do not treat feedback as Calendar apply approval.

## Risks / Trade-offs

- [Explicit defaults change existing recurrence-enabled installations] → Provide a prominent migration matrix and reject old explicit nested policy settings with replacement guidance.
- [Weighted scoring cannot protect hard commitments by itself] → Exercise actual contested scheduler capacity and allow earlier soft work only when hard placement remains feasible.
- [Scarcity estimates become falsely precise] → Bound them to real eligibility/availability and report unknown horizon coverage honestly; the risk warning describes the selected plan, not proof of global infeasibility.
- [Direct LLM-interpreted replies expand write authority] → Typed bounded output, authorized actor/thread, complete-set validation, live preflight, field allowlist, durable receipts, and no new authority for old AI overrides.
- [A Deadline edit desynchronizes recurring metadata] → Preserve recurrence and reconcile marker provenance/generation under guarded verification, with a regression across the next lifecycle poll.
- [Numbers drift or partial retries duplicate changes] → Persist mappings and exact resolved dates; bind them to proposal/version/input identity and deduplicate execution.
- [Table limits hide tasks or links] → Validate structured payloads, stable-number chunking, complete text fallback, and long/untrusted titles through the real adapter's injectable transport.

## Migration Plan

1. Implement and verify in a separate orb, not the Nextcloud runner. Use only synthetic fixtures and mocked Todoist/Slack/Calendar providers; no credentials, task backups, or production services are needed.
2. Add explicit policy configuration and update examples/docs: omitted mode now means hard even when recurrence is on; select `soft` to retain the old unlabeled soft treatment. Both labels are disabled unless configured, so an existing label named `hard` is not implicitly activated.
3. Move explicitly configured nested recurrence policy fields to task policy settings. Explain four independent windows, all now default 7; old hard urgency default was 5. Existing recurrence lifecycle toggles retain their lifecycle role only.
4. Verify read-only previews, risk tables, replay-safe mitigation execution, native property preservation, and unaffected existing planning/AI/Calendar paths. Report tests and synthetic payload evidence in the implementation PR.
5. Commit/push implementation on the plan branch and create the requested PR. Do not merge, deploy, restart services, or enable SmartPlanner/recurrence. Any later rollout or rollback needs separate authorization and fresh previews; disabling an optional label does not remove native task data or repair old policy automatically.

## Remaining Questions

No unresolved product decisions block implementation. Exact scoring constants, efficient feasibility-preservation strategy, and storage placement are implementation choices constrained by the specs and existing code, not permission to weaken the observable behavior. If implementation discovers a genuine contract conflict that cannot be resolved within those constraints, report it rather than silently narrowing the scope.
