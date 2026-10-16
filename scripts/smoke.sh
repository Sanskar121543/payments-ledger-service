#!/usr/bin/env bash
# End-to-end check against a running stack: accounts, a transfer, a retry, a reversal, invariants.
# Usage: scripts/smoke.sh [base-url]   (reads DEMO_API_KEY from .env)
# EXPECT_INSTANCES (default 3) is how many different app instances must answer: 1 for the lite profile.
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a; source .env; set +a
BASE="${1:-http://localhost:${HTTP_PORT:-8088}}"
KEY_HEADER="X-API-Key: ${DEMO_API_KEY}"

post() { curl -sS -X POST "$BASE$1" -H "$KEY_HEADER" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "$3"; }
get() { curl -sS "$BASE$1" -H "$KEY_HEADER"; }
field() { grep -o "\"$1\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }

# Idempotency keys must be unique per run: reusing one would (correctly) replay an old response.
run="smoke-$(date +%s)-$RANDOM"
alice=$(post /v1/accounts "$run-a" '{"name":"smoke-alice"}' | field id)
bob=$(post /v1/accounts "$run-b" '{"name":"smoke-bob"}' | field id)
post /v1/transfers "$run-fund" "{\"fromAccount\":1,\"toAccount\":$alice,\"amountCents\":10000}" >/dev/null

key="$run-transfer"
first=$(post /v1/transfers "$key" "{\"fromAccount\":$alice,\"toAccount\":$bob,\"amountCents\":2500}")
retry=$(post /v1/transfers "$key" "{\"fromAccount\":$alice,\"toAccount\":$bob,\"amountCents\":2500}")
[ "$first" = "$retry" ] || { echo "FAIL: retry returned a different body"; exit 1; }

transfer=$(echo "$first" | field id)
post "/v1/transfers/$transfer/reversals" "$run-reverse" '{}' >/dev/null

alice_balance=$(get "/v1/accounts/$alice" | field balanceCents)
bob_balance=$(get "/v1/accounts/$bob" | field balanceCents)
ok=$(get /v1/ledger/invariants | field ok)

instances=$(for _ in 1 2 3 4 5 6 7 8 9; do curl -sS -o /dev/null -D - "$BASE/actuator/health" | tr -d '\r' | sed -n 's/^X-Served-By: //Ip'; done | sort -u | tr '\n' ' ')
seen=$(echo "$instances" | wc -w)

echo "alice=$alice_balance bob=$bob_balance invariants_ok=$ok instances_seen=[$instances]"
[ "$alice_balance" = "10000" ] && [ "$bob_balance" = "0" ] && [ "$ok" = "true" ] || { echo "FAIL"; exit 1; }
[ "$seen" -ge "${EXPECT_INSTANCES:-3}" ] || { echo "FAIL: expected ${EXPECT_INSTANCES:-3} instances, saw $seen"; exit 1; }
echo "SMOKE OK"
