# Design

## Context

See proposal.md for motivation. `LlmSchemaValidator.fail(String ignored)` discards semantic reasons; `SubscriptionResponsesLlmGateway.complete` collapses non-auth exceptions into TRANSPORT. `AiAssistanceService` builds failed audits with responseBytes zero regardless of whether transport already received a body. `TodoistCalDavSync` serializes a rejected ai-suggest result then returns zero.

The pending subscription-gateway spec already requires classified bounded failures. Existing errors and receipts are shared by API-key and subscription paths. Diagnostics must not become a second path for untrusted text to escape redaction.

## Goals / Non-Goals

**Goals:** Explain which contract failed through public result metadata; keep errors auditable and shell-safe; preserve all validation and confirmation behavior.

**Non-Goals:** Model/prompt/schema changes to increase acceptance, increased body limits, payload logging, retries/fallback, credential changes, calendar-lock policy, new live fixtures, or declaring any model supported from one successful response.

## Decisions

1. **Extend existing contracts, not a diagnostic subsystem.** Add an optional allowlisted reason code to `LlmError`, and carry that code plus available HTTP status/byte counts through existing gateway-result and audit records. Retain existing constructors/readers with sensible absent defaults. Use null/absence for unavailable measurements; distinguish transport wire bytes from extracted structured-text bytes. Avoid a new wrapper service or unrelated metrics framework.

2. **Generate reasons at the check that owns them.** Replace discarded validator reasons with fixed codes such as unknown_task, plan_mismatch, range_outside_context, expired_override, duplicate_target, and invalid_value. Never forward interpolated messages, arbitrary field paths, values, or validator-library error strings. Structural/schema and unsafe-output errors remain separately classified. Reject the entire bundle exactly as today. The service's independent revalidation must retain the same safe code.

3. **Classify recognized exceptions before the catch-all.** In the subscription gateway, map `LlmBodyLimitException` to RESPONSE_TOO_LARGE, recognized timeout forms to TIMEOUT, and keep unknown exceptions as a safe TRANSPORT failure. Preserve existing authentication/entitlement/SSE classifications. Do not infer a complete response length after a streaming limit; expose only the bound or measured lower bound if the existing transport can provide it accurately. Do not change transport limits as part of diagnostic work.

4. **Make one-shot automation honest.** `ai-suggest` emits its JSON and returns one when rejected, zero when accepted; configuration/usage errors retain current exit two handling. Do not change daemon process-exit behavior or conflate an allowed empty result with rejection. Document the shell compatibility change before shipping.

5. **Validate through existing public seams.** Extend `LlmSchemaAndAssistanceSpec`, `SubscriptionResponsesGatewaySpec`, and the existing main CLI specs. Use asymmetric invalid inputs that differentiate unknown-task, wrong-plan, and expiry failures. Inject body-limit/timeout exceptions and secret-bearing canaries; assert error/audit/CLI results and zero applicable bundles. No tests of private helper names or call order.

## Risks / Trade-offs

- More detailed errors can disclose input values → finite application-defined codes only; canary checks across serialized errors, receipts and CLI.
- CLI rejection exits change existing shell behavior → keep JSON fields stable, document exit one, update existing CLI consumers/tests.
- Audit field additions affect stored receipts → optional additive metadata and backward-readable existing records; do not rewrite historical QA evidence.
- Larger models may exceed current bounds → report that accurately; compatibility/model/limit decisions remain separate from observability.

## Migration Plan

Implement and verify the diagnostics without changing inference acceptance criteria. Run targeted AI/CLI suites, the prepared contracts, and the full build. Then replay the recorded model matrix with bounded synthetic requests. Compare semantic acceptance and no-write invariants separately from the improved reasons. Rollback restores the previous binary/exit behavior; no provider data migration is involved.
