#!/bin/bash

# ==============================================================================
# CI: Run sample checks for wasmline-multiplatform
# ==============================================================================

cd "$(dirname "$0")/../wasmline-multiplatform" || exit

# Execute sample module verification tests
./gradlew :wasmline-sample:common:check :wasmline-sample:multiplatform:check --no-daemon
