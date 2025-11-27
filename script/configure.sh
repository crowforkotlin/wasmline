#!/bin/bash

# Exit on any error
set -e

# Import environment variables
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
fi

echo "[shell configured.sh] --> -----------------------------"

# --- CLEANING ---

# Remove old build directory
if [ -d "$BUILD_DIR" ]; then
    echo "[shell configured.sh] --> Removing existing build directory: $BUILD_DIR"
    rm -rf "$BUILD_DIR"
fi


# --- CONFIGURATION ---

# Create and enter the build directory
echo "[shell configured.sh] --> Creating build directory: $BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Run CMake to generate Makefiles
echo "[shell configured.sh] --> Running CMake..."
cmake ..

# --- DONE ---

echo "[shell configured.sh] --> Configuration SUCCESS."