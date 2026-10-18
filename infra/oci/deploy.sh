#!/usr/bin/env bash
# Sends one commit of this repo to the VM over SSH and (re)starts the stack there with vm-deploy.sh. Nothing on
# the VM talks to GitHub, so the repo can stay private. The first run makes the VM's secrets; later runs keep them,
# the data and the VM's load-test results. Run it after terraform apply, and again whenever you want the VM on a
# newer commit. Needs an SSH key without a passphrase (or one loaded in ssh-agent): it may run unattended.
# Usage: infra/oci/deploy.sh [git-ref]      default HEAD; uncommitted changes are not sent
set -euo pipefail
cd "$(dirname "$0")"

ref="${1:-HEAD}"
commit="$(git rev-parse --short "${ref}^{commit}")"
git cat-file -e "${commit}:infra/oci/vm-deploy.sh" 2> /dev/null \
  || { echo "$ref is older than this deploy tooling (phase 8), so it cannot be deployed with deploy.sh"; exit 1; }
host="ubuntu@$(terraform output -raw public_ip)"
key="$(terraform output -raw ssh_key)"
key="${key/#\~/$HOME}"
profile="$(terraform output -raw compose_profile)"
remote=(ssh -i "$key" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10
  -o ServerAliveInterval=30 "$host")

echo "Waiting for SSH on $host"
for attempt in $(seq 60); do
  error="$("${remote[@]}" true 2>&1)" && break
  [ "$attempt" -lt 60 ] || { echo "SSH did not work within 10 minutes. Last error: $error"; exit 1; }
  sleep 10
done

echo "Waiting for first-boot setup (Docker), a few minutes on a new VM"
"${remote[@]}" 'cloud-init status --wait > /dev/null || true
  docker compose version > /dev/null 2>&1 || { tail -n 30 /var/log/payments-ledger-setup.log; exit 1; }'

echo "Sending $commit (profile $profile); the first build takes about 10 minutes"
git -C ../.. archive --format=tar "$commit" \
  | "${remote[@]}" "set -e; src=\$(mktemp -d); tar -x -f - -C \"\$src\"; bash \"\$src/infra/oci/vm-deploy.sh\" \"\$src\" $profile $commit"
