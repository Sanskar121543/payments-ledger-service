#!/usr/bin/env bash
# Waits for Always Free Arm capacity, then creates the VM and deploys the stack (deploy.sh). Safe to leave running
# for days: only one copy runs at a time, it stops as soon as the VM is in the Terraform state, it never applies a
# plan that would delete or replace anything, and it will not launch while any A1 OCPU is in use elsewhere in the
# tenancy or too little free block storage is left (either could go past what is free).
#
# Each round asks OCI's capacity report for the configured shape (default 2 OCPU / 12 GB), then for FALLBACK_SHAPE
# (default "1 6", which still runs the full profile; resize in place later, see README), and tries every domain
# that reports room. Every BLIND_EVERY-th round with no room reported it tries a real launch anyway, because the
# report is a snapshot and can lag. A failed launch creates nothing billable. Capacity, rate limits and network
# errors mean "wait again"; only errors that waiting cannot fix stop it.
# Once the VM is in the state, its domain and shape are written into terraform.tfvars, so later applies keep the VM
# as created. ssh_allowed_cidr is refreshed before each attempt, in case your public IP changed while it waited.
#
# Usage: infra/oci/wait-and-apply.sh     WAIT_SECONDS (default 120), BLIND_EVERY (default 10, 0 = never),
#        FALLBACK_SHAPE (default "1 6", "" = none)
# Stop it (Git Bash): kill "$(cat infra/oci/.wait-and-apply.lock/pid)"   It finishes a running terraform command first.
set -euo pipefail

# Errors that waiting will not fix: bad credentials or configuration, or a compartment quota. (LimitExceeded is
# checked separately: OCI is known to return it now and then for A1 launches in tenancies with nothing running.)
PERMANENT_ERRORS='QuotaExceeded|NotAuthenticated|NotAuthorizedOrNotFound|InvalidParameter|CannotParseRequest|Error: Invalid|Unsupported argument|Missing required argument|Reference to undeclared|validation rule'

stamp() { date -u +%H:%M:%SZ; }

take_lock() {
  lock=.wait-and-apply.lock
  if ! mkdir "$lock" 2> /dev/null; then
    local other="" tries
    # A copy that has just made the lock writes its pid a moment later, so an empty pid is not yet "stale".
    for tries in 1 2 3 4 5; do
      other="$(cat "$lock/pid" 2> /dev/null || true)"
      [ -z "$other" ] || break
      sleep 1
    done
    if [ -z "$other" ] || kill -0 "$other" 2> /dev/null; then
      echo "Another wait-and-apply.sh is running (pid ${other:-starting}). Stop it first: kill ${other:-<its pid>}"
      exit 1
    fi
    [ "$(cat "$lock/pid" 2> /dev/null || true)" = "$other" ] || { echo "Another copy took the lock; exiting"; exit 1; }
    echo "Removing a stale lock left by pid $other"
    rm -rf "$lock"
    mkdir "$lock"
  fi
  echo "$$" > "$lock/pid"
  [ ! -r "/proc/$$/winpid" ] || cat "/proc/$$/winpid" > "$lock/winpid"
  trap 'rm -rf "$lock"' EXIT
  trap 'on_signal 143' TERM
  trap 'on_signal 130' INT
}

# A stop request is handled once the running command finishes. If that was the apply that made the VM, record it in
# terraform.tfvars first, or a later apply with the default domain would plan to replace it.
on_signal() {
  trap - TERM INT
  if vm_in_state; then
    [ ! -f apply.log ] || cat apply.log
    record_vm_in_tfvars || not_recorded
    echo "Stopped after the VM was created. Deploy it with ./deploy.sh"
  fi
  exit "$1"
}

not_recorded() {
  echo "Stopping: the VM exists but could not be recorded in terraform.tfvars. Before any terraform apply, set"
  echo "availability_domain_number, ocpus and memory_in_gbs there from 'terraform state show oci_core_instance.ledger'."
  exit 1
}

