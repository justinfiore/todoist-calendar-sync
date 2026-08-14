## Context

The Phase 6 branch contains independently tested planner modules for task/calendar normalization, availability, deterministic scheduling, guarded application, weather, Slack messaging and feedback, and bounded AI assistance. The legacy `TodoistCalDavSync` entry point still owns the installed launcher and production lifecycle. The integration branch already contains uncommitted candidate implementation and documentation; implementation work must review and complete it rather than overwrite it blindly.

The integration crosses remote APIs, durable state, safety modes, and operator procedures. Its primary stakeholder is an operator migrating from a due-date calendar renderer to a planner without exposing production tasks and calendars to unreviewed mutations.

Constraints:

- Java 25, Groovy 5, Gradle 9, Spock, and WireMock remain the baseline.
- Scheduling remains deterministic; LLM output has no mutation authority.
- Planner writes are restricted to the configured managed calendar and Todoist due datetime. Todoist deadlines are invariant.
- Automated tests are hermetic and never require provider credentials.
- Legacy sync behavior remains available.
- Existing uncommitted branch content must be preserved, inspected, tested, and refined.

## Goals / Non-Goals

**Goals:**

- Compose all Phase 1–6 capabilities behind the existing installed application.
- Separate read/preview, approval, safe apply, feedback, delivery, and AI operations at explicit command boundaries.
- Make endpoint, credential-reference, state, authorization, and optional-integration configuration complete and fail closed.
- Verify each HTTP contract using checked-in fixtures and WireMock, plus orchestration and CLI integration tests.
- Give an operator a reversible test-account and production rollout procedure.
- Provide discoverable feature documentation from the README.

**Non-Goals:**

- Replacing or silently migrating the legacy sync operation.
- Enabling fully autonomous application. `fully_automated` remains refused.
- Allowing LLM responses, Slack messages, or natural-language feedback to bypass structured confirmation.
- Modifying Todoist deadlines.
- Running automated tests against real Todoist, CalDAV, Weather, Slack, or LLM services.
- Provisioning provider accounts, secrets, calendars, or Slack applications.

## Decisions

### 1. Keep one launcher with an explicit production composition root

`TodoistCalDavSync` remains the installed main class and dispatches named operations. A dedicated `ProductionPlannerOrchestrator` assembles configuration, adapters, state stores, and Phase 1–6 services. This keeps lifecycle and dependency wiring out of deterministic domain services and creates a dependency-injection seam for integration tests.

Alternative: create a separate planner executable. Rejected because it duplicates packaging/configuration and does not satisfy integration into the existing application.

### 2. Make every side effect an explicit operation

Operations are separated into `capacity`, `preview`, `apply`, `apply-safe`, `deliver`, `feedback`, `apply-decision`, and `ai-suggest`. Feedback persists a decision but never applies it in the same call. AI returns bounded suggestions/audit only. Preview may persist an immutable local plan snapshot but performs no remote writes.

Alternative: a single daemon cycle that reads, plans, communicates, and writes. Rejected because it obscures authority boundaries, weakens testability, and makes rollback harder.

### 3. Preserve the three safe rollout modes and refuse automatic mode

- `preview`: read and plan; remote writes are skipped.
- `approval_required`: application requires approval bound to exact plan ID, version, and semantic hash.
- `apply_safe_changes`: ordinary changes may apply; frozen, manually moved, drifted, protected, and approval-required changes remain withheld.
- `fully_automated`: fail closed with a durable refusal and zero writes.

The crawl/walk/run guide uses those first three modes in that order. This corrects ambiguous “automatic mode” terminology without creating autonomous authority.

### 4. Isolate production HTTP adapters behind narrow ports

Todoist read/write, calendar read/write, weather read, messaging, and LLM access remain separate interfaces. Production adapters enforce bounded timeouts/bodies/pages, explicit endpoints, HTTPS by default, credential lookup through environment-variable names, and redacted errors. Todoist writes expose due-time update only; deadline update refuses. CalDAV writes validate managed-calendar ownership and search all configured calendars for UID collisions.

