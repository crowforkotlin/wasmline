#!/usr/bin/env bash

: "${SCRIPT_NAME:=$(basename "$0")}"

COMMON_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_ROOT="${COMMON_SCRIPT_DIR}"
REPO_ROOT="$(cd "${COMMON_SCRIPT_DIR}/../.." && pwd)"
MULTIPLATFORM_ROOT="${REPO_ROOT}/wasmline-multiplatform"
SAMPLE_PLUGIN_ROOT="${SAMPLE_ROOT}/sample-plugin"
SAMPLE_PLUGIN_OUTPUT_ROOT="${SAMPLE_PLUGIN_ROOT}/build/wasmline/output"

PLATFORM=""
ANDROID_DEVICE=""
ARTIFACT_FORMAT=""
ENGINE=""
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

run_gradle_with_runtime_format() {
    local directory="$1"
    shift

    (
        case "$ARTIFACT_FORMAT" in
            pwasm|pwasm64|pwasm32)
                export WASMLINE_ARTIFACT_FORMAT="pwasm"
                ;;
            *)
                if [ -n "$ARTIFACT_FORMAT" ]; then
                    export WASMLINE_ARTIFACT_FORMAT="$ARTIFACT_FORMAT"
                else
                    unset WASMLINE_ARTIFACT_FORMAT || true
                fi
                ;;
        esac
        if [ -n "$ENGINE" ]; then
            run_gradle "$directory" "-Pwasmline.engine=$ENGINE" "$@"
        else
            run_gradle "$directory" "$@"
        fi
    )
}

# Like run_gradle, but redirects output to /dev/null when QUIET=1.
# Use this for build/publish steps where output is noise, not results.
run_gradle_build() {
    if [ "$QUIET" -eq 1 ]; then
        run_gradle "$@" >/dev/null 2>&1
    else
        run_gradle "$@" >&2
    fi
}

copy_artifact() {
    local source_file="$1"
    local target_file="$2"

    mkdir -p "$(dirname "$target_file")"
    rm -f "$target_file"
    cp "$source_file" "$target_file"
}

normalize_platform() {
    case "$1" in
        x86_64-linux|aarch64-linux|aarch64-macos|x86_64-macos|x86_64-windows)
            printf '%s\n' "$1"
            ;;
        mac)
            printf '%s\n' "aarch64-macos"
            ;;
        windows)
            printf '%s\n' "x86_64-windows"
            ;;
        *)
            echo "Unsupported platform: $1" >&2
            echo "Run ./${SCRIPT_NAME} --help for usage." >&2
            exit 1
            ;;
    esac
}

normalize_artifact_format() {
    case "$1" in
        pwasm|pwasm64)
            printf '%s\n' "pwasm64"
            ;;
        pwasm32)
            printf '%s\n' "pwasm32"
            ;;
        cwasm)
            printf '%s\n' "$1"
            ;;
        *)
            echo "Unsupported artifact format: $1" >&2
            echo "Run ./${SCRIPT_NAME} --help for usage." >&2
            exit 1
            ;;
    esac
}

detect_current_platform() {
    local os_name
    local arch_name

    os_name="$(uname -s)"
    arch_name="$(uname -m)"

    case "$os_name" in
        Darwin)
            if [ "$arch_name" = "x86_64" ] && command -v sysctl >/dev/null 2>&1; then
                if [ "$(sysctl -in hw.optional.arm64 2>/dev/null || true)" = "1" ]; then
                    arch_name="arm64"
                fi
            fi
            case "$arch_name" in
                arm64|aarch64)
                    printf '%s\n' "aarch64-macos"
                    ;;
                x86_64|amd64)
                    printf '%s\n' "x86_64-macos"
                    ;;
                *)
                    echo "Unsupported macOS architecture: $arch_name" >&2
                    exit 1
                    ;;
            esac
            ;;
        Linux)
            case "$arch_name" in
                arm64|aarch64)
                    printf '%s\n' "aarch64-linux"
                    ;;
                x86_64|amd64)
                    printf '%s\n' "x86_64-linux"
                    ;;
                *)
                    echo "Unsupported Linux architecture: $arch_name" >&2
                    exit 1
                    ;;
            esac
            ;;
        CYGWIN*|MINGW*|MSYS*|Windows_NT)
            case "$arch_name" in
                x86_64|amd64)
                    printf '%s\n' "x86_64-windows"
                    ;;
                *)
                    echo "Unsupported Windows architecture: $arch_name" >&2
                    exit 1
                    ;;
            esac
            ;;
        *)
            echo "Unsupported OS: $os_name" >&2
            exit 1
            ;;
    esac
}

