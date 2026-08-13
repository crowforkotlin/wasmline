#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

usage() {
    cat <<'EOF'
Usage: bash scripts/lint/zig.sh [--changed|--all] [--format]

Checks Zig and ZON sources with Zig's built-in formatter and AST validation.
The default scope is changed and untracked Zig files.
EOF
}

is_zig_source() {
    local file="$1"
    case "$file" in
        wasmline-multiplatform/wasmline/zig-pkg/*|*/build/*|*/.zig-cache/*|*/zig-out/*)
            return 1
            ;;
        wasmline-multiplatform/wasmline/*.zig|wasmline-multiplatform/wasmline/*.zon|wasmline-multiplatform/wasmline/*/*.zig|wasmline-multiplatform/wasmline/*/*.zon)
            [[ "$file" == *.zig || "$file" == *.zon ]]
            ;;
        *)
            return 1
            ;;
    esac
}

collect_all_files() {
    LINT_FILES=()
    local file
    while IFS= read -r -d '' file; do
        LINT_FILES+=("${file#"${PROJECT_ROOT}/"}")
    done < <(
        find "${PROJECT_ROOT}/wasmline-multiplatform/wasmline" \
            -type d \( -name build -o -name .zig-cache -o -name zig-out -o -name zig-pkg \) -prune -o \
            -type f \( -name '*.zig' -o -name '*.zon' \) -print0
    )
}

split_zig_files() {
    LINT_ZIG_FILES=()
    LINT_ZON_FILES=()

    local file
    for file in "${LINT_FILES[@]}"; do
        case "$file" in
            *.zig) LINT_ZIG_FILES+=("$file") ;;
            *.zon) LINT_ZON_FILES+=("$file") ;;
        esac
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
    collect_changed_files_matching is_zig_source
fi

if ((${#LINT_FILES[@]} == 0)); then
    log_no_files Zig
    exit 0
fi

if ! command -v zig >/dev/null 2>&1; then
    log_error "zig is required. Install the repository's configured Zig version before linting."
    exit 1
fi

log_header "Zig ${LINT_MODE} (${LINT_SCOPE})"
log_info "Checking ${#LINT_FILES[@]} Zig file(s)."

cd "$PROJECT_ROOT"
split_zig_files
if [[ "$LINT_MODE" == format ]]; then
    if ((${#LINT_ZIG_FILES[@]} > 0)); then
        zig fmt "${LINT_ZIG_FILES[@]}"
    fi
    if ((${#LINT_ZON_FILES[@]} > 0)); then
        zig fmt --zon "${LINT_ZON_FILES[@]}"
    fi
else
    if ((${#LINT_ZIG_FILES[@]} > 0)); then
        zig fmt --check --ast-check "${LINT_ZIG_FILES[@]}"
    fi
    if ((${#LINT_ZON_FILES[@]} > 0)); then
        zig fmt --zon --check --ast-check "${LINT_ZON_FILES[@]}"
    fi
fi

log_success "Zig ${LINT_MODE} passed."
