#!/bin/bash

# Exit on any error
set -e

# ==============================================================================
# 1. Source environment context (Reuse context.sh)
# ==============================================================================
# Path resolution: scripts/samples/go/build.sh -> scripts/context.sh (3 levels up)
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname $(dirname $(dirname "${BASH_SOURCE[0]}")))/context.sh"
fi

echo "[shell go.sh] --> ---------------------------------"

# ==============================================================================
# 2. Variable definitions
# ==============================================================================
GO_ROOT="$SAMPLE_ROOT/go"
BUILD_ROOT="$GO_ROOT/build"
TARGET_BIN_NAME="WasmtimeSample"

echo "[shell go.sh] Project Root: $PROJECT_ROOT"
echo "[shell go.sh] Go Root     : $GO_ROOT"
echo "[shell go.sh] Build Dir   : $BUILD_ROOT"

# ==============================================================================
# 3. Configure and clean
# ==============================================================================
echo "[shell go.sh] --> Cleaning build directory..."
if [ -d "$BUILD_ROOT" ]; then
    rm -rf "$BUILD_ROOT"
fi
mkdir -p "$BUILD_ROOT"

# Copy Wasm resources
echo "[shell go.sh] --> Copying Wasm resources..."
cp "$WASM_ROOT"/*.wasm "$BUILD_ROOT/"

# ==============================================================================
# 4. Platform detection and library distribution
# ==============================================================================
OS_NAME=$(uname -s)
ARCH_NAME=$(uname -m)

LIB_SRC=""
LIB_DST_NAME=""
EXE_SUFFIX=""

echo "[shell go.sh] --> Detecting Platform: $OS_NAME ($ARCH_NAME)"

# Resolve wasmtime version and variant
WASMTIME_VER=$(resolve_wasmtime_version)
WASMTIME_VARIANT="${WASMTIME_VARIANT:-pulley}"
PLATFORM_BASE="$PLATFORMS_ROOT/$WASMTIME_VER/$WASMTIME_VARIANT"

if [[ "$OS_NAME" == "Darwin" ]]; then
    # macOS
    LIB_DST_NAME="libwasmtime.dylib"
    if [[ "$ARCH_NAME" == "arm64" ]]; then
        LIB_SRC="$PLATFORM_BASE/mac/aarch64/lib/$LIB_DST_NAME"
    else
        LIB_SRC="$PLATFORM_BASE/mac/x86_64/lib/$LIB_DST_NAME"
    fi
elif [[ "$OS_NAME" == "Linux" ]]; then
    # Linux
    LIB_DST_NAME="libwasmtime.so"
    if [[ "$ARCH_NAME" == "aarch64" ]]; then
        LIB_SRC="$PLATFORM_BASE/linux/aarch64/lib/$LIB_DST_NAME"
    else
        LIB_SRC="$PLATFORM_BASE/linux/x64/lib/$LIB_DST_NAME"
    fi
elif [[ "$OS_NAME" == CYGWIN* ]] || [[ "$OS_NAME" == MINGW* ]] || [[ "$OS_NAME" == MSYS* ]] || [[ "$OS_NAME" == Windows* ]]; then
    # Windows
    LIB_DST_NAME="wasmtime.dll"
    LIB_SRC="$PLATFORM_BASE/windows/x64/lib/$LIB_DST_NAME"
    EXE_SUFFIX=".exe"
else
    echo "Error: Unsupported OS: $OS_NAME"
    exit 1
fi

# Copy library files
if [ -f "$LIB_SRC" ]; then
    cp "$LIB_SRC" "$BUILD_ROOT/"
    echo "    [OK] Copied Runtime: $LIB_DST_NAME"
else
    echo "    [ERROR] Library not found at: $LIB_SRC"
    exit 1
fi

# ==============================================================================
# 5. Build
# ==============================================================================
echo "[shell go.sh] --> Compiling Go Binary..."

# Switch to Go source directory for compilation
cd "$GO_ROOT"

# Build output to build directory
go build -o "$BUILD_ROOT/$TARGET_BIN_NAME$EXE_SUFFIX" .

if [ $? -eq 0 ]; then
    echo "    [OK] Build Success: $BUILD_ROOT/$TARGET_BIN_NAME$EXE_SUFFIX"
else
    echo "    [ERROR] Go Build Failed"
    exit 1
fi
