# SmartPlanner Google QA runbook

This runbook is for a dedicated disposable Todoist account and dedicated disposable Google account.
It is not authorization to use live credentials. During implementation review, run only the hermetic
review commands in the final section. Do not inspect real `.qa/` contents, authenticate, contact a
provider, provision calendars, or start staging until the implementation and each live phase are
explicitly approved.

## Stop conditions

Stop immediately for an account mismatch, unexpected calendar inventory, scope mismatch, credential
or authorization data in output/evidence, unexplained remote change, ambiguous mutation result,
deadline change, or missing before-state snapshot. Preserve redacted logs, receipts, all four planner
state directories, and provider exports. Do not delete state, rerun an ambiguous mutation, or move to
the next phase.

Never paste OAuth client material, consent URLs, authorization codes, credential documents,
access/refresh tokens, Google account passwords, app passwords, Todoist tokens, Slack `xoxb-`/`xapp-`
values, or `Authorization` headers into Slack, tickets, commits, terminal transcripts, screenshots,
logs, receipts, or reports. Secret-bearing local paths and values must be excluded from evidence.

## A. Review and local boundary

- [ ] Confirm the `add-google-calendar-gateway` implementation is reviewed and live credential use is
  separately authorized.
- [ ] Confirm both provider accounts are disposable and contain no personal/work data.
- [ ] Build/install the reviewed revision and record its commit ID, Java/Gradle versions, checksums,
  launcher help, and passing hermetic tests.
- [ ] Copy `.qa.example/planner-google-qa.yaml` to ignored `.qa/smartplanner-qa.yaml` without reading or
  printing existing `.qa` secrets. Keep `.qa/secrets/`, `.qa/tokens/`, `.qa/state/`, and evidence paths
  owner-private.
- [ ] Keep `planner.mode: preview`, daemon/Slack/AI/weather disabled, and four planner state directories
  separate. Configure `provider: google_calendar_api`, the expected account, OAuth file/store
  references, and no inline secrets.

## B. Explicit OAuth setup

Normal planner operation requires a fresh event-only grant. It may run before calendar IDs exist:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-bootstrap
```

- [ ] Verify the listener binds only `127.0.0.1` on `oauth_callback_port` (`8787` by default).
- [ ] Verify the one-time consent URL appears only on the invoking terminal.
- [ ] Verify success changes only the normal token store and the launcher exits; no planner,
  Calendar API list/provision, or daemon operation runs.

For a browser on another machine, establish the tunnel before starting bootstrap and keep it open:

```bash
ssh -N -L 8787:127.0.0.1:8787 hermes@<host>
```

Use the configured port on both sides. Open the printed URL in the local browser; the callback returns
through the SSH tunnel. Do not manually copy a code.

The initial confirmed legacy credential may be imported only into the QA calendar-management store:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-import-legacy-qa \
  --confirm-legacy-qa-import --input-reference .qa/secrets/legacy-qa-credential.json
```

- [ ] Verify the configured account and exact QA scope validate before a write.
- [ ] Verify only the QA token store changes, the normal store remains unchanged, and no Google
  Calendar request is sent.
- [ ] If import fails, stop. Do not weaken validation or import into the normal store.

With an operator available, the distinct QA consent fallback/manual test is:

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-oauth-bootstrap-qa
```

- [ ] Verify loopback-only callback behavior, QA calendar-management scope, QA-store-only persistence,
  unchanged normal store, immediate exit, and zero calendar list/create activity.

## C. Explicit QA calendar preflight and provisioning

```bash
todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-qa-calendars-list --confirm-dedicated-qa-account

todoist-caldav-sync -f .qa/smartplanner-qa.yaml -l conf/log4j.groovy \
  --operation google-qa-calendars-provision --confirm-dedicated-qa-account \
  --qa-calendar 'output|managed_output|SmartPlanner QA Output;blockers|hard_blocker|SmartPlanner QA Blockers'