set_var() { # set_var NAME VALUE (VALUE as HCL): replace the line in terraform.tfvars, or add it
  { grep -v "^$1 *=" terraform.tfvars || true; } > terraform.tfvars.tmp \
    && printf '%s = %s\n' "$1" "$2" >> terraform.tfvars.tmp \
    && mv terraform.tfvars.tmp terraform.tfvars
}
tfvar() { sed -n "s/^$1 *= *\"\{0,1\}\([^\" #]*\).*/\1/p" terraform.tfvars | head -1 | tr -d '\r'; }

vm_in_state() {
  local resources
  resources="$(terraform state list 2> /dev/null || true)"
  grep -q '^oci_core_instance\.ledger$' <<< "$resources"
}

# Writes the domain and shape the VM really has (from the state) into terraform.tfvars. Returns 1 if it cannot.
# Callers check it explicitly: functions called from an if or || run without set -e.
record_vm_in_tfvars() {
  local shown domain ocpus memory
  shown="$(terraform state show -no-color oci_core_instance.ledger)" || return 1
  domain="$(sed -n 's/^ *availability_domain *= *".*-AD-\([123]\)"$/\1/p' <<< "$shown" | head -1)"
  ocpus="$(sed -n 's/^ *ocpus *= *\([0-9]*\)\(\.0*\)\{0,1\}$/\1/p' <<< "$shown" | head -1)"
  memory="$(sed -n 's/^ *memory_in_gbs *= *\([0-9]*\)\(\.0*\)\{0,1\}$/\1/p' <<< "$shown" | head -1)"
  [ -n "$domain" ] && [ -n "$ocpus" ] && [ -n "$memory" ] || return 1
  set_var availability_domain_number "$domain" && set_var ocpus "$ocpus" && set_var memory_in_gbs "$memory" || return 1
  echo "terraform.tfvars records the VM: domain $domain, $ocpus OCPU / $memory GB"
}

limit_value() { # limit_value SERVICE LIMIT FIELD: a region-wide usage counter for the whole tenancy
  local value
  if ! value="$("${OCI_CLI:-oci}" limits resource-availability get --compartment-id "$(tfvar tenancy_ocid)" \
    --service-name "$1" --limit-name "$2" --query "data.$3" --raw-output \
    --profile "$OCI_PROFILE" ${OCI_REGION:+--region "$OCI_REGION"} 2> "$lock/oci.err")"; then
    if grep -Eq 'NotAuthenticated|NotAuthorizedOrNotFound|ConfigFileNotFound|ProfileNotFound' "$lock/oci.err"; then
      cat "$lock/oci.err"
      echo "Stopping: the OCI CLI cannot read this tenancy with profile $OCI_PROFILE (see above)."
      exit 1
    fi
    return 1
  fi
  value="$(tr -d '\r\n ' <<< "$value")"
  [[ "$value" =~ ^[0-9]+(\.[0-9]+)?$ ]] || return 1
  echo "${value%%.*}"
}

# The free A1 and block storage allowances are per tenancy. OCI's region-wide usage counters see every compartment,
# so before a launch: any A1 OCPU already in use (an earlier VM terraform lost track of, one made in the console, in
# any compartment), or less free storage left than the boot volume needs, and a new VM could go past what is free.
# Returns 1 if OCI could not be asked.
check_free_allowance() {
  local cores storage want_gb
  want_gb="$(tfvar boot_volume_size_in_gbs)"
  want_gb="${want_gb:-100}"
  if ! cores="$(limit_value compute standard-a1-core-regional-count used)" \
    || ! storage="$(limit_value block-storage total-free-storage-gb-regional available)"; then
    echo "$(stamp)  Could not read the tenancy's usage from OCI; skipping this attempt"
    return 1
  fi
  if [ "$cores" -ne 0 ]; then
    echo "Stopping: $cores A1 OCPUs are already in use in this tenancy, outside this Terraform state."
    echo "A second VM could go past the free 2 OCPUs / 12 GB. Remove the other one, or import it, then start again."
    exit 1
  fi
  if [ "$storage" -lt "$want_gb" ]; then
    echo "Stopping: $storage GB of the free 200 GB block storage is left, and the boot volume needs $want_gb GB."
    exit 1
  fi
}

