#!/usr/bin/env bash

# Shared CMake configuration for the C and C++ Component fixtures.

configure_component_sample() {
    local sample_dir="$1"
    local build_dir="$2"
    local wasi_toolchain
    local wit_bindgen
    local wasm_tools
    local generator

    if [[ -z "${WASI_SDK_PATH:-}" ]]; then
        printf 'WASI_SDK_PATH must point to a WASI SDK 33 installation.\n' >&2
        return 1
    fi

    wasi_toolchain="${WASI_SDK_PATH}/share/cmake/wasi-sdk.cmake"
    if [[ ! -f "$wasi_toolchain" ]]; then
        printf 'WASI SDK CMake toolchain not found: %s\n' "$wasi_toolchain" >&2
        return 1
    fi

    wit_bindgen="${WIT_BINDGEN_EXECUTABLE:-$(command -v wit-bindgen || true)}"
    wasm_tools="${WASM_TOOLS_EXECUTABLE:-$(command -v wasm-tools || true)}"
    for tool in "$wit_bindgen" "$wasm_tools"; do
        if [[ -z "$tool" || ! -x "$tool" ]]; then
            printf 'Required tool is not executable: %s\n' "${tool:-missing}" >&2
            return 1
        fi
    done

    generator="${CMAKE_GENERATOR:-Ninja}"
    if [[ "$generator" == Ninja ]] && ! command -v ninja >/dev/null 2>&1; then
        printf 'Ninja is required by the default CMake generator. Set CMAKE_GENERATOR to use another generator.\n' >&2
        return 1
    fi

    # A stale cache from a different source directory cannot be reused safely.
    if [[ -f "${build_dir}/CMakeCache.txt" ]] &&
        ! grep -Fqx "CMAKE_HOME_DIRECTORY:INTERNAL=${sample_dir}" "${build_dir}/CMakeCache.txt"; then
        rm -f "${build_dir}/CMakeCache.txt"
        rm -rf "${build_dir}/CMakeFiles"
    fi

    local cmake_arguments=(
        -S "$sample_dir"
        -B "$build_dir"
        -G "$generator"
        "-DCMAKE_TOOLCHAIN_FILE=${wasi_toolchain}"
        "-DWIT_BINDGEN_EXECUTABLE=${wit_bindgen}"
        "-DWASM_TOOLS_EXECUTABLE=${wasm_tools}"
    )
    if [[ -n "${WASI_PREVIEW1_ADAPTER:-}" ]]; then
        cmake_arguments+=("-DWASI_PREVIEW1_ADAPTER=${WASI_PREVIEW1_ADAPTER}")
    fi

    cmake "${cmake_arguments[@]}"
}
