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
ARTIFACT_FORMAT=""
QUIET=0

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

run_gradle_with_runtime_format() {
    local directory="$1"
    shift

    (
        if [ -n "$ARTIFACT_FORMAT" ]; then
            export WASMLINE_ARTIFACT_FORMAT="$ARTIFACT_FORMAT"
        else
            unset WASMLINE_ARTIFACT_FORMAT || true
        fi
        run_gradle "$directory" "$@"
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

# Maps shorthand platform names to standard Rust/LLVM target triples
# required by `wasmtime compile --target`. Without this, wasmtime parses
# e.g. "aarch64-android" as arch=aarch64, vendor=android, os=unknown.
normalize_compile_target() {
    case "$1" in
        aarch64-android)   printf '%s\n' "aarch64-linux-android" ;;
        aarch64-linux)     printf '%s\n' "aarch64-unknown-linux-gnu" ;;
        x86_64-linux)      printf '%s\n' "x86_64-unknown-linux-gnu" ;;
        aarch64-macos)     printf '%s\n' "aarch64-apple-darwin" ;;
        x86_64-macos)      printf '%s\n' "x86_64-apple-darwin" ;;
        aarch64-ios)       printf '%s\n' "aarch64-apple-ios" ;;
        aarch64-ios-sim)   printf '%s\n' "aarch64-apple-ios-sim" ;;
        x86_64-windows)    printf '%s\n' "x86_64-pc-windows-msvc" ;;
        pulley64)          printf '%s\n' "pulley64" ;;
        *)                 printf '%s\n' "$1" ;;
    esac
}

normalize_artifact_format() {
    case "$1" in
        pwasm|cwasm)
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
    local executable

    if [[ "$(uname -s)" == CYGWIN* || "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* || "$(uname -s)" == Windows_NT ]]; then
        executable="$(find "$directory" -type f -name 'wasmtime-min.exe' | sort | head -n 1)"
    else
        executable="$(find "$directory" -type f -name 'wasmtime-min' | sort | head -n 1)"
    fi

    printf '%s\n' "$executable"
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
        run_gradle_build "$MULTIPLATFORM_ROOT" :wasmline-cli:run --args="$download_args"
        wasmtime_dir="$(resolve_wasmtime_dir "$PLATFORM" || true)"
        if [ -n "$wasmtime_dir" ]; then
            wasmtime_executable="$(find_wasmtime_executable "$wasmtime_dir")"
        fi
    fi
    if [ -z "$wasmtime_executable" ] || [ -z "$wasmtime_dir" ]; then
        echo "wasmtime-min executable not found in shared toolchain cache for ${PLATFORM}." >&2
        echo "Tried cache root: ${SHARED_WASMTIME_ROOT}" >&2
        exit 1
    fi

    printf '%s\n' "$wasmtime_dir"
}

publish_local_artifacts() {
    local include_jvm_runtime="${1:-0}"

    run_gradle_build "$MULTIPLATFORM_ROOT" :wasmline-kotlin-plugin:publishToMavenLocal :wasmline-gradle-plugin:publishToMavenLocal

    if [ "$include_jvm_runtime" -eq 1 ]; then
        require_command zig
        (
            cd "$WASMLINE_MODULE_ROOT"
            if [ "$QUIET" -eq 1 ]; then
                zig build --release=small -p src/jvmMain/resources >/dev/null 2>&1
            else
                zig build --release=small -p src/jvmMain/resources >&2
            fi
        )
    fi

    run_gradle_build "$MULTIPLATFORM_ROOT" :wasmline:publishToMavenLocal :wasmline-loader:publishToMavenLocal
}

clean_plugin_builds() {
    rm -rf "${WASMLINE_MODULE_ROOT}/build/kotlin/compileKotlinWasmWasi"
    rm -rf "${WASMLINE_MODULE_ROOT}/build/classes/kotlin/wasmWasi"
    rm -rf "${SAMPLE_PLUGIN_ROOT}/build"
}

build_plugin_raw_wasm() {
    clean_plugin_builds
    run_gradle_build "$SAMPLE_ROOT" :sample-plugin:compileProductionLibraryKotlinWasmWasi

    local input_dir="${SAMPLE_PLUGIN_ROOT}/build/compileSync/wasmWasi/main/productionLibrary/kotlin"
    find_first_file "$input_dir" "*.wasm" "sample plugin wasm input"
}

build_plugin_optimized_wasm() {
    clean_plugin_builds
    run_gradle_build "$SAMPLE_ROOT" :sample-plugin:compileProductionLibraryKotlinWasmWasiOptimize

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
    run_gradle_build "$MULTIPLATFORM_ROOT" :wasmline-cli:run --args="$compile_args"
    find_first_file "$output_root" "*-pulley64.pwasm" "$artifact_description" 2
}

build_plugin_cwasm() {
    local output_root="$1"
    local wasmtime_dir="$2"
    local artifact_description="$3"
    local target="$4"
    local input_file
    local compile_args

    if [ -z "$target" ]; then
        echo "Missing cwasm target for ${artifact_description}" >&2
        exit 1
    fi

    rm -rf "$output_root"
    input_file="$(build_plugin_optimized_wasm)"
    compile_args="$(render_args \
        compile \
        -i "$input_file" \
        -o "$output_root" \
        -v "$WASMLINE_VERSION" \
        -wt "$wasmtime_dir" \
        -a "$target"
    )"
    run_gradle_build "$MULTIPLATFORM_ROOT" :wasmline-cli:run --args="$compile_args"
    find_first_file "$output_root" "*-${target}.cwasm" "$artifact_description" 2
}

