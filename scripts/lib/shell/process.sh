#!/usr/bin/env bash

if [[ -n "${WASMLINE_PROCESS_SH_LOADED:-}" ]]; then
  return 0
fi
WASMLINE_PROCESS_SH_LOADED=1

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/output.sh"

wasmline_require_command() {
  command -v "$1" >/dev/null 2>&1 || wasmline_die "$1 was not found in PATH."
}

wasmline_cpu_count() {
  if command -v nproc >/dev/null 2>&1; then
    nproc
  elif command -v sysctl >/dev/null 2>&1; then
    sysctl -n hw.ncpu 2>/dev/null || printf '4\n'
  else
    printf '4\n'
  fi
}

wasmline_replace_directory() {
  local staging="$1"
  local destination="$2"
  [[ -d "${staging}" ]] || wasmline_die "Staging directory was not found: ${staging}"

  local destination_parent
  destination_parent="$(dirname "${destination}")"
  mkdir -p "${destination_parent}"

  local backup_root backup
  backup_root="$(mktemp -d "${destination_parent}/.wasmline-backup.XXXXXX")"
  backup="${backup_root}/previous"
  local had_destination=false
  if [[ -e "${destination}" || -L "${destination}" ]]; then
    mv "${destination}" "${backup}"
    had_destination=true
  fi

  if mv "${staging}" "${destination}"; then
    rm -rf "${backup_root}"
    return 0
  fi

  if [[ "${had_destination}" == true ]]; then
    mv "${backup}" "${destination}"
  fi
  rm -rf "${backup_root}"
  wasmline_die "Cannot replace directory: ${destination}"
}
