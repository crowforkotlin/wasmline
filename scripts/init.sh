#!/bin/bash

# ==========================================
# Wasmtime C-API 初始化脚本 (精简版)
# ==========================================

# 输出根目录
ROOT_OUT="platforms"
REPO="bytecodealliance/wasmtime"
# 临时工作目录 (仅用于存放当前的单个任务)
TEMP_WORK_DIR="${ROOT_OUT}/temp_single_task_work"

# 初始化目录
rm -rf "$TEMP_WORK_DIR"
mkdir -p "$TEMP_WORK_DIR"
mkdir -p "$ROOT_OUT"

echo "=== Wasmtime SDK 初始化 ==="
echo "输出位置: $ROOT_OUT"

# 1. 获取版本信息
echo "正在检查最新版本..."
LATEST_RELEASE_URL="https://api.github.com/repos/$REPO/releases/latest"
RESPONSE=$(curl -s $LATEST_RELEASE_URL)

TAG_NAME=$(echo "$RESPONSE" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')
if [ -z "$TAG_NAME" ]; then
    echo "Error: 获取版本失败，请检查网络。"
    exit 1
fi
echo "目标版本: $TAG_NAME"

# 2. 提取下载链接
DOWNLOAD_URLS=$(echo "$RESPONSE" | grep '"browser_download_url":' | grep '\-c-api' | sed -E 's/.*"([^"]+)".*/\1/')

# ==========================================
# 单个任务处理函数
# ==========================================
process_single_task() {
    local url=$1
    local filename=$2
    local target_platform=$3
    
    local final_path="$ROOT_OUT/$target_platform"
    local archive_file="$TEMP_WORK_DIR/$filename"
    local extract_dir="$TEMP_WORK_DIR/${filename}_ext"

    echo "----------------------------------------"
    echo "任务: $target_platform"
    echo "文件: $filename"
    
    # 1. 下载
    echo -n "正在下载... "
    curl -L -o "$archive_file" "$url"
    
    if [ ! -f "$archive_file" ]; then
        echo "失败!"
        return
    fi
    echo "完成"

    # 2. 解压
    echo "正在解压... "
    mkdir -p "$extract_dir"
    if [[ "$filename" == *.zip ]]; then
        unzip -q -o "$archive_file" -d "$extract_dir"
    elif [[ "$filename" == *.tar.xz ]]; then
        tar -xf "$archive_file" -C "$extract_dir"
    fi
    echo "完成"

    # >>> 关键点：解压后立即删除压缩包 <<<
    rm -f "$archive_file"

    # 3. 部署文件 (Include/Lib)
    # 查找 include 所在的目录层级
    local content_root=$(find "$extract_dir" -type d -name "include" | head -n 1 | xargs dirname)

    if [ -n "$content_root" ]; then
        # 建立目标目录
        mkdir -p "$final_path"
        # 清理旧数据
        rm -rf "$final_path/include" "$final_path/lib"
        
        # 移动文件
        mv "$content_root/include" "$final_path/"
        mv "$content_root/lib" "$final_path/"
        echo "已部署至: $final_path"
    else
        echo "Error: 压缩包内容结构异常"
    fi

    # >>> 关键点：部署后立即删除解压目录 <<<
    rm -rf "$extract_dir"
}

# ==========================================
# 串行执行任务
# ==========================================

for url in $DOWNLOAD_URLS; do
    filename=$(basename "$url")

    # 按需匹配架构，匹配到一个处理一个，处理完删除一个，再进行下一个

    # 1. Android (arm64-v8a)
    if [[ "$filename" == *"aarch64-android-c-api"* ]]; then
        process_single_task "$url" "$filename" "android/arm64-v8a"

    # 2. Linux (aarch64)
    elif [[ "$filename" == *"aarch64-linux-c-api"* ]]; then
        process_single_task "$url" "$filename" "linux/aarch64"

    # 3. macOS (aarch64)
    elif [[ "$filename" == *"aarch64-macos-c-api"* ]]; then
        process_single_task "$url" "$filename" "mac/aarch64"

    # 4. Windows (x64)
    elif [[ "$filename" == *"x86_64-windows-c-api"* ]]; then
        process_single_task "$url" "$filename" "windows/x64"
    fi
done

# 最后清理空的临时工作根目录
rm -rf "$TEMP_WORK_DIR"

echo "----------------------------------------"
echo "=== 全部完成 ==="
# 验证目录结构
find "$ROOT_OUT" -maxdepth 3 -type d | grep -E "android|linux|mac|windows"