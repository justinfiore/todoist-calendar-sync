# Spec Delta

## Purpose

Give operators actionable AI failure outcomes without exposing private context or weakening suggestion validation and mutation boundaries.

## ADDED Requirements

### Requirement: Rejections expose stable safe reasons
Rejected AI results SHALL expose an application-defined bounded reason code alongside the existing error class. Semantic validation SHALL distinguish identity, range, expiry, duplicate-target, unsafe-content, and invalid-value failures without returning offending values or accepting a partial bundle.

#### Scenario: Suggestion references an unknown task
- **WHEN** a schema-valid response refers to a task outside the request context
- **THEN** the complete result SHALL be rejected with an unknown-task reason and no offending task identity or model text

#### Scenario: Another semantic constraint fails
- **WHEN** an expiry, range, duplicate target, or plan binding fails
- **THEN** the reason SHALL identify that constraint rather than a generic semantic rejection

### Requirement: Known transport failures retain their classification
Known response-bound and timeout failures SHALL be classified separately from unknown transport errors. Available HTTP status and measured byte counts SHALL be retained as metadata; unobserved values SHALL remain absent or explicitly unknown, not fabricated as zero.

#### Scenario: Stream exceeds its configured byte bound
- **WHEN** subscription streaming exceeds the configured response limit
- **THEN** the result SHALL report RESPONSE_TOO_LARGE with a bounded reason, without a raw stream, accepted suggestion, automatic retry, or fallback

#### Scenario: Timeout precedes a complete response
- **WHEN** the HTTP transport reports a recognized timeout
- **THEN** the result SHALL report TIMEOUT rather than generic TRANSPORT

### Requirement: Error and audit surfaces preserve safe metadata
Safe failure reasons and available transport metadata SHALL survive gateway, service, audit, and CLI boundaries. Raw prompts, responses, exception messages, credentials, and offending field values SHALL NOT be serialized into those diagnostic surfaces.

#### Scenario: Sensitive material appears in invalid output
- **WHEN** rejected output or a provider exception contains a synthetic credential or identity canary
- **THEN** observable diagnostics SHALL contain only allowlisted metadata and SHALL exclude the canary

### Requirement: One-shot suggestion exit status reflects acceptance
The ai-suggest command SHALL retain structured JSON output, return exit zero for an accepted result, and return exit one for a rejected result. Existing invalid-command/configuration exit conventions SHALL remain unchanged, and the new outcome convention SHALL be documented.

#### Scenario: Provider request completes but validation rejects
- **WHEN** the provider returns HTTP 200 but the suggestion is rejected
- **THEN** command JSON SHALL report accepted false with its safe reason and the process SHALL exit one

#### Scenario: Valid empty suggestions are accepted
- **WHEN** an otherwise valid result contains an allowed empty suggestion list
- **THEN** command JSON SHALL report accepted true and the process SHALL exit zero without claiming useful non-empty suggestions
