#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/run-sample-common.sh"

DESKTOP_RESOURCE_DIR="${SAMPLE_ROOT}/sample-apps/multiplatform/desktopApp/src/main/resources"
COMPILE_OUTPUT_ROOT="${SAMPLE_ROOT}/build/desktop-output"

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--platform VALUE] [-f pwasm32|pwasm64|cwasm] [-q]

Build and run the desktop sample.

Options:
  --platform VALUE   Wasmtime host toolchain used by wasmline-cli compile.
                     Supported values:
                       Linux:   x86_64-linux, aarch64-linux
                       macOS:   aarch64-macos, x86_64-macos
                       Windows: x86_64-windows
                     If omitted, auto-detect the current platform.
  -f, --format VALUE Runtime artifact format.
                     Supported values: pwasm32, pwasm64, cwasm.
                     pwasm32 builds the pulley32 (32-bit) runtime artifact.
                     pwasm64 builds the pulley64 (64-bit) runtime artifact.
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
build_plugin_runtime_artifacts "$COMPILE_OUTPUT_ROOT" "$WASMTIME_DIR" "desktop plugin artifact" "$PLATFORM"
sync_runtime_artifacts "$DESKTOP_RESOURCE_DIR" "plugin" "plugin.generated"
run_gradle_with_runtime_format "$SAMPLE_ROOT" :sample-apps:multiplatform:desktopApp:run
