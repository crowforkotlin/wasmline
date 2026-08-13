#!/usr/bin/env bash

set -euo pipefail

SAMPLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SAMPLE_ROOT}/../lib/configure-component.sh"

usage() {
    cat <<'EOF'
Usage: bash wasmline-samples/cpp/configure.sh

Configures the C++ sample-component-plugin fixture.

Required environment:
  WASI_SDK_PATH              WASI SDK 33 installation directory

Optional environment:
  WIT_BINDGEN_EXECUTABLE     Path to wit-bindgen (default: wit-bindgen on PATH)
  WASM_TOOLS_EXECUTABLE      Path to wasm-tools (default: wasm-tools on PATH)
  WASI_PREVIEW1_ADAPTER      Preview 1 reactor adapter, when needed by the SDK
  CMAKE_GENERATOR            CMake generator (default: Ninja)
EOF
}

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
    usage
    exit 0
fi

configure_component_sample "${SAMPLE_ROOT}/sample-component-plugin" "${SAMPLE_ROOT}/build"