# Checks the plan just made, by Terraform's own summary line ("Plan: 1 to add, 0 to change, 0 to destroy."), which
# counts resource changes only: a replace counts as a destroy, outputs and drift do not count. 0: nothing would be
# destroyed or forgotten. 1: something would. 2: no summary line, so the plan is not understood and not applied.
plan_check() {
  local summary
  summary="$(grep -E '^Plan: ' apply.log | tail -n 1)"
  [ -n "$summary" ] || return 2
  [[ "$summary" =~ \ 0\ to\ destroy\.$ ]] || return 1
}

# launch DOMAIN OCPUS MEMORY: one plan + apply. Returns 0 if the VM now exists, 1 to wait again; exits on errors
# that waiting cannot fix, and on any plan that would delete or replace something. It runs from an if, so without
# set -e: every step that matters is checked explicitly.
launch() {
  local domain="$1" want_ocpus="$2" want_memory="$3" ip status=0
  check_free_allowance || return 1
  ip="$(curl -fsS --max-time 10 https://checkip.amazonaws.com | tr -d '[:space:]' || true)"
  [ -z "$ip" ] || set_var ssh_allowed_cidr "\"$ip/32\""
  echo "$(stamp)  Trying $want_ocpus OCPU / $want_memory GB in domain $domain"

  : > apply.log
  terraform plan -input=false -no-color -lock-timeout=5m -out=phase8.tfplan -var "availability_domain_number=$domain" \
    -var "ocpus=$want_ocpus" -var "memory_in_gbs=$want_memory" >> apply.log 2>&1 || status=$?
  if [ "$status" -eq 0 ]; then
    local check=0
    plan_check || check=$?
    if [ "$check" -eq 1 ]; then
      cat apply.log
      echo "Stopping: this plan would delete or replace something, and this script never applies that."
      exit 1
    fi
    if [ "$check" -eq 2 ]; then
      cat apply.log
      echo "$(stamp)  Could not read the plan, so it was not applied; waiting again"
      return 1
    fi
    terraform apply -input=false -no-color -lock-timeout=5m phase8.tfplan >> apply.log 2>&1 || status=$?
  fi
  cat apply.log

  if vm_in_state; then
    record_vm_in_tfvars || not_recorded
    if [ "$status" -ne 0 ]; then
      echo "Stopping: the VM was created but terraform reported the error above. Check 'terraform plan', then ./deploy.sh."
      exit 1
    fi
    echo "$(stamp)  VM created: $want_ocpus OCPU / $want_memory GB in domain $domain"
    return 0
  fi
  [ "$status" -ne 0 ] || { echo "Stopping: terraform apply succeeded but the VM is not in the state."; exit 1; }
  if grep -q 'LimitExceeded' apply.log; then
    local available
    if available="$(limit_value compute standard-a1-core-regional-count available)" \
      && [ "$available" -lt "$want_ocpus" ]; then
      echo "$(stamp)  Stopping: the tenancy's A1 limit leaves $available OCPUs, and the VM needs $want_ocpus."
      exit 1
    fi
    echo "$(stamp)  LimitExceeded although the A1 limit has room (OCI does this now and then); waiting again"
    return 1
  fi
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
  # The same credentials and region as the Terraform provider, for every OCI CLI call (capacity-check.sh too).
  OCI_PROFILE="$(tfvar oci_profile)"
  export OCI_PROFILE="${OCI_PROFILE:-DEFAULT}"
  OCI_REGION="$(tfvar region)"
  export OCI_REGION

  local ocpus memory primary fallback blind_every round=0 created=""
  ocpus="$(tfvar ocpus)" memory="$(tfvar memory_in_gbs)"
  primary="${ocpus:-2} ${memory:-12}"
  fallback="${FALLBACK_SHAPE-1 6}"
  [ "$fallback" != "$primary" ] || fallback=""
  blind_every="${BLIND_EVERY:-10}"
  [[ "$blind_every" =~ ^[0-9]+$ ]] || { echo "BLIND_EVERY must be a whole number (0 = never)"; exit 1; }

  while true; do
    if vm_in_state; then
      record_vm_in_tfvars || not_recorded
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
    if [ -z "$candidates" ] && [ "$errors" -eq 0 ] && [ "$blind_every" -gt 0 ] && [ $((round % blind_every)) -eq 0 ]; then
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
