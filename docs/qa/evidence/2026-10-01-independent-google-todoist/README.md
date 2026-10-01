# Independent Google Calendar + Todoist QA Evidence

This directory contains the secret-free review package from the independent
Google Calendar API + Todoist API QA campaign completed on October 1, 2026.

## Result

- Verdict: **PASS — 12/12 gates**
- Reviewed application base: `939edefde61c2cefad52503c3b22420ec10976ab`
- Tested local commit: `9b3bebf43bfe2d02d6a241d8dd52391bc4b1a76b`
- Scoped hermetic tests: **299/299 passed**, with no failures, errors, or skips
- Earlier clean-orb full run: **891/891 passed**
- Final cleanup restored Todoist and Google inventories to preflight and restored
  the `plans`, `applications`, `decisions`, and `deliveries` state directories
  together to their empty baseline.

See `results.json` for the gate matrix, `manifest.json` for campaign identity and
scope, and `command-status-index.txt` for the captured command/exit-status index.
`provider-proof.png` is a representative inspected view of exact apply,
apply-safe, and rollback evidence. The tarball contains the complete secret-free
report package, including normalized provider exports, receipts, report media,
and its internal checksum manifests.

## Package integrity

`manual-qa-report-package.tar.gz` has SHA-256:

```text
afbefb40293d5704cfea7e2876a698cd17615fc958b3aac47e642ca91f19aa28
```

Verify it from this directory with:

```bash
sha256sum -c manual-qa-report-package.tar.gz.sha256
```

The tarball passed path-safety and high-confidence credential/consent scans.
Its packaged `report/evidence-manifest.sha256` retains the original
`.qa/runs/20261001T120759Z-live-9b3bebf43bfe/` prefix. A direct
`sha256sum -c` after extraction therefore reports missing paths; stripping that
prefix verifies all 134 distributable entries. This portability defect should be
fixed in the next evidence packager revision.

## Boundaries and limitations

- No OAuth client material, token stores, access/refresh tokens, authorization
  codes, consent URLs, Slack credentials, or archived private evidence is
  included.
- The first combined Gradle invocation exited 1 because test-specific `--tests`
  options were also applied to `installDist`. The campaign then ran the same
  scoped tests and `installDist` separately; both passed. The failed invocation
  remains in the command index rather than being omitted.
- Native Todoist and Google Calendar UI captures were unavailable because the
  orb browser was not authenticated. API exports, provider diffs, receipts,
  ownership metadata, and final inventories are the authoritative evidence.
- Recurring Todoist Date/Due behavior was deliberately excluded because the
  current branch can replace recurrence with a fixed `due_datetime`. It requires
  a separate post-fix live safety case.
- Slack, Weather, LLM, combined integrations, CalDAV, and production rollout were
  not tested in this campaign.
