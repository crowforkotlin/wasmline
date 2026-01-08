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

sh ${SCRIPT_ROOT}/samples/go/build.sh
cd ${SAMPLE_ROOT}/go/build

echo "[shell run.sh] --> ---------------------------------"

GO_ROOT="$SAMPLE_ROOT/go"
BUILD_ROOT="$GO_ROOT/build"
TARGET_BIN_NAME="WasmtimeSample"

# ==============================================================================
# 6. 运行 (Run) - 集成 run.sh 的 Windows 逻辑
# ==============================================================================
echo "[shell run.sh] --> Launching Application..."

# 切换到 build 目录准备运行
cd "$BUILD_ROOT"
OS_NAME=$(uname -s)
ARCH_NAME=$(uname -m)
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
        echo "echo ==============================================================">> "$BAT_FILE"
        echo "echo ==============================================================">> "$BAT_FILE"
        echo "echo ^> Go" >>"$BAT_FILE"
        echo "echo ==============================================================">> "$BAT_FILE"
        echo "echo ==============================================================">> "$BAT_FILE"
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

echo "[shell run.sh] --> Done."