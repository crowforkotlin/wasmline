#!/bin/bash
set -e

# ==========================================
# 1. 配置 NDK 路径
# ⚠️⚠️⚠️ 请务必确认下面这个路径存在！⚠️⚠️⚠️
# 如果你的版本不是 26.1.10909125，请去 ~/Library/Android/sdk/ndk/ 目录下看一眼
export ANDROID_NDK="/Users/crowforkotlin/Library/Android/sdk/ndk/26.1.10909125"

if [ ! -d "$ANDROID_NDK" ]; then
    echo "Error: NDK 路径不存在: $ANDROID_NDK"
    echo "请修改 configure.sh 中的 ANDROID_NDK 变量"
    exit 1
fi

# ==========================================
# 2. 清理旧构建 (这一步非常重要，必须删掉旧的缓存)
# ==========================================
BUILD_DIR="build"
echo "[configure] Cleaning build directory..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# ==========================================
# 3. 生成构建文件 (Configuring)
# ==========================================
cd "$BUILD_DIR"

echo "[configure] Running CMake with Android NDK Toolchain..."

# 注意：CMakeLists.txt 在 build 目录的上一级，所以用 ..
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-24" \
    -DANDROID_STL="c++_shared" \
    -DCMAKE_BUILD_TYPE=Debug

echo "[configure] Configuration SUCCESS."