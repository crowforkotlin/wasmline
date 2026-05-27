#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CORE_SRC="${PROJECT_ROOT}/wasmline-core/src"
IOS_SRC="${SCRIPT_DIR}/src/iosMain/native"

TARGET_KIND="${1:-simulator-arm64}"
case "$TARGET_KIND" in
    simulator-arm64|simulator|sim)
        SDK="iphonesimulator"
        PLATFORM_DIR="${PROJECT_ROOT}/build/platforms/ios/simulator-arm64"
        BUILD_DIR="${SCRIPT_DIR}/build/ios/simulator-arm64"
        ;;
    arm64|device|ios-arm64)
        SDK="iphoneos"
        PLATFORM_DIR="${PROJECT_ROOT}/build/platforms/ios/arm64"
        BUILD_DIR="${SCRIPT_DIR}/build/ios/arm64"
        ;;
    *)
        echo "Unsupported iOS target kind: ${TARGET_KIND}" >&2
        echo "Supported values: simulator-arm64, arm64" >&2
        exit 1
        ;;
esac

HEADER_DIR="${PLATFORM_DIR}/include"
LIB_DIR="${PLATFORM_DIR}/lib"
if [ ! -f "${HEADER_DIR}/wasmtime.h" ]; then
    echo "Missing wasmtime header: ${HEADER_DIR}/wasmtime.h" >&2
    echo "Initialize iOS platform assets under build/platforms/ios before building." >&2
    exit 1
fi
if [ ! -f "${LIB_DIR}/libwasmtime.a" ]; then
    echo "Missing wasmtime static library: ${LIB_DIR}/libwasmtime.a" >&2
    echo "Initialize iOS platform assets under build/platforms/ios before building." >&2
    exit 1
fi

INCLUDE_DIRS="-I${PROJECT_ROOT}/wasmline-core/include \
              -I${PROJECT_ROOT}/wasmline-core/include/extensions \
              -I${IOS_SRC} \
              -I${HEADER_DIR}"

mkdir -p "${BUILD_DIR}"

ARCH="arm64"

SOURCES=(
    "$CORE_SRC/Api.cpp"
    "$CORE_SRC/Engine.cpp"
    "$CORE_SRC/Module.cpp"
    "$CORE_SRC/Session.cpp"
    "$CORE_SRC/extensions/FileUtils.cpp"
    "$IOS_SRC/WasmlineNative.cpp"
    "$IOS_SRC/IosLogger.cpp"
)

echo "Compiling object files for ${TARGET_KIND}..."

OBJECTS=""

for src in "${SOURCES[@]}"; do
    filename=$(basename "$src")
    objname="${filename%.*}.o"
    output="$BUILD_DIR/$objname"

    echo "  Compiling $filename..."

    clang++ -c -std=c++17 -x c++ \
        -arch "$ARCH" \
        -isysroot "$(xcrun --sdk "$SDK" --show-sdk-path)" \
        $INCLUDE_DIRS \
        "$src" -o "$output"

    OBJECTS="$OBJECTS $output"
done

echo "Creating static library..."
ar rcs "${BUILD_DIR}/libwasmline_core_ios.a" $OBJECTS

if [ -f "${BUILD_DIR}/libwasmline_core_ios.a" ]; then
    echo "✅ Done. Library created at: ${BUILD_DIR}/libwasmline_core_ios.a"
else
    echo "❌ Failed to create library."
    exit 1
fi
