#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PLATFORMS_ROOT="${ROOT_DIR}/build/platforms"
PROFILE_FILES=("$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile")
PLATFORM_TARGETS=(
    # Format: "variant|relative_dir|label|asset_id"
    # Cranelift targets (7 platforms)
    "cranelift|android/arm64-v8a|Android arm64-v8a (Cranelift)|aarch64-android"
    "cranelift|android/x86_64|Android x86_64 (Cranelift)|x86_64-android"
    "cranelift|linux/aarch64|Linux aarch64 (Cranelift)|aarch64-linux"
    "cranelift|linux/x64|Linux x64 (Cranelift)|x86_64-linux"
    "cranelift|mac/aarch64|macOS aarch64 (Cranelift)|aarch64-macos"
    "cranelift|mac/x64|macOS x64 (Cranelift)|x86_64-macos"
    "cranelift|windows/x64|Windows x64 (Cranelift)|x86_64-windows"
    # Pulley targets (11 platforms)
    "pulley|android/arm64-v8a|Android arm64-v8a (Pulley)|aarch64-android-pulley"
    "pulley|android/armeabi-v7a|Android armeabi-v7a (Pulley)|armv7-android-pulley"
    "pulley|android/x86_64|Android x86_64 (Pulley)|x86_64-android-pulley"
    "pulley|android/x86|Android x86 (Pulley)|x86-android-pulley"
    "pulley|ios/arm64|iOS arm64 device (Pulley)|aarch64-ios-pulley"
    "pulley|ios/simulator-arm64|iOS arm64 simulator (Pulley)|aarch64-ios-sim-pulley"
    "pulley|linux/aarch64|Linux aarch64 (Pulley)|aarch64-linux-pulley"
    "pulley|linux/x64|Linux x64 (Pulley)|x86_64-linux-pulley"
    "pulley|mac/aarch64|macOS aarch64 (Pulley)|aarch64-macos-pulley"
    "pulley|mac/x64|macOS x64 (Pulley)|x86_64-macos-pulley"
    "pulley|windows/x64|Windows x64 (Pulley)|x86_64-windows-pulley"
)
REQUIRED_JBR_VERSION="21"
REQUIRED_ZIG_VERSION="0.15.1"

# Inline version resolution (doctor.sh is standalone, does not source context.sh)
resolve_wasmtime_version() {
    if [ -n "${WASMTIME_VERSION:-}" ]; then
        printf '%s\n' "$WASMTIME_VERSION"; return
    fi
    if [ -f "$ROOT_DIR/scripts/versions.json" ]; then
        local ver
        ver=$(python3 -c "import json;print('release-v'+json.load(open('$ROOT_DIR/scripts/versions.json'))['versions']['wasmtime_version'])" 2>/dev/null || true)
        if [ -n "$ver" ]; then printf '%s\n' "$ver"; return; fi
    fi
    ls -1d "$PLATFORMS_ROOT"/release-v* 2>/dev/null | sort -V | tail -1 | xargs basename
}

OK_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

supports_unicode() {
    local charmap=""
    if command -v locale >/dev/null 2>&1; then
        charmap="$(LC_ALL="${LC_ALL:-}" LANG="${LANG:-}" locale charmap 2>/dev/null || true)"
    fi
    case "$charmap" in
        UTF-8|UTF8|utf-8|utf8) return 0 ;;
        *) return 1 ;;
    esac
}

init_ui() {
    if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
        red='\033[1;31m'
        green='\033[1;32m'
        yellow='\033[1;33m'
        blue='\033[1;34m'
        magenta='\033[1;35m'
        cyan='\033[1;36m'
        bold='\033[1m'
        dim='\033[2m'
        reset='\033[0m'
    else
        red=''
        green=''
        yellow=''
        blue=''
        magenta=''
        cyan=''
        bold=''
        dim=''
        reset=''
    fi

    if supports_unicode; then
        DOCTOR_ICON='🩺'
        JAVA_ICON='☕'
        ASSET_ICON='📦'
        DESKTOP_ICON='🧱'
        SUMMARY_ICON='🏁'
        RULE_CHAR='─'
    else
        DOCTOR_ICON='[doctor]'
        JAVA_ICON='[java]'
        ASSET_ICON='[assets]'
        DESKTOP_ICON='[desktop]'
        SUMMARY_ICON='[summary]'
        RULE_CHAR='-'
    fi
}

