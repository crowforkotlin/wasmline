#!/bin/bash

# Exit on any error
set -e

# ==============================================================================
# 1. Source environment context (Reuse context.sh)
# ==============================================================================
# Path resolution: scripts/samples/cpp/run.sh -> scripts/context.sh (3 levels up)
if [ "$ENV_SOURCED_MARKER" != "true" ]; then
    source "$(dirname $(dirname $(dirname "${BASH_SOURCE[0]}")))/context.sh"
fi
# ================================= C++ =================================
echo """
==============================================================
==============================================================
==============================================================
> C++
==============================================================
==============================================================
==============================================================
"""


sh ${SCRIPT_ROOT}/samples/cpp/configure.sh
sh ${SCRIPT_ROOT}/samples/cpp/build.sh
cd ${SAMPLE_ROOT}/cpp/build

echo "[shell run.sh] --> -----------------------------"
# Detect OS name
OS_NAME=$(uname -s)
case "$OS_NAME" in
    Linux*)
        echo "Detected: Linux"
        ./WasmtimeSample
        ;;
    # Detect macOS
    Darwin*)
        echo "Detected: macOS"
        ./WasmtimeSample
        ;;
    CYGWIN*|MINGW*|MSYS*|Windows*)
        echo "Detected: Windows (via shell like Git Bash/MSYS)"
        # =======================================================
        # Core logic: Smart PATH cleaning for Windows
        # Remove Git/MSYS-injected paths to retain native system
        # and user configuration, preventing environment conflicts.
        # =======================================================
        echo "[Setup] Cleaning PATH for Windows CMD..."
        
        CLEAN_PATH=""
        
        # Split Bash $PATH (colon-delimited) into an array
        IFS=':' read -r -a PATH_ARRAY <<< "$PATH"
        
        for p in "${PATH_ARRAY[@]}"; do
            # Convert /d/msys/... to D:\msys\... via cygpath for keyword matching
            # 2>/dev/null suppresses conversion errors
            WIN_P=$(cygpath -w "$p" 2>/dev/null || echo "$p")
            
            # Filter out paths containing "Git", "msys", or "cygwin" (case-insensitive)
            # Note: standalone MinGW installations under matching names may also be removed
            if [[ "$WIN_P" =~ [Gg][Ii][Tt] ]] || \
               [[ "$WIN_P" =~ [Mm][Ss][Yy][Ss] ]] || \
               [[ "$WIN_P" =~ [Cc][Yy][Gg][Ww][Ii][Nn] ]]; then
                # echo "  -> Dropping polluted path: $WIN_P"
                continue
            fi
            
            # Assemble retained paths (semicolon-delimited)
            CLEAN_PATH="${CLEAN_PATH}${WIN_P};"
        done
        
        # Remove trailing semicolon
        CLEAN_PATH=${CLEAN_PATH%;}

        # =======================================================
        # Generate exec.bat
        # =======================================================
        BAT_FILE="exec.bat"
        echo "@echo off" > "$BAT_FILE"
        # Set cleaned PATH (current directory first to ensure DLL resolution)
        echo "set PATH=.;${CLEAN_PATH}" >> "$BAT_FILE"
        echo "echo [BAT] Path Cleaned. Starting Go Sample..." >> "$BAT_FILE"
        echo "echo ==============================================================" >> "$BAT_FILE"
        echo "echo ==============================================================" >> "$BAT_FILE"
        echo "echo ^> C++" >>"$BAT_FILE"
        echo "echo ==============================================================" >> "$BAT_FILE"
        echo "echo ==============================================================" >> "$BAT_FILE"
        echo "WasmtimeSample.exe" >> "$BAT_FILE"
        echo "if %errorlevel% neq 0 (" >> "$BAT_FILE"
        echo "    echo." >> "$BAT_FILE"
        echo "    echo [ERROR] Crashed! Code: %errorlevel%" >> "$BAT_FILE"
        echo ")" >> "$BAT_FILE"
        echo "pause" >> "$BAT_FILE"

        # =======================================================
        # Launch in external console
        # =======================================================
        if [ -f "WasmtimeSample.exe" ]; then
            cmd //c "start "$BAT_FILE""
        else
            echo "Error: WasmtimeSample.exe not found!"
            exit 1
        fi
        ;;
    # Other operating systems (e.g. FreeBSD, SunOS)
    *)
        echo "Detected: Other OS ($OS_NAME)"
        # Default: execute the unsuffixed binary
        ./WasmtimeSample
        ;;
esac