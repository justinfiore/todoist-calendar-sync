# AI subscription authentication

`codex_subscription` and `grok_build_subscription` are present as explicit **unsupported** profiles.
They are disabled by default and cannot be enabled for inference. See the
[compatibility manifest](AI_SUBSCRIPTION_COMPATIBILITY.md) for the exact release gate.

The installed launcher exposes the future lifecycle contract without touching credentials or starting
planner, Todoist, calendar, Slack, weather, legacy sync, or vendor processes:

```bash
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-status --ai-provider codex --json
todoist-caldav-sync -f conf/planner.yaml -l conf/log4j.groovy \
  --operation ai-auth-status --ai-provider grok --remote --json
```

Both currently exit 3 with `state: unsupported`. Login/logout commands have the same safe result; they
do not invoke a vendor binary until that provider has a frozen adapter revision.

When support is eventually proven, device will be the headless default and browser flow will require
`--auth-flow browser`. Official CLIs must run with a newly created dedicated `CODEX_HOME` or `GROK_HOME`
beneath the configured auth root. SmartPlanner must never inspect or copy personal `~/.codex/auth.json`
or `~/.grok/auth.json`.

The auth root is password-equivalent data: keep it outside planner state and backups by default, on an
encrypted local disk, owned by the service account, with 0700 directories and 0600 files. A future
supported adapter must use staging, atomic replacement, one refresh owner and one provider lock. Safe
migration is re-login into the dedicated home—not copying a renewable cache. Recovery is disable AI,
revoke/logout through the supported operation, remove only the dedicated profile, and re-login.

There is no automatic paid-API fallback. Existing `openai_compatible` configuration and behavior are
unchanged.
