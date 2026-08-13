#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINT_DIR="${SCRIPT_DIR}/lint"

usage() {
    cat <<'EOF'
Usage: bash scripts/lint.sh [--changed|--all] [check|format] [kotlin|cpp|zig ...]

Checks changed and untracked source files for every supported language by default.
Use --all to check every source file, and select languages to narrow the run.

Examples:
  bash scripts/lint.sh
  bash scripts/lint.sh kotlin
  bash scripts/lint.sh cpp format
  bash scripts/lint.sh --all check
  bash scripts/lint.sh --all format kotlin zig
EOF
}

scope=changed
mode=check
languages=()

while (($#)); do
    case "$1" in
        --all)
            scope=all
            ;;
        --changed)
            scope=changed
            ;;
        check)
            mode=check
            ;;
        format|--format|-F)
            mode=format
            ;;
        kotlin|cpp|zig)
            languages+=("$1")
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown lint language or option: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

if ((${#languages[@]} == 0)); then
    languages=(kotlin cpp zig)
fi

for language in "${languages[@]}"; do
    arguments=("--${scope}")
    if [[ "$mode" == format ]]; then
        arguments+=(--format)
    fi
    bash "${LINT_DIR}/${language}.sh" "${arguments[@]}"
done
