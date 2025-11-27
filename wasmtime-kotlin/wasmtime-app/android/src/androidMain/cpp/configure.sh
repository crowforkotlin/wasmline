#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

# Define the build directory name
BUILD_DIR="build"

echo "--- Starting Project Configuration ---"

# Clean up the old build directory if it exists
if [ -d "$BUILD_DIR" ]; then
    echo "Removing existing build directory: $BUILD_DIR"
    rm -rf "$BUILD_DIR"
fi

# Create a new build directory
echo "Creating new build directory: $BUILD_DIR"
mkdir "$BUILD_DIR"

# Enter the build directory to run cmake
cd "$BUILD_DIR"

# Run CMake to generate the Makefiles
# This is where the project is configured.
echo "Running CMake to generate Makefiles..."
cmake -G "MinGW Makefiles" ..

echo "--- Configuration complete. You can now run ./build.sh to compile. ---"