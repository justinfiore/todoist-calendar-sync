# Design

## Context

See `proposal.md` for motivation. SmartPlanner currently has one optional `openai_compatible` path:
`PlannerConfig` validates an environment-variable name, `ProductionPlannerOrchestrator` constructs
`OpenAiCompatibleLlmGateway`, and the gateway sends a bearer API key through JDK `HttpClient`. Strict
context construction, output schemas, semantic validation, confirmation, and audit already sit outside
that transport and must remain the authority boundary.

Official clients expose the required login UX but not one shared stable integration contract:

- Codex provides `codex login`, `codex login --device-auth`, `codex login status`, and `codex logout`.
  Its default cache is `$CODEX_HOME/auth.json` (`~/.codex/auth.json` by default). Device authorization
  includes vendor-specific steps in addition to OAuth authorization-code/PKCE.
- Grok Build provides `grok login`, `grok login --device-auth`/`--device-code`, and `grok logout`. Its
  default cache is `$GROK_HOME/auth.json` (`~/.grok/auth.json` by default). Device authorization follows
  RFC 8628 semantics, but the CLI does not currently expose a dedicated status command.
- Both coding CLIs can produce structured output, but both are agents with local tools. Neither currently
  provides a sufficiently stable, provable all-tools-off boundary for use as an inference subprocess.
- Subscription traffic is not the same as separately billed public API traffic. Codex subscription
  sessions and Grok Build's CLI proxy require provider-specific account binding and protocol behavior.
  Those protocols may change independently of this application.

Credential files are password-equivalent and may contain rotating refresh tokens. Merely pointing
SmartPlanner at a personal CLI home would create concurrent refresh races and make logout ownership
ambiguous. The repository already has examples of private token storage, atomic replacement, file locks,
redaction, bounded JDK HTTP, strict JSON, WireMock, and installed-launcher testing to reuse.

## Goals / Non-Goals

**Goals:**

- Give a headless host an operator-friendly official device-login path and useful secret-free status.
- Establish one owner for renewable credentials and one bounded, tool-free inference boundary.
- Make vendor protocol drift observable, testable, reversible, and isolated by provider.
- Preserve existing API-key behavior and every existing AI validation/confirmation boundary.

**Non-Goals:**

- Automating a user's browser, collecting username/password/MFA, or implementing vendor consent UI.
- Treating a ChatGPT, Codex, xAI, or Grok subscription as permission to use a separately billed API.
- Supporting arbitrary OAuth issuers, arbitrary vendor CLI versions, or arbitrary credential JSON.
- Sharing a personal `~/.codex` or `~/.grok` cache, scraping an OS keyring, or copying credentials
  between concurrently active installations.
- Giving a coding-agent process access to the repository, host tools, shell, network destinations, or
  planner/provider mutation interfaces.

## Decisions

### 1. Add provider profiles without changing the existing API-key profile

`PlannerAiConfig.provider` gains `codex_subscription` and `grok_build_subscription`; `none`, `fixture`,
and `openai_compatible` retain their existing meanings. Subscription-only configuration is nested under
`planner.ai.subscription` and contains no secrets:

- `auth_root`: canonical directory outside the plan/application/decision/delivery stores;
- provider CLI executable paths and supported version constraints;
- `experimental_protocol_acknowledged`;
- bounded login, refresh, connect, request, response-size, and shutdown limits;
- provider model selection and explicitly allowlisted hosts.

`secret_env` remains required only for enabled `openai_compatible`. A subscription provider requires a
safe auth root, a ready provider record, a compatible adapter, and explicit protocol acknowledgment.
Authentication commands can inspect an unready profile, but normal planner startup fails before daemon
scheduling or remote planning work.

**Alternative considered:** overload `secret_env` with a token-file path. Rejected because it obscures
refresh ownership, weakens file validation, and cannot represent login/status/logout lifecycle.

### 2. Add authentication operations to the existing installed launcher

Extend the current operation parser with `ai-auth-login`, `ai-auth-status`, and `ai-auth-logout`, plus
`--ai-provider codex|grok`, `--auth-flow device|browser`, `--remote`, and `--json` where applicable.
Device is the documented/default flow for headless machines. These operations load and validate only the
configuration slice required for authentication and exit before constructing sync, planner, Todoist,
calendar, Slack, or weather services.

Provider command adapters use `ProcessBuilder` with an absolute executable and fixed argument arrays:

| Provider | device login | browser login | version/status source |
|---|---|---|---|
| Codex | `codex login --device-auth` | `codex login` | `codex --version`; local store plus `codex login status` only in the isolated temporary home |
| Grok Build | `grok login --device-auth` | `grok login` | `grok --version`; normalized local store plus a bounded optional entitlement probe |

