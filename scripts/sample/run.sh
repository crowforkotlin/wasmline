#!/bin/bash

# Exit on any error
set -e


# Import environment variables
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname "${BASH_SOURCE[0]}")/context.sh"
fi
echo $ENV_SCRIPT_DIR
sh ${ENV_SCRIPT_DIR}/configure.sh
sh ${ENV_SCRIPT_DIR}/build.sh
cd ${PROJECT_ROOT}/build

echo "[shell run.sh] --> -----------------------------"
# 'uname' 命令可以获取操作系统的名称。
OS_NAME=$(uname -s)
case "$OS_NAME" in
    Linux*)
        echo "Detected: Linux"
        ./WasmtimeSample
        ;;
    # 识别 macOS 系统
    Darwin*)
        echo "Detected: macOS"
        ./WasmtimeSample
        ;;
    CYGWIN*|MINGW*|MSYS*|Windows*)
        echo "Detected: Windows (via shell like Git Bash/MSYS)"
        # =======================================================
        # 【核心逻辑】智能清洗 PATH (Smart Path Cleaning)
        # 目标：去除 Git/MSYS 注入的路径，保留系统和用户的原生配置， gitbash可能会引入gitbash和msys2的环境，清除后采用默认环境去运行，防止出现问题
        # =======================================================
        echo "[Setup] Cleaning PATH for Windows CMD..."
        
        CLEAN_PATH=""
        
        # 将 Bash 的 $PATH (以冒号分隔) 读入数组
        IFS=':' read -r -a PATH_ARRAY <<< "$PATH"
        
        for p in "${PATH_ARRAY[@]}"; do
            # 1. 使用 cygpath 将 /d/msys/... 转为 D:\msys\... 格式，方便关键词匹配
            #    2>/dev/null 防止转换失败报错
            WIN_P=$(cygpath -w "$p" 2>/dev/null || echo "$p")
            
            # 2. 关键词过滤 (忽略大小写)
            #    剔除包含 "Git", "msys", "cygwin" 的路径
            #    注意：如果你独立的 MinGW 安装在 "D:\Git_Stuff\MinGW"，这里可能会误杀，
            #    但在标准安装下，这能精准剔除 Shell 注入的层。
            if [[ "$WIN_P" =~ [Gg][Ii][Tt] ]] || \
               [[ "$WIN_P" =~ [Mm][Ss][Yy][Ss] ]] || \
               [[ "$WIN_P" =~ [Cc][Yy][Gg][Ww][Ii][Nn] ]]; then
                # echo "  -> Dropping polluted path: $WIN_P"
                continue
            fi
            
            # 3. 拼接保留下来的路径 (分号分隔)
            CLEAN_PATH="${CLEAN_PATH}${WIN_P};"
        done
        
        # 去掉最后一个多余的分号
        CLEAN_PATH=${CLEAN_PATH%;}

        # =======================================================
        # 生成 exec.bat
        # =======================================================
        echo "@echo off" > exec.bat
        
        # 1. 设置清洗后的 PATH (当前目录优先级最高)
        echo "set PATH=.;${CLEAN_PATH}" >> exec.bat
        echo "echo [BAT] Path Cleaned. Starting..." >> exec.bat
        echo "echo ---------------------------------------------------" >> exec.bat
        echo "WasmtimeSample.exe" >> exec.bat
        echo "if %errorlevel% neq 0 (" >> exec.bat
        echo "    echo." >> exec.bat
        echo "    echo [ERROR] Crashed! Code: %errorlevel%" >> exec.bat
        echo ")" >> exec.bat
        echo "echo ---------------------------------------------------" >> exec.bat
        echo "pause" >> exec.bat

        # =======================================================
        # 弹窗运行
        # =======================================================
        if [ -f "WasmtimeSample.exe" ]; then
            cmd //c "start exec.bat"
        else
            echo "Error: WasmtimeSample.exe not found!"
            exit 1
        fi
        ;;
    # 其他操作系统 (如 FreeBSD, SunOS 等)
    *)
        echo "Detected: Other OS ($OS_NAME)"
        # 默认执行无后缀版本
        ./WasmtimeSample
        ;;
esac