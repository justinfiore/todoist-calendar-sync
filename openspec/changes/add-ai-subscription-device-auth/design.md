# Design

## Context

See `proposal.md` for motivation. SmartPlanner currently has one optional `openai_compatible` path:
`PlannerConfig` validates an environment-variable name, `ProductionPlannerOrchestrator` constructs
`OpenAiCompatibleLlmGateway`, and the gateway sends a bearer API key through JDK `HttpClient`. Strict
context construction, output schemas, semantic validation, confirmation, and audit already sit outside
that transport and must remain the authority boundary.

The providers do not expose one shared integration contract:

- OpenAI SIWC now documents dynamic public-client registration, authorization-code/PKCE through
  `https://auth.openai.com/api/accounts/authorize`, rotating refresh, discovery-based revocation,
  protected self-hosted credential transfer, and OAuth Bearer inference at the public
  `https://api.openai.com/v1/responses` endpoint. The HTTP request may omit tools entirely.
- Grok Build provides `grok login`, `grok login --device-auth`/`--device-code`, and `grok logout`. Its
  default cache is `$GROK_HOME/auth.json` (`~/.grok/auth.json` by default). Device authorization follows
  RFC 8628 semantics, but official source binds its public client and `grok-build` referrer to the CLI,
  routes OAuth inference to `cli-chat-proxy.grok.com`, and reserves `api.x.ai` for API-key routing.
- Both coding CLIs can produce structured output, but both are agents with local tools. Neither currently
  provides a sufficiently stable, provable all-tools-off boundary for use as an inference subprocess.
- OpenAI explicitly authorizes eligible ChatGPT-plan use on its public Responses API through SIWC; this
  is distinct from API-key billing. xAI has not made the equivalent third-party registration/entitlement
  authorization, so the Grok profile remains independently fail-closed.

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
Device remains the launcher default for headless machines, but Codex device import and every Grok flow
currently report their explicit compatibility gates. OpenAI browser login is the implemented local login
path; a protected record may then be transferred using the official self-hosted procedure. These
operations load and validate only the configuration slice required for authentication and exit before
constructing sync, planner, Todoist, calendar, Slack, or weather services.

OpenAI browser login is implemented directly from the official SIWC contract: generate/reuse the host
ID, start the exact `127.0.0.1` callback, generate fresh state/nonce/PKCE, register with
`dynamic_agent_client`, exchange using the callback-issued client ID, validate ID-token signature,
issuer/audience/expiry/nonce against pinned JWKS, validate granted scopes, and atomically store the
record. Codex device login/import fails closed until a live probe proves CLI-issued scopes work on this
public endpoint. Official secure credential transfer is the headless/self-hosted path before then.

The xAI RFC 8628 implementation is hermetically tested for pending, slow-down, denial, expiry, endpoint
pinning, and normalization, but is unreachable from the launcher until xAI authorizes third-party use of
the registration and a live direct Responses probe passes. No CLI is used for inference.

### 3. Transfer, do not share, credential ownership after official login

Each successful direct login writes a normalized SmartPlanner record beneath
`auth_root/providers/<provider>/credential.json`. A future capability-proven CLI import must run in a
new owner-only temporary vendor home and transfer—not share—its validated credential. Personal default
vendor homes are never read.

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

Use Nimbus OAuth 2.0 SDK with OpenID Connect SDK `11.38.2` (Apache-2.0) for signed JWT/JWK processing.
JDK HTTP implements bounded form exchange, discovery, model probing, refresh, revocation, and Responses
SSE. Polling, persistence, and filesystem safety remain application responsibilities. Do not use Nimbus
or source-visible constants to invent or impersonate a vendor client registration.

`ai-auth-status` is local-only by default and never refreshes. `--remote` explicitly permits locked
refresh and one read-only entitlement request. Human and JSON forms expose enums/timestamps/version data,
not email, subject, account/workspace ID, token claims, raw provider body, or credential path. Logout
attempts bounded revocation when supported, then removes the local normalized record regardless; its
result distinguishes `revoked_and_removed`, `local_removed_revocation_unavailable`, and safe failures.

### 5. Perform inference through bounded tool-free HTTP adapters, not coding-agent execution

