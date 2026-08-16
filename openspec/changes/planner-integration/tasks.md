## 1. Preserve and revise the implementation contract

- [x] 1.1 Preserve the completed Phase 1–6 integration baseline, current review fixes, and separate planner/legacy behavior.
- [x] 1.2 Research authoritative Slack Socket Mode, Java SDK, slash-command acknowledgement, thread messaging, and `assistant.threads.setStatus` contracts.
- [x] 1.3 Validate this revised OpenSpec change and commit only proposal/design/spec/task artifacts before production code edits.

## 2. Daemon configuration and lifecycle

- [ ] 2.1 Extend production config with `planner.daemon`, unique named planning runs, positive bounded horizon/interval/initial delay, run-on-startup, retry/backoff, shutdown, concurrency/coalescing, and startup-connectivity settings.
- [ ] 2.2 Extend feedback config with ordered bounded Java regex rules, supported actions, optional predefined override templates, and fail-fast regex/action validation.
- [ ] 2.3 Extend Slack config with Socket Mode app-token/bot-token environment references, configured channel, app name defaulting to `SmartPlanner`, command name, status text/loading messages, event bounds, and enabled receive behavior; reject webhook-only daemon configuration.
- [ ] 2.4 Add configuration tests for defaults, valid multi-horizon schedules, duplicates, malformed/unsafe durations/regexes, secret references, missing bidirectional Slack settings, and actionable secret-free errors.
- [ ] 2.5 Add bounded startup Todoist/CalDAV authenticated read probes and classifications that fail before daemon readiness for configuration/auth/connectivity errors.

## 3. Long-running scheduler and resilience

- [ ] 3.1 Add `SmartPlannerDaemon` with injected clock/scheduler/executor/shutdown seams, blocking start, graceful close, and serial/coalesced work dispatch.
- [ ] 3.2 Schedule every configured horizon independently, calculate `[now, now+horizon)` in planner timezone, reuse the correct prior run plan, persist immutable plans, and publish proposals.
- [ ] 3.3 Keep one-shot operations for diagnostics while adding `planner-daemon` CLI dispatch/help and stable startup/runtime exit behavior.
- [ ] 3.4 Catch and persist work-item failures, retry transient failures with bounded backoff, keep running after planning/Slack/Weather/LLM failures, and terminate only for startup validation/required-provider failures or later fatal required-provider auth loss.
- [ ] 3.5 Add deterministic lifecycle tests for multiple horizons, initial/due runs, no overlap, coalescing, retry, survival, fatal startup, fatal auth loss, and graceful shutdown without real sleeps.

## 4. Conversation, feedback, and override state

- [ ] 4.1 Add durable conversation records keyed by Slack channel/root thread with current exact plan/proposal identity, run name, iteration lineage, timestamps, and restart-safe lookup/update.
- [ ] 4.2 Add durable inbound event deduplication so repeated Socket Mode envelopes or message events cannot duplicate planning, decisions, replies, or writes.
- [ ] 4.3 Implement ordered regex parsing for acknowledge/approve/reject/replan/apply-safe/status/help with actor/channel/thread authorization and bounded captures.
- [ ] 4.4 Add bounded temporary `PlanningOverride` persistence/validation for exclusions, priority adjustments, criteria, expiry, source, and exact plan binding without modifying Todoist source tasks or permanent config.
- [ ] 4.5 Integrate optional LLM conversational interpretation and temporary-override suggestions for unmatched feedback, requiring an explicit deterministic confirmation before replanning/apply.
- [ ] 4.6 Make replan iterations create immutable linked plans, post diffs in the same thread, update only the conversation's current identity, and refuse stale iteration approvals.
- [ ] 4.7 Apply authorized current-plan decisions through existing guarded apply/safe-only paths; prove acknowledge/reject/replan and unconfirmed LLM output make zero provider writes.

## 5. Slack App and Socket Mode Messaging Surface

