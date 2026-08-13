#!/usr/bin/env bash

# Shared terminal formatting and logging helpers for repository scripts.

if [[ -n "${WASMLINE_STYLE_SH_LOADED:-}" ]]; then
    return 0
fi
WASMLINE_STYLE_SH_LOADED=1

if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
    STYLE_USE_ANSI=true
    RED=$'\033[1;31m'
    GREEN=$'\033[1;32m'
    YELLOW=$'\033[1;33m'
    BLUE=$'\033[1;34m'
    MAGENTA=$'\033[1;35m'
    CYAN=$'\033[1;36m'
    WHITE=$'\033[1;37m'
    GRAY=$'\033[0;90m'
    NC=$'\033[0m'
else
    STYLE_USE_ANSI=false
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    MAGENTA=''
    CYAN=''
    WHITE=''
    GRAY=''
    NC=''
fi

cursor_hide() { [[ "$STYLE_USE_ANSI" == true ]] && printf '\033[?25l'; }
cursor_show() { [[ "$STYLE_USE_ANSI" == true ]] && printf '\033[?25h'; }
cursor_up() { [[ "$STYLE_USE_ANSI" == true ]] && printf '\033[%dA' "$1"; }
clear_line() { [[ "$STYLE_USE_ANSI" == true ]] && printf '\033[2K\r'; }

format_size() {
    local bytes="$1"
    if ((bytes < 1024)); then
        printf '%sB\n' "$bytes"
    elif ((bytes < 1048576)); then
        printf '%sKB\n' "$(((bytes + 512) / 1024))"
    else
        local hundredths=$((bytes * 100 / 1048576))
        printf '%s.%02dMB\n' "$((hundredths / 100))" "$((hundredths % 100))"
    fi
}

get_progress_bar_str() {
    local percent="$1"
    local width=15
    local filled=$((percent * width / 100))
    local index
    local bar=''

    for ((index = 0; index < width; index++)); do
        if ((index < filled)); then
            bar+='#'
        else
            bar+='-'
        fi
    done
    printf '%s[%s]%s\n' "$BLUE" "$bar" "$NC"
}

log_header() {
    printf '%s=================================================%s\n' "$CYAN" "$NC"
    printf '      %s\n' "$1"
    printf '%s=================================================%s\n' "$CYAN" "$NC"
}

log_info() { printf '%s[INFO]%s %s\n' "$MAGENTA" "$NC" "$1"; }
log_success() { printf '%s[OK]%s   %s\n' "$GREEN" "$NC" "$1"; }
log_warn() { printf '%s[WARN]%s %s\n' "$YELLOW" "$NC" "$1"; }
log_error() { printf '%s[ERR]%s  %s\n' "$RED" "$NC" "$1" >&2; }
log_step() { printf '%s[STEP]%s %s\n' "$BLUE" "$NC" "$1"; }
log_detail() { printf '       %s->%s %s\n' "$GRAY" "$NC" "$1"; }
