# Design

## Context

See `proposal.md` for motivation and the capability delta for observable behavior. The original live bundles were not retained, so the observed 30-minute revision cannot establish their exact contents. The translator defect is independently reproduced offline.

Current boundaries:

- `SmartPlannerDaemon.handleFeedback` accepts the interpretation result, calls temporary-override generation, converts suggestions into a map, and persists pending confirmation. Its translator handles priority/exclusion/freeze, disjoint from the real schema's duration/context types.
- `ProductionPlannerOrchestrator.preview` transforms normalized provider tasks only for exclusion/priority; changing the Slack map alone cannot implement duration/context behavior.
- `LlmSchemaValidator` issues authenticated in-process bundles backed by canonical structured output and the original request. Casting restored JSON to a bundle cannot restore that provenance.
- `AiSuggestionConfirmationService` and `AiSuggestionDecisionStore` already validate exact identity and persist signed, replay-aware per-suggestion decisions. `ConfirmedOverrideApplier` already implements duration/context changes, but is not wired into production preview.
- The current applier validates one override against an unchanged baseline and copies only older task fields. Composing several overrides by repeatedly applying that single-override API would violate baseline identity and risk losing newer recurrence/Due/Deadline metadata.
- Planning range is currently represented by the immutable plan's available-slot envelope. Confirmation currently replans from a new clock time, which can extend the rolling horizon beyond an override's authorized range.

## Goals / Non-Goals

**Goals:** Join the existing validated suggestion, durable confirmation and deterministic planning boundaries. Make duration/context changes observable in a new proposal, with explicit refusal on invalid authority and complete source-field preservation.

**Non-Goals:** Expand the AI schema to priority/exclusion/freeze, let models directly edit task/provider/config state, bypass range checks, introduce another model provider, or redesign unrelated scheduler/auth behavior. The existing regex override path stays separate.

## Decisions

### Keep typed AI authority separate from regex override maps

Replace AI use of `mergeAiOverrides` with the existing validated bundle and explicit confirmed-decision references. Keep regex maps and their existing validation unchanged. The production orchestrator owns the confirmation-to-preview boundary; `AiAssistanceService` remains an authority-free suggestion service.

Rejected alternative: translating `duration_minutes` to a new unchecked map key. It still leaves preview unimplemented and duplicates or bypasses existing confirmation guards.

### Persist only bounded structured suggestion state, then revalidate

Extend existing conversation/pending state with a versioned AI envelope containing schema version, correlation, canonical accepted structured data, exact plan/input/content identity, selected suggestion IDs, source conversation/event, reviewed range and confirmation expiry. Persist no prompt, raw SSE stream or OAuth/device authorization material. Keep ordinary audit metadata separate from this private actionable state.

Add a narrow serialization/restoration boundary to `LlmSchemaValidator`, which already owns canonicalization and provenance. Revalidate canonical structured output before creating pending state and use that canonical validator-issued bundle for pending identity and all later decisions. On restoration, reconstruct the minimal request from the bound immutable plan and reviewed constraints, validate the same canonical output at the current authoritative time, and check its persisted identity; never register a deserialized object as authentic directly. This keeps the bundle content hash stable across restart despite original wire whitespace, without rewriting the original response/audit hash or historical decisions.

Pending content must remain bound to the displayed confirmation summary. Use the existing signed AI decision provenance mechanism for its integrity boundary, not an editable map or an unkeyed hash that can be changed with the data. Retain the canonical pending content hash and selected suggestion identities through confirmation.

### Reuse the signed decision store with service-owned key lifecycle

Wire `AiSuggestionConfirmationService` and `AiSuggestionDecisionStore` into the orchestrator under the existing private decisions state, initialized only when the AI confirmation path needs them. Provision a cryptographically random persistent signing key with owner-only permissions; do not require another user-supplied provider secret. Reuse it after restart and preserve it together with the decision state. If retained AI records exist but the key is missing/corrupt, fail closed instead of generating replacement authority.

At confirmation, re-read the current conversation and bound plan, fetch normalized source tasks and relevant events, compare their identities to the pre-override input, and authorize the actual actor/channel/thread. Verify the pending envelope, plan/version/hash, targets, selected bundle content, range and expiry before recording decisions. Use the earlier of the 15-minute confirmation deadline and the selected override expirations. Reject incompatible or partially valid sets as a whole.

### Transform all selected overrides against one baseline

Extend `ConfirmedOverrideApplier` at its existing source of truth to support the selected confirmed set: verify all signed decisions against the same original task/event snapshot before constructing task copies in one deterministic pass. Preserve the existing single-override API through the same implementation rather than adding a second override engine.

Compose duration and context changes for the same target without repeated baseline-hash comparisons against already-mutated task copies. Retain validator rejection of duplicate/conflicting logical targets. Copy every task field, including native Due/recurrence, Deadline date, description, creation/update timestamps, completion/lifecycle metadata, manual and scheduling controls; only effective duration/duration provenance or the confirmed planning-only context label may change.

