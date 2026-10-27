# Bounded LLM integration

AI is disabled by default and is not part of deterministic scheduling or application. The explicit
`ai-suggest` operation loads a stored plan, builds a size-bounded/redacted minimum context, and calls
an OpenAI-compatible structured-output endpoint. The AI service receives no Todoist write gateway,
CalDAV write gateway, plan/config store, decision store, messaging gateway, or `PlanApplier`.

```yaml
planner:
  ai:
    enabled: true
    provider: openai_compatible
    endpoint: https://api.openai.com/v1/chat/completions
    model: gpt-5-mini
    secret_env: OPENAI_API_KEY
    allowed_hosts: [api.openai.com]
    connect_timeout: PT5S
    request_timeout: PT30S
    max_request_bytes: 65536
    max_response_bytes: 65536
    max_items: 100
    max_string_chars: 500
    max_tokens: 1200
    allowed_suggestion_types: [task_suggestions]
    redaction_enabled: true
    require_confirmation: true
```

Only allowlisted HTTPS hosts/default port are accepted; redirects and tool/function calls are
rejected. The request fixes temperature zero, caps tokens/body sizes, supplies a strict JSON schema,
and binds plan hash plus planning-input hash. Responses must have one assistant message and validate
against the selected versioned schema. Errors/audit receipts omit raw prompts, responses, and secrets.
Rate-limit metadata is returned without automatic retry because a suggestion request is not safely
replayed by assumption.

Run:

```bash
todoist-caldav-sync ... --operation ai-suggest --plan-id ID \
  --ai-type task_suggestions --correlation-id review-2026-08-14
```

The command emits result JSON, then exits **0 for accepted results** (including an empty suggestion
bundle) or **1 for rejected results**. Usage/configuration errors retain exit **2**. Rejections no
longer exit zero: shell consumers must capture the actual exit without discarding the JSON, especially
under `set -e`. HTTP 200 proves transport success only, not validation acceptance or a useful nonempty
suggestion. Acceptance and suggestion count are separate observations; neither grants write authority.

Existing JSON compatibility fields are retained. The error's `class` and safe `detail` are accompanied
by optional `reasonCode`, `statusCode`, `responseBytes`, `structuredTextBytes`, and `responseTruncated`;
the audit receipt carries the same safe metadata, never raw exception/model text. `responseBytes`
measures observed wire bytes, with null for unknown. `responseTruncated: true` marks an incomplete,
lower-bound observation rather than a complete response size. `structuredTextBytes` is the extracted
structured output's UTF-8 byte count only when known, not the wire size. See the
[QA replay outcome guidance](SMARTPLANNER_QA_REPLAY.md#c-xai-status-optional-device-login-and-four-strict-schemas)
for evidence handling. Diagnostics do not change validation, providers, limits, retries or authority.

Allowed types are task suggestions, event-classification suggestions, temporary planning overrides,
and conversational feedback interpretation, subject to configuration. In daemon proposal threads,
unmatched feedback may produce a bounded suggestion that is persisted against the exact current plan
and expires after 15 minutes. SmartPlanner posts a summary; an allowlisted actor must then use the
configured deterministic phrase for the same action. Before that confirmation there is no replan,
decision, Todoist write, or CalDAV write. Stale/expired/mismatched suggestions and unknown or
out-of-range task overrides are rejected. No AI output directly changes a plan, calendar, Todoist task,
configuration, or decision. Test against a mock/approved test
endpoint first and verify redaction, schema rejection, token/body bounds, and unchanged remote/local
write surfaces.

## Confirmed temporary planning overrides

The daemon carries the schema-supported `duration_minutes` and `context_label` suggestions separately
from deterministic regex priority/exclusion/freeze maps. A nonempty validated set displays its actual
targets, values and **fixed reviewed range** (the original plan's available-slot envelope). Every
suggestion must cover that complete range; a narrower suggestion is refused, not stretched. Empty
or rejected output is not an actionable pending confirmation. Confirmation does not roll the horizon
forward with the clock.

Before saving pending state, the validator revalidates its canonical structured output. The private,
versioned envelope binds that canonical identity, selected suggestions, plan/version/hash, task/event
input hash, source event, conversation and expiry. The original inference audit/wire hash is unchanged.
Restart revalidates structured output at the current authoritative time; it never casts a restored map
into a trusted suggestion. Legacy untyped AI pending maps must be retried. Altered content, changed
tasks/events, wrong conversation/actor, missing provenance and expired confirmation fail closed without
a revised proposal or provider writes. The confirmation deadline is the earlier of 15 minutes and
the selected suggestions' expiry.

The existing signed decision ledger uses a service-owned 256-bit random key in
`decisions_dir/.ai-suggestion-signing-key`, created owner-readable/writable only. Preserve this key,
the store identity/decision records, plans, conversations and delivery ledger together across restart
and backup. Missing or corrupt retained signing state is an error, not permission to generate new
authority. This is local private service state, not another provider credential. Do not publish it or
the actionable pending structured output.

Exact allowlisted confirmation rereads source tasks and events and validates the entire selected set
against one original baseline. Duration and context changes compose on temporary task copies while
preserving native Due/recurrence, Deadline, lifecycle metadata and manual controls. The new proposal
records temporary-change explanations; source duration/labels, prior plans and permanent configuration
do not change. The ordinary availability, weather and deterministic scheduling pipeline still decides
placement. A separate exact apply approval is required for scheduling-provider writes.

Confirmation intent, its actor/time and stable successor identity are saved before generation. A
restart can reconcile already-recorded decisions and reuse the retained successor rather than create
another. Pending authority is retained through the publishing barrier; the resolved conversation
retains its signed successor link. An uncertain publication/final persistence outcome leaves the
conversation blocked for delivery-ledger reconciliation, not automatic republishing or approval of the
old plan. Do not delete these records to force a retry.

Subscription profiles are documented separately in
[`AI_SUBSCRIPTION_AUTH.md`](AI_SUBSCRIPTION_AUTH.md). OpenAI SIWC provides an authorized direct,
tool-free public `/v1/responses` route. The xAI profile uses the distinct Hermes-style RFC 8628 route and
sends its own rotating bearer to `api.x.ai/v1/responses`; it does not import Grok Build state or use the
CLI proxy. Both remain pending owner-run disposable live entitlement/inference validation. SmartPlanner does not invoke
`codex exec`, `grok -p`, ACP, or coding-agent subprocesses; does not rely on prompt-only tool
restrictions; and never falls back to separately billed public APIs.

Subscription inference uses SSE and accepts output only after `response.completed`. The xAI-specific
default wire limit is 262144 bytes (other providers retain 65536); explicit configured limits are not
overridden. For the verified `grok-4.6`/`grok-4.7` request contract, low reasoning effort and bounded
`max_output_tokens` reduce stream size. Temporary overrides receive the same range/expiry bounds used
by local validation, and request-specific schema constants constrain correlation and applicable
plan/proposal identities. None of these controls bypass validation or grant mutation authority.
