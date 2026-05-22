#!/usr/bin/env bash

: "${SCRIPT_NAME:=$(basename "$0")}"

COMMON_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_ROOT="${COMMON_SCRIPT_DIR}"
REPO_ROOT="$(cd "${COMMON_SCRIPT_DIR}/../.." && pwd)"
MULTIPLATFORM_ROOT="${REPO_ROOT}/wasmline-multiplatform"
WASMLINE_MODULE_ROOT="${MULTIPLATFORM_ROOT}/wasmline"
WASMLINE_CLI_ROOT="${MULTIPLATFORM_ROOT}/wasmline-cli"
SAMPLE_PLUGIN_ROOT="${SAMPLE_ROOT}/sample-plugin"
SHARED_WASMTIME_ROOT="${REPO_ROOT}/build/wasmline/wasmtime"

PLATFORM=""
ANDROID_DEVICE=""
WASMTIME_VERSION=""
WASMLINE_VERSION=""

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
    shift 2

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

load_wasmline_metadata() {
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

    if [ -z "$PLATFORM" ]; then
        PLATFORM="$(detect_current_platform)"
    fi

    local wasmtime_dir
    local wasmtime_executable
    wasmtime_dir="$(resolve_wasmtime_dir "$PLATFORM" || true)"
    wasmtime_executable=""
    if [ -n "$wasmtime_dir" ]; then
        wasmtime_executable="$(find_wasmtime_executable "$wasmtime_dir")"
    fi
    if [ -z "$wasmtime_executable" ]; then
        download_args="$(render_args \
            download \
            -v "v${WASMTIME_VERSION}" \
            -a "${PLATFORM}" \
            -o "${SHARED_WASMTIME_ROOT}" \
        )"
        run_gradle "$MULTIPLATFORM_ROOT" :wasmline-cli:run --args="$download_args" >&2
        wasmtime_dir="$(resolve_wasmtime_dir "$PLATFORM" || true)"
        if [ -n "$wasmtime_dir" ]; then
            wasmtime_executable="$(find_wasmtime_executable "$wasmtime_dir")"
        fi
    fi
    if [ -z "$wasmtime_executable" ] || [ -z "$wasmtime_dir" ]; then
        echo "Wasmtime executable not found in shared toolchain cache for ${PLATFORM}." >&2
        echo "Tried cache root: ${SHARED_WASMTIME_ROOT}" >&2
        exit 1
    fi

    printf '%s\n' "$wasmtime_dir"
}

publish_local_artifacts() {
    local include_jvm_runtime="${1:-0}"

    run_gradle "$MULTIPLATFORM_ROOT" :wasmline-kotlin-plugin:publishToMavenLocal :wasmline-gradle-plugin:publishToMavenLocal

    if [ "$include_jvm_runtime" -eq 1 ]; then
        require_command zig
        (
            cd "$WASMLINE_MODULE_ROOT"
            zig build --release=small -p src/jvmMain/resources
        )
    fi

    run_gradle "$MULTIPLATFORM_ROOT" :wasmline:publishToMavenLocal :wasmline-loader:publishToMavenLocal
}

clean_plugin_builds() {
    rm -rf "${WASMLINE_MODULE_ROOT}/build/kotlin/compileKotlinWasmWasi"
    rm -rf "${WASMLINE_MODULE_ROOT}/build/classes/kotlin/wasmWasi"
    rm -rf "${SAMPLE_PLUGIN_ROOT}/build"
}

build_plugin_raw_wasm() {
    clean_plugin_builds
    run_gradle "$SAMPLE_ROOT" :sample-plugin:compileProductionLibraryKotlinWasmWasi >&2

    local input_dir="${SAMPLE_PLUGIN_ROOT}/build/compileSync/wasmWasi/main/productionLibrary/kotlin"
    find_first_file "$input_dir" "*.wasm" "sample plugin wasm input"
}

build_plugin_optimized_wasm() {
    clean_plugin_builds
    run_gradle "$SAMPLE_ROOT" :sample-plugin:compileProductionLibraryKotlinWasmWasiOptimize >&2

    local input_dir="${SAMPLE_PLUGIN_ROOT}/build/compileSync/wasmWasi/main/productionLibrary/optimized"
    find_first_file "$input_dir" "*.wasm" "sample plugin wasm input"
}

build_plugin_pwasm() {
    local output_root="$1"
    local wasmtime_dir="$2"
    local artifact_description="$3"
    local input_file
    local compile_args

    rm -rf "$output_root"
    input_file="$(build_plugin_optimized_wasm)"
    compile_args="$(render_args \
        compile \
        -i "$input_file" \
        -o "$output_root" \
        -v "$WASMLINE_VERSION" \
        -wt "$wasmtime_dir" \
        -a pulley64
    )"
    run_gradle "$MULTIPLATFORM_ROOT" :wasmline-cli:run --args="$compile_args" >&2
    find_first_file "$output_root" "*-pulley64.pwasm" "$artifact_description" 2
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
