# AI subscription authentication

SmartPlanner has two opt-in subscription transports:

- `codex_subscription`: OpenAI Sign in with ChatGPT (SIWC), browser PKCE, and the public Responses API.
- `grok_build_subscription`: a Hermes-style xAI OAuth session obtained directly with RFC 8628 and used
  at `api.x.ai/v1/responses`. Despite the configuration name, this does not import Grok Build CLI state
  or call its `cli-chat-proxy.grok.com` service.

Both are disabled by default and still require owner-run disposable live validation. Merging or
upgrading creates no auth directory, performs no login/network request, and changes neither existing
`openai_compatible` API-key behavior nor legacy sync.

## Common setup

Choose a service-owned path outside the repository and planner state:

```bash
sudo install -d -m 0700 -o SMARTPLANNER_USER -g SMARTPLANNER_GROUP /var/lib/smartplanner/ai-auth
```

Configure no token, API key, email, client secret, or copied vendor auth file in YAML. For either
subscription profile set:

```yaml
planner:
  ai:
    enabled: false
    provider: codex_subscription # or grok_build_subscription
    model: OWNER_VALIDATED_MODEL
    subscription:
      auth_root: /var/lib/smartplanner/ai-auth
      experimental_protocol_acknowledged: true
      login_timeout: PT5M
      codex:
        allowed_hosts: [api.openai.com]
      grok:
        allowed_hosts: [api.x.ai]
```

Leave `enabled: false` through login and status checks. The configured service user must exclusively own
the auth root. Personal `~/.codex/auth.json`, `~/.grok/auth.json`, and Hermes auth files are never read,
copied, or shared. No Codex, Grok, or Hermes CLI is needed by either implemented direct login flow.

## OpenAI SIWC matrix

**Owner authorization request:** authorize one disposable eligible ChatGPT account/workspace for OpenAI
SIWC browser login, model-list/status, bounded tool-free Responses requests for all four SmartPlanner
schemas, one forced refresh/restart check, revocation/logout, and cleanup. Permit only secret-free outcome
evidence. This does not authorize testing personal Codex caches or coding-agent inference.

Requirements: interactive access to the disposable ChatGPT account, a system browser, and a loopback
callback reaching the machine running the launcher. No API key, client secret, installed Codex CLI,
environment variable, pasted token, or copied Codex auth file is required.

```bash
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-login --ai-provider codex --auth-flow browser
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-status --ai-provider codex --json
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-status --ai-provider codex --remote --json
```

The launcher uses `dynamic_agent_client`, a stable host ID, fresh state/nonce/PKCE, exact
`127.0.0.1` callback, scopes `openid profile email offline_access resource.invoke
chatgpt.tokens.use.direct`, and resource `https://api.openai.com/v1`. It validates the issued client ID,
signed ID token, identity binding, expiry, and granted scopes before replacing the protected record.

Codex CLI device import is **not implemented or proven**. The default device command exits 3. Do not
install Codex or copy `~/.codex/auth.json` to work around this.

For a self-hosted VM, either complete browser login while running the launcher on the desktop host that
will own the credential, or follow OpenAI's official protected-transfer procedure: prepare and preserve
the VM's stable host ID, complete OAuth locally with the same app/client and that host identity, transfer
only the protected SmartPlanner credential record over SSH, set directories/files to 0700/0600 and the
service owner, and make the VM the sole refresh owner. Never leave two active copies racing a rotating
refresh token. The transfer contents are secrets and must not appear in commands, tickets, or evidence.

## xAI OAuth matrix

**Owner authorization request:** authorize one disposable eligible SuperGrok/Premium+ account for the
direct Hermes-style RFC 8628 device grant, model-list/status, bounded tool-free
`api.x.ai/v1/responses` requests for all four schemas, refresh/restart, local logout, and cleanup. The
owner has confirmed third-party subscription use is allowed; the remaining gate is live technical
entitlement and protocol validation, not separate terms permission.

Requirements: interactive access to the disposable xAI account and any browser capable of opening the
printed verification URL and entering its one-time code. The browser may be on another machine. No API
key, installed Grok Build/Hermes CLI, environment variable, copied auth file, loopback callback, or
`cli-chat-proxy.grok.com` access is required.

```bash
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-login --ai-provider grok --auth-flow device
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-status --ai-provider grok --json
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-status --ai-provider grok --remote --json
```

SmartPlanner requests the source-visible public client and scopes `openid profile email offline_access
grok-cli:access api:access`, owns the resulting rotating grant, and pins auth traffic to `auth.x.ai` and
inference to `api.x.ai`. Explicit `--auth-flow browser` exits 3 because xAI uses device authorization.
An HTTP 401/403/usage error after successful login is a live compatibility or account-tier result; the
launcher does not switch to Grok Build's proxy or an `XAI_API_KEY`.

## Preview, rollout, rollback, and cleanup

After local and remote status succeed, keep Slack/daemon disabled and set `enabled: true` for exactly one
provider. Run a fresh deterministic `preview`, then one `ai-suggest` per allowed schema with synthetic
disposable data. Verify strict accepted output, no Todoist/calendar writes, and metadata-only audits.
Only then restart the daemon and verify startup auth, one suggestion, refresh across restart,
reauthentication state, and cancellation. Neither provider is live-proven until this owner matrix passes.

Rollback is configuration-first: stop the daemon, set `planner.ai.enabled: false`, and restore
`openai_compatible` only when its separately billed API-key path is intentionally desired. Then remove
the dedicated credential:

```bash
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-logout --ai-provider codex --json
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-logout --ai-provider grok --json
```

OpenAI logout discovers and attempts revocation before local removal. xAI has no pinned revocation
endpoint in this adapter, so logout removes the local record; revoke the disposable session in account
settings when available. Confirm the provider credential file is gone, preserve unrelated provider and
planner state, and delete the empty auth root only when no profile uses it.
