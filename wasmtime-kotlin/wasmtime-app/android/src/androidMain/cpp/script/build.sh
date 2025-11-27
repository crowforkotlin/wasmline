#!/bin/bash
set -e

BUILD_DIR="build"

echo "[build] Checking build directory..."
if [ ! -d "$BUILD_DIR" ]; then
    echo "Error: Build directory not found. Run configure.sh first."
    exit 1
fi

# ==========================================
# 开始编译
# ==========================================
cd "$BUILD_DIR"

echo "[build] Starting compilation..."
# --build . 表示编译当前目录下的项目
# --verbose 可以看到详细的编译命令（可选）
cmake --build .

echo "[build] Build SUCCESS."