- [ ] 5.1 Add pinned Slack Bolt Socket Mode/WebSocket dependencies, verify the resolved dependency graph, and keep SDK code behind a narrow `MessagingSurface` adapter.
- [ ] 5.2 Add `conf/smartplanner-slack-app-manifest.example.yaml` with default name/display name `SmartPlanner`, Socket Mode, `/smartplanner`, least-privilege scopes, and required event subscriptions; document operator name customization before registration.
- [ ] 5.3 Implement outbound-only Socket Mode startup/reconnect/shutdown using separate app and bot tokens, prompt envelope acknowledgement, callback-thread isolation, bounds, redaction, bot/self filtering, and normalized command/message events.
- [ ] 5.4 Implement `/smartplanner plan [RUN]`, `replan [RUN] [feedback]`, `status`, and `help`, plus equivalent app-mention parsing; authorize channel/actor, ack within three seconds, enqueue work, and provide actionable replies.
- [ ] 5.5 Publish proposals as configured-channel parent messages, persist returned channel/`ts`, and require all proposal feedback/iterations in that parent thread.
- [ ] 5.6 Call `assistant.threads.setStatus` with configurable bounded working/loading text for Slack-requested work, clear it on reply/error, and degrade observably without terminating or granting authority.
- [ ] 5.7 Add fake-surface/SDK composition tests and WireMock tests for `apps.connections.open`-adjacent seams as applicable, `chat.postMessage` parent/thread bodies, status set/clear, auth, rate limits, malformed/oversized responses, reconnects, duplicate envelopes, and no inbound listener.

## 6. Existing provider and safety boundaries

- [ ] 6.1 Preserve managed-calendar ownership/UID collision checks, Todoist due-only writes, deadline invariance, exact approval, safe-only withholding, and `fully_automated` refusal in daemon flows.
- [ ] 6.2 Preserve application/delivery ambiguity barriers across daemon cycles and restarts; never blind-resend unknown provider outcomes.
- [ ] 6.3 Verify disabled optional Weather/LLM providers are not constructed/called and their runtime failures degrade only the affected run/event.
- [ ] 6.4 Add end-to-end daemon tests with fixture tasks/events and a fake Slack surface for scheduled proposal, threaded approval/apply, rejection, regex replan, LLM-confirmed replan, multiple iterations, restart recovery, and idempotency.

## 7. Documentation and operator artifacts

- [ ] 7.1 Rewrite README and quick start to make `planner-daemon` the primary SmartPlanner runtime while retaining legacy and one-shot diagnostics.
- [ ] 7.2 Update example configuration and `docs/SMART_PLANNER_CONFIGURATION.md` for every daemon, horizon, retry, Slack Socket Mode, regex, override, status, authorization, and state key.
- [ ] 7.3 Rewrite `docs/SLACK_INTEGRATION.md` with manifest creation/custom naming, app-level and bot tokens, scopes, Socket Mode/no inbound port, command setup, channel parents, threaded feedback/iterations, status messages, testing, troubleshooting, and disable/rollback.
- [ ] 7.4 Update LLM and end-to-end guides for conversational interpretation, explicit confirmation, isolated daemon tests, supervised service operation, health/status, startup fail-fast behavior, resilience, backup, rollback, and crawl/walk/run gates.
- [ ] 7.5 Verify all links, manifest/config parsing, CLI examples/help, command names, documented Slack source URLs, and tracked-file secret/personal-data cleanliness.

## 8. Verification and delivery

- [ ] 8.1 Run focused daemon/config/conversation/regex/Slack/WireMock/orchestration/CLI tests and resolve every failure.
- [ ] 8.2 Run `./gradlew :app:test --rerun-tasks`, inspect JUnit XML/HTML counts, and run `./gradlew build` plus `./gradlew installDist`.
- [ ] 8.3 Exercise installed help, one-shot hermetic preview, and a bounded hermetic daemon smoke test proving scheduled run, Slack-triggered run, thread feedback, and clean shutdown.
- [ ] 8.4 Run strict OpenSpec validation, documentation-link/config/manifest checks, `git diff --check`, `git show --check`, and full branch authority/security review.
- [ ] 8.5 Leave live Slack/Todoist/CalDAV verification unchecked if credentials/test workspace are unavailable; record the blocker and exact operator procedure rather than claiming live success.
- [ ] 8.6 Commit the implementation/docs/tests separately from the planning-artifact commit; push only if explicitly requested and never approve or merge without operator approval.
