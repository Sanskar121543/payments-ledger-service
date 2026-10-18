#!/usr/bin/env bash
# Runs on the VM as ubuntu, started by deploy.sh with the commit it has just unpacked into a temporary directory.
# Moves the code into place, keeps .env (made on the first run, so the secrets never leave the VM), builds the
# image, (re)starts the stack through systemd and runs the smoke test.
# Usage (deploy.sh does this): vm-deploy.sh <unpacked-dir> <lite|full> <commit>
set -euo pipefail

src="$1" profile="$2" commit="$3"
app_dir=/opt/payments-ledger-service

chmod 755 "$src" # mktemp made it 700, and rsync -a gives the app directory the same mode
rsync -a --delete --exclude /.env "$src/" "$app_dir/"
rm -rf "$src"
cd "$app_dir"

set_env() { # set_env NAME VALUE: replace the line, or add it
  grep -v "^$1=" .env > .env.tmp || true
  printf '%s=%s\n' "$1" "$2" >> .env.tmp
  mv .env.tmp .env
  chmod 600 .env
}

if [ ! -f .env ]; then
  cp .env.example .env
  chmod 600 .env
  set_env POSTGRES_PASSWORD "$(openssl rand -hex 24)"
  set_env GRAFANA_ADMIN_PASSWORD "$(openssl rand -hex 24)"
  keys="$(bash scripts/new-api-key.sh demo)"
  set_env DEMO_API_KEY "$(sed -n 's/^DEMO_API_KEY=//p' <<< "$keys")"
  set_env API_KEY_HASHES "$(sed -n 's/^API_KEY_HASHES=//p' <<< "$keys")"
  echo "Made .env with new secrets"
fi

# Load-test results record the machine and the commit; there is no .git here, so the commit comes from deploy.sh.
shape_region="$(curl -fsS -H 'Authorization: Bearer Oracle' http://169.254.169.254/opc/v2/instance/ \
  | python3 -c 'import json, sys; m = json.load(sys.stdin); print(m["shape"] + ", " + m.get("canonicalRegionName", m["region"]))' \
  || echo "unknown shape and region")"
cpu_model="$(lscpu | sed -n 's/^Model name: *//p' | head -1)"
memory_gb="$(awk '/^MemTotal:/ { printf "%.1f", $2 / 1048576 }' /proc/meminfo)"
os_name="$(. /etc/os-release && echo "$PRETTY_NAME")"
set_env HARDWARE_NOTE "\"Oracle Cloud ${shape_region}, Ampere ${cpu_model} ($(nproc) cores), ${memory_gb} GB RAM, ${os_name}, Docker $(docker version --format '{{.Server.Version}}')\""
set_env DEPLOYED_COMMIT "$commit"
set_env COMPOSE_PROFILES "$profile"

docker compose build
sudo install -m 0644 infra/oci/payments-ledger.service /etc/systemd/system/payments-ledger.service
sudo systemctl daemon-reload
sudo systemctl enable --quiet payments-ledger
sudo systemctl restart payments-ledger || { docker compose ps -a; docker compose logs --tail 40; exit 1; }

docker compose ps --format 'table {{.Service}}\t{{.Status}}'
if [ "$profile" = full ]; then expect=3; else expect=1; fi
EXPECT_INSTANCES="$expect" scripts/smoke.sh
echo "Deployed $commit (profile $profile)"
