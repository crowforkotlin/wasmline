#!/bin/bash

# Exit on any error
set -e


# Import environment variables
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
fi

sh ${PROJECT_ROOT}/script/configure.sh
sh ${PROJECT_ROOT}/script/build.sh
cd ${PROJECT_ROOT}

echo "[shell run.sh] --> -----------------------------"
./build/wasmline_sample