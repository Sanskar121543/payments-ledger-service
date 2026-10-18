#!/usr/bin/env bash
# Waits for Always Free Arm capacity, then creates the VM and deploys the stack (deploy.sh). Safe to leave running
# for days: only one copy runs at a time, it stops as soon as the VM is in the Terraform state, it never applies a
# plan that would delete or replace anything, and it will not launch while any other A1 instance exists in the
# compartment (two could go past the free 2 OCPUs / 12 GB).
#
# Each round asks OCI's capacity report for the configured shape (default 2 OCPU / 12 GB), then for FALLBACK_SHAPE
# (default "1 6", which still runs the full profile; resize in place later, see README), and tries every domain
# that reports room. Every BLIND_EVERY-th round with no room reported it tries a real launch anyway, because the
# report is a snapshot and can lag. A failed launch creates nothing billable. Capacity, rate limits and network
# errors mean "wait again"; only errors that waiting cannot fix stop it.
# Once the VM is in the state, its domain and shape are written into terraform.tfvars, so later applies keep the VM
# as created. ssh_allowed_cidr is refreshed before each attempt, in case your public IP changed while it waited.
#
# Usage: infra/oci/wait-and-apply.sh     WAIT_SECONDS (default 120), BLIND_EVERY (default 10), FALLBACK_SHAPE ("" = none)
# Stop it (Git Bash): kill "$(cat infra/oci/.wait-and-apply.lock/pid)"   It finishes a running terraform command first.
set -euo pipefail

# Errors that waiting will not fix: bad credentials or configuration, or an account limit (which would also mean
# the free allowance is smaller than this configuration assumes).
PERMANENT_ERRORS='LimitExceeded|QuotaExceeded|NotAuthenticated|NotAuthorizedOrNotFound|InvalidParameter|CannotParseRequest|Error: Invalid|Unsupported argument|Missing required argument|Reference to undeclared|validation rule'

stamp() { date -u +%H:%M:%SZ; }

take_lock() {
  lock=.wait-and-apply.lock
  if ! mkdir "$lock" 2> /dev/null; then
    local other
    other="$(cat "$lock/pid" 2> /dev/null || true)"
    if [ -n "$other" ] && kill -0 "$other" 2> /dev/null; then
      echo "Another wait-and-apply.sh is running (pid $other). Stop it first: kill $other"
      exit 1
    fi
    echo "Removing a stale lock left by pid ${other:-unknown}"
    rm -rf "$lock"
    mkdir "$lock"
  fi
  echo "$$" > "$lock/pid"
  [ ! -r "/proc/$$/winpid" ] || cat "/proc/$$/winpid" > "$lock/winpid"
  trap 'rm -rf "$lock"' EXIT
  trap 'exit 143' TERM
  trap 'exit 130' INT
}

set_var() { # set_var NAME VALUE (VALUE as HCL): replace the line in terraform.tfvars, or add it
  grep -v "^$1 *=" terraform.tfvars > terraform.tfvars.tmp || true
  printf '%s = %s\n' "$1" "$2" >> terraform.tfvars.tmp
  mv terraform.tfvars.tmp terraform.tfvars
}
tfvar() { sed -n "s/^$1 *= *\"\{0,1\}\([^\" #]*\).*/\1/p" terraform.tfvars | head -1 | tr -d '\r'; }

vm_in_state() {
  local resources
  resources="$(terraform state list 2> /dev/null || true)"
  grep -q '^oci_core_instance\.ledger$' <<< "$resources"
}

# Writes the domain and shape the VM really has (from the state) into terraform.tfvars.
record_vm_in_tfvars() {
  local shown domain ocpus memory
  shown="$(terraform state show -no-color oci_core_instance.ledger)"
  domain="$(sed -n 's/^ *availability_domain *= *".*-AD-\([123]\)"$/\1/p' <<< "$shown" | head -1)"
  ocpus="$(sed -n 's/^ *ocpus *= *\([0-9]*\)\(\.0*\)\{0,1\}$/\1/p' <<< "$shown" | head -1)"
  memory="$(sed -n 's/^ *memory_in_gbs *= *\([0-9]*\)\(\.0*\)\{0,1\}$/\1/p' <<< "$shown" | head -1)"
  [ -z "$domain" ] || set_var availability_domain_number "$domain"
  [ -z "$ocpus" ] || set_var ocpus "$ocpus"
  [ -z "$memory" ] || set_var memory_in_gbs "$memory"
  echo "terraform.tfvars records the VM: domain ${domain:-?}, ${ocpus:-?} OCPU / ${memory:-?} GB"
}

