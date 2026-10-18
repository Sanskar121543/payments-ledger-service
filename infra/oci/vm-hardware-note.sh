#!/usr/bin/env bash
# Prints one line describing this Oracle Cloud VM for load-test results, from what the VM reports right now, so it
# stays right after a resize. Used on the VM by vm-deploy.sh and scripts/load-test.sh.
set -euo pipefail

shape_region="$(curl -fsS -H 'Authorization: Bearer Oracle' http://169.254.169.254/opc/v2/instance/ \
  | python3 -c 'import json, sys; m = json.load(sys.stdin); print(m["shape"] + ", " + m.get("canonicalRegionName", m["region"]))' \
  || echo "unknown shape and region")"
cpu_model="$(lscpu | sed -n 's/^Model name: *//p' | head -1)"
memory_gb="$(awk '/^MemTotal:/ { printf "%.1f", $2 / 1048576 }' /proc/meminfo)"
os_name="$(. /etc/os-release && echo "$PRETTY_NAME")"
echo "Oracle Cloud ${shape_region}, Ampere ${cpu_model} ($(nproc) cores), ${memory_gb} GB RAM, ${os_name}, Docker $(docker version --format '{{.Server.Version}}')"