paint() {
    local color="$1"
    shift
    local text="$*"
    local code=''
    case "$color" in
        red) code="$red" ;;
        green) code="$green" ;;
        yellow) code="$yellow" ;;
        blue) code="$blue" ;;
        magenta) code="$magenta" ;;
        cyan) code="$cyan" ;;
        bold) code="$bold" ;;
        dim) code="$dim" ;;
        *) printf '%s' "$text"; return 0 ;;
    esac

    if [ -n "$code" ]; then
        printf '%b%s%b' "$code" "$text" "$reset"
    else
        printf '%s' "$text"
    fi
}

repeat_char() {
    local char="$1"
    local count="$2"
    local out=""
    local i
    for ((i = 0; i < count; i++)); do
        out="${out}${char}"
    done
    printf '%s' "$out"
}

pad_right() {
    local width="$1"
    local text="$2"
    printf '%-*s' "$width" "$text"
}

status_word() {
    case "$1" in
        pass) printf 'PASS' ;;
        warn) printf 'WARN' ;;
        fail) printf 'FAIL' ;;
        info) printf 'INFO' ;;
        *) printf '%s' "$1" ;;
    esac
}

status_color() {
    case "$1" in
        pass) printf 'green' ;;
        warn) printf 'yellow' ;;
        fail) printf 'red' ;;
        info) printf 'blue' ;;
        *) printf 'bold' ;;
    esac
}

status_cell() {
    local kind="$1"
    local label
    label="$(pad_right 7 "$(status_word "$kind")")"
    printf '%s' "$(paint "$(status_color "$kind")" "$label")"
}

count_status() {
    case "$1" in
        pass) OK_COUNT=$((OK_COUNT + 1)) ;;
        warn) WARN_COUNT=$((WARN_COUNT + 1)) ;;
        fail) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
    esac
}

rule() {
    printf '%s\n' "$(paint dim "$(repeat_char "$RULE_CHAR" 88)")"
}

banner() {
    rule
    printf '%s %s\n' "$(paint magenta "$DOCTOR_ICON")" "$(paint bold "Wasmline Doctor")"
    printf '%s\n' "$(paint dim "Environment preflight for Gradle and native runtime assets")"
    rule
}

section() {
    printf '\n%s %s\n' "$(paint cyan "$1")" "$(paint bold "$2")"
    rule
    printf '%-7s | %-24s | %s\n' "Status" "Check" "Details"
    printf '%s\n' "$(paint dim "$(repeat_char "$RULE_CHAR" 88)")"
}

table_row() {
    local kind="$1"
    local label="$2"
    local details="$3"
    local should_count="${4:-1}"
    if [ "$should_count" -eq 1 ]; then
        count_status "$kind"
    fi
    printf '%s | %-24s | %s\n' "$(status_cell "$kind")" "$(pad_right 24 "$label")" "$details"
}

table_note() {
    printf '%-7s | %-24s | %s\n' "" "" "$1"
}

usage() {
    cat <<'EOF'
Usage:
  bash ./scripts/doctor.sh

Options:
  -h, --help          Show this help.
EOF
}

java_home_summary() {
    local java_home="$1"
    if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
        return 1
    fi

    local version_line release_file implementor runtime_version
    version_line="$("$java_home/bin/java" -version 2>&1 | head -n 1 || true)"
    release_file="$java_home/release"
    implementor=""
    runtime_version=""
    if [ -f "$release_file" ]; then
        implementor="$(grep '^IMPLEMENTOR=' "$release_file" | head -n 1 | cut -d'=' -f2- | tr -d '"' || true)"
        runtime_version="$(grep '^JAVA_RUNTIME_VERSION=' "$release_file" | head -n 1 | cut -d'=' -f2- | tr -d '"' || true)"
    fi
    printf '%s|%s|%s\n' "$version_line" "$implementor" "$runtime_version"
}

is_required_jbr_home() {
    local java_home="$1"
    if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
        return 1
    fi

    local summary version_line implementor runtime_version
    summary="$(java_home_summary "$java_home")"
    version_line="${summary%%|*}"
    local rest="${summary#*|}"
    implementor="${rest%%|*}"
    runtime_version="${summary##*|}"

    case "$version_line $implementor $runtime_version $java_home" in
        *"${REQUIRED_JBR_VERSION}"*JBR*|*"${REQUIRED_JBR_VERSION}"*JetBrains*|*"${REQUIRED_JBR_VERSION}"*jbr*|*"${REQUIRED_JBR_VERSION}"*jbrsdk*) return 0 ;;
        *) return 1 ;;
    esac
}

detect_java_home_from_path() {
    command -v java >/dev/null 2>&1 || return 1

    local detected
    detected="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^[[:space:]]*java\.home = / { print $2; exit }')"
    [ -n "$detected" ] || return 1
    [ -x "$detected/bin/java" ] || return 1
    printf '%s\n' "$detected"
}

