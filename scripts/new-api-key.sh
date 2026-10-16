#!/usr/bin/env bash
# Prints a fresh random API key and the matching API_KEY_HASHES entry for .env.
set -euo pipefail

client="${1:-demo}"
key="pls_${client}_$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
hash="$(printf '%s' "$key" | sha256sum | cut -d' ' -f1)"

echo "DEMO_API_KEY=${key}"
echo "API_KEY_HASHES=${client}:${hash}"
