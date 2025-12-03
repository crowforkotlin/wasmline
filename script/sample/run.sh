#!/bin/bash

# Exit on any error
set -e


# Import environment variables
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname "${BASH_SOURCE[0]}")/context.sh"
fi
echo $ENV_SCRIPT_DIR
sh ${ENV_SCRIPT_DIR}/configure.sh
sh ${ENV_SCRIPT_DIR}/build.sh
cd ${PROJECT_ROOT}/build

echo "[shell run.sh] --> -----------------------------"
./WasmtimeSample