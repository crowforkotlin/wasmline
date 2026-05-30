#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/run-sample-common.sh"

APPLICATION_RESOURCE_DIR="${SAMPLE_ROOT}/sample-apps/application/src/main/resources"
COMPILE_OUTPUT_ROOT="${SAMPLE_ROOT}/build/application-output"

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--platform VALUE] [-f pwasm|cwasm] [-q]

Build and run the application sample.

Options:
  --platform VALUE   Wasmtime host toolchain used by wasmline-cli compile.
                     Supported values:
                       Linux:   x86_64-linux, aarch64-linux
                       macOS:   aarch64-macos, x86_64-macos
                       Windows: x86_64-windows
                     If omitted, auto-detect the current platform.
  -f, --format VALUE Runtime artifact format.
                     Supported values: pwasm, cwasm.
                     If omitted, copies both plugin.pwasm and plugin.cwasm.
                     When omitted, runtime prefers plugin.cwasm.
                     When provided, the same format is selected at runtime.
  --artifact-format VALUE
                     Backward-compatible alias for --format.
  -q, --quiet        Suppress build output; only show program results.
  -h, --help         Show this help and exit
EOF
}

load_wasmline_metadata
parse_common_args 1 0 1 "$@"
if [ -z "$PLATFORM" ]; then
  PLATFORM="$(detect_current_platform)"
fi
WASMTIME_DIR="$(ensure_wasmtime_toolchain)"
publish_local_artifacts 1
build_plugin_runtime_artifacts "$COMPILE_OUTPUT_ROOT" "$WASMTIME_DIR" "application plugin artifact" "$PLATFORM"
sync_runtime_artifacts "$APPLICATION_RESOURCE_DIR" "plugin" "plugin.generated"
run_gradle_with_runtime_format "$SAMPLE_ROOT" :sample-apps:application:run
