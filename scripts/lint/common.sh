#!/usr/bin/env bash

# Shared argument and Git working-tree helpers for language lint scripts.

LINT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${LINT_DIR}/../lib/context.sh"

parse_lint_options() {
    LINT_SCOPE=changed
    LINT_MODE=check
    LINT_SHOW_HELP=false

    while (($#)); do
        case "$1" in
            --all)
                LINT_SCOPE=all
                ;;
            --changed)
                LINT_SCOPE=changed
                ;;
            --format|-F)
                LINT_MODE=format
                ;;
            --help|-h)
                LINT_SHOW_HELP=true
                ;;
            *)
                log_error "Unknown option: $1"
                return 2
                ;;
        esac
        shift
    done
}

collect_changed_files() {
    git diff --name-only -z --diff-filter=ACMR HEAD 2>/dev/null || true
    git ls-files --others --exclude-standard -z
}

collect_changed_files_matching() {
    local matcher="$1"
    LINT_FILES=()

    local file
    while IFS= read -r -d '' file; do
        if "$matcher" "$file"; then
            LINT_FILES+=("$file")
        fi
    done < <(collect_changed_files)
}

log_no_files() {
    log_info "No $1 source files to ${LINT_MODE} in the ${LINT_SCOPE} scope."
}
