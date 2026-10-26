# Tasks

## 1. Safe semantic diagnostics

- [x] 1.1 Extend the existing error contract and validator rejection path with allowlisted reason codes; extend LlmSchemaAndAssistanceSpec to distinguish unknown-task, plan-binding, expiry, range and duplicate-target failures, reject whole bundles, and exclude synthetic secret/identity canaries.
- [x] 1.2 Carry safe codes through service revalidation and audit serialization using existing contracts; document the optional metadata and verify current successful bundles and legacy receipt readers remain compatible in the AI contract suites.

## 2. Accurate transport and audit outcomes

- [x] 2.1 Classify known response-limit and timeout exceptions before generic subscription transport handling; extend SubscriptionResponsesGatewaySpec to prove RESPONSE_TOO_LARGE versus TIMEOUT versus TRANSPORT without raw exception data, retry or fallback.
- [x] 2.2 Preserve available HTTP status and measured wire/structured-text byte metadata in existing results and audits, using absent values for unknown measurements; cover HTTP-200 semantic rejection and a stream aborted at its bound, and document byte semantics in the replay guide.

## 3. Honest CLI outcome signaling

- [x] 3.1 Return ai-suggest JSON plus exit one for rejected results, zero for accepted results, and preserve existing configuration exit two; extend the existing main CLI specifications with rejected, accepted-empty, accepted-nonempty and invalid-config cases.
- [x] 3.2 Update CLI/runbook documentation and any repository consumers that rely on exit zero, and verify examples distinguish transport success, validation acceptance and a useful nonempty suggestion.

## 4. Integrated verification

- [x] 4.1 Run focused AI/CLI specifications, scripts/smartplanner-qa.sh contracts, and the full Gradle build; retain exact commands and results, requiring zero regressions and no new network dependency in automated tests.
- [x] 4.2 Under the existing disposable authorization, replay bounded current-catalog model cases against synthetic context; publish only safe metadata showing improved reason classification, unchanged fail-closed behavior and zero scheduling mutations, separately from any later inference/model compatibility fix.