No command is assembled by a shell. The adapter clears inherited environment variables and adds only
required locale/terminal values and a temporary `CODEX_HOME` or `GROK_HOME`. Standard input remains
interactive; stdout/stderr are bounded and filtered to the verification URI, one-time code, progress,
and safe error classes rather than relayed or retained verbatim. Child and descendants are terminated on
timeout/cancellation. Tests receive a fake `VendorCliRunner` instead of launching real executables.

**Alternative considered:** implement both device flows directly with Nimbus. Rejected as the default
because Codex's device bootstrap/client registration is vendor-owned and Grok's supported UX resides in
its CLI. Nimbus is used only for standard token parsing/refresh/revocation portions verified by the
provider compatibility adapter.

### 3. Transfer, do not share, credential ownership after official login

Each login runs in a newly created owner-only temporary vendor home beneath `auth_root/staging`. After
successful exit, a provider importer validates the expected regular file, byte limit, permissions,
schema, issuer/provider, expiry, and required account/workspace binding. It writes a normalized
SmartPlanner record beneath `auth_root/providers/<provider>/credential.json` and deletes the staging
home. It never reads the user's default vendor home.

The normalized record is versioned and contains only fields needed by that provider adapter: access and
refresh tokens, expiry, granted scope, provider-required account/workspace binding, protocol generation,
and credential generation. It is written through `CREATE_NEW` temporary files, fsync, atomic move, and
directory fsync where supported. Directories are 0700 and files 0600 on POSIX; equivalent current-user
ACL checks apply elsewhere. Symlinks, unexpected owner/group/world access, hard-link anomalies where
detectable, non-local/unsupported permission semantics, and path escape fail closed.

A profile lock covers read-refresh-replace and logout. Refresh rereads after lock acquisition and writes
the provider's rotated refresh token before returning an access token. The provider adapter, not the
vendor CLI, is the only refresh owner after import. A process never refreshes a shared personal cache.

Existing personal logins are migrated by rerunning `ai-auth-login` into the dedicated store. Initial
support does not offer a raw copy/import command: copying a renewable cache while the source remains in
use can invalidate either installation through refresh-token rotation. Documentation may explain the
vendor cache locations for backup/removal diagnosis but instructs users not to copy those files.

**Alternatives considered:** point at the normal CLI home (rejected due to races and broad collateral
logout); copy `auth.json` manually (rejected due to schema drift and dual refresh owners); leave the CLI
as refresh owner (rejected because neither CLI offers one stable noninteractive refresh-only contract).

### 4. Use a provider-neutral credential service with versioned provider adapters

Introduce an `AiSubscriptionCredentialService` around `SubscriptionCredentialStore`,
`SubscriptionProviderAdapter`, and a clock. The common service owns storage, locking, early-refresh,
safe status, and failure classification. Provider adapters own only externally variable details:

- CLI executable/version and temporary credential parser;
- issuer/client/protocol metadata and refresh/revocation messages;
- provider-required account binding header/claim;
- entitlement check and subscription inference serialization;
- exact supported hosts and compatibility revision.

Use Nimbus OAuth 2.0 SDK with OpenID Connect SDK (planned baseline `11.38.2`, rechecked at implementation)
for typed standard token, PKCE, refresh, revocation, OIDC, and OAuth error handling where the adapter's
live spike confirms the contract. Polling, cancellation, persistence, output filtering, and filesystem
safety remain application responsibilities. Do not use Nimbus to invent or impersonate a vendor client
registration.

`ai-auth-status` is local-only by default and never refreshes. `--remote` explicitly permits locked
refresh and one read-only entitlement request. Human and JSON forms expose enums/timestamps/version data,
not email, subject, account/workspace ID, token claims, raw provider body, or credential path. Logout
attempts bounded revocation when supported, then removes the local normalized record regardless; its
result distinguishes `revoked_and_removed`, `local_removed_revocation_unavailable`, and safe failures.

### 5. Perform inference through bounded tool-free HTTP adapters, not coding-agent execution

Add `CodexSubscriptionLlmGateway` and `GrokBuildSubscriptionLlmGateway` behind the existing `LlmGateway`
interface. They reuse `LlmContextBuilder`, existing request identity/hash handling, `LlmSchemaValidator`,
and `AiAssistanceService`; only authentication and wire serialization differ. The transport uses JDK
`HttpClient`, redirects disabled, exact HTTPS hosts, DNS/host checks consistent with the current gateway,
bounded request/response sizes, and explicit timeouts.

Before production code is accepted, two disposable compatibility spikes must prove the exact current
provider endpoints, account binding, model discovery/selection, refresh rotation, subscription billing
surface, request shape, strict structured output, and `tools: []`/equivalent semantics. The discovered
contract becomes an explicit provider adapter revision with golden fixtures and WireMock tests. No raw
live payload or credential is committed. If a provider cannot prove direct tool-free structured inference
under subscription entitlement, that profile remains unsupported; the implementation does not substitute
`codex exec`, `grok -p`, ACP, prompt instructions, OS sandboxing, or public API billing.

