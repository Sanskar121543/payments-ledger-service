#!/usr/bin/env bash
# Asks OCI whether the Always Free Arm shape can be placed right now, in each availability domain.
# Read-only: a capacity report creates nothing and costs nothing.
# Exits 0 if any domain has room, 2 if OCI answered and none has, 1 if OCI could not be asked for every domain.
# Usage: infra/oci/capacity-check.sh [ocpus] [memory-gb]      e.g. infra/oci/capacity-check.sh 1 6
# Wait for capacity: until infra/oci/capacity-check.sh; do sleep 600; done   (or let wait-and-apply.sh do it)
set -euo pipefail

ocpus="${1:-2}"
memory="${2:-12}"
profile="${OCI_PROFILE:-DEFAULT}"
oci_cli="${OCI_CLI:-oci}"
if ! command -v "$oci_cli" > /dev/null; then
  for candidate in "/c/Program Files (x86)/Oracle/oci_cli/oci.exe" "$HOME/bin/oci"; do
    [ -x "$candidate" ] && oci_cli="$candidate" && break
  done
fi

tenancy="$(sed -n "/^\[$profile\]/,/^\[/s/^tenancy=//p" "$HOME/.oci/config" | head -1 | tr -d '\r')"
[ -n "$tenancy" ] || { echo "No tenancy= line for profile [$profile] in ~/.oci/config"; exit 1; }

# oci.exe ends its lines with CRLF, so every answer has its CRs removed.
if ! listing="$("$oci_cli" iam availability-domain list --compartment-id "$tenancy" --profile "$profile" \
  --query 'data[].name' --output json)"; then
  echo "$(date -u +%H:%M:%SZ)  could not list availability domains (network or OCI error)"
  exit 1
fi
mapfile -t domains < <(tr -d '[]", \r' <<< "$listing" | sed '/^$/d')
[ "${#domains[@]}" -gt 0 ] || { echo "$(date -u +%H:%M:%SZ)  OCI returned no availability domains"; exit 1; }

result=2 errors=0
for i in "${!domains[@]}"; do
  if status="$("$oci_cli" compute compute-capacity-report create --compartment-id "$tenancy" \
    --availability-domain "${domains[$i]}" --profile "$profile" \
    --shape-availabilities "[{\"instanceShape\":\"VM.Standard.A1.Flex\",\"instanceShapeConfig\":{\"ocpus\":$ocpus,\"memoryInGBs\":$memory}}]" \
    --query 'data."shape-availabilities"[0]."availability-status"' --raw-output)"; then
    status="$(tr -d '\r' <<< "$status")"
  else
    status="ERROR (no answer from OCI)" errors=$((errors + 1))
  fi
  echo "$(date -u +%H:%M:%SZ)  availability_domain_number = $((i + 1))  (${domains[$i]})  ${ocpus} OCPU / ${memory} GB: $status"
  [ "$status" = "AVAILABLE" ] && result=0
done
[ "$result" -eq 0 ] || [ "$errors" -lt "${#domains[@]}" ] || result=1
exit "$result"
