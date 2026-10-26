# Proposal

## Why

Live subscription trials rejected suggestions without identifying the failing semantic check, classified a body-limit exception as generic transport failure, and returned CLI exit zero for rejected suggestions. Operators need actionable metadata without exposing model content or weakening the acceptance boundary.

## What Changes

- Preserve bounded, application-defined reason codes for structural, semantic, streaming, and transport failures through existing error and audit records.
- Classify known body-limit and timeout exceptions accurately; preserve available HTTP status and measured byte counts, distinguishing unknown counts from zero.
- **BREAKING**: `ai-suggest` returns a documented nonzero exit when its result is rejected, while retaining machine-readable JSON and the existing success shape.
- Add regression coverage for diagnostic accuracy, all-or-nothing rejection, and sensitive-data exclusion.
- Keep model selection explicit. Model trials precede any inference compatibility fix; this proposal does not assume grok-4.3 is retired or that another model fixes validation.

## Capabilities

### New Capabilities

- `ai-failure-diagnostics`: Safe, actionable error metadata and one-shot AI command outcome signaling across provider boundaries.

### Modified Capabilities

None. This complements the pending `ai-subscription-gateway` and planner CLI integration changes without copying their authority or authentication requirements.

## Impact

Existing `LlmSchemaValidator`, `LlmError`/`LlmGatewayResult`/`LlmAuditReceipt`, `SubscriptionResponsesLlmGateway`, `AiAssistanceService`, and `TodoistCalDavSync` CLI. Extend existing AI and CLI Spock specifications. No new dependency, raw-payload logging, credential-store change, automatic retry/fallback, scheduling policy change, or provider-resource mutation.
