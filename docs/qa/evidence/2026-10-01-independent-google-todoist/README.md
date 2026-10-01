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

The complete report package is extracted under `qa-report/` as individual files:

- `qa-report/report/results.json` contains the gate matrix.
- `qa-report/manifest.json` contains campaign identity and scope.
- `qa-report/report/command-status-index.txt` contains the captured
  command/exit-status index.
- `qa-report/report/media/provider-proof.png` is a representative inspected view
  of exact apply, apply-safe, and rollback evidence.
- `qa-report/report/evidence/` contains normalized provider exports, receipts,
  and redacted command logs.
- `qa-report/report/index.html` and `qa-report/report/media/` contain the
  human-readable report and inspected walkthrough media.

## Package integrity

The package-content manifest uses paths relative to `qa-report/`. Verify all 134
report files with:

```bash
cd qa-report
sha256sum -c report/evidence-manifest.sha256
```

The original source tarball had SHA-256
`afbefb40293d5704cfea7e2876a698cd17615fc958b3aac47e642ca91f19aa28`.
It was safely extracted, and its package manifest's stale source-directory prefix
was removed so verification works from the committed `qa-report/` directory.
The disposable Google account email was also replaced consistently with
`<redacted-google-account>` in the preflight and final calendar inventories;
their manifest entries were regenerated after redaction.

`qa-report/checksums/evidence.sha256` and `exact-build.sha256` preserve the
campaign's original full-run/build indexes. They reference private build and
fresh-run paths that are deliberately not committed, so they are provenance
records rather than independently runnable manifests in this checkout.

## Boundaries and limitations

- No OAuth client material, token stores, access/refresh tokens, authorization
  codes, consent URLs, Slack credentials, or archived private evidence is
  included.
- The first combined Gradle invocation exited 1 because test-specific `--tests`
  options were also applied to `installDist`. The campaign then ran the same
  scoped tests and `installDist` separately; both passed. The failed invocation
  remains in `qa-report/report/command-status-index.txt` rather than being
  omitted.
- Native Todoist and Google Calendar UI captures were unavailable because the
  orb browser was not authenticated. API exports, provider diffs, receipts,
  ownership metadata, and final inventories are the authoritative evidence.
- Recurring Todoist Date/Due behavior was deliberately excluded because the
  current branch can replace recurrence with a fixed `due_datetime`. It requires
  a separate post-fix live safety case.
- Slack, Weather, LLM, combined integrations, CalDAV, and production rollout were
  not tested in this campaign.
