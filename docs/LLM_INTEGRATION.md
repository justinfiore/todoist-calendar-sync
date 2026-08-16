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

Allowed types are task suggestions, event-classification suggestions, temporary planning overrides,
and conversational feedback interpretation, subject to configuration. Output remains a proposal.
Confirmation/policy workflows are separate AI Assistance APIs; no AI output directly changes a plan,
calendar, Todoist task, configuration, decision, or Slack message. Test against a mock/approved test
endpoint first and verify redaction, schema rejection, token/body bounds, and unchanged remote/local
write surfaces.
