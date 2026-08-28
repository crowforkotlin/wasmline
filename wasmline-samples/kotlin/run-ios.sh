#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_ROOT="${SCRIPT_DIR}"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MULTIPLATFORM_ROOT="${REPO_ROOT}/wasmline-multiplatform"
SAMPLE_PLUGIN_ROOT="${SAMPLE_ROOT}/sample-plugin"
SAMPLE_PLUGIN_OUTPUT_ROOT="${SAMPLE_PLUGIN_ROOT}/build/wasmline/output"
QUIET=0
PLUGIN_ASSEMBLY_ROOT=""

require_value() {
    local option="$1"
    local value="$2"
    if [ -z "$value" ]; then
        echo "Missing value for ${option}" >&2
        echo "Run ./${SCRIPT_NAME} --help for usage." >&2
        exit 1
    fi
}

require_directory() {
    local path="$1"
    local description="$2"
    if [ ! -d "$path" ]; then
        echo "Missing ${description}: $path" >&2
        exit 1
    fi
}

require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Missing required command: $command_name" >&2
        exit 1
    fi
}

run_gradle() {
    local directory="$1"
    shift
    (
        cd "$directory"
        if [ "$QUIET" -eq 1 ]; then
            ./gradlew --quiet "$@"
        else
            ./gradlew "$@"
        fi
    )
}

run_gradle_build() {
    if [ "$QUIET" -eq 1 ]; then
        run_gradle "$@" >/dev/null 2>&1
    else
        run_gradle "$@" >&2
    fi
}

copy_package() {
    local source_directory="$1"
    local target_directory="$2"
    rm -rf "$target_directory"
    mkdir -p "$target_directory"
    cp -R "${source_directory}/." "$target_directory"
}

load_wasmline_metadata() {
    require_directory "$MULTIPLATFORM_ROOT" "wasmline-multiplatform root"
    require_directory "$SAMPLE_PLUGIN_ROOT" "sample-plugin module"
}

assemble_sample_plugin() {
    local artifact_description="$1"
    local gradle_args=(:sample-plugin:wasmlineAssembleDebug)

    run_gradle_build "$SAMPLE_ROOT" "${gradle_args[@]}"
    local manifest_file
    manifest_file="$(find "$SAMPLE_PLUGIN_OUTPUT_ROOT" -mindepth 2 -maxdepth 2 -type f -name manifest.wlm | sort | head -n 1)"
    if [ -z "$manifest_file" ]; then
        echo "Unable to locate assembled ${artifact_description} under ${SAMPLE_PLUGIN_OUTPUT_ROOT}" >&2
        exit 1
    fi
    PLUGIN_ASSEMBLY_ROOT="$(dirname "$manifest_file")"
}

IOS_APP_ROOT="${SAMPLE_ROOT}/iosApp"
IOS_PROJECT_FILE="${IOS_APP_ROOT}/iosApp.xcodeproj"
IOS_SCHEME="iosApp"
IOS_RESOURCE_DIRECTORY="${IOS_APP_ROOT}/plugin-package"
IOS_DERIVED_DATA_PATH="${SAMPLE_ROOT}/build/ios-derived-data"
IOS_PRODUCT_NAME="wasmline"
IOS_BUNDLE_ID="crow.wasmline.wasmline"
IOS_DEVICE=""

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--device NAME_OR_UDID] [-q]

Build and run the iOS simulator sample.

Options:
  --device NAME_OR_UDID Optional simulator name or UDID. Uses the booted simulator
                        when available, otherwise the first available iPhone simulator.
  -q, --quiet           Suppress build output.
  -h, --help            Show this help and exit
EOF
}

parse_ios_args() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
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
    # Resolve the downstream release tag for Pulley iOS assets.
    local _wasmtime_ver
    _wasmtime_ver=$(python3 -c "import json;print('v'+json.load(open('${REPO_ROOT}/scripts/versions.json'))['versions']['wasmtime_release_version'])" 2>/dev/null || echo "v48.0.1.1")
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
build_ios_frameworks
assemble_sample_plugin "ios plugin package"
copy_package "$PLUGIN_ASSEMBLY_ROOT" "$IOS_RESOURCE_DIRECTORY"
SIMULATOR_INFO="$(select_ios_simulator)"
SIMULATOR_ID="${SIMULATOR_INFO%%|*}"
SIMULATOR_NAME_AND_STATE="${SIMULATOR_INFO#*|}"
echo "[iOS] Using simulator: ${SIMULATOR_NAME_AND_STATE}"
boot_ios_simulator "$SIMULATOR_ID"
build_ios_sample "$SIMULATOR_ID"
install_and_launch_ios_sample "$SIMULATOR_ID"
