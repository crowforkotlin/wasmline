#!/bin/bash

# ==============================================================================
# Compile wasmline core static library for iOS targets
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CORE_SRC="${PROJECT_ROOT}/wasmline-core/src"
IOS_SRC="${PROJECT_ROOT}/wasmline-multiplatform/wasmline/src/iosMain/native"

# Resolve wasmtime version tag (e.g. "release-v45.0.5")
if [ -z "${WASMTIME_VERSION:-}" ]; then
    WASMTIME_VERSION=$(python3 -c "import json;print('release-v'+json.load(open('${PROJECT_ROOT}/scripts/versions.json'))['versions']['wasmtime_version'])" 2>/dev/null || echo "release-v45.0.5")
fi

TARGET_KIND="${1:-simulator-arm64}"
case "$TARGET_KIND" in
    simulator-arm64|simulator|sim)
        SDK="iphonesimulator"
        PLATFORM_DIR="${PROJECT_ROOT}/build/platforms/${WASMTIME_VERSION}/pulley/ios/simulator-arm64"
        BUILD_DIR="${SCRIPT_DIR}/build/ios/simulator-arm64"
        ;;
    arm64|device|ios-arm64)
        SDK="iphoneos"
        PLATFORM_DIR="${PROJECT_ROOT}/build/platforms/${WASMTIME_VERSION}/pulley/ios/arm64"
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
              -I${PROJECT_ROOT}/wasmline-core/src \
              -I${IOS_SRC} \
              -I${HEADER_DIR}"

mkdir -p "${BUILD_DIR}"

ARCH="arm64"

SOURCES=(
    "$CORE_SRC/api/Api.cpp"
    "$CORE_SRC/runtime/Component.cpp"
    "$CORE_SRC/runtime/ComponentSession.cpp"
    "$CORE_SRC/runtime/Engine.cpp"
    "$CORE_SRC/runtime/Module.cpp"
    "$CORE_SRC/runtime/RawModuleSession.cpp"
    "$CORE_SRC/runtime/Session.cpp"
    "$CORE_SRC/value/ComponentValue.cpp"
    "$CORE_SRC/invocation/InvocationResult.cpp"
    "$CORE_SRC/invocation/TypedInvocationCodec.cpp"
    "$CORE_SRC/protocol/WasmlineProtocol.cpp"
    "$CORE_SRC/io/FileIO.cpp"
    "$CORE_SRC/wasmtime/WasmtimeMessage.cpp"
    "$CORE_SRC/wasi/WasiConfig.cpp"
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
