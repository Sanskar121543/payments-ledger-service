#!/usr/bin/env bash
# One command to reproduce the load test: starts the full stack, runs k6 through nginx and writes
# results/k6/<timestamp>.json, latest.json, latest.md and a docker-stats snapshot taken mid-run.
# Usage: scripts/load-test.sh [rate-per-second] [duration]     e.g. scripts/load-test.sh 200 60s
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Copy .env.example to .env first"; exit 1; }
set -a; source .env; set +a
# On the cloud VM (a copy sent by infra/oci/deploy.sh), describe the machine as it is now, which survives a resize.
if [ -n "${DEPLOYED_COMMIT:-}" ] && [ -f infra/oci/vm-hardware-note.sh ]; then
  export HARDWARE_NOTE="$(bash infra/oci/vm-hardware-note.sh)"
fi
[ -n "${HARDWARE_NOTE:-}" ] || { echo "Set HARDWARE_NOTE in .env (CPU, RAM, OS) so the results say what ran them"; exit 1; }

export RATE="${1:-200}"
export DURATION="${2:-60s}"
# -dirty if anything outside results/ differs from HEAD, staged or not: the image is built from the working tree.
# A copy sent by infra/oci/deploy.sh has no .git; deploy.sh records the commit it sent as DEPLOYED_COMMIT.
if [ -n "${DEPLOYED_COMMIT:-}" ]; then
  export GIT_SHA="$DEPLOYED_COMMIT"
else
  export GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)$(git diff --quiet HEAD -- . ':!results' 2>/dev/null || echo '-dirty')"
fi
export DOCKER_INFO="$(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes RAM visible to Docker, {{.OperatingSystem}}, engine {{.ServerVersion}}')"
export TOPOLOGY="nginx + app1/app2/app3 (768 MB each) + postgres + kafka, compose profile full"
other=$(docker ps --format '{{.Names}}' | grep -cv '^payments-ledger-' || true)
export OTHER_LOAD="${OTHER_LOAD:-${other} other running containers not part of this project}"
export HOST_UID="$(id -u)" HOST_GID="$(id -g)"

mkdir -p results/k6
docker compose --profile full up -d --build --wait

stamp="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
( sleep 55; docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' \
    | grep -E 'NAME|payments-ledger' > "results/k6/${stamp}-docker-stats.txt" ) &
stats_pid=$!

status=0
docker compose --profile full --profile load run --rm k6 || status=$?
wait "$stats_pid" || true

# Server-side figures for the run (warm-up + measured part), so tuning claims can point at a file.
case "$DURATION" in
  *m) seconds=$(( ${DURATION%m} * 60 + 30 )) ;;
  *s) seconds=$(( ${DURATION%s} + 30 )) ;;
  *) seconds=300 ;;
esac
prometheus="http://localhost:${PROMETHEUS_PORT:-9091}/api/v1/query"
{
  for query in \
    "max by (app_instance, gc, cause) (max_over_time(jvm_gc_pause_seconds_max[${seconds}s]))" \
    "max by (app_instance) (max_over_time(hikaricp_connections_pending[${seconds}s]))" \
    "max by (app_instance) (max_over_time(hikaricp_connections_acquire_seconds_max[${seconds}s]))" \
    "max(max_over_time(ledger_outbox_unpublished[${seconds}s]))" \
    "sum by (scope) (increase(ledger_lock_timeouts_total[${seconds}s]))"; do
    echo "# $query"
    curl -sSG "$prometheus" --data-urlencode "query=$query" || echo "(prometheus not reachable)"
    echo
  done
} > "results/k6/${stamp}-prometheus.txt"

echo "Results: results/k6/latest.md, results/k6/${stamp}-docker-stats.txt, results/k6/${stamp}-prometheus.txt"
exit "$status"
