#!/bin/bash

# Exit on any error
set -e

# ==============================================================================
# 1. 引入环境上下文 (Reuse context.sh)
# ==============================================================================
# 路径推导：scripts/samples/go/go.sh -> scripts/context.sh (向上3层)
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname $(dirname $(dirname "${BASH_SOURCE[0]}")))/context.sh"
fi

echo "[shell go.sh] --> ---------------------------------"

# ==============================================================================
# 2. 变量定义
# ==============================================================================
GO_ROOT="$SAMPLE_ROOT/go"
BUILD_ROOT="$GO_ROOT/build"
TARGET_BIN_NAME="WasmtimeSample"

echo "[shell go.sh] Project Root: $PROJECT_ROOT"
echo "[shell go.sh] Go Root     : $GO_ROOT"
echo "[shell go.sh] Build Dir   : $BUILD_ROOT"

# ==============================================================================
# 3. 清理与初始化 (Configure & Clean)
# ==============================================================================
echo "[shell go.sh] --> Cleaning build directory..."
if [ -d "$BUILD_ROOT" ]; then
    rm -rf "$BUILD_ROOT"
fi
mkdir -p "$BUILD_ROOT"

# 复制 Wasm 资源
echo "[shell go.sh] --> Copying Wasm resources..."
cp "$WASM_ROOT"/*.wasm "$BUILD_ROOT/"

# ==============================================================================
# 4. 平台检测与库文件分发 (Platform Detection)
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
    # MacOS
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

# 复制库文件
if [ -f "$LIB_SRC" ]; then
    cp "$LIB_SRC" "$BUILD_ROOT/"
    echo "    [OK] Copied Runtime: $LIB_DST_NAME"
else
    echo "    [ERROR] Library not found at: $LIB_SRC"
    exit 1
fi

# ==============================================================================
# 5. 编译 (Build)
# ==============================================================================
echo "[shell go.sh] --> Compiling Go Binary..."

# 切换到 Go 源码目录进行编译
cd "$GO_ROOT"

# 编译输出到 build 目录
go build -o "$BUILD_ROOT/$TARGET_BIN_NAME$EXE_SUFFIX" .

if [ $? -eq 0 ]; then
    echo "    [OK] Build Success: $BUILD_ROOT/$TARGET_BIN_NAME$EXE_SUFFIX"
else
    echo "    [ERROR] Go Build Failed"
    exit 1
fi
