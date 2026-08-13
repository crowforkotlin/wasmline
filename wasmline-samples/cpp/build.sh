#!/usr/bin/env bash

set -euo pipefail

SAMPLE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

bash "${SAMPLE_ROOT}/configure.sh"
cmake --build "${SAMPLE_ROOT}/build" --target wasmline_component
