# AI subscription compatibility manifest

Checked 2026-10-04. This manifest is secret-free. No account login, one-time code, token, identity, or
live provider payload was used or retained.

## Release decision

| Profile | Hermetic contract | Pre-live state | Remaining owner gate |
|---|---|---|---|
| `codex_subscription` | `openai-siwc-2026-10-03-v1` | Official SIWC registration, refresh, discovery/JWKS/revocation, model catalog, and public Responses SSE implemented | Disposable browser login, entitlement/model selection, refresh/restart, four schemas, daemon, revocation, cleanup; optional Codex CLI token probe remains separate and unimplemented |
| `grok_build_subscription` | `xai-device-responses-hermetic-v1` | Hermes-style direct RFC 8628, rotating refresh, model catalog, and `api.x.ai/v1/responses` implemented and reachable | Disposable account device login, tier entitlement/model selection, refresh/restart, four schemas, daemon, logout/account revocation, cleanup |

Neither profile falls back to an API key, another provider/account, or another billing surface. Neither
invokes `codex exec`, `grok -p`, ACP, shell/MCP tools, or any coding-agent subprocess for inference.
“Implemented” is not a claim that live subscription entitlement has passed.

## OpenAI evidence

OpenAI's official [open-source SIWC overview](https://developers.openai.com/siwc/token-sharing-open-source)
explicitly permits eligible ChatGPT-plan use in open-source/local applications. Its
[sign-in contract](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) specifies
`dynamic_agent_client`, an issued client ID, fresh state/nonce/PKCE, `127.0.0.1`, scopes `openid profile
email offline_access resource.invoke chatgpt.tokens.use.direct`, resource
`https://api.openai.com/v1`, and token exchange at
`https://auth.openai.com/api/accounts/oauth/token`. The
[self-hosted guide](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms)
permits secure protected-record transfer and makes the VM the refresh owner.

Inference lists models at `GET https://api.openai.com/v1/models` and posts to
`https://api.openai.com/v1/responses` with OAuth Bearer, `store:false`, `stream:true`, complete `input`,
no tools, and success only on `response.completed`. SmartPlanner does not use the private ChatGPT Codex
backend. Codex CLI device import remains disabled until a live probe proves its separately issued grant
has the required public SIWC scopes.

## xAI route reconciliation

Three paths must not be conflated:

1. **Official Grok Build CLI auth.** Current [`xai-org/grok-build`](https://github.com/xai-org/grok-build)
   uses issuer `auth.x.ai`, client `b1a00492-073a-47ea-816f-4c329264a828`, expanded CLI scopes, a
   `grok-build` referrer, and client identity headers. Its OAuth/session inference defaults to
   `https://cli-chat-proxy.grok.com/v1`; its API-key route defaults to `https://api.x.ai/v1`.
2. **Hermes `xai-oauth`.** [`NousResearch/hermes-agent`](https://github.com/NousResearch/hermes-agent)
   independently performs RFC 8628 with the same source-visible client and the bounded scopes `openid
   profile email offline_access grok-cli:access api:access`, stores/refreshes its own rotating grant, and
   sends the bearer through its Responses transport at `https://api.x.ai/v1/responses`. It neither
   imports `~/.grok/auth.json` nor uses the CLI proxy. SmartPlanner implements this route and omits the
   Grok Build referrer/client-identity headers.
3. **Public API-key billing.** Official xAI API documentation sends `XAI_API_KEY` as Bearer to the same
   `api.x.ai/v1/responses` host. That separately billed credential and quota are not used or accepted by
   the subscription profile.

The official Grok Build source proves only its own proxy routing; it does not prove that every OAuth
bearer is restricted to that proxy. Hermes proves the direct route is technically implemented and has
tests/documentation for SuperGrok/Premium+, while also warning that account tiers can receive 403 and
that broader quota semantics are not documented. No current official xAI page located in this review
explicitly documents the Hermes third-party route. The owner has stated that third-party subscription
use is allowed, so separate terms permission is **not** retained as a project gate. The honest remaining
gate is owner-authorized disposable live proof of login, entitlement, model availability, inference,
rotation, and cleanup. A failure stays provider-local and does not trigger CLI-proxy or API-key fallback.