Feed the transformed copies into the existing availability/weather/scheduler pipeline in `ProductionPlannerOrchestrator`. Record the temporary change in plan explanations. Source gateways remain read-only in preview, and previous immutable plan objects remain unchanged. Do not retain AI overrides as ordinary map entries that automatically carry into subsequent plan identities.

### Fix the reviewed range instead of weakening containment

Persist and display the original available-slot envelope as the reviewed AI planning scope. Require every selected suggestion to cover that complete fixed scope under the existing containment/expiry rules. Request that exact scope from the model; do not silently stretch a narrower response or change the scope during confirmation. Empty slots, expired scope, changed relevant inputs or an incompatible narrower suggestion require a fresh preview/review.

After confirmation, recompute availability/weather for that fixed scope and use the current clock for validity. Do not use `now + configured horizon` for this AI-confirmed revision. Normal scheduled and regex-triggered runs keep their existing rolling behavior. Any later proposal identity requires a new validation/confirmation before reusing AI-derived effects.

### Make the confirmation transition durable before publication

Preserve existing event deduplication, delivery ledger and proposal-publication barriers. Keep a recoverable pending/confirmed intent until its exact successor plan and delivery outcome are durably linked; do not clear the only actionable state before successful generation. A resolved confirmation points to its existing successor and cannot create a second revision after restart.

If per-suggestion decision persistence is interrupted, retain the exact intent and reconcile already-recorded decisions through their stable identities; never authorize a valid subset. Unknown Slack delivery outcomes stop automatic republishing and require ledger reconciliation. This is local confirmation/publication recovery, not permission to retry unknown provider writes.

### Verify behavior through production boundaries

Replace the invalid `exclude` AI fixture in `SmartPlannerDaemonSpec` with validator-issued real-schema duration/context bundles. Assert the revised plan's actual 45-minute interval, a distinct unchanged control, context-sensitive placement, unchanged source fixtures, and no provider writes before or after confirmation. Include one combined set to catch partial application and incorrect composition.

Extend existing confirmation/orchestrator tests for current-time restart restoration, input drift, actor/thread mismatch, tampering, legacy pending records, missing key, expiry/range boundaries and duplicate confirmation. Keep private-helper interaction assertions out of permanent tests. Run the regression before the fix, then the focused contracts and full credential-free build after it.

## Risks / Trade-offs

- [Larger pending state] -> Store bounded normalized structured output only, within existing AI/event item and size limits; keep it private and out of published evidence.
- [Two notions of task input after a temporary transformation] -> Bind authorization to the original provider snapshot; explanations and the successor plan record the transformed copy. Never compare a transformed copy as though it were the unchanged source.
- [Signing-key loss] -> Preserve key and decision state together; explicit refusal is safer than recreating authority over old records.
- [Range guard appears stricter than ordinary replan] -> Display the fixed scope and rejection reason; generate a fresh suggestion instead of broadening authority.
- [Protected metadata lost by copying] -> Extend the existing applier's copy behavior and regression assertions, not a one-off caller-side restoration wrapper.
- [Passing inference mistaken for application proof] -> Require an actual revised duration/context effect in both hermetic and real-provider evidence, not merely accepted JSON or a new iteration number.

## Migration Plan

1. Commit this planning change separately on `fix/xai-device-login-qa-plan`.
2. On a subsequent explicit apply request, implement and verify the change, update only its completed tasks, and commit code/tests/docs separately. Push the branch before the requested fresh live campaign; no merge or deployment is implied.
3. Old conversations/plans and regex state remain readable. Legacy AI pending maps without validated envelope identity are refused with a request to retry, not silently migrated to trusted suggestions.
4. Use a fresh ignored campaign and owned resource inventory; never overwrite earlier evidence or restore stale environment snapshots over rotating credential files. Verify Google/Todoist/Slack account/resource scope before mutations and stop on ambiguity.
5. Run bounded real xAI subscription requests on the verified models, current exact-location Open-Meteo, native Slack confirmation/restart and the owner's signed-in Firefox Todoist UI. Confirm the displayed proposal actually changes 30 to 45 minutes while source task duration/labels remain unchanged. Use Google UI/API cross-checks for owned schedule values and links.
6. Restore native Todoist completion checks for approved disposable recurrence variants: record protected tuples and API state before/after two native completions, preserve historical event identity, and distinguish native actions from API-only observations. Do not mark skipped, blocked or synthetic cases live-passed.
7. Stop the daemon, disable optional mutation features, clean only recorded owned resources, independently verify zero active leftovers/unrelated inventories, and securely persist current canonical rotating credentials. Update the existing report and publish only inspected sanitized screenshots/recordings and verified case results.

Rollback stops the daemon and uses the prior revision/configuration while preserving all state for inspection. Do not recreate lost provenance or delete unresolved delivery/decision records to make a retry succeed.
