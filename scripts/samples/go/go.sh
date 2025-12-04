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

if [[ "$OS_NAME" == "Darwin" ]]; then
    # MacOS
    LIB_DST_NAME="libwasmtime.dylib"
    if [[ "$ARCH_NAME" == "arm64" ]]; then
        LIB_SRC="$PLATFORMS_ROOT/mac/aarch64/lib/$LIB_DST_NAME"
    else
        LIB_SRC="$PLATFORMS_ROOT/mac/x86_64/lib/$LIB_DST_NAME"
    fi
elif [[ "$OS_NAME" == "Linux" ]]; then
    # Linux
    LIB_DST_NAME="libwasmtime.so"
    if [[ "$ARCH_NAME" == "aarch64" ]]; then
        LIB_SRC="$PLATFORMS_ROOT/linux/aarch64/lib/$LIB_DST_NAME"
    else
        LIB_SRC="$PLATFORMS_ROOT/linux/x86_64/lib/$LIB_DST_NAME"
    fi
elif [[ "$OS_NAME" == CYGWIN* ]] || [[ "$OS_NAME" == MINGW* ]] || [[ "$OS_NAME" == MSYS* ]] || [[ "$OS_NAME" == Windows* ]]; then
    # Windows
    LIB_DST_NAME="wasmtime.dll"
    LIB_SRC="$PLATFORMS_ROOT/windows/x64/lib/$LIB_DST_NAME"
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

# ==============================================================================
# 6. 运行 (Run) - 集成 run.sh 的 Windows 逻辑
# ==============================================================================
echo "[shell go.sh] --> Launching Application..."

# 切换到 build 目录准备运行
cd "$BUILD_ROOT"

case "$OS_NAME" in
    CYGWIN*|MINGW*|MSYS*|Windows*)
        echo "Detected: Windows (via shell like Git Bash/MSYS)"
        
        # --- 复用 run.sh 的 PATH 清洗逻辑 ---
        echo "[Setup] Cleaning PATH for Windows CMD..."
        CLEAN_PATH=""
        IFS=':' read -r -a PATH_ARRAY <<< "$PATH"
        for p in "${PATH_ARRAY[@]}"; do
            WIN_P=$(cygpath -w "$p" 2>/dev/null || echo "$p")
            # 过滤 Git, MSYS, Cygwin 防止环境干扰
            if [[ "$WIN_P" =~ [Gg][Ii][Tt] ]] || \
               [[ "$WIN_P" =~ [Mm][Ss][Yy][Ss] ]] || \
               [[ "$WIN_P" =~ [Cc][Yy][Gg][Ww][Ii][Nn] ]]; then
                continue
            fi
            CLEAN_PATH="${CLEAN_PATH}${WIN_P};"
        done
        CLEAN_PATH=${CLEAN_PATH%;}

        # --- 生成 exec.bat ---
        BAT_FILE="exec.bat"
        echo "@echo off" > "$BAT_FILE"
        # 设置清洗后的 PATH (当前目录优先级最高，确保找到 dll)
        echo "set PATH=.;${CLEAN_PATH}" >> "$BAT_FILE"
        echo "echo [BAT] Path Cleaned. Starting Go Sample..." >> "$BAT_FILE"
        echo "echo ---------------------------------------------------" >> "$BAT_FILE"
        # 运行 exe，传入 wasm 参数
        echo "$TARGET_BIN_NAME$EXE_SUFFIX plugin.wasm" >> "$BAT_FILE"
        echo "if %errorlevel% neq 0 (" >> "$BAT_FILE"
        echo "    echo." >> "$BAT_FILE"
        echo "    echo [ERROR] Crashed! Code: %errorlevel%" >> "$BAT_FILE"
        echo ")" >> "$BAT_FILE"
        echo "echo ---------------------------------------------------" >> "$BAT_FILE"
        echo "pause" >> "$BAT_FILE"

        # --- 弹窗运行 ---
        if [ -f "$TARGET_BIN_NAME$EXE_SUFFIX" ]; then
            echo "    [RUN] Opening external console..."
            cmd //c "start $BAT_FILE"
        else
            echo "Error: Binary not found!"
            exit 1
        fi
        ;;

    Darwin*)
        echo "Detected: macOS"
        export DYLD_LIBRARY_PATH=.:$DYLD_LIBRARY_PATH
        ./$TARGET_BIN_NAME plugin.wasm
        ;;
        
    Linux*)
        echo "Detected: Linux"
        export LD_LIBRARY_PATH=.:$LD_LIBRARY_PATH
        ./$TARGET_BIN_NAME plugin.wasm
        ;;
        
    *)
        echo "Detected: Other OS ($OS_NAME)"
        ./$TARGET_BIN_NAME plugin.wasm
        ;;
esac

echo "[shell go.sh] --> Done."