#!/usr/bin/env bash
# Asks OCI whether the Always Free Arm shape can be placed right now, in each availability domain.
# Read-only: a capacity report creates nothing and costs nothing. Exits 0 if any domain has room, 2 if none.
# Usage: infra/oci/capacity-check.sh [ocpus] [memory-gb]      e.g. infra/oci/capacity-check.sh 2 12
# Wait for capacity: until infra/oci/capacity-check.sh; do sleep 600; done
set -euo pipefail

ocpus="${1:-4}"
memory="${2:-24}"
profile="${OCI_PROFILE:-DEFAULT}"
oci_cli="${OCI_CLI:-oci}"
if ! command -v "$oci_cli" > /dev/null; then
  for candidate in "/c/Program Files (x86)/Oracle/oci_cli/oci.exe" "$HOME/bin/oci"; do
    [ -x "$candidate" ] && oci_cli="$candidate" && break
  done
fi

tenancy="$(sed -n "/^\[$profile\]/,/^\[/s/^tenancy=//p" "$HOME/.oci/config" | head -1 | tr -d '\r')"
[ -n "$tenancy" ] || { echo "No tenancy= line for profile [$profile] in ~/.oci/config"; exit 1; }

mapfile -t domains < <("$oci_cli" iam availability-domain list --compartment-id "$tenancy" --profile "$profile" \
  --query 'data[].name' --output json | tr -d '[]", \r' | sed '/^$/d')

found=2
for i in "${!domains[@]}"; do
  status="$("$oci_cli" compute compute-capacity-report create --compartment-id "$tenancy" \
    --availability-domain "${domains[$i]}" --profile "$profile" \
    --shape-availabilities "[{\"instanceShape\":\"VM.Standard.A1.Flex\",\"instanceShapeConfig\":{\"ocpus\":$ocpus,\"memoryInGbs\":$memory}}]" \
    --query 'data."shape-availabilities"[0]."availability-status"' --raw-output | tr -d '\r')" # oci.exe ends lines with CRLF
  echo "$(date -u +%H:%M:%SZ)  availability_domain_number = $((i + 1))  (${domains[$i]})  ${ocpus} OCPU / ${memory} GB: $status"
  [ "$status" = "AVAILABLE" ] && found=0
done
exit "$found"