host_environment_note() {
    case "$(uname -s)" in
        Darwin)
            printf '%s\n' 'macOS matches the documented primary setup.'
            ;;
        MINGW*|MSYS*|CYGWIN*)
            printf '%s\n' 'Windows/Git Bash matches the documented Windows workflow.'
            ;;
        *)
            printf '%s\n' 'Non-primary host environment; verify the toolchain manually.'
            ;;
    esac
}

collect_profile_hit_count() {
    local file total=0 count
    for file in "${PROFILE_FILES[@]}"; do
        if [ -f "$file" ]; then
            count="$(grep -cE 'JAVA_HOME|jbr|JetBrainsRuntime|jbrsdk' "$file" || true)"
            total=$((total + count))
        fi
    done
    printf '%s\n' "$total"
}

collect_candidate_paths() {
    local file
    for file in "${PROFILE_FILES[@]}"; do
        [ -f "$file" ] || continue
        sed -nE 's/.*=("?)([^"#]*(jbr|jbrsdk)[^"#]*)\1.*/\2/p' "$file" 2>/dev/null || true
    done | while IFS= read -r candidate; do
        [ -n "$candidate" ] || continue
        candidate="${candidate//\$HOME/$HOME}"
        candidate="${candidate//\$\{HOME\}/$HOME}"
        printf '%s\n' "$candidate"
    done | awk '!seen[$0]++'
}

check_jbr() {
    section "$JAVA_ICON" "JBR ${REQUIRED_JBR_VERSION} Gate"

    local current_java_home="${JAVA_HOME:-}"
    local current_java_ok=0
    local java_source='JAVA_HOME environment variable'
    local preferred_jbr_home=''

    if [ -z "$current_java_home" ]; then
        current_java_home="$(detect_java_home_from_path || true)"
        java_source='Detected from java.home'
    fi

    table_row info "Host environment" "$(host_environment_note)" 0
    table_row info "Java home source" "$java_source" 0

    if [ -n "$current_java_home" ]; then
        if is_required_jbr_home "$current_java_home"; then
            current_java_ok=1
            table_row pass "Active JBR ${REQUIRED_JBR_VERSION}" "$current_java_home"
        else
            table_row warn "Active Java home" "$current_java_home"
            table_note "The current shell is not using JBR ${REQUIRED_JBR_VERSION}."
        fi
    else
        table_row warn "Active Java home" "No usable Java home was detected."
    fi

    local profile_hits candidate_paths
    profile_hits="$(collect_profile_hit_count)"
    if [ "$profile_hits" -eq 0 ]; then
        table_row info "Shell profiles" "No JBR-related hints found in ~/.zshrc, ~/.bashrc, or ~/.bash_profile." 0
    else
        table_row info "Shell profiles" "Found $profile_hits JBR-related line(s) in shell profiles." 0
    fi

    candidate_paths="$(collect_candidate_paths || true)"
    if [ -n "$candidate_paths" ]; then
        while IFS= read -r candidate; do
            [ -n "$candidate" ] || continue
            if [ -d "$candidate" ]; then
                if is_required_jbr_home "$candidate"; then
                    [ -n "$preferred_jbr_home" ] || preferred_jbr_home="$candidate"
                    table_row pass "Candidate JBR" "$candidate"
                else
                    table_row warn "Candidate path" "$candidate"
                    table_note "Path exists but is not a JBR ${REQUIRED_JBR_VERSION} home."
                fi
            else
                table_row warn "Candidate path" "$candidate"
                table_note "Path was referenced in shell config but does not exist."
            fi
        done <<< "$candidate_paths"
    else
        table_row info "Candidate paths" "No explicit JBR paths were extracted from shell profiles." 0
    fi

    if [ "$current_java_ok" -eq 1 ]; then
        table_row pass "Gradle gate" "JBR ${REQUIRED_JBR_VERSION} is active in the current shell."
        return 0
    fi

    table_row fail "Gradle gate" "JBR ${REQUIRED_JBR_VERSION} is required before any Gradle build or test."
    if [ -n "$preferred_jbr_home" ]; then
        table_note "Recommended fix:"
        table_note "export JAVA_HOME=\"$preferred_jbr_home\""
        table_note "\"\$JAVA_HOME/bin/java\" -version"
    else
        table_note "Install or locate a valid JBR ${REQUIRED_JBR_VERSION} and export JAVA_HOME before continuing."
    fi
    exit 2
}

