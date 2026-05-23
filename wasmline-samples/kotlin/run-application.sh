#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/run-sample-common.sh"

APPLICATION_RESOURCE_FILE="${SAMPLE_ROOT}/sample-apps/application/src/main/resources/plugin.generated.pwasm"
COMPILE_OUTPUT_ROOT="${SAMPLE_ROOT}/build/application-output"

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--platform VALUE] [-q]

Build and run the application sample.

Options:
  --platform VALUE   Wasmtime host toolchain used by wasmline-cli compile.
                     Supported values:
                       Linux:   x86_64-linux, aarch64-linux
                       macOS:   aarch64-macos, x86_64-macos
                       Windows: x86_64-windows
                     If omitted, auto-detect the current platform.
  -q, --quiet        Suppress build output; only show program results.
  -h, --help         Show this help and exit
EOF
}

load_wasmline_metadata
parse_common_args 1 0 "$@"
WASMTIME_DIR="$(ensure_wasmtime_toolchain)"
publish_local_artifacts 1
PLUGIN_OUTPUT_FILE="$(build_plugin_pwasm "$COMPILE_OUTPUT_ROOT" "$WASMTIME_DIR" "application plugin artifact")"
copy_artifact "$PLUGIN_OUTPUT_FILE" "$APPLICATION_RESOURCE_FILE"
run_gradle "$SAMPLE_ROOT" :sample-apps:application:run
