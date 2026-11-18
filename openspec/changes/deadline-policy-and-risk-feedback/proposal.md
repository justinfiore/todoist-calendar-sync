# Proposal

## Why

SmartPlanner currently couples Deadline hardness to the recurrence feature gate, even for non-recurring tasks. The owner needs an explicit hard/soft policy and one actionable Slack risk workflow for all tasks with native Deadlines, rather than special urgency and feedback reserved for a soft label.

## What Changes

- **BREAKING:** classify every native Deadline using explicit configuration, independently of recurrence. `default_deadline_mode` defaults to `hard` and can be `soft`. Optional `hard_label` and `soft_label` both default to no label; configured labels override the default. Hard wins when both match, and the proposal identifies the conflict.
- Treat default-soft and label-selected soft tasks identically. Preserve native date-only Deadline interpretation and normal no-Deadline task behavior; do not manufacture a Deadline from Due merely for this policy.
- Protect capacity needed to meet feasible hard Deadlines; never silently place hard work late. Permit urgent soft work first when hard deadlines remain feasible, rather than placing every distant hard task before every soft task. Preserve manual and frozen placements.
- Increase soft urgency with both approaching date and scarcity of remaining feasible capacity before Deadline, including duration and competing work. Inside the soft urgency window, even user-facing P4 outranks P1–P3 tasks without Deadline, whether or not they have Due. Soft work may finish late and retains elevated urgency when overdue. Urgency alone never changes native priority.
- Provide separate configurable hard/soft urgency windows and hard/soft warning windows, all defaulting to seven local calendar days.
- Warn in Slack plan proposals for approaching or overdue hard/soft tasks left unscheduled or finishing late. Mark unscheduled coverage beyond the proposal horizon as unverified, not proven impossible.
- Render at-risk tasks in a native Slack table with proposal-bound numbers, linked task name, project, Deadline, effective hard/soft mode, and user-facing P1–P4 priority. Ask whether to change Deadline or raise priority.
- Accept minimal natural-language replies for multiple numbered tasks, with the task number anywhere in its instruction line. The LLM interprets fuzzy/absolute dates, relative delays, and requested native priority into bounded actions; application code validates and executes them. An unambiguous authorized reply is sufficient authority without a second confirmation.
- If any requested instruction cannot be resolved, return a clarification result and post a question in the same Slack thread before any task mutation. Reconcile ambiguous provider outcomes and report partial execution honestly; no cross-task transaction is implied.
- **BREAKING:** add a narrow exception to Deadline invariance for explicit authorized risk replies. Warning generation, ordinary preview, and existing temporary AI planning overrides remain non-mutating. Calendar application retains its separate approval protections.

## Capabilities

### New Capabilities

- `task-deadline-policy`: Recurrence-independent classification, optional label overrides, hard-capacity protection, and time/capacity-sensitive scheduling precedence.
- `deadline-risk-feedback`: General hard/soft risk detection, numbered Slack tables, concise LLM-interpreted mitigation replies, validated native task edits, and durable reconciliation.

### Modified Capabilities

- `calendar-provider-routing`: Preserve existing provider/apply safety while permitting only independently authorized Deadline/priority feedback writes, not granting Calendar apply authority.

## Impact

- Configuration/domain/scheduling: `PlannerConfig`, task projection, `PlanScorer`, `DeterministicScheduler`, batching, retained placements, diagnostics, plan identity, and relevant configuration examples/docs.
- Messaging/AI/state: `MessageRenderer`, `MessagingService`, Slack payloads, feedback dispatch, validated LLM contracts, proposal-bound task-number mapping, and replay-safe action receipts.
- Todoist: existing read/write/lifecycle gateways and adapters need a guarded Deadline/priority write path; recurring metadata must remain consistent without changing native Due or its recurrence tuple.
- Spec reconciliation: this replaces `explicit-soft-deadline-support` and supersedes the gate-based hardness assumptions in the unarchived `add-deadline-recurrence-support` change for this behavior. Existing confirmed temporary AI overrides remain separately confirmed, preview-only operations.
- Migration: recurrence-enabled installations wanting prior unlabeled soft behavior must explicitly select `soft`. Move existing configured hard-label/urgency policy into explicit deadline settings; no implicit `hard` or `soft-deadline` matching remains. Document migration rather than silently retaining hidden recurrence aliases.
- Delivery: planning artifacts here; implementation is authorized for a separate orb using Grok 4.7 medium, followed by verification, commit/push, and a PR on the plan branch. No runner access, service restarts, SmartPlanner enablement, production deployment, live account mutation, credential access, or real-task backups are authorized.
