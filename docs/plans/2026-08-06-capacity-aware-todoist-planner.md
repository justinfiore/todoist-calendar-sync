# Capacity-Aware Todoist Planner Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Evolve `todoist-calendar-sync` from a due-date-to-calendar renderer into a capacity-aware planner that uses Todoist deadlines, calendars, task metadata, weather, and user policy to propose and safely apply realistic daily, short-range, and medium-range plans.

**Architecture:** Keep scheduling deterministic, explainable, and independently testable. New adapters normalize Todoist tasks, calendar events, weather forecasts, and optional Slack interactions into planner inputs. A policy engine classifies availability and task suitability; a deterministic scheduler creates versioned plan proposals; existing CalDAV output code writes only approved planner-owned events and updates Todoist due dates to the selected calendar block start time. An optional LLM layer may enrich, summarize, and interpret feedback, but cannot directly mutate a plan or calendar.

**Tech Stack:** Target baseline from the prerequisite upgrade PR: Java 25 / Groovy 5 / Gradle 9; Todoist Sync API v1 / CalDAV; targeted Groovy tests; Google Calendar API or CalDAV read support; optional Open-Meteo-compatible weather adapter; optional Slack adapter; optional OpenAI-compatible/Anthropic/xAI/local LLM adapters. This planner work assumes the Java/Gradle/Groovy upgrade and baseline-test work land separately first.

---

## 1. Product framing and key decisions

### 1.1 The problem being solved

The current application is a sync renderer: it includes eligible Todoist tasks with due dates, assigns the Todoist due datetime directly to the generated event `DTSTART`, gives date-only tasks a 9:00 AM start, assumes 30 minutes without a duration, and writes every result to CalDAV. It does not read busy time, distinguish personally-attended events from informational family events, assess capacity, account for weather, batch project work, or preserve a stable plan.

That makes the calendar a display of *demand*, not a feasible plan. It causes visible overlap and a daily cycle of moving tasks that could not have fit in the first place.

### 1.2 Todoist field semantics — explicit decision

The planner must distinguish a **deadline** from a **scheduled start**:

| Field | Planner meaning |
|---|---|
| Todoist **Deadline** | Primary completion constraint / latest acceptable completion time. This is the main input to scheduling. |
| Todoist **Due date/time** | Planner-managed scheduled start. When a task is created or moved to a calendar block, update the Todoist due date and time to exactly match the block start. |
| Planner calendar event start | Same value as the Todoist due date/time for a planner-managed task. |
| Planner calendar event duration | Native Todoist duration when present; otherwise configured label duration or planner default. |

This preserves a meaningful deadline while making Todoist’s daily views correspond to the actual plan. The planner must **never silently alter a Todoist deadline**. A task with no deadline can be planned only if policy explicitly allows it; otherwise it remains in backlog or is a lower-priority filler task.

### 1.3 Planning principles

1. **Deadline, availability, and effort are constraints; due time is the output.**
2. **Do not schedule every eligible task.** A plan that leaves work unscheduled with an explanation is more useful than an impossible calendar.
3. **Honor hard blockers, normally avoid soft blockers, and display informational events without consuming availability.**
4. **Reserve buffer and penalize context switching.**
5. **Prefer project batching:** a Scouts focus block may contain several small Scouts tasks rather than interleaving Scouts, faith, AI, and kids work.
6. **Prefer task contexts:** for example, pick `@phone` in short/mobile gaps and avoid `@home`/`@computer` there according to policy.
7. **Respect weather conditions for outdoor tasks, and replace weather-invalid blocks with eligible indoor work when possible.**
8. **Minimize churn:** preserve near-term and manually moved planner blocks unless a real conflict or approval requires a change.
9. **Make every proposed or applied change explainable, reviewable, and reversible.**

---

## 2. Architecture and ownership boundaries

```text
Todoist reader ─┐
Calendar reader ├─> Normalizers ─> Policy engine ─> Deterministic planner
Weather reader ─┘                                      │
                                                       ├─> Plan store / diff / explanations
                                                       ├─> Approval + feedback workflow
                                                       ├─> CalDAV / Google managed-output writer
                                                       ├─> Todoist due-date writer
                                                       └─> Optional Slack summary and alerts

Optional LLM adapters ─> enrichment, summaries, and feedback interpretation
                         (never a direct scheduler or mutation authority)
```

