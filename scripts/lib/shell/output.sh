#!/usr/bin/env bash

if [[ -n "${WASMLINE_OUTPUT_SH_LOADED:-}" ]]; then
  return 0
fi
WASMLINE_OUTPUT_SH_LOADED=1

if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
  WASMLINE_COLOR_GREEN=$'\033[1;32m'
  WASMLINE_COLOR_RED=$'\033[1;31m'
  WASMLINE_COLOR_YELLOW=$'\033[1;33m'
  WASMLINE_COLOR_CYAN=$'\033[1;36m'
  WASMLINE_COLOR_RESET=$'\033[0m'
else
  WASMLINE_COLOR_GREEN=''
  WASMLINE_COLOR_RED=''
  WASMLINE_COLOR_YELLOW=''
  WASMLINE_COLOR_CYAN=''
  WASMLINE_COLOR_RESET=''
fi

wasmline_title() {
  if [[ -n "$1" ]]; then
    printf '%s\n\n' "$1" >&2
  else
    printf '\n' >&2
  fi
}

wasmline_status() {
  local status="$1"
  local label="$2"
  local details="$3"
  local color=''
  case "${status}" in
    OK) color="${WASMLINE_COLOR_GREEN}" ;;
    ERR) color="${WASMLINE_COLOR_RED}" ;;
    SKIP) color="${WASMLINE_COLOR_YELLOW}" ;;
    INFO) color="${WASMLINE_COLOR_CYAN}" ;;
  esac
  if [[ -n "${label}" ]]; then
    printf '%s%-4s%s    %s    %s\n' \
      "${color}" "${status}" "${WASMLINE_COLOR_RESET}" "${label}" "${details}" >&2
  else
    printf '%s%-4s%s    %s\n' \
      "${color}" "${status}" "${WASMLINE_COLOR_RESET}" "${details}" >&2
  fi
}

wasmline_die() {
  wasmline_status ERR "Command" "$1"
  exit "${2:-1}"
}
