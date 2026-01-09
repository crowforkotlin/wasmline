#!/bin/bash

# 1. 基础路径 (往上跳3级回到根目录)
PROJECT_ROOT="../../.."
CORE_SRC="$PROJECT_ROOT/wasmline-core/src"
IOS_SRC="src/iosMain/native"

# 2. 头文件路径 (包含 wasmtime.h)
# 请确保 $PROJECT_ROOT/platforms/ios/include 下有 wasmtime.h
INCLUDE_DIRS="-I$PROJECT_ROOT/wasmline-core/include \
              -I$PROJECT_ROOT/wasmline-core/include/extensions \
              -I$IOS_SRC \
              -I$PROJECT_ROOT/platforms/ios/include"

BUILD_DIR="build/ios"
mkdir -p $BUILD_DIR

SDK="iphonesimulator"
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

echo "Compiling object files..."

OBJECTS=""

for src in "${SOURCES[@]}"; do
    filename=$(basename "$src")
    objname="${filename%.*}.o"
    output="$BUILD_DIR/$objname"

    echo "  Compiling $filename..."

    clang++ -c -std=c++17 -x c++ \
        -arch $ARCH \
        -isysroot $(xcrun --sdk $SDK --show-sdk-path) \
        $INCLUDE_DIRS \
        "$src" -o "$output"

    if [ $? -ne 0 ]; then
        echo "❌ Error compiling $filename"
        exit 1
    fi

    OBJECTS="$OBJECTS $output"
done

echo "Creating static library..."
ar rcs $BUILD_DIR/libwasmline_core_ios.a $OBJECTS

if [ -f "$BUILD_DIR/libwasmline_core_ios.a" ]; then
    echo "✅ Done. Library created at: $BUILD_DIR/libwasmline_core_ios.a"
else
    echo "❌ Failed to create library."
    exit 1
fi