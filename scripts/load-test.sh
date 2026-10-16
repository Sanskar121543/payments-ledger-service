#!/usr/bin/env bash
# One command to reproduce the load test: starts the full stack, runs k6 through nginx and writes
# results/k6/<timestamp>.json, latest.json, latest.md and a docker-stats snapshot taken mid-run.
# Usage: scripts/load-test.sh [rate-per-second] [duration]     e.g. scripts/load-test.sh 200 60s
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Copy .env.example to .env first"; exit 1; }
set -a; source .env; set +a
[ -n "${HARDWARE_NOTE:-}" ] || { echo "Set HARDWARE_NOTE in .env (CPU, RAM, OS) so the results say what ran them"; exit 1; }

export RATE="${1:-200}"
export DURATION="${2:-60s}"
export GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)$(git diff --quiet 2>/dev/null || echo '-dirty')"
export DOCKER_INFO="$(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes RAM visible to Docker, {{.OperatingSystem}}, engine {{.ServerVersion}}')"
export TOPOLOGY="nginx + app1/app2/app3 (768 MB each) + postgres + kafka, compose profile full"
other=$(docker ps --format '{{.Names}}' | grep -cv '^payments-ledger-' || true)
export OTHER_LOAD="${OTHER_LOAD:-${other} other running containers not part of this project}"
export HOST_UID="$(id -u)" HOST_GID="$(id -g)"

mkdir -p results/k6
docker compose --profile full up -d --build --wait

stamp="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
( sleep 30; docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' \
    | grep -E 'NAME|payments-ledger' > "results/k6/${stamp}-docker-stats.txt" ) &
stats_pid=$!

docker compose --profile full --profile load run --rm k6
wait "$stats_pid" || true
echo "Results: results/k6/latest.md (and results/k6/${stamp}-docker-stats.txt)"
