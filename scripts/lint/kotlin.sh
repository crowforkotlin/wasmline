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

if ! command -v ktlint >/dev/null 2>&1; then
    log_error "ktlint is required. Install the CI version before running Kotlin lint."
    exit 1
fi

log_header "Kotlin ${LINT_MODE} (${LINT_SCOPE})"
log_info "Checking ${#LINT_FILES[@]} Kotlin file(s)."

cd "$PROJECT_ROOT"
KTLINT_ARGS=(--relative --editorconfig "wasmline-multiplatform/.editorconfig")
if [[ "$LINT_MODE" == format ]]; then
    KTLINT_ARGS+=(--format)
fi
ktlint "${KTLINT_ARGS[@]}" "${LINT_FILES[@]}"

log_success "Kotlin ${LINT_MODE} passed."
