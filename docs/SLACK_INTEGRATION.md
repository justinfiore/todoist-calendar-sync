# Slack integration

Slack is explicit and fail closed. Planning does not construct or contact Slack when
`planner.messaging.enabled` is false. When enabled, `provider` must be `slack`, `destination` is
required, and exactly the selected mode's credential environment-variable name must resolve.

Webhook mode uses `webhook_url_env` and permits only HTTPS `hooks.slack.com`. Chat API mode uses
`bot_token_env`, permits only HTTPS `slack.com/api/...`, and sends the configured channel id. Redirects
are disabled, request/response bodies are bounded, credentials are absent from persisted receipts,
and provider errors are classified. Do not put webhook URLs or bot tokens in YAML.

```yaml
planner:
  messaging:
    enabled: true
    provider: slack
    destination: CPLANNER
    slack_mode: chat_api
    bot_token_env: SLACK_PLANNER_BOT_TOKEN
    enabled_kinds: [proposal, daily_summary, capacity_risk_alert]
    schedules:
      - name: daily
        kind: daily_summary
        schedule: "06:00"
        horizon: P1D
        window: PT30M
```

Use `--operation deliver --plan-id ID --kind proposal` for an explicit kind, or omit `--kind` to
evaluate due schedules. The delivery ledger claims an idempotency key before sending. A delivered key
is terminal; pending/unknown states refuse blind resend; a classified failed send may be reclaimed.
HTTP 429 stores `Retry-After` metadata but the adapter does not sleep inside a request.

Inbound Slack event handling is intentionally not a network listener in this app. Pass a bounded
structured command to `--operation feedback` with provider actor/message/correlation identities. The
exact actor must appear in `planner.integration.feedback.allowed_actors`; omitted/empty denies all,
including help/status. Feedback only persists a decision. Applying an accepted APPROVE/APPLY_SAFE is
a separate `apply-decision` command that rechecks plan id/version/hash. Idempotent feedback replay
never authorizes a second apply.

Test first with a private test channel. Acceptance: expected payload/channel, one delivery per key,
accurate rate/failure receipts, no secret in logs/state, unauthorized actor refusal, and zero writes
from feedback alone.