build_plugin_runtime_artifact() {
    local output_root="$1"
    local wasmtime_dir="$2"
    local artifact_description="$3"
    local cwasm_target="$4"

    if [ "$ARTIFACT_FORMAT" = "cwasm" ]; then
        build_plugin_cwasm "$output_root" "$wasmtime_dir" "$artifact_description" "$cwasm_target"
    else
        build_plugin_pwasm "$output_root" "$wasmtime_dir" "$artifact_description"
    fi
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

build_plugin_runtime_artifacts() {
    local output_root="$1"
    local wasmtime_dir="$2"
    local artifact_description="$3"
    local cwasm_target="$4"
    local input_file
    local compile_args=()
    local targets=()
    local target

    RUNTIME_PWASM_FILE=""
    RUNTIME_CWASM_FILE=""

    rm -rf "$output_root"
    input_file="$(build_plugin_optimized_wasm)"

    if [ "$ARTIFACT_FORMAT" = "pwasm" ]; then
        targets=("pulley64")
    elif [ "$ARTIFACT_FORMAT" = "cwasm" ]; then
        if [ -z "$cwasm_target" ]; then
            echo "Missing cwasm target for ${artifact_description}" >&2
            exit 1
        fi
        targets=("$(normalize_compile_target "$cwasm_target")")
    else
        targets=("pulley64")
        if [ -n "$cwasm_target" ]; then
            targets+=("$(normalize_compile_target "$cwasm_target")")
        fi
    fi

    compile_args=(
        compile
        -i "$input_file"
        -o "$output_root"
        -v "$WASMLINE_VERSION"
        -wt "$wasmtime_dir"
    )
    for target in "${targets[@]}"; do
        compile_args+=( -a "$target" )
    done

    run_gradle_build "$MULTIPLATFORM_ROOT" :wasmline-cli:run --args="$(render_args "${compile_args[@]}")"

    if printf '%s\n' "${targets[@]}" | grep -Fxq 'pulley64'; then
        RUNTIME_PWASM_FILE="$(find_optional_file "$output_root" '*-pulley64.pwasm' 2)"
        if [ -z "$RUNTIME_PWASM_FILE" ]; then
            echo "Unable to locate ${artifact_description} pwasm artifact under ${output_root}" >&2
            exit 1
        fi
    fi

    local normalized_cwasm_target
    normalized_cwasm_target="$(normalize_compile_target "$cwasm_target")"
    if [ -n "$cwasm_target" ] && printf '%s\n' "${targets[@]}" | grep -Fxq "$normalized_cwasm_target"; then
        RUNTIME_CWASM_FILE="$(find_optional_file "$output_root" "*-${normalized_cwasm_target}.cwasm" 2)"
        if [ -z "$RUNTIME_CWASM_FILE" ]; then
            echo "Unable to locate ${artifact_description} cwasm artifact under ${output_root}" >&2
            exit 1
        fi
    fi
}

sync_runtime_artifact() {
    local source_file="$1"
    local target_file="$2"
    local target_dir
    local target_base

    target_dir="$(dirname "$target_file")"
    target_base="$(basename "$target_file")"
    target_base="${target_base%.*}"

    mkdir -p "$target_dir"
    rm -f "$target_dir/${target_base}.pwasm" "$target_dir/${target_base}.cwasm"
    cp "$source_file" "$target_file"
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

    if [ "$ARTIFACT_FORMAT" = "pwasm" ]; then
        cp "$RUNTIME_PWASM_FILE" "$target_dir/${target_base}.pwasm"
    elif [ "$ARTIFACT_FORMAT" = "cwasm" ]; then
        cp "$RUNTIME_CWASM_FILE" "$target_dir/${target_base}.cwasm"
    else
        cp "$RUNTIME_PWASM_FILE" "$target_dir/${target_base}.pwasm"
        if [ -n "$RUNTIME_CWASM_FILE" ]; then
            cp "$RUNTIME_CWASM_FILE" "$target_dir/${target_base}.cwasm"
        fi
    fi
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