parse_common_args() {
    local allow_platform="$1"
    local allow_device="$2"
    local allow_artifact_format="$3"
    shift 3

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --platform)
                if [ "$allow_platform" -ne 1 ]; then
                    echo "${SCRIPT_NAME} does not accept --platform" >&2
                    exit 1
                fi
                require_value "$1" "${2:-}"
                PLATFORM="$(normalize_platform "$2")"
                shift 2
                ;;
            --platform=*)
                if [ "$allow_platform" -ne 1 ]; then
                    echo "${SCRIPT_NAME} does not accept --platform" >&2
                    exit 1
                fi
                require_value "--platform" "${1#*=}"
                PLATFORM="$(normalize_platform "${1#*=}")"
                shift
                ;;
            --device)
                if [ "$allow_device" -ne 1 ]; then
                    echo "${SCRIPT_NAME} does not accept --device" >&2
                    exit 1
                fi
                require_value "$1" "${2:-}"
                ANDROID_DEVICE="$2"
                shift 2
                ;;
            --device=*)
                if [ "$allow_device" -ne 1 ]; then
                    echo "${SCRIPT_NAME} does not accept --device" >&2
                    exit 1
                fi
                require_value "--device" "${1#*=}"
                ANDROID_DEVICE="${1#*=}"
                shift
                ;;
            -f|--format|--artifact-format)
                if [ "$allow_artifact_format" -ne 1 ]; then
                    echo "${SCRIPT_NAME} does not accept $1" >&2
                    exit 1
                fi
                require_value "$1" "${2:-}"
                ARTIFACT_FORMAT="$(normalize_artifact_format "$2")"
                shift 2
                ;;
            --format=*|--artifact-format=*)
                if [ "$allow_artifact_format" -ne 1 ]; then
                    echo "${SCRIPT_NAME} does not accept ${1%%=*}" >&2
                    exit 1
                fi
                require_value "${1%%=*}" "${1#*=}"
                ARTIFACT_FORMAT="$(normalize_artifact_format "${1#*=}")"
                shift
                ;;
            -e|--engine)
                require_value "$1" "${2:-}"
                case "$2" in
                    pulley|cranelift) ENGINE="$2" ;;
                    *)
                        echo "Unsupported engine: $2 (expected: pulley or cranelift)" >&2
                        exit 1
                        ;;
                esac
                shift 2
                ;;
            --engine=*)
                require_value "--engine" "${1#*=}"
                case "${1#*=}" in
                    pulley|cranelift) ENGINE="${1#*=}" ;;
                    *)
                        echo "Unsupported engine: ${1#*=} (expected: pulley or cranelift)" >&2
                        exit 1
                        ;;
                esac
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

load_wasmline_metadata() {
    require_directory "$MULTIPLATFORM_ROOT" "wasmline-multiplatform root"
    require_directory "$SAMPLE_PLUGIN_ROOT" "sample-plugin module"
}

find_optional_file() {
    local directory="$1"
    local pattern="$2"
    local maxdepth="${3:-1}"
    local file

    file="$(find "$directory" -maxdepth "$maxdepth" -type f -name "$pattern" | sort | head -n 1)"
    printf '%s\n' "$file"
}

RUNTIME_PWASM_FILE=""
RUNTIME_CWASM_FILE=""

assemble_sample_plugin() {
    local cwasm_target="$1"
    local artifact_description="$2"
    local gradle_args=(
        :sample-plugin:wasmlineAssembleDebug
        "-Pwasmline.compile.target=${cwasm_target}"
    )
    local manifest_file

    if [ -z "$cwasm_target" ]; then
        echo "Missing cwasm target for ${artifact_description}" >&2
        exit 1
    fi
    if [ -n "$ARTIFACT_FORMAT" ]; then
        gradle_args+=("-Pwasmline.artifact.format=${ARTIFACT_FORMAT}")
    fi

    rm -rf "$SAMPLE_PLUGIN_OUTPUT_ROOT"
    run_gradle_build "$SAMPLE_ROOT" "${gradle_args[@]}"

    manifest_file="$(find "$SAMPLE_PLUGIN_OUTPUT_ROOT" -mindepth 2 -maxdepth 2 -type f -name manifest.wlm | sort | head -n 1)"
    if [ -z "$manifest_file" ]; then
        echo "Unable to locate assembled ${artifact_description} under ${SAMPLE_PLUGIN_OUTPUT_ROOT}" >&2
        exit 1
    fi
    PLUGIN_ASSEMBLY_ROOT="$(dirname "$manifest_file")"
}

