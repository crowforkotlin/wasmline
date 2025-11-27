#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

# Define the build directory name
BUILD_DIR="build"

echo "--- Starting Project Build ---"

# Check if the build directory exists. If not, remind the user to configure first.
if [ ! -d "$BUILD_DIR" ]; then
    echo "Error: Build directory '$BUILD_DIR' not found."
    echo "Please run ./configure.sh first to set up the project."
    exit 1
fi

# Enter the build directory
cd "$BUILD_DIR"

# Run the build command
# This calls 'make' (or the equivalent) to compile the code.
echo "Building the project..."

cmake --build .

echo "--- Build complete! Executables are in the '$BUILD_DIR' directory. ---"