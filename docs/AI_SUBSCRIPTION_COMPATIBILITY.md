# AI subscription compatibility manifest

Checked 2026-10-04. This manifest is secret-free. No account login, one-time code, token, identity, or
live provider payload was used or retained.

## Release decision

| Profile | Contract | Hermetic status | Remaining live gate |
|---|---|---|---|
| `codex_subscription` | OpenAI SIWC `openai-siwc-2026-10-03-v1` | Implemented against official dynamic registration, rotating refresh, discovery/JWKS/revocation, model catalog, and public `/v1/responses` SSE contract | Owner-authorized disposable-account matrix: actual registration/consent, entitlement/model selection, forced rotation, revocation, and all suggestion schemas |
| `grok_build_subscription` | xAI `xai-device-responses-hermetic-v1` | RFC 8628, endpoint pinning, rotation, and direct Responses serialization are hermetically implemented but deliberately unreachable | xAI authorization for third-party reuse of the Grok Build client registration and a live proof that its OAuth bearer is entitled at `api.x.ai/v1/responses` |

Neither profile falls back to an API key, another provider/account, or another billing surface. Neither
invokes `codex exec`, `grok -p`, ACP, shell/MCP tools, or any coding-agent subprocess for inference.

## OpenAI SIWC evidence

OpenAI's official [open-source token-sharing overview](https://developers.openai.com/siwc/token-sharing-open-source)
explicitly permits eligible ChatGPT plan use in open-source/local applications. SmartPlanner follows the
documented contracts for [registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in),
[profiles and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions),
[models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference),
[self-hosted VMs](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms), and
[preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

The pinned contract uses `dynamic_agent_client`, an issued `oaiapp_…` client ID, fresh state/nonce/PKCE,
`127.0.0.1` callback, scopes `openid profile email offline_access resource.invoke
chatgpt.tokens.use.direct`, resource `https://api.openai.com/v1`, rotating refresh at
`https://auth.openai.com/api/accounts/oauth/token`, and discovery-based revocation. Inference lists
models at `GET https://api.openai.com/v1/models` and posts only to
`https://api.openai.com/v1/responses`. Requests set `store:false`, `stream:true`, send complete `input`
history, omit tools and unsupported preview fields, and accept success only after `response.completed`.
The private `chatgpt.com/backend-api/codex` endpoint is expressly not used.

Codex CLI device import remains disabled. It can be added only after an owner-authorized capability
probe proves that an isolated CLI-issued grant contains the required SIWC scopes and works on the public
endpoint. Until then, complete browser login locally and use OpenAI's documented secure transfer of the
protected credential record for a self-hosted VM; the VM becomes refresh owner.

## xAI/Grok evidence and exact gate

The current official Grok Build source publishes issuer `https://auth.x.ai`, discovery, RFC 8628 device
endpoint `https://auth.x.ai/oauth2/device/code`, source-visible client ID
`b1a00492-073a-47ea-816f-4c329264a828`, and CLI scopes. Hermes demonstrates rotating refresh and bearer
Responses mechanics at `api.x.ai/v1/responses`.

That is not sufficient service authorization. Grok Build's official source binds the registration to
the `grok-build` referrer and CLI-specific scopes/headers, routes OAuth/session inference to
`https://cli-chat-proxy.grok.com/v1`, and routes API-key inference to `https://api.x.ai/v1`. Neither the
Apache-2.0 source license nor xAI's consumer terms authorize an unrelated app to impersonate that client.
The launcher therefore performs no xAI authorization until xAI grants that permission and the live
public-endpoint capability/entitlement matrix passes.