### 2.1 Separate planner core from integrations

The planner core must operate on local domain objects and contain no HTTP, credential, Slack, or LLM code. This enables fast tests for capacity, deadline risk, batching, weather, stability, and policy precedence.

Adapters must be isolated behind interfaces so users can adopt the planner without Slack, weather, Google Calendar API, or an LLM.

### 2.2 Proposed source layout

```text
app/src/main/groovy/todoistcaldavsync/
  TodoistCalDavSync.groovy              # keep as legacy sync entry point during migration
  planner/
    PlannerCli.groovy
    PlannerService.groovy
    domain/Task.groovy
    domain/CalendarEvent.groovy
    domain/TimeSlot.groovy
    domain/Plan.groovy
    domain/PlanChange.groovy
    domain/PlanningExplanation.groovy
    policy/AvailabilityPolicy.groovy
    policy/EventClassifier.groovy
    policy/TaskPolicy.groovy
    scheduling/AvailabilityCalculator.groovy
    scheduling/ProjectBatcher.groovy
    scheduling/DeterministicScheduler.groovy
    scheduling/PlanScorer.groovy
    scheduling/WeatherEvaluator.groovy
    state/PlanStore.groovy
    adapters/TodoistGateway.groovy
    adapters/CalendarGateway.groovy
    adapters/WeatherGateway.groovy
    adapters/MessagingGateway.groovy
    adapters/LlmGateway.groovy

app/src/test/groovy/todoistcaldavsync/planner/
conf/
  todoist-planner.conf.example.yaml
docs/plans/
```

Exact names can change during implementation, but boundaries must remain: pure domain/policy/scheduling code, state, and side-effect adapters.

---

## 3. Configuration model

The planner configuration must be declarative, validated, documented, and safe-by-default. Use explicit rule order and explain which rule matched.