check_platform_assets() {
    section "$ASSET_ICON" "Platform Assets"

    local available_count=0
    local missing_count=0
    local entry variant relative_dir label asset_id asset_dir missing_parts missing_display

    if [ ! -d "$PLATFORMS_ROOT" ]; then
        table_row warn "build/platforms/" "Directory not found. No Wasmtime runtime assets are currently available."
        table_note "Run sh ./scripts/init-wasmtime.sh when you need native or desktop targets."
        return 0
    fi

    # Resolve wasmtime version directory
    local version_dir
    version_dir=$(resolve_wasmtime_version)
    if [ -z "$version_dir" ] || [ ! -d "$PLATFORMS_ROOT/$version_dir" ]; then
        table_row warn "build/platforms/" "Version directory not found: $version_dir"
        table_note "Run sh ./scripts/init-wasmtime.sh to download Wasmtime assets."
        return 0
    fi

    for entry in "${PLATFORM_TARGETS[@]}"; do
        IFS='|' read -r variant relative_dir label asset_id <<< "$entry"
        asset_dir="$PLATFORMS_ROOT/$version_dir/$variant/$relative_dir"
        missing_parts=""
        [ -d "$asset_dir/include" ] || missing_parts="${missing_parts}include,"
        [ -d "$asset_dir/lib" ] || missing_parts="${missing_parts}lib,"

        if [ -z "$missing_parts" ]; then
            available_count=$((available_count + 1))
            table_row pass "$label" "path=build/platforms/$version_dir/$variant/$relative_dir"
        else
            missing_count=$((missing_count + 1))
            missing_display="${missing_parts%,}"
            table_row warn "$label" "path=build/platforms/$version_dir/$variant/$relative_dir; missing=$missing_display"
        fi
    done

    if [ "$available_count" -eq 0 ]; then
        table_row warn "Coverage" "0/${#PLATFORM_TARGETS[@]} targets are ready." 0
        table_note "Run sh ./scripts/init-wasmtime.sh before native builds."
    elif [ "$missing_count" -gt 0 ]; then
        table_row info "Coverage" "$available_count/${#PLATFORM_TARGETS[@]} targets are ready. Missing targets stay as warnings." 0
        table_note "Warnings are usually safe to ignore for web-only work."
    else
        table_row pass "Coverage" "All ${#PLATFORM_TARGETS[@]} known Wasmtime platform targets are ready." 0
    fi
}

check_zig() {
    if ! command -v zig >/dev/null 2>&1; then
        table_row warn "Zig ${REQUIRED_ZIG_VERSION}" "zig was not found in PATH."
        return 1
    fi

    local version
    version="$(zig version 2>/dev/null || true)"
    if [ "$version" = "$REQUIRED_ZIG_VERSION" ]; then
        table_row pass "Zig ${REQUIRED_ZIG_VERSION}" "Detected zig version $version."
    else
        table_row warn "Zig version" "Detected $version. Version ${REQUIRED_ZIG_VERSION} is required for Compose Desktop/native."
        return 1
    fi

}

check_desktop_native_outputs() {
    if find "$ROOT_DIR/wasmline-multiplatform/wasmline/src/jvmMain/resources" -type f \( -name '*.dylib' -o -name '*.so' -o -name '*.dll' \) 2>/dev/null | grep -q .; then
        table_row pass "JNI/native outputs" "Desktop native libraries were found under wasmline/src/jvmMain/resources."
        return 0
    else
        table_row warn "JNI/native outputs" "No desktop native libraries were found under wasmline/src/jvmMain/resources."
        return 1
    fi
}

check_compose_native() {
    section "$DESKTOP_ICON" "Compose Desktop / Native"
    table_row info "Mode" "Desktop checks are advisory and reported for visibility." 0
    check_zig || true
    check_desktop_native_outputs || true
}

print_summary() {
    section "$SUMMARY_ICON" "Summary"
    table_row pass "Hard gate" "JBR ${REQUIRED_JBR_VERSION} is active; Gradle work may proceed." 0
    table_row info "Results" "$OK_COUNT pass, $WARN_COUNT warning, $FAIL_COUNT fail." 0
    if [ "$WARN_COUNT" -gt 0 ]; then
        table_row info "Next step" "Review warnings only for the platforms or desktop flow you actually need." 0
    else
        table_row info "Next step" "No outstanding warnings in the current doctor run." 0
    fi
}

main() {
    init_ui
    case "${1:-}" in
        "")
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf '%s\n' "$(paint red "Unknown argument: ${1}")" >&2
            usage
            exit 64
            ;;
    esac

    banner
    check_jbr
    check_platform_assets
    check_compose_native
    print_summary
}

main "$@"
