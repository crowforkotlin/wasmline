#!/usr/bin/env bash

# Shared repository paths and Wasmtime version resolution.

if [[ -n "${WASMLINE_CONTEXT_SH_LOADED:-}" ]]; then
    return 0
fi
WASMLINE_CONTEXT_SH_LOADED=1

CONTEXT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CONTEXT_DIR}/style.sh"

export PROJECT_ROOT="$(cd "${CONTEXT_DIR}/../.." && pwd)"
export SCRIPTS_ROOT="${PROJECT_ROOT}/scripts"
export BUILD_ROOT="${PROJECT_ROOT}/build"
export PLATFORMS_ROOT="${BUILD_ROOT}/platforms"
export TEMP_WORK_DIR="${PLATFORMS_ROOT}/.temp_work"

export ENV_SOURCED_MARKER=true

if [[ "$PWD" != "$PROJECT_ROOT" ]]; then
    cd "$PROJECT_ROOT"
fi

# Resolves the Wasmtime release tag directory name (for example, release-v47.0.2).
# Priority: WASMTIME_VERSION > scripts/versions.json > latest local asset directory.
resolve_wasmtime_version() {
    if [[ -n "${WASMTIME_VERSION:-}" ]]; then
        printf '%s\n' "$WASMTIME_VERSION"
        return
    fi

    local manifest="${SCRIPTS_ROOT}/versions.json"
    if [[ -f "$manifest" ]]; then
        local version
        version="$(python3 -c 'import json, sys; print("release-v" + json.load(open(sys.argv[1], encoding="utf-8"))["versions"]["wasmtime_version"])' "$manifest" 2>/dev/null || true)"
        if [[ -n "$version" ]]; then
            printf '%s\n' "$version"
            return
        fi
    fi

    local latest
    latest="$(find "$PLATFORMS_ROOT" -mindepth 1 -maxdepth 1 -type d -name 'release-v*' -exec basename {} \; 2>/dev/null | sort -V | tail -n 1)"
    printf '%s\n' "$latest"
}
