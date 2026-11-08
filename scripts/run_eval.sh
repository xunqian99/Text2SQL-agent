#!/usr/bin/env bash
set -euo pipefail

SPLIT="${1:-eval}"
LIMIT="${2:-0}"
DRY_RUN="${DRY_RUN:-false}"
PORT="${PORT:-18080}"

if [ "$DRY_RUN" != "true" ] && [ -z "${AGENT_LLM_API_KEY:-}" ]; then
  echo "真实评估需要 AGENT_LLM_API_KEY；dry-run 用 DRY_RUN=true bash scripts/run_eval.sh all" >&2
  exit 1
fi

ARGS="--spring.profiles.active=local --server.port=$PORT --agent.eval.enabled=true --agent.eval.split=$SPLIT --agent.eval.limit=$LIMIT"
if [ "$DRY_RUN" = "true" ]; then
  ARGS="$ARGS --agent.eval.dry-run=true"
fi

mvn spring-boot:run "-Dspring-boot.run.arguments=$ARGS"
