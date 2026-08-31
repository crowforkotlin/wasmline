#!/usr/bin/env bash

if [[ -n "${WASMLINE_VERSIONS_SH_LOADED:-}" ]]; then
  return 0
fi
WASMLINE_VERSIONS_SH_LOADED=1

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/paths.sh"

wasmline_version() {
  python3 -c \
    'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["versions"][sys.argv[2]])' \
    "${WASMLINE_PROJECT_ROOT}/versions.json" "$1"
}
