#!/usr/bin/env bash

set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_ROOT}/.." && pwd)"
MANIFEST_FILE="${REPOSITORY_ROOT}/scripts/versions.json"
CATALOG_FILE="${REPOSITORY_ROOT}/aot-compatibility.json"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/release.sh verify [release-tag]
  ./scripts/release.sh verify-tag <release-tag>
  ./scripts/release.sh prepare-assets [output-directory]
  ./scripts/release.sh write-notes <output-file>

The script validates release metadata and prepares generated GitHub Release
assets. Maven publication and tag creation remain explicit workflow actions.
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Required command was not found: %s\n' "$1" >&2
    exit 2
  }
}

json_value() {
  jq -er "$1" "${MANIFEST_FILE}"
}

catalog_value() {
  jq -er "$@" "${CATALOG_FILE}"
}

wasmtime_tag_code() {
  local version="$1"
  local major minor patch
  IFS='.' read -r major minor patch <<<"${version}"
  [[ "${major}" =~ ^[0-9]+$ && "${minor}" =~ ^[0-9]+$ && "${patch}" =~ ^[0-9]+$ ]] || {
    printf 'Invalid Wasmtime version: %s\n' "${version}" >&2
    exit 2
  }
  ((10#${minor} <= 9 && 10#${patch} <= 9)) || {
    printf 'Wasmtime minor and patch versions must be single digits: %s\n' "${version}" >&2
    exit 2
  }
  printf '%d\n' "$((10#${major} * 100 + 10#${minor} * 10 + 10#${patch}))"
}

verify_tag() {
  local tag="$1"
  local wasmline_version wasmtime_version wasmtime_release_version expected_code
  local tag_version tag_code release_base catalog_version

  if [[ ! "${tag}" =~ ^release-([0-9]+\.[0-9]+\.[0-9]+)\.([0-9]+)$ ]]; then
    printf 'Release tag must use release-x.y.z.v: %s\n' "${tag}" >&2
    exit 2
  fi
  tag_version="${BASH_REMATCH[1]}"
  tag_code="${BASH_REMATCH[2]}"
  wasmline_version="$(json_value '.versions.wasmline_version')"
  wasmtime_version="$(json_value '.versions.wasmtime_version')"
  wasmtime_release_version="$(json_value '.versions.wasmtime_release_version')"
  expected_code="$(wasmtime_tag_code "${wasmtime_version}")"
  release_base="${wasmtime_release_version%.*}"
  catalog_version="$(jq -er '.currentWasmlineVersion' "${CATALOG_FILE}")"

  [[ "${tag_version}" == "${wasmline_version}" ]] || {
    printf 'Release tag Wasmline version %s does not match manifest %s.\n' \
      "${tag_version}" "${wasmline_version}" >&2
    exit 2
  }
  [[ "${tag_code}" == "${expected_code}" ]] || {
    printf 'Release tag Wasmtime code %s does not match %s (%s).\n' \
      "${tag_code}" "${wasmtime_version}" "${expected_code}" >&2
    exit 2
  }
  [[ "${release_base}" == "${wasmtime_version}" ]] || {
    printf 'Wasmtime distribution %s does not use upstream version %s.\n' \
      "${wasmtime_release_version}" "${wasmtime_version}" >&2
    exit 2
  }
  [[ "${catalog_version}" == "${wasmline_version}" ]] || {
    printf 'AOT catalog current version %s does not match manifest %s.\n' \
      "${catalog_version}" "${wasmline_version}" >&2
    exit 2
  }
  printf 'Release metadata is valid for %s.\n' "${tag}"
}

verify_repository() {
  require_command jq
  [[ -f "${MANIFEST_FILE}" ]] || {
    printf 'Version manifest was not found: %s\n' "${MANIFEST_FILE}" >&2
    exit 2
  }
  [[ -f "${CATALOG_FILE}" ]] || {
    printf 'AOT catalog was not found: %s\n' "${CATALOG_FILE}" >&2
    exit 2
  }
  "${REPOSITORY_ROOT}/scripts/wasmline" versions check
  "${REPOSITORY_ROOT}/scripts/wasmline" aot check
  printf 'Repository release inputs are synchronized.\n'
}

sha256_digest() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{ print $1 }'
  else
    printf 'A SHA-256 utility was not found.\n' >&2
    exit 2
  fi
}

prepare_assets() {
  local output_directory="${1:-${REPOSITORY_ROOT}/build/release}"
  local output_catalog="${output_directory}/aot-compatibility.json"
  local output_checksum="${output_directory}/aot-compatibility.json.sha256"
  local digest temporary_checksum

  verify_repository
  mkdir -p "${output_directory}"
  cp "${CATALOG_FILE}" "${output_catalog}"
  digest="$(sha256_digest "${output_catalog}")"
  temporary_checksum="$(mktemp "${output_checksum}.XXXXXX")"
  printf '%s  aot-compatibility.json\n' "${digest}" >"${temporary_checksum}"
  mv -f "${temporary_checksum}" "${output_checksum}"
  printf 'Release assets prepared in %s.\n' "${output_directory}"
}

write_notes() {
  local output_file="$1"
  local wasmline_version wasmtime_release_version generation distribution changed added
  local temporary_file

  require_command jq
  verify_repository
  mkdir -p "$(dirname "${output_file}")"
  wasmline_version="$(json_value '.versions.wasmline_version')"
  wasmtime_release_version="$(json_value '.versions.wasmtime_release_version')"
  generation="$(catalog_value '.ranges[-1].aotGeneration')"
  distribution="$(catalog_value '.ranges[-1].wasmtimeDistributionVersion')"
  changed="$(catalog_value '.ranges[-1].changedBackends | join(", ")')"
  added="$(catalog_value --arg version "${wasmline_version}" \
    '.ranges[-1].fromWasmlineVersion == $version')"
  temporary_file="$(mktemp "${output_file}.XXXXXX")"
  {
    printf '# Wasmline %s\n\n' "${wasmline_version}"
    printf -- '- Wasmtime fork distribution: `%s`\n' "${wasmtime_release_version}"
    printf -- '- AOT generation: `%s`\n' "${generation}"
    printf -- '- New AOT generation in this release: `%s`\n' "${added}"
    printf -- '- Changed backends: `%s`\n\n' "${changed}"
    printf 'Review the [AOT compatibility documentation](https://crowforkotlin.github.io/wasmline/en/docs/wasmtime-download) before selecting native artifacts.\n'
  } >"${temporary_file}"
  mv -f "${temporary_file}" "${output_file}"
}

main() {
  local command="${1:-verify}"
  case "${command}" in
    verify)
      verify_repository
      if [[ "${2:-}" != "" ]]; then
        verify_tag "$2"
      fi
      ;;
    verify-tag)
      [[ "${2:-}" != "" ]] || { usage >&2; exit 2; }
      verify_repository
      verify_tag "$2"
      ;;
    prepare-assets)
      prepare_assets "${2:-}"
      ;;
    write-notes)
      [[ "${2:-}" != "" ]] || { usage >&2; exit 2; }
      write_notes "$2"
      ;;
    -h|--help|help)
      usage
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