find_plugin_artifact() {
    local pattern="$1"
    local description="$2"
    local file

    file="$(find_optional_file "$PLUGIN_ASSEMBLY_ROOT" "$pattern")"
    if [ -z "$file" ]; then
        echo "Unable to locate ${description} under ${PLUGIN_ASSEMBLY_ROOT}" >&2
        exit 1
    fi
    printf '%s\n' "$file"
}

build_plugin_wasm() {
    local artifact_description="$1"

    assemble_sample_plugin "$(detect_current_platform)" "$artifact_description"
    find_plugin_artifact "*.wasm" "${artifact_description} wasm"
}

build_plugin_runtime_artifacts() {
    local cwasm_target="$1"
    local artifact_description="$2"

    RUNTIME_PWASM_FILE=""
    RUNTIME_CWASM_FILE=""
    assemble_sample_plugin "$cwasm_target" "$artifact_description"

    case "$ARTIFACT_FORMAT" in
        pwasm32)
            RUNTIME_PWASM_FILE="$(find_plugin_artifact '*-pulley32.pwasm' "${artifact_description} pwasm32 artifact")"
            ;;
        pwasm|pwasm64)
            RUNTIME_PWASM_FILE="$(find_plugin_artifact '*-pulley64.pwasm' "${artifact_description} pwasm64 artifact")"
            ;;
        cwasm)
            RUNTIME_CWASM_FILE="$(find_plugin_artifact "*-${cwasm_target}.cwasm" "${artifact_description} cwasm artifact")"
            ;;
        *)
            RUNTIME_PWASM_FILE="$(find_plugin_artifact '*-pulley64.pwasm' "${artifact_description} pwasm64 artifact")"
            RUNTIME_CWASM_FILE="$(find_plugin_artifact "*-${cwasm_target}.cwasm" "${artifact_description} cwasm artifact")"
            ;;
    esac
}

sync_runtime_artifacts() {
    local target_dir="$1"
    local target_base="$2"
    shift 2
    local cleanup_bases=("$target_base" "$@")
    local cleanup_base

    mkdir -p "$target_dir"
    for cleanup_base in "${cleanup_bases[@]}"; do
        rm -f "$target_dir/${cleanup_base}.pwasm" "$target_dir/${cleanup_base}.cwasm"
    done

    case "$ARTIFACT_FORMAT" in
        pwasm|pwasm64|pwasm32)
            cp "$RUNTIME_PWASM_FILE" "$target_dir/${target_base}.pwasm"
            ;;
        cwasm)
            cp "$RUNTIME_CWASM_FILE" "$target_dir/${target_base}.cwasm"
            ;;
        *)
            cp "$RUNTIME_PWASM_FILE" "$target_dir/${target_base}.pwasm"
            if [ -n "$RUNTIME_CWASM_FILE" ]; then
                cp "$RUNTIME_CWASM_FILE" "$target_dir/${target_base}.cwasm"
            fi
            ;;
    esac
}

adb_command() {
    if [ -n "$ANDROID_DEVICE" ]; then
        adb -s "$ANDROID_DEVICE" "$@"
    else
        adb "$@"
    fi
}

ensure_android_device() {
    require_command adb

    local devices
    devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    if [ -z "$devices" ]; then
        echo "No Android device/emulator is connected." >&2
        exit 1
    fi

    if [ -n "$ANDROID_DEVICE" ] && ! printf '%s\n' "$devices" | grep -Fxq "$ANDROID_DEVICE"; then
        echo "Android device not available: $ANDROID_DEVICE" >&2
        exit 1
    fi
}

launch_android_activity() {
    local component="$1"
    adb_command shell am start -n "$component" >/dev/null
}
