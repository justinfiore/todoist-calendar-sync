# AI subscription compatibility manifest

Checked 2026-10-03. This manifest is secret-free. No account login, credential file, consent URL,
one-time code, token, identity, or live provider payload was used or retained.

## Release decision

| Profile | Observed CLI | Authentication CLI | SmartPlanner support | Exact gate |
|---|---|---|---|---|
| `codex_subscription` | `codex-cli 0.160.0` | vendor-owned browser login, `login --device-auth`, `login status`, `logout` | **unsupported** | OpenAI documents Codex as a coding agent, but does not publish a stable direct tool-free ChatGPT-subscription inference protocol or third-party entitlement contract. |
| `grok_build_subscription` | `grok 1.0.46 (2765805b9442)` | vendor-owned browser login, `login --device-auth` (`--device-code` alias), `logout`; no auth status command | **unsupported** | xAI documents Grok Build headless/ACP surfaces as coding-agent execution; its subscription proxy and entitlement check are implementation details, not a stable direct tool-free third-party protocol. |

The profiles are independent and fail closed. Neither falls back to `OPENAI_API_KEY`, `XAI_API_KEY`,
another provider/account, `api.x.ai`, or public OpenAI API billing. `codex exec`, `grok -p`, Grok
`agent stdio`/ACP, prompt-only restrictions, and OS sandboxing are not accepted inference boundaries.

## Codex observations

- Official install sources: `npm install -g @openai/codex`, Homebrew cask, and OpenAI's standalone
  installer, documented by [OpenAI](https://developers.openai.com/codex/cli).
- The official npm registry reported `@openai/codex` 0.160.0. Isolated execution confirmed
  `codex --version`, `codex login --help`, and `codex logout --help`.
- Exact login commands observed: `codex login`, `codex login --device-auth`,
  `codex login status`, and `codex logout`.
- OpenAI documents file credentials at `$CODEX_HOME/auth.json` when file storage is selected, but
  keyring/auto/ephemeral stores are also supported. The current internal file shape includes auth mode,
  token data, refresh time, and account binding. It is not a supported interchange schema.
- ChatGPT plans include Codex usage; API-key usage is separately billed through the OpenAI Platform.
  The CLI's ChatGPT backend and account-binding headers are internal implementation, not a public
  subscription API contract.
- OpenAI's consumer terms and authentication docs prohibit sharing account credentials. SmartPlanner
  therefore never reads or shares a personal Codex cache.

Official references: [authentication](https://developers.openai.com/codex/auth),
[CLI reference](https://developers.openai.com/codex/cli/reference), and
[source](https://github.com/openai/codex).

## Grok Build observations

- Official install source: `curl -fsSL https://x.ai/cli/install.sh | bash`; the official source also
  publishes `@xai-official/grok`.
- The official npm registry and isolated execution reported 1.0.46. The isolated run confirmed
  `grok --version`, `grok login --help`, and `grok logout --help`.
- Exact login commands observed: `grok login`, `grok login --oauth`,
  `grok login --device-auth` / `--device-code`, and `grok logout`. No dedicated auth status command exists.
- The CLI documents `$GROK_HOME/auth.json`; the current internal multi-scope shape includes access and
  refresh material, expiry, principal/team/organization binding, and OIDC metadata. It is not a
  supported interchange schema.
- Grok Build subscription traffic uses a CLI proxy internally. Public `api.x.ai` usage and API credits
  are separate billing surfaces. SmartPlanner refuses public-API substitution.
- SpaceXAI consumer terms dated 2026-09-11 prohibit account-credential sharing and direct business/API
  use to separate terms. SmartPlanner therefore never reads or shares a personal Grok cache.

Official references: [overview](https://docs.x.ai/build/overview),
[CLI reference](https://docs.x.ai/build/cli/reference),
[terms](https://x.ai/legal/terms-of-service), and
[source](https://github.com/xai-org/grok-build).

## Evidence method and live gates

Help/version probes used temporary `HOME`, `CODEX_HOME`, and `GROK_HOME` directories and performed no
login. Source inspection confirmed that both direct subscription transports are private coding-agent
implementation details. No adapter revision is frozen.

Owner authorization and disposable subscription accounts are still required for device/browser login,
refresh rotation, entitlement, revocation, model selection, and cleanup QA. Even a successful live login
cannot enable a provider until the vendor publishes or explicitly approves a stable direct tool-free
structured inference contract and the complete live matrix passes.
