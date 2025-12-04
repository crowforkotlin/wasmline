#!/bin/bash

# Exit on any error
set -e


# Import environment variables
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname $(dirname "${BASH_SOURCE[0]}"))/context.sh"
fi

sh ${SCRIPT_ROOT}/samples/cpp/run.sh
sh ${SCRIPT_ROOT}/samples/go/run.sh