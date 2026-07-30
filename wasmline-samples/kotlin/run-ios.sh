#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/run-sample-common.sh"

IOS_APP_ROOT="${SAMPLE_ROOT}/iosApp"
IOS_PROJECT_FILE="${IOS_APP_ROOT}/iosApp.xcodeproj"
IOS_SCHEME="iosApp"
IOS_RESOURCE_FILE="${IOS_APP_ROOT}/plugin.pwasm"
COMPILE_OUTPUT_ROOT="${SAMPLE_ROOT}/build/ios-output"
IOS_DERIVED_DATA_PATH="${SAMPLE_ROOT}/build/ios-derived-data"
IOS_PRODUCT_NAME="wasmline"
IOS_BUNDLE_ID="crow.wasmline.wasmline"
IOS_DEVICE=""

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--platform VALUE] [--device NAME_OR_UDID] [-q]

Build and run the iOS simulator sample.

Options:
  --platform VALUE      Wasmtime host toolchain used by wasmline-cli compile.
                        Supported values:
                          Linux:   x86_64-linux, aarch64-linux
                          macOS:   aarch64-macos, x86_64-macos
                          Windows: x86_64-windows
                        If omitted, auto-detect the current platform.
  --device NAME_OR_UDID Optional simulator name or UDID. Uses the booted simulator
                        when available, otherwise the first available iPhone simulator.
  -q, --quiet           Suppress build output.
  -h, --help            Show this help and exit
EOF
}

parse_ios_args() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --platform)
                require_value "$1" "${2:-}"
                PLATFORM="$(normalize_platform "$2")"
                shift 2
                ;;
            --platform=*)
                require_value "--platform" "${1#*=}"
                PLATFORM="$(normalize_platform "${1#*=}")"
                shift
                ;;
            --device)
                require_value "$1" "${2:-}"
                IOS_DEVICE="$2"
                shift 2
                ;;
            --device=*)
                require_value "--device" "${1#*=}"
                IOS_DEVICE="${1#*=}"
                shift
                ;;
            -h|--help)
                print_help
                exit 0
                ;;
            -q|--quiet)
                QUIET=1
                shift
                ;;
            *)
                echo "Unknown option: $1" >&2
                echo "Run ./${SCRIPT_NAME} --help for usage." >&2
                exit 1
                ;;
        esac
    done
}

ensure_ios_prerequisites() {
    require_command xcodebuild
    require_command xcrun
    require_command open
    require_directory "$IOS_APP_ROOT" "ios sample app root"
    require_directory "$IOS_PROJECT_FILE" "ios sample Xcode project"
    # Resolve wasmtime version for pulley iOS assets
    local _wasmtime_ver
    _wasmtime_ver=$(python3 -c "import json;print('release-v'+json.load(open('${REPO_ROOT}/scripts/versions.json'))['versions']['wasmtime_version'])" 2>/dev/null || echo "release-v47.0.2")
    require_directory "${REPO_ROOT}/build/platforms/${_wasmtime_ver}/pulley/ios/simulator-arm64/include" "iOS simulator headers"
    require_directory "${REPO_ROOT}/build/platforms/${_wasmtime_ver}/pulley/ios/simulator-arm64/lib" "iOS simulator libraries"
}