```yaml
planner:
  mode: preview # preview | approval_required | apply_safe_changes | fully_automated
  timezone: America/New_York
  output_calendar: Todoist Planned

  planning_runs:
    - name: daily
      horizon: P3D
      schedule: "0 6 * * *"
      create_concrete_blocks_until: P3D
    - name: short_range
      horizon: P14D
      schedule: "0 7 * * 1"
      create_concrete_blocks_until: P14D
    - name: medium_range
      horizon: P2M
      schedule: "0 8 */14 * *"
      create_concrete_blocks_until: P14D
      report_capacity_risk_only_after: P14D

  stability:
    freeze_within: PT48H
    keep_manual_moves: true
    require_approval_for_move_within: P7D
    minimum_buffer_between_blocks_minutes: 10

  availability:
    working_windows:
      weekday: ["06:30-07:30", "12:00-13:00", "20:00-22:00"]
      weekend: ["09:00-12:00", "14:00-16:00"]

    calendars:
      - calendar: Work
        default_role: hard_blocker
      - calendar: Family
        default_role: soft_blocker
      - calendar: Bob
        default_role: informational
      - calendar: Todoist Planned
        default_role: managed_output

    event_rules:
      - name: Justin transports or attends an activity
        calendar_regex: "^Bob$"
        title_regex: "(?i)\\b(justin|dad)\\b.*\\b(takes|drive|drives|coaches|attend)\\b"
        role: hard_blocker
        buffer_before_minutes: 15
        buffer_after_minutes: 20
      - name: Family logistics involving Justin
        calendar_regex: "^(Family|Kids)$"
        text_regex: "(?is)\\b(justin|dad)\\b.*\\b(pickup|drop.?off|transport|volleyball|scouts)\\b"
        role: soft_blocker
      - name: Default informational child event
        calendar_regex: "^(Bob|Kids)$"
        role: informational

    availability_overrides:
      - when_event_matches:
          title_regex: "(?i)\\b(volleyball|scouts|travel)\\b"
        prefer_task_labels: [phone, errand]
        avoid_task_labels: [home, computer]

  tasks:
    scheduling_eligible_labels: [schedule]
    manual_label: manual # displayed in Todoist as @manual
    default_duration_minutes: 30
    duration_labels:
      t15: 15
      t30: 30
      t60: 60
      t90: 90
    contexts:
      phone:
        match_labels: [phone]
        preferred_windows: ["weekday 12:00-13:00", "weekday 16:30-17:30"]
      home:
        match_labels: [home]
        preferred_windows: ["weekday 18:00-21:30", "weekend 09:00-17:00"]
      computer:
        match_labels: [computer]
        preferred_windows: ["weekday 06:30-08:00", "weekday 19:30-22:00"]

  batching:
    enabled: true
    project_batch_bonus: 25
    max_focus_block_minutes: 90
    minimum_focus_block_minutes: 30
    context_switch_penalty: 15

  weather:
    enabled: true
    provider: open_meteo
    task_rules:
      - match_labels: [outdoor, dry-weather]
        require:
          precipitation_probability_max: 25
          precipitation_mm_max: 0.5
          wind_speed_kph_max: 25
      - match_labels: [paint, deck]
        require:
          precipitation_probability_max: 15
          precipitation_mm_max: 0
          temperature_min_c: 10
          wind_speed_kph_max: 20
        preferred:
          daylight: true
          forecast_confidence_min: 0.70

  messaging:
    enabled: false
    provider: slack
    channel: "#todoist-planner"
    daily_summary: true
    proposal_summary: true
    capacity_risk_alerts: true

  ai:
    enabled: false
    provider:
      type: openai_compatible # openai_compatible | anthropic | xai | local | command
      model: ""
      base_url: ""
      api_key_env: ""
    capabilities:
      summarize_plan: true
      interpret_feedback: true
      suggest_task_metadata: false
      suggest_task_decomposition: false
    safety:
      never_apply_changes_directly: true
      require_structured_output: true
      require_confirmation_for_policy_changes: true
      send_minimum_necessary_data: true
```

### 3.1 Rule precedence

For availability classification, resolve in this order:

1. first explicit `event_rules` match in configuration order;
2. configured calendar default role;
3. global safe fallback (`informational` or explicitly configured default).

Persist the matched rule name and reason in the plan explanation. Unknown calendar defaults must be visible in diagnostics rather than silently treated as free time.

### 3.2 Task metadata

Recommended labels include `@schedule`, `@manual`, `@phone`, `@home`, `@computer`, `@outdoor`, `@errand`, `@deep`, `@admin`, `@no-kids`, and configured effort labels such as `t30` / `t60`. Todoist native duration takes precedence over labels. Labels guide suitability and weighting; they must not implicitly change a task deadline.

`@manual` is an explicit planner opt-out. The planner must not select, move, defer, score, or report a manual task as scheduling work. The legacy sync path continues to synchronize an eligible `@manual` task's calendar event to its already-set Todoist due date/time and duration exactly as it does today. This preserves user-owned scheduling while preventing the planner from changing it.

---

## 4. Deterministic planning algorithm

### 4.1 Inputs

For each eligible task, normalize:

- Todoist task ID, content, project, labels, priority, duration, due date/time, deadline, parent/section details where available;
- deadline as the primary latest-completion constraint;
- current Todoist due date/time and managed calendar event as an existing scheduled placement;
- project, task context, outdoor/weather rules, and manual-override state.

Before candidate generation, exclude tasks with the configured `manual_label` from planner selection. Retain them in the legacy calendar-sync input/output path so their existing Todoist due datetime and duration continue to control their calendar event.

For calendar events, normalize title, description, calendar name/ID, start/end, source, all-day state, and role/classification explanation.

### 4.2 Slot creation

1. Read calendar events across the planning horizon.
2. Apply event classification rules.
3. Subtract hard blockers and configured buffers from working windows.
4. Represent soft blockers as available-but-penalized slots.
5. Preserve planner-owned blocks in frozen/stable windows and exclude unrelated external events from writes.
6. Produce candidate slots with location/context cues and weather evaluations.

