#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/run-sample-common.sh"

ANDROID_RESOURCE_DIR="${SAMPLE_ROOT}/sample-apps/multiplatform/androidApp/src/androidMain/assets"
COMPILE_OUTPUT_ROOT="${SAMPLE_ROOT}/build/android-compose-output"
ANDROID_COMPONENT="crow.wasmline/crow.wasmline.sample.MainActivity"

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--platform VALUE] [--device SERIAL] [-f pwasm|cwasm] [-q]

Build and run the Compose Android sample on a connected device or emulator.

Options:
  --platform VALUE   Wasmtime host toolchain used by wasmline-cli compile.
                     Supported values:
                       Linux:   x86_64-linux, aarch64-linux
                       macOS:   aarch64-macos, x86_64-macos
                       Windows: x86_64-windows
                     If omitted, auto-detect the current platform.
  --device SERIAL    Optional adb device serial. Uses the default connected device when omitted.
  -f, --format VALUE Runtime artifact format.
                     Supported values: pwasm, cwasm.
                     cwasm builds the Android arm64 runtime artifact.
                     If omitted, copies both plugin.pwasm and plugin.cwasm.
                     When omitted, runtime prefers plugin.cwasm.
  --artifact-format VALUE
                     Backward-compatible alias for --format.
  -q, --quiet        Suppress build output.
  -h, --help         Show this help and exit
EOF
}

load_wasmline_metadata
parse_common_args 1 1 1 "$@"
WASMTIME_DIR="$(ensure_wasmtime_toolchain)"
publish_local_artifacts 0
build_plugin_runtime_artifacts "$COMPILE_OUTPUT_ROOT" "$WASMTIME_DIR" "android compose plugin artifact" "aarch64-linux-android"
sync_runtime_artifacts "$ANDROID_RESOURCE_DIR" "plugin"
ensure_android_device
run_gradle "$SAMPLE_ROOT" :sample-apps:multiplatform:androidApp:installDebug
launch_android_activity "$ANDROID_COMPONENT"
