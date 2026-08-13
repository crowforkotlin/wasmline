#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

usage() {
    cat <<'EOF'
Usage: bash scripts/lint/cpp.sh [--changed|--all] [--format]

Checks the repository's C/C++ core with wasmline-core/.clang-format.
The default scope is changed and untracked C/C++ files.
EOF
}

is_cpp_source() {
    local file="$1"
    case "$file" in
        */build/*|*/.gradle/*|*/.cxx/*|*/CMakeFiles/*|*/generated/*|*/test-gen/*)
            return 1
            ;;
        wasmline-core/*)
            case "$file" in
                *.c|*.cc|*.cpp|*.cxx|*.h|*.hh|*.hpp|*.hxx) return 0 ;;
            esac
            ;;
    esac
    return 1
}

collect_all_files() {
    LINT_FILES=()
    local roots=("${PROJECT_ROOT}/wasmline-core")
    local root file
    for root in "${roots[@]}"; do
        [[ -d "$root" ]] || continue
        while IFS= read -r -d '' file; do
            LINT_FILES+=("${file#"${PROJECT_ROOT}/"}")
        done < <(
            find "$root" \
                -type d \( -name build -o -name .gradle -o -name .cxx -o -name CMakeFiles -o -name generated -o -name test-gen \) -prune -o \
                -type f \( -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.cxx' -o -name '*.h' -o -name '*.hh' -o -name '*.hpp' -o -name '*.hxx' \) -print0
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
    collect_changed_files_matching is_cpp_source
fi

if ((${#LINT_FILES[@]} == 0)); then
    log_no_files 'C/C++'
    exit 0
fi

if ! command -v clang-format >/dev/null 2>&1; then
    log_error "clang-format is required. Install clang before running C/C++ lint."
    exit 1
fi

log_header "C/C++ ${LINT_MODE} (${LINT_SCOPE})"
log_info "Checking ${#LINT_FILES[@]} C/C++ file(s)."

cd "$PROJECT_ROOT"
CLANG_FORMAT_ARGS=(--style="file:${PROJECT_ROOT}/wasmline-core/.clang-format")
if [[ "$LINT_MODE" == format ]]; then
    CLANG_FORMAT_ARGS+=(-i)
else
    CLANG_FORMAT_ARGS+=(--dry-run --Werror)
fi
clang-format "${CLANG_FORMAT_ARGS[@]}" "${LINT_FILES[@]}"

log_success "C/C++ ${LINT_MODE} passed."