### 4.3 Selection and scoring

Schedule tasks using a deterministic greedy approach first, with a future option to replace the ordering/search implementation with a constraint solver while preserving the policy interface.

Suggested score:

```text
score =
  deadline_urgency
+ Todoist_priority_weight
+ project_batching_bonus
+ preferred_context_bonus
+ weather_suitability_bonus
+ task_age_or_repeated_deferral_bonus
- hard_conflict_penalty (effectively infeasible)
- soft_conflict_penalty
- context_switch_penalty
- task_move_churn_penalty
- fragmented_slot_penalty
```

Todoist P1–P4 influences the score strongly, but a high-priority task does not bypass impossible duration, hard conflict, deadline, or weather constraints. If a P1 cannot fit, generate a capacity-risk alert with alternatives rather than inventing availability.

### 4.4 Project batching

Group compatible, ready tasks in the same Todoist project into focus blocks. A 60-minute `#Scouts` block can contain multiple small tasks, retain each Todoist task as a scheduled entity, and render an aggregate block title/description in the planner calendar. Prefer project cohesion while respecting task deadlines and task-specific contexts.

Example:

```text
Scouts focus block — 60 minutes
- update advancement spreadsheet — 15 min
- send parent communication — 10 min
- plan den meeting — 20 min
- order supplies — 10 min
- review event logistics — 5 min
```

Do not batch unrelated tasks solely to fill time, and do not delay an urgent deadline task merely for a batching bonus.

### 4.5 Weather handling

Weather rules modify slot feasibility or score. For `Paint the Deck`, Saturday rain can invalidate the planned outdoor block, cause the planner to search a weather-safe replacement before the deadline, and let an indoor task occupy the released Saturday time. Weather decisions must include forecast timestamp, provider data used, and the rule that passed/failed. Re-evaluate nearer to the event because forecast confidence declines over time.

### 4.6 Plan output and explanation

Each run produces a versioned plan and a human-readable diff, for example:

```text
Plan #42

Moved
- Paint the Deck: Saturday 10:00 AM → Sunday 1:00 PM
  Reason: Saturday precipitation probability (75%) exceeded the task rule maximum (15%).

Added
- Scouts focus block: Saturday 10:00 AM–11:00 AM
  Reason: indoor work, project batching bonus, and upcoming project deadline.

Unscheduled
- Replace basement light fixture
  Reason: requires 90 minutes; no @home block is available before deadline.
  Suggested action: split task, free capacity, or change deadline.
```

---

## 5. Calendar and Todoist mutation rules

### 5.1 Planned calendar ownership

Use a dedicated configured `managed_output` calendar. Only planner-owned events with a deterministic planner metadata marker/UID may be modified or deleted. Never overwrite external calendar events.

### 5.2 Todoist due date synchronization

After a plan is approved or allowed by the configured apply mode:

1. create or move the managed calendar event;
2. update the Todoist task’s **due date and time to the selected calendar event start**;
3. retain the Todoist **deadline** unchanged;
4. persist an auditable mapping between task ID, event UID, selected slot, plan ID, and update timestamp.

The update must be idempotent and use a compensating/retry strategy. If one side succeeds and the other fails, record a recoverable partial-application result rather than silently claiming success. Preview mode must never write either system.

### 5.3 Manual moves and overrides

Treat a user-moved managed calendar event or manually changed Todoist due time as a manual override when it differs from the last applied plan. Preserve it by default and include it in subsequent planning runs. A user may explicitly allow the planner to reconsider it.

This user-move detection is distinct from the `@manual` label: a manual override is a planner-managed task whose selected time the user adjusted, while an `@manual` task is never considered by the planner at all.

---

## 6. Messaging and approval workflow

Messaging is an optional adapter. Slack is the first implementation but must not be required for planning.

### 6.1 Modes

| Mode | Behavior |
|---|---|
| `preview` | Calculate/report only; no Todoist or calendar writes. |
| `approval_required` | Generate versioned plan and wait for approval. |
| `apply_safe_changes` | Auto-apply only planner-owned, non-manual, non-frozen changes; request approval for the rest. |
| `fully_automated` | Opt-in future mode after trust is established. |

