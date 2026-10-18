#!/usr/bin/env bash
# Waits for Always Free Arm capacity, then creates the VM and deploys the stack (deploy.sh).
# Each round asks OCI's capacity report for the configured shape (default 4 OCPU / 24 GB), then for FALLBACK_SHAPE
# (default "2 12", still room for the full profile; resize later, see README). Every BLIND_EVERY-th round it also
# tries a real launch, because the report is a snapshot and can lag. A failed launch creates nothing billable.
# Only after a successful apply does it write the domain (and the shape, if it fell back) into terraform.tfvars, so
# later applies keep the VM as created. It refreshes ssh_allowed_cidr if your public IP changed while it waited.
# Usage: infra/oci/wait-and-apply.sh     WAIT_SECONDS (default 120), BLIND_EVERY (default 10), FALLBACK_SHAPE ("" = none)
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
primary="${ocpus:-4} ${memory:-24}"
fallback="${FALLBACK_SHAPE-2 12}"
[ "$fallback" != "$primary" ] || fallback=""
blind_every="${BLIND_EVERY:-10}"

round=0
while true; do
  round=$((round + 1))
  domain="" shape=""
  for candidate in "$primary" ${fallback:+"$fallback"}; do
    # shellcheck disable=SC2086 # "4 24" is two arguments: OCPUs and memory
    report="$(./capacity-check.sh $candidate || true)"
    echo "$report"
    domain="$(sed -n 's/.*availability_domain_number = \([123]\) .*: AVAILABLE$/\1/p' <<< "$report" | head -1)"
    [ -z "$domain" ] || { shape="$candidate"; break; }
  done
  if [ -z "$domain" ] && [ $((round % blind_every)) -eq 0 ]; then
    domain=$(((round / blind_every - 1) % 3 + 1)) shape="$primary"
    echo "No domain reports room; trying a real launch of $shape in domain $domain anyway"
  fi

  if [ -n "$domain" ]; then
    read -r want_ocpus want_memory <<< "$shape"
    ip="$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]' || true)"
    [ -z "$ip" ] || set_var ssh_allowed_cidr "\"$ip/32\""
    rm -f apply.log
    if terraform plan -input=false -no-color -out=phase8.tfplan -var "availability_domain_number=$domain" \
        -var "ocpus=$want_ocpus" -var "memory_in_gbs=$want_memory" \
      && terraform apply -input=false -no-color phase8.tfplan 2>&1 | tee apply.log; then
      set_var availability_domain_number "$domain"
      set_var ocpus "$want_ocpus"
      set_var memory_in_gbs "$want_memory"
      echo "$(date -u +%H:%M:%SZ)  VM created: $want_ocpus OCPU / $want_memory GB in domain $domain"
      break
    fi
    # The room can be gone by the time the VM is placed, and launch attempts can be rate limited: both pass.
    grep -Eqi 'out of host capacity|TooManyRequests' apply.log 2> /dev/null \
      || { echo "terraform plan or apply failed for another reason (see above)"; exit 1; }
    echo "$(date -u +%H:%M:%SZ)  No room when the VM was placed; waiting again"
  fi
  sleep "${WAIT_SECONDS:-120}"
done

rm -f apply.log
./deploy.sh
