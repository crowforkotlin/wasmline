#!/bin/bash

# ==============================================================================
# Global Context & Environment Setup
# ==============================================================================

if [ -n "$ENV_SOURCED_MARKER" ]; then return 0; fi

# --- Path Resolution ---
CURRENT_SCRIPT_PATH="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$( cd "$( dirname "$CURRENT_SCRIPT_PATH" )" && pwd )"

# --- Import Style ---
STYLE_FILE="${SCRIPT_DIR}/style.sh"
if [ -f "$STYLE_FILE" ]; then source "$STYLE_FILE"; else echo "Error: style.sh missing"; exit 1; fi

# --- Project Structure ---
export PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
export BUILD_ROOT="${PROJECT_ROOT}/build"
export PLATFORMS_ROOT="${BUILD_ROOT}/platforms"
export TEMP_WORK_DIR="${PLATFORMS_ROOT}/.temp_work"

# --- Environment ---
if [ "$PWD" != "$PROJECT_ROOT" ]; then
    cd "$PROJECT_ROOT" || { log_error "Cannot switch to project root"; exit 1; }
fi

export ENV_SOURCED_MARKER="true"
log_info "Context loaded. Root: ${PROJECT_ROOT}"