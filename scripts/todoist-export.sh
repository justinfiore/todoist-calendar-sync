#!/usr/bin/env bash
set -euo pipefail
readonly ROOT="$(builtin cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
case "${1:-}" in
  --help|-h)
    echo 'Usage: todoist-export.sh --config FILE --output-dir NEW_TIMESTAMPED_DIRECTORY'
    echo 'Build first: ./gradlew :app:installDist. Read docs/TODOIST_TASK_EXPORT.md.'
    exit 0 ;;
esac
[[ -x "$ROOT/app/build/install/todoist-caldav-sync/bin/todoist-caldav-sync" ]] || {
  echo 'Build the installed runtime first: ./gradlew :app:installDist' >&2
  exit 2
}
umask 077
exec java -cp "$ROOT/app/build/install/todoist-caldav-sync/lib/*" \
  todoistcaldavsync.TodoistTaskExporter "$@"
