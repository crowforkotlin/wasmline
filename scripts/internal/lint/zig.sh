#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../../lib/shell/lint.sh"

usage() {
  cat <<'EOF'
Usage: ./scripts/wasmline lint [--changed|--all] [--format] zig

Checks Zig and ZON sources with Zig's built-in formatter and AST validation.
The default scope is changed and untracked Zig files.
EOF
}

is_zig_source() {
  local file="$1"
  case "$file" in
  wasmline-multiplatform/wasmline/zig-pkg/* | */build/* | */.zig-cache/* | */zig-out/*)
    return 1
    ;;
  wasmline-multiplatform/wasmline/*.zig | wasmline-multiplatform/wasmline/*.zon | wasmline-multiplatform/wasmline/*/*.zig | wasmline-multiplatform/wasmline/*/*.zon)
    [[ "$file" == *.zig || "$file" == *.zon ]]
    ;;
  *)
    return 1
    ;;
  esac
}

collect_all_files() {
  WASMLINE_LINT_FILES=()
  local file
  while IFS= read -r -d '' file; do
    WASMLINE_LINT_FILES+=("${file#"${WASMLINE_PROJECT_ROOT}/"}")
  done < <(
    find "${WASMLINE_PROJECT_ROOT}/wasmline-multiplatform/wasmline" \
      -type d \( -name build -o -name .zig-cache -o -name zig-out -o -name zig-pkg \) -prune -o \
      -type f \( -name '*.zig' -o -name '*.zon' \) -print0
  )
}

split_zig_files() {
  WASMLINE_LINT_ZIG_FILES=()
  WASMLINE_LINT_ZON_FILES=()

  local file
  for file in "${WASMLINE_LINT_FILES[@]}"; do
    case "$file" in
    *.zig) WASMLINE_LINT_ZIG_FILES+=("$file") ;;
    *.zon) WASMLINE_LINT_ZON_FILES+=("$file") ;;
    esac
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
  wasmline_collect_changed_files_matching is_zig_source
fi

if ((${#WASMLINE_LINT_FILES[@]} == 0)); then
  wasmline_log_no_files Zig
  exit 0
fi

if ! command -v zig >/dev/null 2>&1; then
  wasmline_status ERR "Zig" "Not found in PATH."
  exit 1
fi

wasmline_title ""
wasmline_status INFO "Zig" "$(wasmline_lint_action) ${#WASMLINE_LINT_FILES[@]} file(s)."

cd "$WASMLINE_PROJECT_ROOT"
split_zig_files
if [[ "$WASMLINE_LINT_MODE" == format ]]; then
  if ((${#WASMLINE_LINT_ZIG_FILES[@]} > 0)); then
    zig fmt "${WASMLINE_LINT_ZIG_FILES[@]}"
  fi
  if ((${#WASMLINE_LINT_ZON_FILES[@]} > 0)); then
    zig fmt --zon "${WASMLINE_LINT_ZON_FILES[@]}"
  fi
else
  if ((${#WASMLINE_LINT_ZIG_FILES[@]} > 0)); then
    zig fmt --check --ast-check "${WASMLINE_LINT_ZIG_FILES[@]}"
  fi
  if ((${#WASMLINE_LINT_ZON_FILES[@]} > 0)); then
    zig fmt --zon --check --ast-check "${WASMLINE_LINT_ZON_FILES[@]}"
  fi
fi

wasmline_status OK "Zig" "${WASMLINE_LINT_MODE} passed."
