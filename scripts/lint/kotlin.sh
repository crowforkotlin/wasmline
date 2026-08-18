#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

usage() {
    cat <<'EOF'
Usage: bash scripts/lint/kotlin.sh [--changed|--all] [--format]

Checks Kotlin sources managed by wasmline-multiplatform/.editorconfig.
The default scope is changed and untracked Kotlin files.
EOF
}

is_kotlin_source() {
    local file="$1"
    case "$file" in
        wasmline-multiplatform/wasmline-build-logic/*|*/iosMain/*|*/build/*|*/test-gen/*)
            return 1
            ;;
        wasmline-multiplatform/*)
            case "$file" in
                *.kt|*.kts) return 0 ;;
            esac
            ;;
    esac
    return 1
}

ktlint_version() {
    "$1" --version 2>/dev/null | sed -nE 's/.*([0-9]+\.[0-9]+\.[0-9]+).*/\1/p' | head -n 1 || true
}

resolve_ktlint() {
    local expected_version="$1"

    local path_ktlint actual_version
    path_ktlint="$(command -v ktlint || true)"
    if [[ -n "$path_ktlint" ]]; then
        actual_version="$(ktlint_version "$path_ktlint")"
        if [[ "$actual_version" == "$expected_version" ]]; then
            printf '%s\n' "$path_ktlint"
            return
        fi
        log_warn "Ignoring ktlint ${actual_version:-unknown} from PATH; repository requires ${expected_version}." >&2
    fi

    local cache_dir cached_ktlint temporary_ktlint
    cache_dir="${BUILD_ROOT}/tools/ktlint/${expected_version}"
    cached_ktlint="${cache_dir}/ktlint"
    if [[ -x "$cached_ktlint" ]]; then
        actual_version="$(ktlint_version "$cached_ktlint")"
        if [[ "$actual_version" == "$expected_version" ]]; then
            printf '%s\n' "$cached_ktlint"
            return
        fi
    fi

    if ! command -v curl >/dev/null 2>&1; then
        log_error "curl is required to download ktlint ${expected_version}."
        return 1
    fi

    mkdir -p "$cache_dir"
    temporary_ktlint="$(mktemp "${cache_dir}/.ktlint.XXXXXX")"
    log_info "Downloading ktlint ${expected_version}." >&2
    if ! curl --fail --location --silent --show-error --retry 3 \
        "https://github.com/ktlint/ktlint/releases/download/${expected_version}/ktlint" \
        --output "$temporary_ktlint"; then
        rm -f "$temporary_ktlint"
        log_error "Failed to download ktlint ${expected_version}."
        return 1
    fi
    chmod +x "$temporary_ktlint"
    actual_version="$(ktlint_version "$temporary_ktlint")"
    if [[ "$actual_version" != "$expected_version" ]]; then
        rm -f "$temporary_ktlint"
        log_error "Downloaded ktlint reports ${actual_version:-unknown}; expected ${expected_version}."
        return 1
    fi
    mv "$temporary_ktlint" "$cached_ktlint"
    printf '%s\n' "$cached_ktlint"
}

collect_all_files() {
    LINT_FILES=()
    local roots=("${PROJECT_ROOT}/wasmline-multiplatform")
    local root file
    for root in "${roots[@]}"; do
        [[ -d "$root" ]] || continue
        while IFS= read -r -d '' file; do
            LINT_FILES+=("${file#"${PROJECT_ROOT}/"}")
        done < <(
            find "$root" \
                -type d \( -name build -o -name .gradle -o -name .kotlin -o -name test-gen -o -name wasmline-build-logic -o -name iosMain \) -prune -o \
                -type f \( -name '*.kt' -o -name '*.kts' \) -print0
        )
    done
}

parse_lint_options "$@"
if [[ "$LINT_SHOW_HELP" == true ]]; then
    usage
    exit 0
fi

if [[ "$LINT_SCOPE" == all ]]; then
    collect_all_files
else
    collect_changed_files_matching is_kotlin_source
fi

if ((${#LINT_FILES[@]} == 0)); then
    log_no_files Kotlin
    exit 0
fi

KTLINT_VERSION="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["versions"]["ktlint_version"])' "${SCRIPTS_ROOT}/versions.json")"
KTLINT_BINARY="$(resolve_ktlint "$KTLINT_VERSION")"

log_header "Kotlin ${LINT_MODE} (${LINT_SCOPE})"
log_info "Using ktlint ${KTLINT_VERSION} from ${KTLINT_BINARY}."
log_info "Checking ${#LINT_FILES[@]} Kotlin file(s)."

cd "$PROJECT_ROOT"
KTLINT_ARGS=(--relative --editorconfig "wasmline-multiplatform/.editorconfig")
if [[ "$LINT_MODE" == format ]]; then
    KTLINT_ARGS+=(--format)
fi
"$KTLINT_BINARY" "${KTLINT_ARGS[@]}" "${LINT_FILES[@]}"

log_success "Kotlin ${LINT_MODE} passed."
