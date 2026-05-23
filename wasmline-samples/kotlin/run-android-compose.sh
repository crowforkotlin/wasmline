#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/run-sample-common.sh"

ANDROID_RESOURCE_FILE="${SAMPLE_ROOT}/sample-apps/multiplatform/androidApp/src/androidMain/assets/plugin.pwasm"
COMPILE_OUTPUT_ROOT="${SAMPLE_ROOT}/build/android-compose-output"
ANDROID_COMPONENT="crow.wasmline/crow.wasmline.sample.MainActivity"

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--platform VALUE] [--device SERIAL] [-q]

Build and run the Compose Android sample on a connected device or emulator.

Options:
  --platform VALUE   Wasmtime host toolchain used by wasmline-cli compile.
                     Supported values:
                       Linux:   x86_64-linux, aarch64-linux
                       macOS:   aarch64-macos, x86_64-macos
                       Windows: x86_64-windows
                     If omitted, auto-detect the current platform.
  --device SERIAL    Optional adb device serial. Uses the default connected device when omitted.
  -q, --quiet        Suppress build output.
  -h, --help         Show this help and exit
EOF
}

load_wasmline_metadata
parse_common_args 1 1 "$@"
WASMTIME_DIR="$(ensure_wasmtime_toolchain)"
publish_local_artifacts 0
PLUGIN_OUTPUT_FILE="$(build_plugin_pwasm "$COMPILE_OUTPUT_ROOT" "$WASMTIME_DIR" "android compose plugin artifact")"
copy_artifact "$PLUGIN_OUTPUT_FILE" "$ANDROID_RESOURCE_FILE"
ensure_android_device
run_gradle "$SAMPLE_ROOT" :sample-apps:multiplatform:androidApp:installDebug
launch_android_activity "$ANDROID_COMPONENT"
