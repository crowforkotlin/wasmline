#!/bin/bash

# Exit on any error
set -e

# Import environment variables
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
fi

echo "[shell build.sh] --> -------------------------------"

# --- VALIDATION ---

echo "[shell build.sh] --> Checking build directory..."

# Check if the build directory exists
if [ ! -d "$BUILD_DIR" ]; then
    echo "Error: Build directory '$BUILD_DIR' not found."
    echo "Please run './configure.sh' first."
    echo "----------------------------------------"
    exit 1
fi

echo "[shell build.sh] Directory found: $BUILD_DIR"

# --- COMPILATION ---

# Enter the build directory
cd "$BUILD_DIR"

# Run the build command
echo "[shell build.sh] --> Starting project build..."
cmake --build .

# --- DONE ---
echo "[shell build.sh] --> Executables are located in the '$BUILD_DIR' directory."
echo "[shell build.sh] --> Build SUCCESS."

