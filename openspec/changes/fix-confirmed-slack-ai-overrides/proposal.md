# Proposal

## Why

Fresh live Slack QA requested a 45-minute task but the confirmed revision retained 30 minutes. An independent offline runtime probe confirmed that the daemon drops both schema-supported override types, while production preview has no duration/context application path; the existing daemon test masks the mismatch with a fabricated `exclude` AI response that the real schema rejects.

## What Changes

- Carry validator-issued `duration_minutes` and `context_label` suggestions through durable Slack pending confirmation instead of translating them into incompatible priority/exclusion maps.
- Reuse the existing exact-confirmation decision service and confirmed-override task transformation in production preview, including plan/input identity, task ownership, range, expiry and replay checks.
- Revalidate restored suggestions after restart and reject legacy untyped AI pending records rather than treating arbitrary maps as confirmed authority. Preserve deterministic regex priority/exclusion/freeze behavior.
- Apply overrides only to temporary task copies for the reviewed planning scope; do not modify Todoist source duration/labels, permanent configuration, historical plans or provider-write approvals.
- Explain rejected, empty or unapplied overrides truthfully, and test observable revised-plan effects using real schema-valid fixtures.
- After implementation verification and push, execute a new isolated Google-only QA campaign with xAI subscription OAuth, Open-Meteo, Slack and native Todoist verification in the owner's signed-in Firefox session. Retain sanitized screenshots/recordings and update the existing report without replacing historical evidence.

## Capabilities

### New Capabilities

- `confirmed-planning-overrides`: End-to-end acceptance, durable confirmation and bounded application of schema-supported AI planning overrides to new proposal inputs, including restart and fail-closed behavior.

### Modified Capabilities

None. The durable spec inventory currently covers Calendar routing, writeback, change detection and Google OAuth, not this AI confirmation boundary. The in-flight `planner-integration` change describes regex priority/exclusion overrides; those remain separate and unchanged.

## Impact

- Implementation owners: `SmartPlannerDaemon`, `ProductionPlannerOrchestrator`, conversation state, `LlmSchemaValidator`, `AiSuggestionConfirmationService`, `AiSuggestionDecisionStore` and `ConfirmedOverrideApplier`.
- Extend existing daemon/orchestrator and AI confirmation tests; no credential-dependent tests in the committed suite and no new provider dependency or fallback.
- Compatibility: old AI pending confirmations without reconstructable validated suggestion identity must be retried. Existing plans, resolved decisions and regex overrides remain valid under their existing contracts.
- Out of scope: model/schema expansion, API-key fallback, auth-lifecycle redesign, permanent task-policy changes, unrelated scheduler cleanup and unowned provider resources. This proposal does not claim the original live model bundles were retained or establish their exact contents.
