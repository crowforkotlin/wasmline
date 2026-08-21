#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../../lib/shell/lint.sh"

usage() {
  cat <<'EOF'
Usage: ./scripts/wasmline lint [--changed|--all] [--format] kotlin

Checks Kotlin sources managed by wasmline-multiplatform/.editorconfig.
The default scope is changed and untracked Kotlin files.
EOF
}

is_kotlin_source() {
  local file="$1"
  case "$file" in
  wasmline-multiplatform/wasmline-build-logic/* | */build/* | */test-gen/*)
    return 1
    ;;
  wasmline-multiplatform/*)
    case "$file" in
    *.kt | *.kts) return 0 ;;
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
    wasmline_status INFO "ktlint" "Ignoring ${actual_version:-unknown} from PATH; requires ${expected_version}."
  fi

  local cache_dir cached_ktlint temporary_ktlint
  cache_dir="${WASMLINE_BUILD_ROOT}/tools/ktlint/${expected_version}"
  cached_ktlint="${cache_dir}/ktlint"
  if [[ -x "$cached_ktlint" ]]; then
    actual_version="$(ktlint_version "$cached_ktlint")"
    if [[ "$actual_version" == "$expected_version" ]]; then
      printf '%s\n' "$cached_ktlint"
      return
    fi
  fi

  if ! command -v curl >/dev/null 2>&1; then
    wasmline_status ERR "ktlint" "curl is required to download ${expected_version}."
    return 1
  fi

  mkdir -p "$cache_dir"
  temporary_ktlint="$(mktemp "${cache_dir}/.ktlint.XXXXXX")"
  wasmline_status INFO "ktlint" "Downloading ${expected_version}."
  if ! curl --fail --location --silent --show-error --retry 3 \
    "https://github.com/ktlint/ktlint/releases/download/${expected_version}/ktlint" \
    --output "$temporary_ktlint"; then
    rm -f "$temporary_ktlint"
    wasmline_status ERR "ktlint" "Download failed for ${expected_version}."
    return 1
  fi
  chmod +x "$temporary_ktlint"
  actual_version="$(ktlint_version "$temporary_ktlint")"
  if [[ "$actual_version" != "$expected_version" ]]; then
    rm -f "$temporary_ktlint"
    wasmline_status ERR "ktlint" "Downloaded ${actual_version:-unknown}; expected ${expected_version}."
    return 1
  fi
  mv "$temporary_ktlint" "$cached_ktlint"
  printf '%s\n' "$cached_ktlint"
}

collect_all_files() {
  WASMLINE_LINT_FILES=()
  local roots=("${WASMLINE_PROJECT_ROOT}/wasmline-multiplatform")
  local root file
  for root in "${roots[@]}"; do
    [[ -d "$root" ]] || continue
    while IFS= read -r -d '' file; do
      WASMLINE_LINT_FILES+=("${file#"${WASMLINE_PROJECT_ROOT}/"}")
    done < <(
      find "$root" \
        -type d \( -name build -o -name .gradle -o -name .kotlin -o -name test-gen -o -name wasmline-build-logic \) -prune -o \
        -type f \( -name '*.kt' -o -name '*.kts' \) -print0
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
  wasmline_collect_changed_files_matching is_kotlin_source
fi

if ((${#WASMLINE_LINT_FILES[@]} == 0)); then
  wasmline_log_no_files Kotlin
  exit 0
fi

KTLINT_VERSION="$(wasmline_version ktlint_version)"
KTLINT_BINARY="$(resolve_ktlint "$KTLINT_VERSION")"

wasmline_title ""
wasmline_status INFO "ktlint" "Using ${KTLINT_VERSION} from ${KTLINT_BINARY}."
wasmline_status INFO "Kotlin" "$(wasmline_lint_action) ${#WASMLINE_LINT_FILES[@]} file(s)."

cd "$WASMLINE_PROJECT_ROOT"
KTLINT_ARGS=(--relative --editorconfig "wasmline-multiplatform/.editorconfig")
if [[ "$WASMLINE_LINT_MODE" == format ]]; then
  KTLINT_ARGS+=(--format)
fi
"$KTLINT_BINARY" "${KTLINT_ARGS[@]}" "${WASMLINE_LINT_FILES[@]}" \
  2> >(sed '/^Picked up JAVA_TOOL_OPTIONS:/d' >&2)

wasmline_status OK "Kotlin" "${WASMLINE_LINT_MODE} passed."
