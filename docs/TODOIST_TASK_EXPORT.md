# Private Todoist task backup before SmartPlanner

Build the installed runtime, then run the standalone exporter (not the default
application launcher):

```sh
./gradlew :app:installDist
scripts/todoist-export.sh --config conf/todoist-calendar-sync.conf.yaml \
  --output-dir "$HOME/todoist-backup-$(date -u +%Y%m%dT%H%M%SZ)"
```

The output directory must be **new**, with an existing parent; reruns refuse it.
Use a timestamped destination as above. POSIX permissions are required: the
directory is created 0700 and files 0600. Store outside version control and cloud
sharing; task text, descriptions, labels and dates are private. The utility
prints no tasks, paths, credentials, provider errors or exception traces.

## Authentication and bounds

Only the Todoist config is read. No Google, Slack, LLM, planner lifecycle or
legacy sync is initialized. No provider mutation is made.

* Explicit `planner.integration.todoist.token_env` selects that environment
  variable. Missing/empty credentials fail; there is no fallback. If legacy
  `todoist.accessToken` is also present and differs, export fails rather than
  guessing the account. Use an unambiguous config for the intended account.
* Without explicit `token_env`, legacy `todoist.accessToken` wins over ambient
  variables, matching the legacy runner. Without either, use
  `TODOIST_ACCESS_TOKEN`.
* Optional planner Todoist `base_url` (HTTPS), `timeout` (ISO-8601 duration),
  `max_pages` and `max_response_bytes` are honored. Defaults: gateway API v1,
  10 seconds, 100 pages of 200 REST tasks, and 64 MiB per response. The larger
  response bound accommodates the single full Sync response. Exceeding any
  bound fails, not truncates. Raise the explicit bounds for larger accounts.

## Contents and verification

* `active-tasks.json`: all active REST task records, cursor-paginated with no
  scope/project/label/date filters, transformation, or project enrichment. Keeps
  all returned fields including unknown fields, original Due (null, date-only,
  timed, recurring), Deadline, description, labels, project IDs and priority.
  Project names are retained only if present in the original task response;
  this does not export project metadata.
* `sync-items.json`: a **separate** full items inventory using the gateway's
  read-only POST `/sync`, `sync_token=*`, `resource_types=["items"]`, **without
  commands**. Preserves raw item fields, including native recurrence and
  `completed_count` when returned. This is not completed task history.
* `manifest.json`: success marker written last, counts per inventory, scope,
  UTC capture start/end, full Sync date, byte sizes and SHA-256 per data file.
  Check the data files against these hashes before relying on a backup.

Counts may differ: these inventories are obtained independently and tasks may
change during/between requests. This is **not an atomic server snapshot**, a
completed-history export, or a full-account archive (comments, attachments,
project metadata, etc. are not exported). Missing server fields cannot be
reconstructed. There is **no automatic restore** or write-back facility.

Stop task-changing automation and avoid editing tasks while capturing if you
need a stable pre-planner baseline. Confirm success and counts before enabling
SmartPlanner. On ordinary capture/write failure, no success manifest or data
files remain; the empty reserved directory is retained and must not be reused.
If the process is killed or the filesystem fails during cleanup, private partial
files can remain: **without a valid manifest and matching hashes, do not treat
the directory as a completed backup**. Hashes detect corruption, not malicious
tampering; the manifest itself is not cryptographically authenticated.