```

- [ ] List first and verify the one primary calendar exactly matches `account_email`; stop on mismatch.
- [ ] Record approval for the two exact disposable names and expected roles before provision.
- [ ] Verify provision creates or uniquely reuses only those names and writes returned IDs only to
  ignored `.qa/state/calendar-ids.json`.
- [ ] Put the returned IDs into the ignored complete QA configuration with unique names/IDs, exactly
  one `managed_output`, and `planner.output_calendar: SmartPlanner QA Output`.
- [ ] Verify ordinary `capacity`, `preview`, `apply`, `apply-safe`, and `planner-daemon` refuse QA
  provisioning flags and cannot reach the provisioning service.

## D. Preview-first evidence gate

- [ ] Create the approved disposable Todoist/calendar fixture inventory and take normalized provider
  exports plus a snapshot of all four planner state directories.
- [ ] Run `capacity`, then `preview`, with explicit UTC bounds. Capture exit status, sanitized output,
  plan ID/version/hash/diff, classification, unscheduled reasons, and local state changes.
- [ ] Repeat preview and prove stable output and zero Todoist or Google Calendar mutations.
- [ ] Review evidence for global UID collision checks, managed-output routing, no credential leakage,
  and Todoist deadline invariance.
- [ ] Obtain explicit review approval before changing from `preview`.

## E. Refusal and scoped-write gates

- [ ] In `approval_required`, preview again; apply once without approval and once with a stale/wrong
  hash. Require refused receipts and zero remote writes.
- [ ] Back up/export providers and all four state directories. Approve one exact small plan, apply once,
  and prove only the expected owned managed-calendar event and Todoist due time changed; deadlines are
  byte-for-byte unchanged.
- [ ] Rerun the same apply and prove idempotent/no-op behavior.
- [ ] With separate approval, exercise `apply-safe` using one ordinary and one protected change; prove
  only the ordinary change occurred and all protected changes are withheld.
- [ ] Reconcile/delete only an owned test event after live ownership/block checks. Never use external
  or wrong-calendar resources.

Every case requires command/exit evidence, sanitized logs and receipts, before/after normalized
provider diff, planner-state snapshot, expected mutation, reviewer approval, cleanup result, and a
native UI screenshot when behavior is user-visible. Hash captured files and label automated proof,
live isolated proof, manual observation, and not-yet-tested distinctly.

## F. Rollback and credential revocation

1. Stop apply/delivery/daemon activity and return configuration to `preview`.
2. Preserve the failure state, redacted logs/receipts, live provider export, and all four planner state
   directories. Reconcile an ambiguous result before any retry.
3. Restore Todoist due values and calendar resources from the matching before-export; restore all four
   state directories together from the matching snapshot.
4. For credential compromise or retirement, revoke the OAuth app grant in the dedicated Google
   account, then remove the ignored normal and QA token stores and local OAuth client material.
5. Revoke/rotate separately staged Todoist or Slack credentials when in scope. Token revocation does
   not undo provider mutations; keep the data restoration step.
6. If intentionally reverting to CalDAV, configure a complete `provider: caldav` section and remove
   the Google provider section. Never rely on fallback.

## G. Hermetic review commands

These are the only commands approved during documentation/review validation:

```bash
./gradlew :app:test --tests 'todoistcaldavsync.planner.config.PlannerConfigSpec' \
  --tests 'todoistcaldavsync.planner.ProductionIntegrationConfigSpec' \
  --tests 'todoistcaldavsync.TodoistCalDavSyncOAuthBootstrapSpec' \
  --tests 'todoistcaldavsync.TodoistCalDavSyncQaProvisioningSpec' \
  --tests 'todoistcaldavsync.planner.oauth.*' \
  --tests 'todoistcaldavsync.planner.qa.*' \
  --tests 'todoistcaldavsync.planner.adapters.GoogleCalendarApiGatewayWireMockSpec'
./gradlew :app:test --rerun-tasks
./gradlew build
./gradlew installDist
./app/build/install/todoist-caldav-sync/bin/todoist-caldav-sync --help
openspec validate add-google-calendar-gateway --strict
git diff --check
git status --short
git diff --stat
git diff --name-only
```

Also inspect the full diff, scan only tracked files for secret patterns, and mechanically verify every
documented launcher operation/flag against installed `--help`. These commands must not read `.qa`,
load credentials, contact providers, mutate staging, or create a commit.