### 6.2 Daily summary example

```text
Today’s feasible plan
Available focus capacity: 2h 15m
Scheduled:
- 8:00 AM–9:00 AM — Scouts focus block
- 12:15 PM–12:35 PM — Phone/admin
- 8:00 PM–8:45 PM — AI project review
Reserve: 30m

Risk: Paint the Deck has five days remaining, but no weather-safe slot is currently available.
```

### 6.3 Proposal feedback

Every proposal must have an ID and diff. Support structured actions such as approve, reject, apply safe changes, keep a specific block, defer a task, and temporary priority override. Natural language feedback is optional and must resolve into a validated structured change.

Example human request: “That works mostly, but the Scouts stuff really needs to get done.”

Example resulting structured temporary override:

```json
{
  "project": "Scouts",
  "scope": "this_week",
  "weight_adjustment": 30,
  "reason": "Explicit user planning feedback"
}
```

Policy changes that persist beyond the plan must always require confirmation.

All human-facing plan diffs, Slack messages, alerts, and approval summaries must render local times using a 12-hour clock with an explicit `AM`/`PM` suffix. Configuration values, API payloads, state, and machine-readable JSON may remain ISO-8601 / 24-hour time to avoid ambiguity.

---

## 7. Optional LLM design

### 7.1 What an LLM must not do

An LLM must not replace the core scheduler, determine hard constraint feasibility without deterministic validation, directly alter Todoist/calendar data, or treat model prose as executable policy. The core problem is a constrained scheduling problem and benefits from deterministic, reproducible, low-cost logic.

### 7.2 Good optional LLM uses

- suggest task duration, context labels, weather sensitivity, and task decomposition;
- suggest a classification for an unfamiliar event and request confirmation;
- turn deterministic plan data into helpful summaries;
- interpret natural-language planning feedback into structured candidate overrides;
- explain trade-offs and alternatives;
- analyze repeated deferral patterns during a weekly reflection.

All AI output must conform to a schema, be validated by deterministic code, and be surfaced as a proposal. Send only minimum necessary task/event content to the selected provider. Provider support should include cloud and local deployments through adapters, not provider-specific logic in the scheduler.

---

## 8. Phased implementation plan

### Phase 1: Domain foundation, read-only availability, and capacity diagnostics

**Objective:** Using the Java 25 / Groovy 5 / Gradle 9 and baseline-test foundation delivered by the prerequisite PR, create validated planner domain models and produce an explainable, non-mutating report of free capacity, task demand, event classification, and deadline risk.

**Files:**
- Create: `planner/domain/*.groovy`
- Create: `planner/policy/EventClassifier.groovy`
- Create: `planner/scheduling/AvailabilityCalculator.groovy`
- Create: `planner/PlannerCli.groovy`
- Create: `conf/todoist-planner.conf.example.yaml`
- Create tests for domain normalization, calendar defaults, title/description regex rules, buffers, unknown-calendar behavior, and `@manual` exclusion.

**Steps:**
1. Write failing tests for task/deadline/due-time normalization and immutable/validated task, event, slot, plan, and explanation models.
2. Implement the domain models and planner configuration validation.
3. Write failing unit tests for calendar-default and event-rule precedence.
4. Implement classifier with persisted matched-rule explanation.
5. Write failing slot-generation tests with hard/soft/informational events.
6. Implement free-slot calculation and working-window/buffer subtraction.
7. Add read-only Todoist/calendar gateway methods and `@manual` planner exclusion; do not alter the legacy writer's handling of manual tasks.
8. Implement a CLI `--mode capacity-report` that emits Markdown/JSON diagnostics.
9. Verify with fixture data and a dry-run against a configured non-production account/calendar.

**Acceptance criteria:** Report identifies usable capacity, tasks that cannot fit before deadlines, and why each relevant event consumed or did not consume time. `@manual` tasks are absent from planner candidates but remain eligible for legacy due-time/duration synchronization. No remote writes occur.