This choice intentionally treats the official CLI as the supported login UX but not as the model
execution security boundary. It also avoids exposing the repository as a working directory or parsing an
agent event stream as if it were one model response.

**Alternatives considered:** invoke `codex exec` or `grok -p` with JSON schema (rejected because the
current tools retain coding-agent capabilities and ambient authority); use an external broker (viable
future fallback, but adds another service and credential boundary); call public OpenAI/xAI APIs with the
subscription token (rejected because subscription and API products are not interchangeable).

### 6. Treat provider compatibility as a release gate

Each adapter declares a compatibility revision, tested CLI version range, credential schema revision,
issuer/hosts, and live-probe date. Startup checks the selected provider only. Unknown CLI versions block
new login but do not erase a valid credential; unknown stored/protocol versions block inference and tell
the operator to upgrade/re-authenticate. No adapter auto-downgrades, guesses fields, or falls back.

Hermetic tests use fake process runners, a temporary filesystem, injected clocks/movers, and WireMock.
They cover device approval/denial/expiry/timeout, output filtering, process-tree cancellation, hostile
files, atomic failures, refresh rotation/races, status/logout, redirects/host changes, entitlement,
strict schemas, tool events, malformed/oversized output, daemon cancellation, and redaction. Existing
`openai_compatible` and full suites must remain green.

Live QA is provider-specific and authorization-gated. It uses the installed distribution and disposable
Codex and Grok subscription accounts to exercise device and browser login, local/remote status, forced
refresh, every allowed suggestion contract, daemon restart, revoked/expired credentials, logout, and
cleanup. A failed provider is documented as unsupported even if the other passes. Evidence stores only
versions, timestamps, safe status classes, hashes, and pass/fail results after secret/identity scanning.

## Risks / Trade-offs

- **[Private provider protocols can change without notice]** → Isolate them behind revisioned adapters,
  require explicit acknowledgment and live gates, pin tested CLI ranges, and fail closed per provider.
- **[Vendor terms may prohibit or narrow third-party subscription use]** → Verify official terms and
  actual entitlement during the spike; do not ship/document a provider until acceptable. Never route to
  separately billed API endpoints implicitly.
- **[Credential importer depends on CLI file shapes]** → Parse exact bounded schema only, test pinned
  fixtures, stage login separately, and preserve the prior credential on every import failure.
- **[A refresh token can rotate during crash]** → One file lock, reread-under-lock, atomic generation
  replacement, persist before request, and classify ambiguous transport failure as reauthentication
  rather than replaying a potentially consumed token.
- **[Filtered CLI output may break when wording changes]** → Match versioned structured/proven patterns,
  cap all streams, and classify unknown output without printing it. The live login gate tests this.
- **[Dedicated login duplicates a user's consent/session]** → This is deliberate to achieve singular
  ownership and safe logout; documentation explains why existing personal caches are not reused.
- **[Direct subscription inference may not expose a stable tool-free contract]** → Keep that provider
  disabled. Do not lower the existing AI authority boundary to make subscription reuse work.
- **[Local plaintext refresh storage remains sensitive]** → Minimize fields, enforce OS permissions,
  keep it outside evidence/backups by default, never log it, and document host-disk security. OS keyring
  support can be added later without changing the credential service contract.

## Migration Plan

1. Land configuration/CLI/storage abstractions with both subscription providers disabled; run all legacy
   and `openai_compatible` regression suites.
2. Complete provider compatibility spikes and terms review. Implement and mark supported only adapters
   whose complete tool-free login/refresh/inference/logout contract passes.
3. Add hermetic contract matrices, installed-launcher tests, documentation, and ignored private QA
   scaffolding. Keep sample configuration disabled and free of credentials.
4. Run authorization-gated disposable live matrices and commit secret-free evidence. Enable each profile
   in documentation only after its own pass.
5. Operators upgrade with no behavior change. To opt in, they configure one subscription profile, run
   `ai-auth-login --auth-flow device`, inspect local then remote status, enable AI in preview, and later
   run the daemon under existing planner safeguards.

Rollback is configuration-first: disable AI or restore `openai_compatible`; subscription code then has
no execution path. Run `ai-auth-logout` to revoke/remove the dedicated record, remove the provider
configuration, and uninstall the optional vendor CLI. Existing plan/application/decision/delivery stores
and legacy sync state require no migration or rollback.

## Open Questions

- Exact minimum/maximum official CLI versions and provider adapter revision values are outputs of the
  required compatibility spikes; choosing them does not alter this architecture or its requirements.
- Whether a supported provider exposes revocation is recorded per adapter. Local removal remains the
  deterministic logout behavior when remote revocation is unavailable.
