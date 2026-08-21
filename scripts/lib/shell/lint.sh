#!/usr/bin/env bash

# Shared argument and Git working-tree helpers for language lint scripts.

WASMLINE_LINT_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${WASMLINE_LINT_LIB_DIR}/paths.sh"
source "${WASMLINE_LINT_LIB_DIR}/output.sh"
source "${WASMLINE_LINT_LIB_DIR}/versions.sh"

wasmline_parse_lint_options() {
    WASMLINE_LINT_SCOPE=changed
    WASMLINE_LINT_MODE=check
    WASMLINE_LINT_SHOW_HELP=false

    while (($#)); do
        case "$1" in
            --all)
                WASMLINE_LINT_SCOPE=all
                ;;
            --changed)
                WASMLINE_LINT_SCOPE=changed
                ;;
            --format|-F)
                WASMLINE_LINT_MODE=format
                ;;
            --help|-h)
                WASMLINE_LINT_SHOW_HELP=true
                ;;
            *)
                wasmline_status ERR "Argument" "Unknown option: $1"
                return 2
                ;;
        esac
        shift
    done
}

wasmline_collect_changed_files() {
    git diff --name-only -z --diff-filter=ACMR HEAD 2>/dev/null || true
    git ls-files --others --exclude-standard -z
}

wasmline_collect_changed_files_matching() {
    local matcher="$1"
    WASMLINE_LINT_FILES=()

    local file
    while IFS= read -r -d '' file; do
        if "$matcher" "$file"; then
            WASMLINE_LINT_FILES+=("$file")
        fi
    done < <(wasmline_collect_changed_files)
}

wasmline_log_no_files() {
    wasmline_status SKIP "$1" "No files to ${WASMLINE_LINT_MODE} in the ${WASMLINE_LINT_SCOPE} scope."
}

wasmline_lint_action() {
    if [[ "${WASMLINE_LINT_MODE}" == format ]]; then
        printf 'Formatting\n'
    else
        printf 'Checking\n'
    fi
}
