#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_ROOT="${SCRIPT_DIR}"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MULTIPLATFORM_ROOT="${REPO_ROOT}/wasmline-multiplatform"
WASMLINE_MODULE_ROOT="${MULTIPLATFORM_ROOT}/wasmline"
WASMLINE_CLI_ROOT="${MULTIPLATFORM_ROOT}/wasmline-cli"
SAMPLE_PLUGIN_ROOT="${SAMPLE_ROOT}/sample-plugin"
APPLICATION_RESOURCE_DIR="${SAMPLE_ROOT}/sample-apps/application/src/main/resources"
COMPILE_OUTPUT_ROOT="${SAMPLE_ROOT}/build/application-output"
SHARED_WASMTIME_ROOT="${REPO_ROOT}/build/wasmline/wasmtime"
PLATFORM=""
WASMTIME_VERSION=""
WASMLINE_VERSION=""

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--platform VALUE]

Build and run the application sample.

Options:
  --platform VALUE   Wasmtime target used by wasmline-cli compile.
                     Supported values:
                       Linux:   x86_64-linux, aarch64-linux
                       macOS:   aarch64-macos, x86_64-macos
                       Windows: x86_64-windows
                     If omitted, auto-detect the current platform.
  -h, --help         Show this help and exit

Sample:
  ./${SCRIPT_NAME} --platform x86_64-linux
  ./${SCRIPT_NAME} --platform aarch64-linux
  ./${SCRIPT_NAME} --platform aarch64-macos
  ./${SCRIPT_NAME} --platform x86_64-macos
  ./${SCRIPT_NAME} --platform x86_64-windows
EOF
}

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

read_gradle_property() {
    local file="$1"
    local key="$2"

    awk -F= -v key="$key" '
        {
            name = $1
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", name)
            if (name == key) {
                value = substr($0, index($0, "=") + 1)
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
                print value
                exit
            }
        }
    ' "$file"
}

render_args() {
    local result=""
    local quoted
    local token

    for token in "$@"; do
        printf -v quoted '%q' "$token"
        if [ -n "$result" ]; then
            result+=" "
        fi
        result+="$quoted"
    done

    printf '%s\n' "$result"
}

run_gradle() {
    local directory="$1"
    shift
    (
        cd "$directory"
        ./gradlew "$@"
    )
}

find_first_file() {
    local directory="$1"
    local pattern="$2"
    local description="$3"
    local maxdepth="${4:-1}"
    local file

    file="$(find "$directory" -maxdepth "$maxdepth" -type f -name "$pattern" | sort | head -n 1)"
    if [ -z "$file" ]; then
        echo "Unable to locate ${description} under ${directory}" >&2
        exit 1
    fi

    printf '%s\n' "$file"
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

parse_args() {
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
            -h|--help)
                print_help
                exit 0
                ;;
            *)
                echo "Unknown option: $1" >&2
                echo "Run ./${SCRIPT_NAME} --help for usage." >&2
                exit 1
                ;;
        esac
    done
}

