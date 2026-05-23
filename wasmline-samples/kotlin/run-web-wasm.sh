#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/run-sample-common.sh"

WEB_RESOURCE_FILE="${SAMPLE_ROOT}/sample-apps/multiplatform/webApp/src/webMain/resources/plugin/wasmline-sample-sample-plugin.wasm"

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [-q]

Build the sample plugin wasm, copy it into the web resources directory, and start the Wasm browser dev server.

Options:
  -q, --quiet   Suppress build output.
  -h, --help    Show this help and exit
EOF
}

load_wasmline_metadata
parse_common_args 0 0 "$@"
publish_local_artifacts 0
PLUGIN_WASM_FILE="$(build_plugin_raw_wasm)"
copy_artifact "$PLUGIN_WASM_FILE" "$WEB_RESOURCE_FILE"
run_gradle "$SAMPLE_ROOT" :sample-apps:multiplatform:webApp:wasmJsBrowserDevelopmentRun
