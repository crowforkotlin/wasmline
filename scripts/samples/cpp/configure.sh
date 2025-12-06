#!/bin/bash

# Exit on any error
set -e

# Import environment variables
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname $(dirname $(dirname "${BASH_SOURCE[0]}")))/context.sh"
fi

BUILD_DIR="$SAMPLE_ROOT/cpp/build"

echo "[shell configured.sh] --> -----------------------------"

# --- CLEANING ---

# Remove old build directory
if [ -d "$BUILD_DIR" ]; then
    echo "[shell configured.sh] --> Removing existing build directory: $BUILD_DIR"
    rm -rf "$BUILD_DIR"
fi


# --- CONFIGURATION ---

# Create and enter the build directory
echo $BUILD_DIR
echo "[shell configured.sh] --> Creating build directory: $BUILD_DIR"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

OS_NAME=$(uname -s)
ARCH_NAME=$(uname -m)

# Run CMake to generate Makefiles
echo "[shell configured.sh] --> Running CMake..."


if [[ "$OS_NAME" == "Darwin" ]]; then
    cmake ..
else 
    cmake -G "MinGW Makefiles" ..
fi

# --- DONE ---

echo "[shell configured.sh] --> Configuration SUCCESS."