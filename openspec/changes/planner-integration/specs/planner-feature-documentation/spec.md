## ADDED Requirements

### Requirement: README is the documentation entry point
The README SHALL describe the integrated planner, supported safety modes and operations, preserve the legacy-sync path, and link to the quick start, full planner configuration reference, end-to-end testing guide, and Slack, LLM, and Weather feature guides using valid relative links.

#### Scenario: New operator navigates from README
- **WHEN** an operator reads the planner section of the README
- **THEN** the operator SHALL be able to identify the safe default, supported rollout progression, principal commands, configuration template, and each detailed guide without searching the repository

### Requirement: Configuration reference is complete and example-driven
The documentation and annotated example YAML SHALL cover every supported planner key, type, default, required/optional status, allowed values, precedence, validation behavior, credential environment-variable reference, state path, and interaction among modes and features. Safe features SHALL be disabled by default.

#### Scenario: Operator configures production preview
- **WHEN** an operator follows the configuration reference and example
- **THEN** they SHALL be able to produce a valid `preview` configuration with explicit Todoist/CalDAV endpoints, least-privilege credential references, all state paths, calendar classification, task policy, and disabled optional integrations without embedding secrets

#### Scenario: Invalid or unsafe configuration is documented
- **WHEN** a value is absent, conflicts with another key, contains a raw secret, or selects an unsupported mode/provider behavior
- **THEN** the reference SHALL state the expected fail-closed validation result and remediation

### Requirement: Slack feature guide is operationally complete
The Slack guide SHALL cover disabled-by-default behavior, webhook and chat API setup, minimal permissions, destination semantics, secret environment variables, message kinds and schedules, delivery idempotency/unknown outcomes, actor allowlisting, feedback/decision separation, test procedure, troubleshooting, and rollback/disable steps.

#### Scenario: Slack is enabled safely
- **WHEN** an operator follows the Slack guide
- **THEN** they SHALL be able to send a test message to an isolated destination, verify the durable receipt, test unauthorized and authorized feedback without implicit apply, and disable the integration without affecting planning

### Requirement: LLM feature guide is operationally complete
The LLM guide SHALL describe the provider-neutral and OpenAI-compatible configuration, allowed host and HTTPS controls, model/endpoint/secret reference, request/response/time/token/item/string bounds, redaction, strict schemas, suggestion types, confirmation boundaries, audit metadata, test procedure, troubleshooting, and disable steps. It SHALL state that LLM output never directly mutates plans or providers.

#### Scenario: LLM suggestion is tested safely
- **WHEN** an operator enables AI for an isolated test and invokes an explicit suggestion operation
- **THEN** they SHALL verify bounded redacted context, validated structured output, audit information, no automatic confirmation, and zero Todoist/calendar/Slack mutations

### Requirement: Weather feature guide is operationally complete
The Weather guide SHALL cover Open-Meteo endpoint/location/timezone/horizon/max-age/body/timeout configuration, task suitability rules, units, stale/missing/malformed forecast handling, fail-open versus fail-closed trade-offs, deterministic scheduling effect, fixture/live test procedure, troubleshooting, and disable steps.

#### Scenario: Weather behavior is verified
- **WHEN** an operator tests clear, unsuitable, stale, and unavailable forecast cases
- **THEN** the guide SHALL enable them to verify expected scheduling explanations and fallback behavior without causing remote writes

### Requirement: End-to-end commands and examples are reproducible
Every command SHALL use the actual Gradle task, installed launcher, main class, option name, and configuration path implemented by the repository. Examples SHALL use placeholders or fake values and SHALL not contain personal or production secrets.

#### Scenario: Documentation verification is run
- **WHEN** implementation is complete
- **THEN** maintainers SHALL verify relative links, copy/paste command syntax, CLI help alignment, example-config parsing, and absence of secrets or stale Phase-only limitations
