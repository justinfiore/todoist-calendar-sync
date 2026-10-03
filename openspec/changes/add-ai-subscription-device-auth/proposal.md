# Proposal

## Why

SmartPlanner's AI integration currently requires separately billed API keys, so an operator cannot use
the Codex or Grok Build access already included with a consumer or workspace subscription. Both official
vendor CLIs now support headless device login, making a subscription-backed option possible without
embedding browser automation or asking operators to paste renewable credentials.

## What Changes

- Add explicit `codex_subscription` and `grok_build_subscription` AI provider profiles while preserving
  the existing `openai_compatible` API-key profile unchanged and disabled by default.
- Add bounded `ai-auth-login`, `ai-auth-status`, and `ai-auth-logout` launcher operations. Login delegates
  device authorization to the installed official `codex` or `grok` executable using a dedicated
  SmartPlanner-owned vendor home; status reports binary, local credential, expiry, and optional remote
  entitlement health without exposing identity or tokens; logout affects only that dedicated profile.
- Keep renewable credentials out of planner YAML, normal process output, logs, evidence, and the four
  existing planner state stores. Require owner-only directories/files, atomic replacement, strict
  symlink/ownership checks, and one refresh owner per profile.
- Add provider-specific bounded suggestion gateways that use the authenticated subscription session
  without exposing credentials to model context or coding-agent tools, preserve the existing strict
  suggestion schemas and validation, and retain zero direct mutation authority.
- Gate each provider behind a versioned live compatibility probe proving its documented CLI login flow,
  subscription entitlement, structured-output protocol, refresh behavior, and provider terms before it
  can be enabled. Unsupported or changed vendor protocol fails closed and does not fall back to an API
  key or another account.
- Document official CLI installation, `codex login --device-auth` / `grok login --device-auth`, dedicated
  credential locations, status/logout commands, headless operation, migration from an existing CLI login,
  revocation, rollback, and the distinction between subscription and public API billing.

## Capabilities

### New Capabilities

- `ai-subscription-authentication`: Dedicated vendor-CLI device login, credential lifecycle, status,
  logout, migration, redaction, and headless operator behavior for Codex and Grok Build subscriptions.
- `ai-subscription-gateway`: Fail-closed subscription-backed suggestion execution that preserves
  SmartPlanner's bounded context, strict schemas, confirmation gates, and no-mutation AI authority.

### Modified Capabilities

- None.

## Impact

- Affected code includes launcher operation parsing, AI configuration, gateway composition, credential
  storage, bounded process execution, provider adapters, daemon startup/runtime classification, and AI
  tests/documentation.
- The official Codex or Grok Build executable becomes an optional operator dependency for login and
  compatibility checks only for its corresponding subscription profile. API-key installations and
  legacy sync remain unaffected.
- Nimbus OAuth 2.0 SDK with OpenID Connect SDK is the preferred Java protocol library if a documented
  native RFC 8628/PKCE/refresh path is needed; the default login path delegates to official CLIs so the
  application does not impersonate vendor client registrations or own undocumented consent UX.
- Subscription protocols and entitlements are external compatibility risks. No profile may be described
  as supported until its hermetic contract tests and disposable live probe pass at pinned vendor CLI
  versions.
