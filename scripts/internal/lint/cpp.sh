#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../../lib/shell/lint.sh"

usage() {
  cat <<'EOF'
Usage: ./scripts/wasmline lint [--changed|--all] [--format] cpp

Checks the repository's C/C++ core with wasmline-core/.clang-format.
The default scope is changed and untracked C/C++ files.
EOF
}

is_cpp_source() {
  local file="$1"
  case "$file" in
  */build/* | */.gradle/* | */.cxx/* | */CMakeFiles/* | */generated/* | */test-gen/*)
    return 1
    ;;
  wasmline-core/*)
    case "$file" in
    *.c | *.cc | *.cpp | *.cxx | *.h | *.hh | *.hpp | *.hxx) return 0 ;;
    esac
    ;;
  esac
  return 1
}

collect_all_files() {
  WASMLINE_LINT_FILES=()
  local roots=("${WASMLINE_PROJECT_ROOT}/wasmline-core")
  local root file
  for root in "${roots[@]}"; do
    [[ -d "$root" ]] || continue
    while IFS= read -r -d '' file; do
      WASMLINE_LINT_FILES+=("${file#"${WASMLINE_PROJECT_ROOT}/"}")
    done < <(
      find "$root" \
        -type d \( -name build -o -name .gradle -o -name .cxx -o -name CMakeFiles -o -name generated -o -name test-gen \) -prune -o \
        -type f \( -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.cxx' -o -name '*.h' -o -name '*.hh' -o -name '*.hpp' -o -name '*.hxx' \) -print0
    )
  done
}

wasmline_parse_lint_options "$@"
if [[ "$WASMLINE_LINT_SHOW_HELP" == true ]]; then
  usage
  exit 0
fi

if [[ "$WASMLINE_LINT_SCOPE" == all ]]; then
  collect_all_files
else
  wasmline_collect_changed_files_matching is_cpp_source
fi

if ((${#WASMLINE_LINT_FILES[@]} == 0)); then
  wasmline_log_no_files 'C/C++'
  exit 0
fi

if ! command -v clang-format >/dev/null 2>&1; then
  wasmline_status ERR "clang-format" "Not found in PATH."
  exit 1
fi

wasmline_title ""
wasmline_status INFO "C/C++" "$(wasmline_lint_action) ${#WASMLINE_LINT_FILES[@]} file(s)."

cd "$WASMLINE_PROJECT_ROOT"
CLANG_FORMAT_ARGS=(--style="file:${WASMLINE_PROJECT_ROOT}/wasmline-core/.clang-format")
if [[ "$WASMLINE_LINT_MODE" == format ]]; then
  CLANG_FORMAT_ARGS+=(-i)
else
  CLANG_FORMAT_ARGS+=(--dry-run --Werror)
fi
clang-format "${CLANG_FORMAT_ARGS[@]}" "${WASMLINE_LINT_FILES[@]}"

wasmline_status OK "C/C++" "${WASMLINE_LINT_MODE} passed."