Alternative: reuse legacy internals directly. Rejected because legacy classes combine configuration, looping, HTTP, mapping, and mutation, making the new safety contracts difficult to enforce and test.

### 5. Treat four state stores as one operational consistency unit

Plans, applications/mappings, decisions, and deliveries use explicit independent paths but are backed up/restored together. Ambiguous provider success is recorded as unknown/reconciliation-required and never resolved by deleting state or blindly retrying.

### 6. Test both transport contracts and orchestration semantics

WireMock tests cover every HTTP method, URL/query/body/header/auth contract and representative success, pagination, retryable failure, non-retryable failure, malformed/oversized response, timeout, and ambiguous write outcome. Checked-in JSON/YAML/ICS fixtures make responses reviewable. In-memory gateways then test multi-step orchestration deterministically without conflating scheduler assertions with transport stubs. CLI tests validate dispatch, required options, help, exit codes, and secret-free errors.

### 7. Organize documentation for operators

The README contains a concise planner overview, safety modes, command links, and links to the full configuration, end-to-end, Slack, LLM, and Weather guides. The example YAML is executable documentation with safe disabled defaults and environment-variable names—not secrets. Feature guides include prerequisites, minimal and advanced examples, permissions, verification, failure behavior, and troubleshooting.

## Risks / Trade-offs

- **[Existing uncommitted implementation may be incomplete or internally inconsistent]** → Review its diff file-by-file, run focused and full tests, and amend it rather than assuming it satisfies this spec.
- **[Provider behavior differs from fixtures]** → Include representative protocol details and negative cases; require isolated live-account testing before production.
- **[Partial CalDAV/Todoist application]** → Persist itemized receipts, classify ambiguity, stop blind retries, and document reconciliation/rollback.
- **[Mode or operation confusion causes writes]** → Safe configuration default, explicit operation names, no implicit preview-to-apply transition, and guide checkpoints that verify zero writes first.
- **[State restore is inconsistent]** → Document and test paths; snapshot/restore all four stores together.
- **[Secrets leak through config/logs/test output]** → Reject inline production secrets, use env-name references, test redaction, and use fake fixture credentials only.
- **[Legacy and planner behavior diverge]** → Preserve `legacy-sync` as default and add regression coverage for its dispatch.
- **[Broad integration tests become brittle]** → Keep provider contract tests focused per adapter and use injected in-memory ports for orchestration behavior.

## Migration Plan

1. Preserve and inventory the existing integration-worktree changes.
2. Complete configuration validation, composition, adapters, and CLI wiring behind explicit operations.
3. Complete fixtures, WireMock provider tests, orchestration tests, CLI tests, and legacy regression tests.
4. Run focused tests, full `:app:test`, `build`, and `installDist`; exercise the installed launcher help and a hermetic preview path.
5. Finish README, quick start, example config, configuration reference, feature guides, and end-to-end guide; verify all relative links and commands.
6. Use dedicated Todoist and CalDAV test data for preview, exact approval, idempotency, safe-only write, ownership, and rollback gates.
7. Production crawl: `preview`, disabled optional integrations, narrow horizon, verified zero remote writes.
8. Production walk: `approval_required`, exact small approval, minimal write permissions, observed receipts and idempotent rerun.
9. Production run: `apply_safe_changes`, short horizon and daily receipt review; expand only after clean observations. Never advance to `fully_automated`.
10. Rollback on unexplained/ambiguous outcomes: stop applies/deliveries, retain evidence, compare live resources, restore remote backup and matching four-store snapshot, and return to `preview`.

## Open Questions

None. The requested implementation surface, WireMock approach, documentation set, and rollout progression are sufficiently defined. Provider-specific details must be implemented against the current live API contracts during apply rather than invented in this proposal.