### Phase 2: Deterministic proposal scheduler

**Objective:** Assign feasible tasks to slots in preview mode using deadline, Todoist priority, duration, context, batching, and stability rules.

**Files:**
- Create: `planner/scheduling/DeterministicScheduler.groovy`
- Create: `planner/scheduling/PlanScorer.groovy`
- Create: `planner/scheduling/ProjectBatcher.groovy`
- Create tests covering P1–P4 ordering, deadline infeasibility, context preference, batching, buffers, and churn.

**Steps:**
1. Write failing tests for a P1 task, an impossible task, and a task contextual preference.
2. Implement a deterministic sort/scoring model with stable tie-breakers.
3. Write failing tests showing same-project tasks form a focus block when feasible.
4. Implement batching with explicit aggregate block membership.
5. Add frozen/manual move penalties and an output plan diff.
6. Add plan snapshot/state persistence without changing Todoist or calendars.
7. Run the full test suite on the prerequisite Java 25 / Groovy 5 / Gradle 9 baseline and inspect a sample two-week proposal manually.

**Acceptance criteria:** Same fixture always returns the same plan; no conflicts with hard blockers; proposal explicitly lists unscheduled tasks and reasons; all changes remain preview-only.

### Phase 3: Managed calendar and Todoist due-time application

**Objective:** Safely apply an approved proposal to the planner calendar and synchronize Todoist due date/time to each selected event start while leaving Todoist deadlines untouched.

**Files:**
- Modify: `TodoistCalDavSync.groovy` or extract writer gateway
- Create: `planner/adapters/TodoistGateway.groovy`
- Create: `planner/state/PlanStore.groovy`
- Create integration tests with fake Todoist/CalDAV gateways.

**Steps:**
1. Write failing integration tests proving an approved task writes a managed event and updates Todoist due time to the exact `DTSTART`.
2. Implement idempotent managed-event metadata and task/event mapping.
3. Implement Todoist due-time update request, asserting deadline preservation in test fixtures.
4. Implement partial-failure receipts and retry/reconciliation handling.
5. Detect manual calendar/Todoist changes from last applied state and preserve them by default.
6. Gate all writes behind `approval_required` semantics.
7. Verify first against a dedicated test Todoist project and dedicated planner calendar.

**Acceptance criteria:** Approved plan changes write only owned events; Todoist due time equals planned event start; deadline is unchanged; rerun is idempotent; a forced partial failure is reported/recoverable.

### Phase 4: Weather-aware replanning

**Objective:** Evaluate outdoor tasks against forecast policy and propose replacements rather than silently shuffling work.

**Files:**
- Create: `planner/adapters/WeatherGateway.groovy`
- Create: `planner/scheduling/WeatherEvaluator.groovy`
- Create weather fixture tests and config validation tests.

**Steps:**
1. Write failing tests for rain invalidating deck painting and an indoor replacement selection.
2. Implement provider-neutral weather data model and Open-Meteo adapter.
3. Implement threshold and daylight evaluation with explanation data.
4. Incorporate weather score/feasibility into the planner.
5. Include forecast timestamp and applied rule in the plan diff.
6. Verify behavior using recorded weather fixture payloads and then a preview run.

**Acceptance criteria:** Weather-invalid outdoor work is flagged/moved only as a proposal; feasible indoor replacement is selected when available; absent weather provider does not break non-weather planning.

### Phase 5: Slack summaries, alerts, approval, and feedback

**Objective:** Add optional Slack delivery and a controlled collaboration workflow.

**Files:**
- Create: `planner/adapters/MessagingGateway.groovy`
- Create: `planner/adapters/SlackMessagingGateway.groovy`
- Create: `planner/feedback/FeedbackParser.groovy`
- Create message rendering and feedback validation tests.

**Steps:**
1. Write failing tests for daily summary and capacity-risk alert rendering.
2. Implement provider-neutral message contract and Slack adapter configuration.
3. Implement daily, weekly, and medium-horizon summary delivery based on configured run schedules.
4. Implement proposal IDs, approve/reject/apply-safe actions, and auditable decision records.
5. Implement structured feedback first; add natural-language interpretation only in the optional AI phase.
6. Verify in a test Slack channel with preview-only plan data.

