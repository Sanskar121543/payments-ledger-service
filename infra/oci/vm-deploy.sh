#!/usr/bin/env bash
# Runs on the VM as ubuntu, started by deploy.sh with the commit it has just unpacked into a temporary directory.
# Moves the code into place, keeps .env (made on the first run, so the secrets never leave the VM) and the VM's own
# load-test results, builds the image, (re)starts the stack through systemd and runs the smoke test.
# Usage (deploy.sh does this): vm-deploy.sh <unpacked-dir> <lite|full> <commit>
set -euo pipefail

src="$1" profile="$2" commit="$3"
app_dir=/opt/payments-ledger-service

chmod 755 "$src" # mktemp made it 700, and rsync -a gives the app directory the same mode
# results/ on the VM holds the VM's own load-test runs; the laptop's committed results never overwrite them.
rsync -a --delete --exclude /.env --exclude /results/ "$src/" "$app_dir/"
rm -rf "$src"
cd "$app_dir"
umask 077 # .env and its temporary copies are for ubuntu only

set_env() { # set_env FILE NAME VALUE: replace the line, or add it
  grep -v "^$2=" "$1" > "$1.tmp" || true
  printf '%s=%s\n' "$2" "$3" >> "$1.tmp"
  mv "$1.tmp" "$1"
}

# The first run builds the whole file under a temporary name and only then moves it into place, so an interrupted
# first deploy can never leave an .env with the repo's public demo key in it.
if [ ! -f .env ]; then
  cp .env.example .env.new
  keys="$(bash scripts/new-api-key.sh demo)"
  set_env .env.new POSTGRES_PASSWORD "$(openssl rand -hex 24)"
  set_env .env.new GRAFANA_ADMIN_PASSWORD "$(openssl rand -hex 24)"
  set_env .env.new DEMO_API_KEY "$(sed -n 's/^DEMO_API_KEY=//p' <<< "$keys")"
  set_env .env.new API_KEY_HASHES "$(sed -n 's/^API_KEY_HASHES=//p' <<< "$keys")"
  mv .env.new .env
  echo "Made .env with new secrets"
fi
if [ "$(grep '^API_KEY_HASHES=' .env)" = "$(grep '^API_KEY_HASHES=' .env.example)" ]; then
  echo "Refusing to start: .env accepts the public demo API key from .env.example."
  echo "Make a new one with scripts/new-api-key.sh demo and put both lines into $app_dir/.env"
  exit 1
fi

# Load-test results record the machine and the commit; there is no .git here, so the commit comes from deploy.sh.
set_env .env HARDWARE_NOTE "\"$(bash infra/oci/vm-hardware-note.sh)\""
set_env .env DEPLOYED_COMMIT "$commit"
set_env .env COMPOSE_PROFILES "$profile"

docker compose build
sudo install -m 0644 infra/oci/payments-ledger.service /etc/systemd/system/payments-ledger.service
sudo systemctl daemon-reload
sudo systemctl enable --quiet payments-ledger
sudo systemctl restart payments-ledger || { docker compose ps -a; docker compose logs --tail 40; exit 1; }

docker compose ps --format 'table {{.Service}}\t{{.Status}}'
if [ "$profile" = full ]; then expect=3; else expect=1; fi
EXPECT_INSTANCES="$expect" scripts/smoke.sh
echo "Deployed $commit (profile $profile)"
