# Proposal

## Why

SmartPlanner's AI integration currently requires separately billed API keys, so an operator cannot use
eligible inference included with a consumer or workspace subscription. OpenAI now publishes Sign in with
ChatGPT (SIWC) specifically for open-source/local apps, including dynamic public-client registration,
protected credential transfer to self-hosted VMs, and a tool-free public Responses endpoint. Grok Build
publishes a device flow, but xAI has not documented reuse of its CLI client registration by third-party
apps and the official CLI source routes OAuth sessions to its CLI proxy rather than the public API.

## What Changes

- Add explicit `codex_subscription` and `grok_build_subscription` AI provider profiles while preserving
  the existing `openai_compatible` API-key profile unchanged and disabled by default.
- Add bounded `ai-auth-login`, `ai-auth-status`, and `ai-auth-logout` launcher operations. OpenAI login
  uses the official dynamic-client authorization-code/PKCE flow with a `127.0.0.1` callback; status and
  logout use the protected SmartPlanner record. A Codex CLI device-login import remains unavailable until
  a live capability probe proves its grant works on the official endpoint. The xAI operation remains at
  its explicit client-registration/terms gate.
- Keep renewable credentials out of planner YAML, normal process output, logs, evidence, and the four
  existing planner state stores. Require owner-only directories/files, atomic replacement, strict
  symlink/ownership checks, and one refresh owner per profile.
- Add provider-specific bounded suggestion gateways that use the authenticated subscription session
  without exposing credentials to model context or coding-agent tools, preserve the existing strict
  suggestion schemas and validation, and retain zero direct mutation authority.
- Gate each provider behind a versioned live compatibility probe proving its documented login flow,
  subscription entitlement, structured-output protocol, refresh behavior, and provider terms before it
  can be enabled. Unsupported or changed vendor protocol fails closed and does not fall back to an API
  key or another account.
- Document official OpenAI SIWC browser login and protected self-hosted transfer, the fail-closed Codex
  CLI-device and Grok gates, dedicated credential locations, status/logout commands, revocation,
  rollback, and the distinction between subscription entitlement and API-key billing.

## Capabilities

### New Capabilities

- `ai-subscription-authentication`: OpenAI SIWC login plus protected credential lifecycle, status,
  logout, migration, redaction, headless transfer behavior, and explicit fail-closed provider gates.
- `ai-subscription-gateway`: Fail-closed subscription-backed suggestion execution that preserves
  SmartPlanner's bounded context, strict schemas, confirmation gates, and no-mutation AI authority.

### Modified Capabilities

- None.

## Impact

- Affected code includes launcher operation parsing, AI configuration, gateway composition, credential
  storage, bounded OAuth/HTTP execution, provider adapters, daemon startup/runtime classification, and AI
  tests/documentation.
- Vendor CLIs are optional compatibility-probe inputs, never inference subprocesses. API-key
  installations and legacy sync remain unaffected.
- Nimbus OAuth 2.0 SDK with OpenID Connect SDK validates signed OIDC identity for the documented OpenAI
  public-client flow. SmartPlanner does not impersonate the Grok Build registration.
- Subscription protocols and entitlements are external compatibility risks. No profile may be described
  as supported until its hermetic contract tests and disposable live probe pass for the pinned adapter
  revision.
