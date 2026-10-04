# AI subscription authentication

`codex_subscription` uses OpenAI's official Sign in with ChatGPT flow and public Responses endpoint.
`grok_build_subscription` remains fail-closed at the xAI client-registration/public-endpoint gate. Both
are disabled by default. Existing `openai_compatible` API-key and legacy sync behavior is unchanged.

Configure a canonical private auth root and the exact OpenAI host, then authenticate on a machine where
the browser can return to `127.0.0.1`:

```bash
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-login --ai-provider codex --auth-flow browser
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-status --ai-provider codex --json
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-status --ai-provider codex --remote --json
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-logout --ai-provider codex --json
```

Browser login dynamically registers the app, validates state, nonce, PKCE, the callback-issued client
ID, signed ID token, issuer, audience, expiry, and granted plan scopes before replacing the credential.
Local status performs no network request or refresh. Remote status may refresh once under the profile
lock and makes one read-only model-catalog request. Logout obtains the revocation endpoint from pinned
OpenAI discovery, attempts revocation, and always removes the local record.

Codex device login currently exits 3 with the exact CLI-token capability gate; it never reads personal
`~/.codex/auth.json`. For a self-hosted VM, follow OpenAI's official procedure: establish the VM's own
stable host ID, complete OAuth locally with this app, securely transfer the protected credential record,
preserve the VM host ID, and let the VM become sole refresh owner. Do not keep two active copies racing
the rotating refresh token.

Grok login/status/logout exit 3 without contacting xAI. See the compatibility manifest for the exact
registration and endpoint evidence required to unlock the hermetically tested RFC 8628 implementation.

The auth root is password-equivalent data. Keep it outside planner state and backups by default, on an
encrypted local disk, owned by the service account, with 0700 directories and 0600 files. Never commit,
log, screenshot, or include its access, refresh, or ID tokens in support/evidence. Recovery is disable AI,
logout/revoke the dedicated profile, and reauthorize. There is no automatic paid-API fallback.
