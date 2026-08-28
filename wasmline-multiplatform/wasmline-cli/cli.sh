#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MULTIPLATFORM_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${MULTIPLATFORM_DIR}"
./gradlew :wasmline-cli:installDist

exec "${SCRIPT_DIR}/build/install/wasmline-cli/bin/wasmline-cli" "$@"
