#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/run-sample-common.sh"

ANDROID_RESOURCE_DIR="${SAMPLE_ROOT}/sample-apps/android/src/androidMain/assets"
ANDROID_COMPONENT="crow.wasmline/crow.wasmline.MainActivity"

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--device SERIAL] [-f pwasm32|pwasm64|cwasm] [-q]

Build and run the Android sample on a connected device or emulator.

Options:
  --device SERIAL    Optional adb device serial. Uses the default connected device when omitted.
  -f, --format VALUE Runtime artifact format.
                     Supported values: pwasm32, pwasm64, cwasm.
                     pwasm32 builds the pulley32 (32-bit) runtime artifact.
                     pwasm64 builds the pulley64 (64-bit) runtime artifact.
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
parse_common_args 0 1 1 "$@"
build_plugin_runtime_artifacts "aarch64-linux-android" "android plugin artifact"
sync_runtime_artifacts "$ANDROID_RESOURCE_DIR" "plugin"
ensure_android_device
run_gradle "$SAMPLE_ROOT" :sample-apps:android:installDebug
launch_android_activity "$ANDROID_COMPONENT"
