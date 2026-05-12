#!/bin/bash

# ==============================================================================
# UI Styles & Logging Module (Enhanced)
# ==============================================================================

if [ -n "$STYLE_SOURCED_MARKER" ]; then return 0; fi
export STYLE_SOURCED_MARKER="true"

# --- 1. Colors (High Intensity) ---
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    export STYLE_USE_ANSI="true"
    export RED='\033[1;31m'
    export GREEN='\033[1;32m'
    export YELLOW='\033[1;33m'
    export BLUE='\033[1;34m'
    export MAGENTA='\033[1;35m'
    export CYAN='\033[1;36m'
    export WHITE='\033[1;37m'
    export GRAY='\033[0;90m'
    export NC='\033[0m'
else
    export STYLE_USE_ANSI="false"
    export RED=''
    export GREEN=''
    export YELLOW=''
    export BLUE=''
    export MAGENTA=''
    export CYAN=''
    export WHITE=''
    export GRAY=''
    export NC=''
fi

# --- 2. Cursor Control (Crucial for Dashboard) ---
cursor_hide() { [ "$STYLE_USE_ANSI" = "true" ] && printf "\033[?25l"; }
cursor_show() { [ "$STYLE_USE_ANSI" = "true" ] && printf "\033[?25h"; }
cursor_up()   { [ "$STYLE_USE_ANSI" = "true" ] && printf "\033[%dA" "$1"; } # Move cursor up N lines
clear_line()  { [ "$STYLE_USE_ANSI" = "true" ] && printf "\033[2K\r"; }     # Clear current line

# --- 3. Format Utilities ---
# Convert bytes to human readable (KB, MB)
format_size() {
    local bytes=$1
    if [ $bytes -lt 1024 ]; then
        echo "${bytes}B"
    elif [ $bytes -lt 1048576 ]; then
        echo "$(( (bytes + 512) / 1024 ))KB"
    else
        # Simulate float by multiplying then string manipulation
        local mb=$(( bytes * 100 / 1048576 ))
        echo "$(( mb / 100 )).$(( mb % 100 ))MB"
    fi
}

# Draw a mini bar: [####--]
get_progress_bar_str() {
    local percent=$1
    local width=15
    local filled=$((percent * width / 100))
    local empty=$((width - filled))
    local bar_filled=$(printf "%0.s#" $(seq 1 $filled))
    local bar_empty=$(printf "%0.s-" $(seq 1 $empty))
    echo "${BLUE}[${GREEN}${bar_filled}${GRAY}${bar_empty}${BLUE}]${NC}"
}

# --- 4. Logging ---
log_header() {
    printf "${CYAN}=================================================${NC}\n"
    printf "      %b       \n" "$1"
    printf "${CYAN}=================================================${NC}\n"
}
log_info()    { printf "${MAGENTA}[INFO]${NC} %b\n" "$1"; }
log_success() { printf "${GREEN}[OK]${NC}   %b\n" "$1"; }
log_warn()    { printf "${YELLOW}[WARN]${NC} %b\n" "$1"; }
log_error()   { printf "${RED}[ERR]${NC}  %b\n" "$1"; }

log_step()    { printf "${BLUE}[STEP]${NC} %b\n" "$1"; }
log_detail()  { printf "       ${GRAY}└─ %b${NC}\n" "$1"; }
