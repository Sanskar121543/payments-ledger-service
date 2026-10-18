#!/usr/bin/env bash
# Waits for Always Free Arm capacity, then creates the VM in the first availability domain that has room and
# deploys the stack (deploy.sh). Writes availability_domain_number into terraform.tfvars, so later applies keep
# the VM where it is, and refreshes ssh_allowed_cidr if your public IP changed while it waited.
# Usage: infra/oci/wait-and-apply.sh      WAIT_SECONDS (default 300) sets how often it checks.
set -euo pipefail
cd "$(dirname "$0")"

terraform init -input=false > /dev/null
if terraform state list 2> /dev/null | grep -q '^oci_core_instance\.ledger$'; then
  echo "The VM already exists; nothing to wait for. Redeploy with ./deploy.sh"
  exit 0
fi

set_var() { # set_var NAME VALUE (VALUE as HCL): replace the line in terraform.tfvars, or add it
  grep -v "^$1 *=" terraform.tfvars > terraform.tfvars.tmp || true
  printf '%s = %s\n' "$1" "$2" >> terraform.tfvars.tmp
  mv terraform.tfvars.tmp terraform.tfvars
}
tfvar() { sed -n "s/^$1 *= *\([0-9]*\).*/\1/p" terraform.tfvars | head -1; }
ocpus="$(tfvar ocpus)" memory="$(tfvar memory_in_gbs)"
ocpus="${ocpus:-4}" memory="${memory:-24}"

while true; do
  report="$(./capacity-check.sh "$ocpus" "$memory" || true)"
  echo "$report"
  domain="$(sed -n 's/.*availability_domain_number = \([123]\) .*: AVAILABLE$/\1/p' <<< "$report" | head -1)"
  if [ -n "$domain" ]; then
    set_var availability_domain_number "$domain"
    ip="$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]' || true)"
    [ -z "$ip" ] || set_var ssh_allowed_cidr "\"$ip/32\""
    terraform plan -input=false -no-color -out=phase8.tfplan
    if terraform apply -input=false -no-color phase8.tfplan 2>&1 | tee apply.log; then
      break
    fi
    # The capacity report is a snapshot: the room can be gone by the time the VM is created.
    grep -qi 'out of host capacity' apply.log || { echo "terraform apply failed for another reason (apply.log)"; exit 1; }
    echo "Capacity went before the VM was placed; waiting again"
  fi
  sleep "${WAIT_SECONDS:-300}"
done

rm -f apply.log
./deploy.sh
