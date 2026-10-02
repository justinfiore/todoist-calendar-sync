#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--live" ]]; then
  cat >&2 <<'EOF'
Live recurrence validation is intentionally not automated from credentials.
Follow docs/SMARTPLANNER_QA_RUNBOOK.md section H after explicit owner authorization.
Use only disposable resources and record zero-resource cleanup.
EOF
  exit 2
fi

exec ./gradlew :app:test \
  --tests 'todoistcaldavsync.planner.adapters.TodoistSyncGatewayWireMockSpec' \
  --tests 'todoistcaldavsync.planner.recurrence.RecurrenceLifecycleSpec' \
  --tests 'todoistcaldavsync.planner.state.PlanStoreSpec' \
  --tests 'todoistcaldavsync.planner.apply.RecurrencePlanApplierSpec' \
  --tests 'todoistcaldavsync.planner.ProductionPlannerOrchestratorIntegrationSpec'