select_ios_simulator() {
    local requested="${IOS_DEVICE}"

    python3 - "$requested" <<'PY'
import json
import subprocess
import sys

requested = sys.argv[1]
runtime_data = json.loads(subprocess.check_output([
    "xcrun", "simctl", "list", "runtimes", "available", "-j",
], text=True))
data = json.loads(subprocess.check_output([
    "xcrun", "simctl", "list", "devices", "available", "-j",
], text=True))

runtimes = [runtime for runtime in runtime_data.get("runtimes", []) if runtime.get("isAvailable")]
devices = []
for runtime, entries in data["devices"].items():
    if "iOS" not in runtime:
        continue
    for device in entries:
        if device.get("isAvailable"):
            devices.append(device)

def emit(device):
    print(f"{device['udid']}|{device['name']}|{device['state']}")
    raise SystemExit(0)

if requested:
    for device in devices:
        if device["udid"] == requested or device["name"] == requested:
            emit(device)
    raise SystemExit(f"No available iOS simulator matched '{requested}'.")

for device in devices:
    if device["state"] == "Booted":
        emit(device)

for device in devices:
    if "iPhone" in device["name"]:
        emit(device)

if devices:
    emit(devices[0])

if not runtimes:
    raise SystemExit(
        "No available iOS Simulator runtimes found. Install one from Xcode > Settings > Components."
    )

raise SystemExit(
    "No available iOS simulators found. Create one in Xcode's Devices and Simulators window."
)
PY
}

boot_ios_simulator() {
    local simulator_id="$1"

    xcrun simctl boot "$simulator_id" >/dev/null 2>&1 || true
    xcrun simctl bootstatus "$simulator_id" -b
    open -a Simulator --args -CurrentDeviceUDID "$simulator_id" >/dev/null 2>&1 || true
}

build_ios_frameworks() {
    run_gradle_build "$MULTIPLATFORM_ROOT" :wasmline:linkDebugFrameworkIosSimulatorArm64
    run_gradle_build "$SAMPLE_ROOT" :sample-apps:multiplatform:shared:linkDebugFrameworkIosSimulatorArm64
}

build_ios_sample() {
    local simulator_id="$1"

    rm -rf "$IOS_DERIVED_DATA_PATH"
    if [ "$QUIET" -eq 1 ]; then
        xcodebuild \
            -project "$IOS_PROJECT_FILE" \
            -scheme "$IOS_SCHEME" \
            -configuration Debug \
            -destination "id=${simulator_id}" \
            -derivedDataPath "$IOS_DERIVED_DATA_PATH" \
            build >/dev/null 2>&1
    else
        xcodebuild \
            -project "$IOS_PROJECT_FILE" \
            -scheme "$IOS_SCHEME" \
            -configuration Debug \
            -destination "id=${simulator_id}" \
            -derivedDataPath "$IOS_DERIVED_DATA_PATH" \
            build
    fi
}

install_and_launch_ios_sample() {
    local simulator_id="$1"
    local app_path="${IOS_DERIVED_DATA_PATH}/Build/Products/Debug-iphonesimulator/${IOS_PRODUCT_NAME}.app"

    if [ ! -d "$app_path" ]; then
        echo "Built iOS app not found: $app_path" >&2
        exit 1
    fi

    xcrun simctl install "$simulator_id" "$app_path"
    xcrun simctl launch --terminate-running-process "$simulator_id" "$IOS_BUNDLE_ID"
    open -a Simulator --args -CurrentDeviceUDID "$simulator_id" >/dev/null 2>&1 || true
}

load_wasmline_metadata
parse_ios_args "$@"
ensure_ios_prerequisites
WASMTIME_DIR="$(ensure_wasmtime_toolchain)"
build_ios_frameworks
PLUGIN_OUTPUT_FILE="$(build_plugin_pwasm "$COMPILE_OUTPUT_ROOT" "$WASMTIME_DIR" "ios plugin artifact")"
copy_artifact "$PLUGIN_OUTPUT_FILE" "$IOS_RESOURCE_FILE"
SIMULATOR_INFO="$(select_ios_simulator)"
SIMULATOR_ID="${SIMULATOR_INFO%%|*}"
SIMULATOR_NAME_AND_STATE="${SIMULATOR_INFO#*|}"
echo "[iOS] Using simulator: ${SIMULATOR_NAME_AND_STATE}"
boot_ios_simulator "$SIMULATOR_ID"
build_ios_sample "$SIMULATOR_ID"
install_and_launch_ios_sample "$SIMULATOR_ID"
