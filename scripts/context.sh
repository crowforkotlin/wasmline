#!/bin/bash

# Exit on any error
set -e

# --- Check and Import env.sh ---
# Use BASH_SOURCE[0] to ensure the path is correct even when sourced.

echo "[shell context.sh] --> -----------------------------"

export SCRIPT_ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
export PROJECT_ROOT="$(dirname "$SCRIPT_ROOT")"
export SAMPLE_ROOT="${PROJECT_ROOT}/samples"
export WASM_ROOT="${PROJECT_ROOT}/wasm"
export PLATFORMS_ROOT="${PROJECT_ROOT}/platforms"

# 3. Export variables for subsequent use
export ENV_SOURCED_MARKER="true"

# 4. [Crucial] Automatically switch the current working directory to the Project Root
if [ "$PWD" != "$PROJECT_ROOT" ]; then
    echo "[shell context.sh] --> Switching to Project Root: $PROJECT_ROOT"
    cd "$PROJECT_ROOT" || exit 1
fi

echo "[shell context.sh] --> Context Config Success!"