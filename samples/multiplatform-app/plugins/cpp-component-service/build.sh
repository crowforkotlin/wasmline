#!/usr/bin/env bash

# Builds the C++ fixture into one raw Component at `build/plugin.component.wasm`.
#
# Required environment:
#   WASI_SDK_PATH   WASI SDK 33 installation directory
#
# Optional environment:
#   WIT_BINDGEN_EXECUTABLE   wit-bindgen binary (default: PATH, then the cache)
#   WASM_TOOLS_EXECUTABLE    wasm-tools binary (default: PATH, then the cache)
#   WASI_PREVIEW1_ADAPTER    Preview 1 reactor adapter; resolved automatically
#                            when the Core Wasm imports wasi_snapshot_preview1
#   WASMLINE_TOOL_CACHE      Tool cache root (default: ~/.wasmline/tools)

set -euo pipefail

SAMPLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WIT="${SAMPLE_ROOT}/../../../../wasmline-plugin-core/src/main/resources/META-INF/wasmline/wit/wasmline-service"
TOOL_CACHE="${WASMLINE_TOOL_CACHE:-${HOME}/.wasmline/tools}"
WIT_BINDGEN_VERSION="0.57.1"
WASM_TOOLS_VERSION="1.262.0"
ADAPTER_VERSION="50.0.0"

: "${WASI_SDK_PATH:?WASI_SDK_PATH must point to a WASI SDK 33 installation}"

# Returns 0 when a tool reports exactly the pinned version prefix.
version_matches() {
    local tool="$1"
    local expected="$2"
    [[ "$("${tool}" --version 2>/dev/null)" == "${expected}"* ]]
}

# Resolves a Component tool. An explicit override must match the pinned version;
# otherwise PATH and then the verified cache are searched, and the first exact
# match wins. PATH commonly holds a different `wasm-tools` build, so a version
# mismatch falls through instead of failing. The cache layout written by
# `wasmline tools download` is `<tool>/<version>/<platform>/<archive>/<binary>`.
resolve_tool() {
    local override="$1"
    local name="$2"
    local tool_dir="$3"
    local version="$4"
    local env_name="$5"
    local expected="${6}"
    local on_path
    local cached

    if [[ -n "${override}" ]]; then
        if ! version_matches "${override}" "${expected}"; then
            printf '%s reported "%s", expected "%s"\n' \
                "${override}" "$("${override}" --version 2>/dev/null)" "${expected}" >&2
            exit 1
        fi
        printf '%s' "${override}"
        return
    fi

    on_path="$(command -v "${name}" 2>/dev/null || true)"
    if [[ -n "${on_path}" ]] && version_matches "${on_path}" "${expected}"; then
        printf '%s' "${on_path}"
        return
    fi

    while IFS= read -r cached; do
        if version_matches "${cached}" "${expected}"; then
            printf '%s' "${cached}"
            return
        fi
    done < <(find "${TOOL_CACHE}/${tool_dir}/${version}" -maxdepth 4 -type f -name "${name}" -perm -u+x 2>/dev/null | sort)

    printf 'Missing %s %s. Run `wasmline tools download --tool %s` or set %s.\n' \
        "${name}" "${version}" "${name}" "${env_name}" >&2
    exit 1
}

WIT_BINDGEN="$(resolve_tool "${WIT_BINDGEN_EXECUTABLE:-}" wit-bindgen wit_bindgen "${WIT_BINDGEN_VERSION}" WIT_BINDGEN_EXECUTABLE "wit-bindgen-cli ${WIT_BINDGEN_VERSION}")"
WASM_TOOLS="$(resolve_tool "${WASM_TOOLS_EXECUTABLE:-}" wasm-tools wasm_tools "${WASM_TOOLS_VERSION}" WASM_TOOLS_EXECUTABLE "wasm-tools ${WASM_TOOLS_VERSION}")"

BUILD="${SAMPLE_ROOT}/build"
rm -rf "${BUILD}"
mkdir -p "${BUILD}/generated"

"${WIT_BINDGEN}" cpp --world plugin --out-dir "${BUILD}/generated" "${WIT}"

# wit-bindgen 0.57.1 calls std::expected::value() on a move-only error type,
# which libc++ rejects. The generated code already checked has_value(), so the
# unchecked accessor is equivalent. Fails closed if the pinned output changes.
GENERATED_CPP="${BUILD}/generated/plugin.cpp"
if ! grep -qF 'std::move(result3).value()' "${GENERATED_CPP}"; then
    printf 'wit-bindgen 0.57.1 C++ compatibility expression not found in %s\n' "${GENERATED_CPP}" >&2
    exit 1
fi
sed -i 's/std::move(result3)\.value()/*std::move(result3)/' "${GENERATED_CPP}"

"${WASI_SDK_PATH}/bin/clang++" -std=c++23 -mexec-model=reactor "-I${BUILD}/generated" \
    "${GENERATED_CPP}" \
    "${BUILD}/generated/plugin_component_type.o" \
    "${SAMPLE_ROOT}/src/plugin_impl.cpp" \
    -o "${BUILD}/plugin.core.wasm"

# The adapter polyfills wasi_snapshot_preview1, so it is only needed when the
# compiled guest actually imports that interface, for example through iostream.
COMPONENT_ARGUMENTS=(component new "${BUILD}/plugin.core.wasm")
# `grep -q` is avoided: it exits before wasm-tools finishes, and `pipefail` then
# reports the resulting SIGPIPE as a failure.
if "${WASM_TOOLS}" print "${BUILD}/plugin.core.wasm" | grep wasi_snapshot_preview1 >/dev/null; then
    ADAPTER="${WASI_PREVIEW1_ADAPTER:-$(find "${TOOL_CACHE}/wasi_preview1_reactor_adapter/${ADAPTER_VERSION}" \
        -type f -name 'wasi_snapshot_preview1.reactor.wasm' 2>/dev/null | sort | head -1)}"
    if [[ ! -f "${ADAPTER}" ]]; then
        printf 'Core Wasm imports wasi_snapshot_preview1 but no adapter was found at "%s".\n' "${ADAPTER}" >&2
        printf 'Set WASI_PREVIEW1_ADAPTER or run `wasmline tools download --tool wasi-adapter`.\n' >&2
        exit 1
    fi
    COMPONENT_ARGUMENTS+=(--adapt "wasi_snapshot_preview1=${ADAPTER}")
fi
COMPONENT_ARGUMENTS+=(-o "${BUILD}/plugin.component.wasm")

"${WASM_TOOLS}" "${COMPONENT_ARGUMENTS[@]}"
"${WASM_TOOLS}" validate "${BUILD}/plugin.component.wasm"
"${WASM_TOOLS}" component wit "${BUILD}/plugin.component.wasm"
