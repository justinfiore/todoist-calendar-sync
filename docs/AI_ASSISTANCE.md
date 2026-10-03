# AI Assistance

AI Assistance adds optional, provider-neutral suggestions without adding autonomous SmartPlanner authority.
AI is disabled by default. The deterministic scheduler, plan applier, messaging service, structured
feedback parser, and baseline CLI neither construct nor invoke an LLM gateway.

## Contracts and flow

`AiAssistanceService` is an explicitly invoked side service:

1. `LlmContextBuilder` copies only allowlisted task/event fields, redacts sensitive patterns before
   serialization, applies deterministic item/string/byte budgets, and reports omission counts.
2. `LlmGateway` receives an immutable `LlmRequest`; it cannot receive planner domain objects or write
   gateways. The OpenAI-compatible adapter uses temperature 0, fixed strict JSON schema, bounded
   tokens and bodies, finite timeouts, HTTPS host allowlisting, and redirect policy `NEVER`.
3. The provider envelope and `LlmSchemaValidator` both require exactly one JSON object root (trailing
   whitespace only) and reject duplicate keys at every depth before inspecting fields. Model content
   is then validated against the versioned draft-2020-12 resource. The accepted
   bundle content hash binds the exact provider bytes while the retained validation tree is redacted.
   It rejects the entire response on malformed JSON, trailing tokens/text, unknown/missing fields,
   identity mismatches, cross-context IDs, duplicate IDs or logical targets, invalid bounds/time ranges, unsafe output,
   or schema-version/correlation mismatch. It returns an immutable `AiSuggestionBundle` only.
4. Every request carries a server-computed, versioned `planningInputHash` over the complete tasks,
   slots, placements, plan fields, metadata, and supplied events before redaction. Task/event ordering
   is canonicalized, nulls are explicit, and instants/durations use stable ISO forms.
5. Audit receipts contain metadata and hashes only. Raw prompts and model responses are not persisted.

The versioned source contracts are in `app/src/main/resources/planner/ai/schemas/v1/` for exactly:

- `task_suggestions`
- `event_classification_suggestions`
- `temporary_planning_overrides`
- `conversational_feedback_interpretation`

## Confirmation and authority

Model output never changes a Plan, PlannerConfig, DecisionStore, application state, delivery ledger,
Todoist, a calendar, or messaging provider.

`AiSuggestionConfirmationService` is fail-closed and requires an authorized actor, an explicit
authoritative time, and a new explicit confirmation correlation. Actor and correlation values must
be exact 1–128 character ASCII opaque identifiers matching `[A-Za-z0-9][A-Za-z0-9._:@-]*`; whitespace,
controls, paths/URLs, Unicode confusables, and credential-like identifiers are rejected before any
store write. Request correlation, plan, proposal, task, and event identities are likewise validated
without trimming or canonicalization. Authorization uses the exact validated actor. `AiSuggestionDecisionStore`
atomically binds the exact suggestion ID, schema/type/version, bundle content hash, plan ID/version/hash,
planning-input hash, actor, correlation, timestamp, and
action and store provenance. It reserves every terminal correlation and exact suggestion identity,
including rejections, and classifies concurrent replay/conflict under one process/file lock. Atomic
move support is mandatory; persistence fails closed instead of using a non-atomic replacement. Every
record has an HMAC-SHA-256 signature over its versioned immutable fields. The store constructor requires
an externally resolved key containing at least 256 bits of high-entropy material; the key is never
written to the ledger, store identity, audit data, errors, configuration, or documentation examples.
Every load and authorization verifies signatures with a constant-time comparison, and malformed JSON,
duplicate keys, missing/invalid signatures, tampering, and records copied to another store fail closed.

Persistent event rules, config changes, task metadata, or classification suggestions produce only a
`CONFIRMED_POLICY_SUGGESTION` audit record. AI Assistance never writes policy/config from that record.

A valid, unexpired temporary `duration_minutes` or `context_label` suggestion produces only a persisted
decision ID after a new exact confirmation. `ConfirmedOverrideApplier` reloads that decision from the
originating store and verifies schema/type, bundle content hash, plan identity, planning-input hash,
suggestion, actor/action, provenance, expiry, an exact current task/event/slot snapshot, and full
requested-range containment before returning task copies. Override validation requires
`rangeStart < rangeEnd <= expiresAt`, a future expiry, and a maximum 31-day lifetime. Application
requires `now < expiresAt`, and its complete range must remain within both the confirmed range and
expiry. It never trusts a caller-created confirmation object or mutates the source Plan/config/tasks.
The normal deterministic scheduler still enforces deadlines and capacity.

Natural-language feedback follows two gates. The request contains the exact expected structured-feedback proposal,
plan ID/version/hash, and allowlisted actions. The model cannot supply a command; the server constructs
the canonical feedback command from validated expected values and a redacted reason. Explicit confirmation returns that structured command
string; the host must separately choose to pass it to `FeedbackParser`. Interpretation and
confirmation do not call `FeedbackParser`, `DecisionStore`, or `PlanApplier`.

## Provider composition

Use `AiAssistanceService.create(plannerConfig, gatewayFactory)`. The gateway factory is not evaluated
when `planner.ai.enabled` is false. For `openai_compatible`, inject `OpenAiCompatibleLlmGateway`; tests
inject `LlmHttpTransport` and make no network calls. Credentials are resolved only through the
configured environment-variable name at request time.

Decision-store composition separately resolves its signing key outside `PlannerConfig` and supplies it
directly to `AiSuggestionDecisionStore`. There is no default durable key and an unconfigured store cannot
record or authorize a confirmation.

The AI Assistance trial is deliberately a non-sensitive fixture trial covering all four schemas. No live LLM
or network trial occurred, and AI Assistance does not provision production secrets or daemon/CLI wiring.
