#!/usr/bin/env bash
# Sends one commit of this repo to the VM over SSH and (re)starts the stack there with vm-deploy.sh. Nothing on
# the VM talks to GitHub, so the repo can stay private. The first run makes the VM's secrets; later runs keep them,
# and the data. Run it after terraform apply, and again whenever you want the VM on a newer commit.
# Usage: infra/oci/deploy.sh [git-ref]      default HEAD; uncommitted changes are not sent
set -euo pipefail
cd "$(dirname "$0")"

ref="${1:-HEAD}"
commit="$(git rev-parse --short "${ref}^{commit}")"
host="ubuntu@$(terraform output -raw public_ip)"
key="$(terraform output -raw ssh_key)"
key="${key/#\~/$HOME}"
profile="$(terraform output -raw compose_profile)"
remote=(ssh -i "$key" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o ServerAliveInterval=30 "$host")

echo "Waiting for SSH on $host"
for attempt in $(seq 60); do
  "${remote[@]}" true 2> /dev/null && break
  [ "$attempt" -lt 60 ] || { echo "SSH did not answer within 10 minutes"; exit 1; }
  sleep 10
done

echo "Waiting for first-boot setup (Docker), a few minutes on a new VM"
"${remote[@]}" 'cloud-init status --wait > /dev/null || true
  docker compose version > /dev/null 2>&1 || { tail -n 30 /var/log/payments-ledger-setup.log; exit 1; }'

echo "Sending $commit (profile $profile); the first build takes about 10 minutes"
git -C ../.. archive --format=tar "$commit" \
  | "${remote[@]}" "set -e; src=\$(mktemp -d); tar -x -f - -C \"\$src\"; bash \"\$src/infra/oci/vm-deploy.sh\" \"\$src\" $profile $commit"