resolve_wasmtime_dir() {
    local platform="$1"
    local wasmtime_dir_name="wasmtime-v${WASMTIME_VERSION}-${platform}"
    local candidate="${SHARED_WASMTIME_ROOT}/${wasmtime_dir_name}"
    if [ -d "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
    fi
    return 1
}

find_wasmtime_executable() {
    local directory="$1"

    if [[ "$(uname -s)" == CYGWIN* || "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == Windows_NT ]]; then
        find "$directory" -type f \( -name 'wasmtime.exe' -o -name 'wasmtime-min.exe' \) | sort | head -n 1
    else
        find "$directory" -type f \( -name 'wasmtime' -o -name 'wasmtime-min' \) | sort | head -n 1
    fi
}

ensure_wasmtime_toolchain() {
    local download_args

    download_args="$(render_args \
        download \
        -v "v${WASMTIME_VERSION}" \
        -a "${PLATFORM}" \
        -o "${SHARED_WASMTIME_ROOT}" \
    )"
    run_gradle "$MULTIPLATFORM_ROOT" :wasmline-cli:run --args="$download_args"
}

require_directory "$MULTIPLATFORM_ROOT" "wasmline-multiplatform root"
require_directory "$WASMLINE_MODULE_ROOT" "wasmline module"
require_directory "$WASMLINE_CLI_ROOT" "wasmline-cli module"
require_directory "$SAMPLE_PLUGIN_ROOT" "sample-plugin module"

WASMTIME_VERSION="$(read_gradle_property "${MULTIPLATFORM_ROOT}/gradle.properties" "wasmtime.version")"
WASMLINE_VERSION="$(read_gradle_property "${MULTIPLATFORM_ROOT}/gradle.properties" "wasmline.version")"

if [ -z "$WASMTIME_VERSION" ] || [ -z "$WASMLINE_VERSION" ]; then
    echo "Unable to read wasmline version metadata from ${MULTIPLATFORM_ROOT}/gradle.properties" >&2
    exit 1
fi

parse_args "$@"

if [ -z "$PLATFORM" ]; then
    PLATFORM="$(detect_current_platform)"
fi

WASMTIME_DIR="$(resolve_wasmtime_dir "$PLATFORM" || true)"
WASMTIME_EXECUTABLE=""
if [ -n "$WASMTIME_DIR" ]; then
    WASMTIME_EXECUTABLE="$(find_wasmtime_executable "$WASMTIME_DIR")"
fi
if [ -z "$WASMTIME_EXECUTABLE" ]; then
    ensure_wasmtime_toolchain
    WASMTIME_DIR="$(resolve_wasmtime_dir "$PLATFORM" || true)"
    if [ -n "$WASMTIME_DIR" ]; then
        WASMTIME_EXECUTABLE="$(find_wasmtime_executable "$WASMTIME_DIR")"
    fi
fi
if [ -z "$WASMTIME_EXECUTABLE" ] || [ -z "$WASMTIME_DIR" ]; then
    echo "Wasmtime executable not found in shared toolchain cache for ${PLATFORM}; application sample cannot compile .pwasm yet." >&2
    echo "Tried cache root: ${SHARED_WASMTIME_ROOT}" >&2
    exit 1
fi

run_gradle "$MULTIPLATFORM_ROOT" :wasmline-kotlin-plugin:publishToMavenLocal :wasmline-gradle-plugin:publishToMavenLocal

(cd "$WASMLINE_MODULE_ROOT" && zig build --release=small -p src/jvmMain/resources)
run_gradle "$MULTIPLATFORM_ROOT" :wasmline:publishToMavenLocal :wasmline-loader:publishToMavenLocal

rm -rf "${WASMLINE_MODULE_ROOT}/build/kotlin/compileKotlinWasmWasi"
rm -rf "${WASMLINE_MODULE_ROOT}/build/classes/kotlin/wasmWasi"
rm -rf "${SAMPLE_PLUGIN_ROOT}/build"
rm -rf "${COMPILE_OUTPUT_ROOT}"

run_gradle "$SAMPLE_ROOT" :sample-plugin:compileProductionLibraryKotlinWasmWasiOptimize

PLUGIN_WASM_INPUT_DIR="${SAMPLE_PLUGIN_ROOT}/build/compileSync/wasmWasi/main/productionLibrary/optimized"
PLUGIN_WASM_INPUT_FILE="$(find_first_file "$PLUGIN_WASM_INPUT_DIR" "*.wasm" "sample plugin wasm input")"

mkdir -p "$APPLICATION_RESOURCE_DIR"
rm -f "$APPLICATION_RESOURCE_DIR/plugin.generated.pwasm"

CLI_COMPILE_ARGS="$(render_args \
    compile \
    -i "$PLUGIN_WASM_INPUT_FILE" \
    -o "$COMPILE_OUTPUT_ROOT" \
    -v "$WASMLINE_VERSION" \
    -wt "$WASMTIME_DIR" \
    -a pulley64
)"

run_gradle "$MULTIPLATFORM_ROOT" :wasmline-cli:run --args="$CLI_COMPILE_ARGS"
PLUGIN_OUTPUT_FILE="$(find_first_file "$COMPILE_OUTPUT_ROOT" "*-pulley64.pwasm" "application plugin artifact" 2)"
cp "$PLUGIN_OUTPUT_FILE" "$APPLICATION_RESOURCE_DIR/plugin.generated.pwasm"
run_gradle "$SAMPLE_ROOT" :sample-apps:application:run
