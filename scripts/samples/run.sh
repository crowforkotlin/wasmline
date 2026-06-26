#!/bin/bash

# ==============================================================================
# Run all sample programs (C++ and Go)
# ==============================================================================

# Exit on any error
set -e


# Source environment context
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname $(dirname "${BASH_SOURCE[0]}"))/context.sh"
fi

sh ${SCRIPT_ROOT}/samples/cpp/run.sh
sh ${SCRIPT_ROOT}/samples/go/run.sh