# Any other A1 instance in the compartment (one terraform lost track of, or one made in the console) plus a new one
# could go past the free allowance, so the loop stops instead. Returns 1 if OCI could not be asked.
check_no_other_a1() {
  local compartment found
  compartment="$(tfvar compartment_ocid)"
  compartment="${compartment:-$(tfvar tenancy_ocid)}"
  if ! found="$("${OCI_CLI:-oci}" compute instance list --compartment-id "$compartment" --all \
    --query "data[?shape=='VM.Standard.A1.Flex' && \"lifecycle-state\"!='TERMINATED'].\"display-name\"" \
    --output json 2> /dev/null)"; then
    echo "$(stamp)  Could not list instances; skipping this attempt"
    return 1
  fi
  found="$(tr -d '[]" \r\n' <<< "$found")"
  if [ -n "$found" ]; then
    echo "Stopping: the compartment already has A1 instances ($found) that this Terraform state does not know."
    echo "A second VM could go past the free 2 OCPUs / 12 GB. Remove the other one, or import it, then start again."
    exit 1
  fi
}

# launch DOMAIN OCPUS MEMORY: one plan + apply. Returns 0 if the VM now exists, 1 to wait again; exits on errors
# that waiting cannot fix, and on any plan that would delete or replace something.
launch() {
  local domain="$1" want_ocpus="$2" want_memory="$3" ip status=0
  check_no_other_a1 || return 1
  ip="$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]' || true)"
  [ -z "$ip" ] || set_var ssh_allowed_cidr "\"$ip/32\""
  echo "$(stamp)  Trying $want_ocpus OCPU / $want_memory GB in domain $domain"

  : > apply.log
  terraform plan -input=false -no-color -lock-timeout=5m -out=phase8.tfplan -var "availability_domain_number=$domain" \
    -var "ocpus=$want_ocpus" -var "memory_in_gbs=$want_memory" >> apply.log 2>&1 || status=$?
  if [ "$status" -eq 0 ]; then
    terraform show -json phase8.tfplan > "$lock/plan.json"
    if grep -q '"actions":\[[^]]*"delete"' "$lock/plan.json"; then
      cat apply.log
      echo "Stopping: this plan would delete or replace something, and this script never applies that."
      exit 1
    fi
    terraform apply -input=false -no-color -lock-timeout=5m phase8.tfplan >> apply.log 2>&1 || status=$?
  fi
  cat apply.log

  if vm_in_state; then
    record_vm_in_tfvars
    if [ "$status" -ne 0 ]; then
      echo "Stopping: the VM was created but terraform reported the error above. Check 'terraform plan', then ./deploy.sh."
      exit 1
    fi
    echo "$(stamp)  VM created: $want_ocpus OCPU / $want_memory GB in domain $domain"
    return 0
  fi
  [ "$status" -ne 0 ] || { echo "Stopping: terraform apply succeeded but the VM is not in the state."; exit 1; }
  if grep -Eq "$PERMANENT_ERRORS" apply.log; then
    echo "$(stamp)  Stopping: waiting will not fix the error above."
    exit 1
  fi
  echo "$(stamp)  No VM this time (capacity, rate limit or a transient error); waiting again"
  return 1
}

main() {
  cd "$(dirname "$0")"
  take_lock
  terraform init -input=false > /dev/null

  local ocpus memory primary fallback blind_every round=0 created=""
  ocpus="$(tfvar ocpus)" memory="$(tfvar memory_in_gbs)"
  primary="${ocpus:-2} ${memory:-12}"
  fallback="${FALLBACK_SHAPE-1 6}"
  [ "$fallback" != "$primary" ] || fallback=""
  blind_every="${BLIND_EVERY:-10}"

  while true; do
    if vm_in_state; then
      record_vm_in_tfvars
      echo "The VM already exists; nothing to wait for. Deploy or redeploy with ./deploy.sh"
      return 0
    fi
    round=$((round + 1))

    local candidates="" errors=0 shape report rc
    for shape in "$primary" ${fallback:+"$fallback"}; do
      rc=0
      # shellcheck disable=SC2086 # "2 12" is two arguments: OCPUs and memory
      report="$(./capacity-check.sh $shape)" || rc=$?
      echo "$report"
      [ "$rc" -ne 1 ] || errors=1
      candidates+="$(sed -n "s/.*availability_domain_number = \([123]\) .*: AVAILABLE\$/\1 $shape/p" <<< "$report")"$'\n'
    done
    candidates="$(sed '/^$/d' <<< "$candidates")"
    if [ -z "$candidates" ] && [ "$errors" -eq 0 ] && [ $((round % blind_every)) -eq 0 ]; then
      candidates="$(((round / blind_every - 1) % 3 + 1)) $primary"
      echo "$(stamp)  No domain reports room; trying a real launch anyway"
    fi

    local domain want_ocpus want_memory
    while read -r domain want_ocpus want_memory; do
      [ -n "$domain" ] || continue
      if launch "$domain" "$want_ocpus" "$want_memory" < /dev/null; then
        created=yes
        break
      fi
    done <<< "$candidates"
    [ -z "$created" ] || break
    sleep "${WAIT_SECONDS:-120}"
  done

  rm -f apply.log
  ./deploy.sh < /dev/null
}

# Everything above is only definitions, and this line is read whole before main runs, so editing the file while it
# runs cannot change what this run does.
main "$@"; exit
