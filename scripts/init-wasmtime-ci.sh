#!/bin/bash
# Non-interactive WASmtime initialization for CI
set -e

export GITHUB_TOKEN="${GITHUB_TOKEN:-}"

echo "=== Non-interactive WASmtime Init ==="
echo "Using: ${WASM_VERSION:-release-v45.0.6}, Platform: iOS Simulator"

# Call the original script with hardcoded inputs in correct order
printf "%s\n%s\n\n\n" \
    "${WASM_VERSION:-latest}" \
    "6" | bash scripts/init-wasmtime.sh
