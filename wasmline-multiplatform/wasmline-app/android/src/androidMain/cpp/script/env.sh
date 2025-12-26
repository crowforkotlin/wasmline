#!/bin/bash

# Exit on any error
set -e

# --- Check and Import env.sh ---
# Use BASH_SOURCE[0] to ensure the path is correct even when sourced.


echo "[shell env.sh] --> -----------------------------"

# 1. Get the absolute path of the current script's directory (e.g., 'script/')
ENV_SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# 2. Get the Project Root Directory (Assuming env.sh is in 'script/', the parent is the root)
PROJECT_ROOT="$(dirname "$ENV_SCRIPT_DIR")"

# 3. Export variables for subsequent use
export ENV_SOURCED_MARKER="true"
export PROJECT_ROOT
export BUILD_DIR="$PROJECT_ROOT/build"


# 4. [Crucial] Automatically switch the current working directory to the Project Root
if [ "$PWD" != "$PROJECT_ROOT" ]; then
    echo "[shell env.sh] --> Switching to Project Root: $PROJECT_ROOT"
    cd "$PROJECT_ROOT" || exit 1
fi

echo "[shell env.sh] --> Env Config Success!"