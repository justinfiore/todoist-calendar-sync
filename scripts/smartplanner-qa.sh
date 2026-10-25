#!/usr/bin/env bash
set -euo pipefail

readonly ROOT="$(builtin cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
case "${1:-help}" in
  help|--help|-h)
    cat <<'EOF'
SmartPlanner Google/xAI orb QA (default commands never contact providers)
  smartplanner-qa.sh init RUN_ID --auth-root ABSOLUTE_EXTERNAL_PATH
      [--account EMAIL] [--slack-channel ID] [--slack-user ID] [--model MODEL]
  smartplanner-qa.sh preflight RUN_ID
  smartplanner-qa.sh restore-auth RUN_ID
  smartplanner-qa.sh contracts

Build first: ./gradlew :app:installDist
Runs live only under .qa/runs/RUN_ID. init refuses existing destinations.
preflight is a strict offline baseline check, not approval for provider activity.
restore-auth decodes project-secret snapshots locally and refuses existing files.
No command logs in, posts Slack messages, provisions, applies, or cleans providers.
Follow docs/SMARTPLANNER_QA_REPLAY.md for separately approved live stages.
EOF
    exit 0 ;;
  contracts)
    [[ $# == 1 ]] || { echo 'contracts takes no arguments' >&2; exit 2; }
    unset TODOIST_ACCESS_TOKEN SLACK_BOT_TOKEN SLACK_APP_TOKEN XAI_API_KEY OPENAI_API_KEY \
      SMARTPLANNER_GOOGLE_OAUTH_CLIENT_JSON_B64 SMARTPLANNER_GOOGLE_NORMAL_TOKEN_JSON_B64 \
      SMARTPLANNER_GOOGLE_QA_TOKEN_JSON_B64 SMARTPLANNER_XAI_SUBSCRIPTION_CREDENTIAL_JSON_B64
    exec "$ROOT/gradlew" --project-dir "$ROOT" --no-daemon \
      -Dorg.gradle.jvmargs='-Xmx256m -Xms64m -XX:MaxMetaspaceSize=192m' \
      -Dorg.gradle.workers.max=1 :app:test \
      --tests 'todoistcaldavsync.*Subscription*Spec' \
      --tests 'todoistcaldavsync.planner.ai.*Spec' \
      --tests 'todoistcaldavsync.planner.SmartPlannerDaemonSpec' \
      --tests 'todoistcaldavsync.planner.Production*Spec' \
      --tests 'todoistcaldavsync.planner.adapters.GoogleCalendarApiGatewayWireMockSpec' \
      --tests 'todoistcaldavsync.planner.adapters.Todoist*Spec' \
      --tests 'todoistcaldavsync.planner.adapters.OpenMeteoWeatherGatewaySpec' \
      --tests 'todoistcaldavsync.planner.adapters.SlackMessagingGatewaySpec' \
      --tests 'todoistcaldavsync.planner.scheduling.*Spec' \
      --tests 'todoistcaldavsync.planner.messaging.*Spec' \
      --tests 'todoistcaldavsync.TodoistCalDavSyncQaProvisioningSpec' \
      --tests 'todoistcaldavsync.planner.feedback.*Spec' \
      --tests 'todoistcaldavsync.planner.writeback.*Spec' \
      --tests 'todoistcaldavsync.planner.apply.*Spec' \
      --tests 'todoistcaldavsync.planner.recurrence.*Spec' \
      --tests 'todoistcaldavsync.planner.oauth.*Spec' \
      --tests 'todoistcaldavsync.planner.qa.*Spec' ;;
  init|preflight|restore-auth) ;;
  *) echo 'Unknown QA command; use help' >&2; exit 2 ;;
esac

[[ -x "$ROOT/app/build/install/todoist-caldav-sync/bin/todoist-caldav-sync" ]] || {
  echo 'Build the installed launcher first: ./gradlew :app:installDist' >&2
  exit 2
}
umask 077
exec java -cp "$ROOT/app/build/install/todoist-caldav-sync/lib/*" groovy.ui.GroovyMain \
  "$ROOT/scripts/smartplanner-qa.groovy" "$ROOT" "$@"