Add a provider-bounded `SubscriptionResponsesLlmGateway` behind the existing `LlmGateway` interface.
It reuses `LlmContextBuilder`, existing request identity/hash handling, `LlmSchemaValidator`, and
`AiAssistanceService`; only authentication and wire serialization differ. The transport uses JDK
`HttpClient`, redirects disabled, exact HTTPS hosts, DNS/host checks consistent with the current gateway,
bounded request/response sizes, and explicit timeouts.

OpenAI revision `openai-siwc-2026-10-03-v1` pins the officially documented authorize/token/resource,
model-list, Responses, and discovery hosts. Requests set `store:false`, `stream:true`, carry complete
history in `input`, omit tools and unsupported preview fields, and succeed only on `response.completed`.
The xAI implementation remains hermetic revision `xai-device-responses-hermetic-v1`, not a supported
adapter revision. Disposable live probes must still prove actual entitlement and compatibility. No raw
live payload or credential is committed. If a provider cannot prove direct tool-free structured inference
under an authorized subscription grant, that profile remains unsupported; the implementation does not substitute
`codex exec`, `grok -p`, ACP, prompt instructions, OS sandboxing, or public API billing.

This choice uses OpenAI's documented SIWC browser flow as the supported login UX and treats vendor CLIs
only as possible future credential sources after a capability probe. It avoids exposing the repository
as a working directory or parsing an agent event stream as if it were one model response.

**Alternatives considered:** invoke `codex exec` or `grok -p` with JSON schema (rejected because the
current tools retain coding-agent capabilities and ambient authority); use an external broker (viable
future fallback, but adds another service and credential boundary); call undocumented ChatGPT backend
endpoints (rejected because OpenAI explicitly requires the public SIWC route); treat xAI CLI source as
service permission (rejected because source licensing does not authorize hosted-service/client use).

### 6. Treat provider compatibility as a release gate

Each adapter declares a compatibility revision, credential schema revision, issuer/hosts, and live-probe
status. Startup checks the selected provider only. Unknown stored/protocol versions block inference and
tell the operator to upgrade or re-authenticate. No adapter auto-downgrades, guesses fields, or falls back.

Hermetic tests use fake transports, a temporary filesystem, injected clocks, and installed launchers.
They cover SIWC callback validation, xAI RFC 8628 pending/slow-down/denial/expiry, hostile files,
atomic persistence, refresh rotation/races, status/logout, redirects/host changes, entitlement,
strict schemas, tool events, malformed/oversized output, and redaction. Existing
`openai_compatible` and full suites must remain green.

Live QA is provider-specific and authorization-gated. It uses the installed distribution and disposable
OpenAI and Grok subscription accounts to exercise authorized login paths, local/remote status, forced
refresh, every allowed suggestion contract, daemon restart, revoked/expired credentials, logout, and
cleanup. A failed provider is documented as unsupported even if the other passes. Evidence stores only
versions, timestamps, safe status classes, hashes, and pass/fail results after secret/identity scanning.

## Risks / Trade-offs

- **[Preview/provider protocols can change]** → Isolate them behind revisioned adapters,
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
- **[A provider may not authorize a direct tool-free contract]** → Keep that provider disabled. Do not
  lower the existing AI authority boundary or impersonate an official CLI to make subscription reuse work.
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
5. Operators upgrade with no behavior change. To opt in to OpenAI, they configure the subscription
   profile, run `ai-auth-login --ai-provider codex --auth-flow browser` on a local machine (then use the
   protected-record transfer procedure when self-hosting), inspect local then remote status, enable AI in
   preview, and later run the daemon under existing planner safeguards. Grok remains unavailable.

Rollback is configuration-first: disable AI or restore `openai_compatible`; subscription code then has
no execution path. Run `ai-auth-logout` to revoke/remove the dedicated record, remove the provider
configuration, and uninstall the optional vendor CLI. Existing plan/application/decision/delivery stores
and legacy sync state require no migration or rollback.

## Open Questions

- A Codex CLI version range and importer may be added only if an owner-authorized probe proves that its
  grant carries the documented SIWC public-endpoint scopes. xAI support additionally requires explicit
  third-party client-registration permission and a public-endpoint entitlement pass.
- Whether a supported provider exposes revocation is recorded per adapter. Local removal remains the
  deterministic logout behavior when remote revocation is unavailable.
