#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
WASMTIME_VERSION="43.0.2"
PLATFORM=""

print_help() {
    cat <<EOF
Usage:
  ./${SCRIPT_NAME} [--platform VALUE]

Build and run the desktop sample.

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

parse_args "$@"

if [ -z "$PLATFORM" ]; then
    PLATFORM="$(detect_current_platform)"
fi

WASMTIME_DIR="build/wasmline/wasmtime/wasmtime-v${WASMTIME_VERSION}-${PLATFORM}"

./gradlew wasmline-kotlin-plugin:publishToMavenLocal :wasmline-gradle-plugin:publishToMavenLocal

(cd ./wasmline && zig build --release=small -p src/jvmMain/resources)

rm -rf ./wasmline/build/kotlin/compileKotlinWasmWasi
rm -rf ./wasmline/build/classes/kotlin/wasmWasi
rm -rf ./wasmline-sample/plugin/build

./gradlew wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize
# Desktop sample only needs the pulley64 artifact that gets copied into resources below.
./gradlew wasmline-cli:run --args="compile -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/wasmline-multiplatform-wasmline-sample-plugin.wasm -wt ${WASMTIME_DIR} -a pulley64"
PLUGIN_OUTPUT_DIR="./wasmline-cli/build/wasmline/output/wasmline-multiplatform-wasmline-sample-plugin-1.0.0"
DESKTOP_RESOURCE_DIR="./wasmline-sample/multiplatform/desktopApp/src/main/resources"

mkdir -p "$DESKTOP_RESOURCE_DIR"
cp "$PLUGIN_OUTPUT_DIR/wasmline-multiplatform-wasmline-sample-plugin-pulley64.pwasm" "$DESKTOP_RESOURCE_DIR/plugin.pwasm"
./gradlew wasmline-sample:multiplatform:desktopApp:run