**Acceptance criteria:** Messages accurately represent the stored plan; approval is required before protected writes; capacity-risk alerts include task, deadline, reason, and alternatives.

### Phase 6: Optional LLM enrichment and conversational feedback

**Objective:** Add provider-pluggable, safety-bounded assistance without reducing deterministic guarantees.

**Files:**
- Create: `planner/adapters/LlmGateway.groovy`
- Create provider implementations and JSON schemas
- Create tests for schema validation, disabled mode, redaction/minimum-data behavior, and rejected unsafe output.

**Steps:**
1. Define structured schemas for task suggestions, event classification suggestions, and temporary planning overrides.
2. Implement provider-neutral gateway and an OpenAI-compatible adapter first; add Anthropic/xAI/local adapters only after interface validation.
3. Enforce `enabled: false` default and zero mutation authority.
4. Add a confirmation boundary for all persistent policy changes.
5. Test malformed model output and verify it cannot alter plans or external systems.
6. Run an opt-in trial using a non-sensitive test data set.

**Acceptance criteria:** Planner produces identical deterministic schedules with AI disabled/enabled unless a user-approved structured override is applied; model output cannot cause unapproved writes.

### Phase 7: Production hardening and gradual automation

**Objective:** Earn trust through observability, reconciliation, secure secrets handling, metrics, and incremental move from preview to safe automation.

**Steps:**
1. Add structured run receipts, plan IDs, adapter health, error classification, and alerting.
2. Add state backup/recovery and reconciliation commands.
3. Add documentation for calendar ownership, deadline/due semantics, and rollback.
4. Run preview for multiple planning cycles; compare proposal quality and actual completion outcomes.
5. Enable approval-required mode; later enable safe automatic changes outside configured stability windows only after explicit user approval.

**Acceptance criteria:** Every mutation is traceable to plan ID and approval/mode; reconciliation detects drift; user can revert or preserve a manual override; production runs have actionable diagnostics.

---

## 9. Test strategy

### Unit tests

- event classification precedence, regex against title/description, calendar defaults, and buffers;
- working-window and slot calculation;
- deadline/duration feasibility;
- Todoist P1–P4 scoring and stable tie-breaks;
- task context preference/avoidance;
- project batching and deadline exceptions;
- weather thresholds and fallback behavior;
- frozen-window/manual-override behavior;
- plan diffs and explanations;
- configuration validation and redaction.

### Integration tests using fakes/fixtures

- Todoist task read/update including deadline preservation;
- calendar event read and owned-event write/delete;
- all-or-recoverable partial apply behavior;
- weather provider fixture parsing;
- Slack message content and approval transitions;
- optional LLM schema rejection and disabled mode.

### Manual end-to-end rollout

1. Use a dedicated Todoist test project and a dedicated planner output calendar.
2. Run capacity reports with real but read-only calendar data.
3. Compare preview plans with actual availability for at least several cycles.
4. Apply a single approved plan; verify both the event start and Todoist due time agree, and the deadline did not change.
5. Test a manual move, a child-calendar event classification, a weather conflict, and a task that cannot fit before deadline.
6. Do not enable full automation until explanations, state reconciliation, and rollback are proven.

---

## 10. Non-goals for initial phases

- Automatically changing Todoist deadlines.
- Automatically classifying new event meanings with an LLM without an existing rule or user confirmation.
- Writing over non-planner calendar events.
- Treating every Todoist task as schedulable work.
- Solving all schedule optimization globally before a deterministic, explainable greedy scheduler proves useful.
- Requiring Slack, weather, or an LLM for baseline planning.

## 11. Success criteria

The planner is successful when it reduces manual rescheduling by presenting and applying a plan that is feasible against real commitments, honors Todoist deadlines and priorities, synchronizes scheduled Todoist due times with calendar starts, groups related work intelligently, adjusts outdoor work for weather, explains unavoidable trade-offs, and changes the calendar only with an appropriate level of user trust and approval.
