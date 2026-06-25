#!/bin/bash

# ==============================================================================
# Wasmtime C-API Init Script (Thread Pool & Frame Buffer)
# ==============================================================================

CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
source "${CURRENT_DIR}/context.sh" || exit 1

REPO="crowforkotlin/wasmtime"
ERROR_FLAG_FILE="${TEMP_WORK_DIR}/.has_error"
MAX_CONCURRENT=3  # Default concurrency limit

# --- Helpers ---

setup_proxy() {
    local p="$1"
    if [ -n "$p" ]; then
        export http_proxy="http://$p" https_proxy="http://$p"
        log_success "Proxy: $p"
    else
        log_info "Direct connection."
        printf "${YELLOW}[TIP] If slow, use: bash $0 127.0.0.1:7890${NC}"
    fi
}

format_time() {
    local s=$1
    if [ "$s" -ge 3600 ]; then echo ">1h"; else printf "%02d:%02d" $((s/60)) $((s%60)); fi
}

get_time_ms() {
    if [[ "$OSTYPE" == "darwin"* ]]; then date +%s000; else echo $(($(date +%s%N)/1000000)); fi
}

target_summary() {
    case "$1" in
        aarch64-android)   printf '%s' 'Android / arm64-v8a -> build/platforms/android/arm64-v8a [asset: aarch64-android-pulley-min-c-api]' ;;
        aarch64-ios-pulley-min-c-api) printf '%s' 'iOS Device / arm64 -> build/platforms/ios/arm64 [asset: aarch64-ios-pulley-min-c-api]' ;;
        aarch64-ios-sim-pulley-min-c-api) printf '%s' 'iOS Simulator / simulator-arm64 -> build/platforms/ios/simulator-arm64 [asset: aarch64-ios-sim-pulley-min-c-api]' ;;
        aarch64-linux)     printf '%s' 'Linux / aarch64 -> build/platforms/linux/aarch64 [asset: aarch64-linux-pulley-min-c-api]' ;;
        x86_64-linux)      printf '%s' 'Linux / x64 -> build/platforms/linux/x64 [asset: x86_64-linux-pulley-min-c-api]' ;;
        aarch64-macos)     printf '%s' 'macOS / aarch64 -> build/platforms/mac/aarch64 [asset: aarch64-macos-pulley-min-c-api]' ;;
        x86_64-macos)      printf '%s' 'macOS / x64 -> build/platforms/mac/x64 [asset: x86_64-macos-pulley-min-c-api]' ;;
        x86_64-windows)    printf '%s' 'Windows / x64 -> build/platforms/windows/x64 [asset: x86_64-windows-pulley-min-c-api]' ;;
        armv7-android)     printf '%s' 'Android / armeabi-v7a -> build/platforms/android/armeabi-v7a [asset: armv7-android-pulley-min-c-api]' ;;
        x86-android)       printf '%s' 'Android / x86 (32-bit) -> build/platforms/android/x86 [asset: x86-android-pulley-min-c-api]' ;;
        x86_64-android)    printf '%s' 'Android / x86_64 -> build/platforms/android/x86_64 [asset: x86_64-android-pulley-min-c-api]' ;;
        all)               printf '%s' 'All Platforms' ;;
        *)                 printf '%s' "$1" ;;
    esac
}

print_target_option() {
    local key="$1"
    local filter="$2"
    printf "  ${WHITE}%s)${NC} %s\n" "$key" "$(target_summary "$filter")"
}

select_target() {
    echo ""
    log_header "Platform & Architecture Selection"
    printf "Select specific target:\n"
    
    print_target_option "1" "aarch64-android"
    print_target_option "2" "aarch64-ios-pulley-min-c-api"
    print_target_option "3" "aarch64-ios-sim-pulley-min-c-api"
    print_target_option "4" "aarch64-linux"
    print_target_option "5" "x86_64-linux"
    print_target_option "6" "aarch64-macos"
    print_target_option "7" "x86_64-macos"
    print_target_option "8" "x86_64-windows"
    print_target_option "9" "armv7-android"
    print_target_option "x" "x86-android"
    print_target_option "0" "x86_64-android"
    print_target_option "a" "all"
    printf "\n"

    local valid=false
    while [ "$valid" = false ]; do
        printf "${CYAN}Choice [1-9, 0, x, a]: ${NC}"
        read c
        case "$c" in
            # 这里的标识符将用于后续的文件名匹配
            1) USER_FILTER="aarch64-android"; valid=true ;;
            2) USER_FILTER="aarch64-ios-pulley-min-c-api"; valid=true ;;
            3) USER_FILTER="aarch64-ios-sim-pulley-min-c-api"; valid=true ;;
            4) USER_FILTER="aarch64-linux";   valid=true ;;
            5) USER_FILTER="x86_64-linux";    valid=true ;;
            6) USER_FILTER="aarch64-macos";   valid=true ;;
            7) USER_FILTER="x86_64-macos";    valid=true ;;
            8) USER_FILTER="x86_64-windows";  valid=true ;;
            9) USER_FILTER="armv7-android";    valid=true ;;
            x|X) USER_FILTER="x86-android";    valid=true ;;
            0) USER_FILTER="x86_64-android";  valid=true ;;
            a|A) USER_FILTER="all";           valid=true ;;
            *) printf "${RED}Invalid input.${NC}\n" ;;
        esac
    done
    
    log_success "Target: ${WHITE}$(target_summary "$USER_FILTER")${NC}"
}

# [NEW] Configure Concurrency
configure_settings() {
    echo ""
    log_header "Download Settings"
    printf "Set max concurrent downloads (Default: ${WHITE}3${NC}):\n"
    printf "${CYAN}Count > ${NC}"
    read input_limit
    # Validate integer input > 0
    if [[ "$input_limit" =~ ^[1-9][0-9]*$ ]]; then
        MAX_CONCURRENT=$input_limit
    else
        MAX_CONCURRENT=3
    fi
    log_success "Concurrency set to: ${WHITE}${MAX_CONCURRENT}${NC}"
}

deploy_platform() {
    local arc=$1
    local plat=$2
    local fname=$(basename "$arc")
    local t_dir=$(dirname "$arc")
    local ex_dir="$t_dir/extracted"
    local f_path="${PLATFORMS_ROOT}/${plat}"

    log_step "Deploying: ${WHITE}${plat}${NC}"
    log_detail "Archive: ${CYAN}${fname}${NC}"

    rm -rf "${f_path}" "$ex_dir"
    mkdir -p "${f_path}" "$ex_dir"

    if [[ "$fname" == *.zip ]]; then
        unzip -q -o "$arc" -d "$ex_dir"
    else
        tar -xf "$arc" -C "$ex_dir"
    fi

    local min_include_root
    min_include_root=$(find "$ex_dir" -type d -path "*/min/include" 2>/dev/null | head -n 1)
    local c_root=""
    [ -n "$min_include_root" ] && c_root=$(dirname "$min_include_root")
    if [ -n "$c_root" ] && [ -d "$c_root/lib" ]; then
        mv "$c_root/include" "$f_path/"
        mv "$c_root/lib" "$f_path/"
        log_success "Installed: ${plat}"
    else
        # Fallback: non-min structure (include/ and lib/ at top level)
        local inc_dir
        inc_dir=$(find "$ex_dir" -maxdepth 3 -type d -name "include" ! -path "*/min/*" 2>/dev/null | head -n 1)
        local lib_dir
        lib_dir=$(find "$ex_dir" -maxdepth 3 -type d -name "lib" ! -path "*/min/*" 2>/dev/null | head -n 1)
        if [ -n "$inc_dir" ] && [ -n "$lib_dir" ]; then
            mv "$inc_dir" "$f_path/"
            mv "$lib_dir" "$f_path/"
            log_success "Installed: ${plat} (non-min)"
        else
            log_error "Invalid artifact structure: $fname"
            touch "$ERROR_FLAG_FILE"
        fi
    fi
    rm -rf "$t_dir"
}

# ==============================================================================
# Main
# ==============================================================================

rm -rf "$TEMP_WORK_DIR"
mkdir -p "$TEMP_WORK_DIR" "$PLATFORMS_ROOT"

log_header "Wasmtime SDK Init"
setup_proxy "$1"

# 1. Fetch Info
log_info "Fetching releases..."
RESP=$(curl -s --retry 3 --connect-timeout 10 "https://api.github.com/repos/$REPO/releases/latest")
TAG=$(echo "$RESP" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')
if [ -z "$TAG" ]; then log_error "Fetch failed."; exit 1; fi
log_info "Version: ${GREEN}${TAG}${NC}"

# 2. Interactions
select_target
configure_settings

# 3. Analyze (Async Probing)
log_info "Analyzing targets..."
D_URLS=$(echo "$RESP" | grep '"browser_download_url":' | grep '\-pulley-min-c-api' | sed -E 's/.*"([^"]+)".*/\1/')

declare -a URLS FILES PLATFORMS TOTALS CURRENTS PREV_SIZES LAST_TIMES SPEEDS ETAS JOB_STATUS
# JOB_STATUS: 0=Pending, 1=Running, 2=Done
count=0

# Dispatch Probes
for url in $D_URLS; do
    fname=$(basename "$url")
    
    # --- 过滤器逻辑 (Filter Logic) ---
    if [ "$USER_FILTER" != "all" ]; then
        if [ "$USER_FILTER" == "aarch64-ios-pulley-min-c-api" ]; then
             if [[ "$fname" != *"$USER_FILTER"* ]] || [[ "$fname" == *"sim"* ]]; then continue; fi
        else
             if [[ "$fname" != *"$USER_FILTER"* ]]; then continue; fi
        fi
    fi

    # --- 路径映射逻辑 (Path Mapping) ---
    # 根据文件名特征，精确拆分到不同目录
    plat=""
    case "$fname" in
        *armv7-android-pulley-min*)        plat="android/armeabi-v7a" ;;
        *x86_64-android-pulley-min*)       plat="android/x86_64" ;;
        *x86-android-pulley-min*)          plat="android/x86" ;;
        *aarch64-android-pulley-min*)      plat="android/arm64-v8a" ;;
        
        # iOS 拆分
        *aarch64-ios-sim-pulley-min*)      plat="ios/simulator-arm64" ;;
        *aarch64-ios-pulley-min-c-api*)    plat="ios/arm64" ;; 
        
        # Linux 拆分
        *aarch64-linux-pulley-min*)        plat="linux/aarch64" ;;
        *x86_64-linux-pulley-min*)         plat="linux/x64" ;;
        
        # macOS 拆分
        *aarch64-macos-pulley-min*)        plat="mac/aarch64" ;;
        *x86_64-macos-pulley-min*)         plat="mac/x64" ;;
        
        # Windows
        *x86_64-windows-pulley-min*)       plat="windows/x64" ;;
    esac

    if [ -n "$plat" ]; then
        URLS[$count]=$url
        FILES[$count]="${TEMP_WORK_DIR}/job_${count}/${fname}"
        PLATFORMS[$count]=$plat
        CURRENTS[$count]=0; PREV_SIZES[$count]=0; SPEEDS[$count]=0; ETAS[$count]="--:--"
        JOB_STATUS[$count]=0
        LAST_TIMES[$count]=$(get_time_ms)

        mkdir -p "${TEMP_WORK_DIR}/job_${count}"

        # Async Size Check
        (
            s=$(curl -sI -L --retry 3 --connect-timeout 5 "$url" | grep -i "Content-Length" | tr -d '\r' | awk '{print $2}' | tail -n 1)
            echo "${s:-0}" > "${TEMP_WORK_DIR}/.size_${count}"
        ) &
        count=$((count + 1))
    fi
done

if [ "$count" -eq 0 ]; then echo ""; log_warn "No assets found."; exit 0; fi

# Wait for Probes with UI
cursor_hide
while true; do
    done_count=$(ls -1 "${TEMP_WORK_DIR}"/.size_* 2>/dev/null | wc -l)
    printf "%b" "   > Probing sizes: ${WHITE}[${done_count}/${count}]${NC}\r"
    if [ "$done_count" -ge "$count" ]; then break; fi
    sleep 0.05
done
clear_line

# Load Sizes
for ((i=0; i<count; i++)); do
    TOTALS[$i]=$(cat "${TEMP_WORK_DIR}/.size_${i}" 2>/dev/null || echo 0)
done

log_info "Ready. Queue: ${count} files (Max concurrent: ${MAX_CONCURRENT})"
echo "---------------------------------------------------------------------------------"

# 4. Download (Thread Pool Logic)
completed_jobs=0
last_text_upd=0

while [ "$completed_jobs" -lt "$count" ]; do
    now_ms=$(get_time_ms)

    # Text update limiter (0.5s)
    upd_txt=false
    if (( (now_ms - last_text_upd) > 500 )); then
        upd_txt=true; last_text_upd=$now_ms
    fi

    # 1. Manage Queue
    running_cnt=0
    for ((i=0; i<count; i++)); do
        s=${JOB_STATUS[$i]}
        if [ "$s" -eq 1 ]; then # Running
            # Check completion
            if [ -f "${TEMP_WORK_DIR}/job_${i}/.done" ]; then
                JOB_STATUS[$i]=2
                ((completed_jobs++))
            else
                ((running_cnt++))
            fi
        fi
    done

    # 2. Fill Slots
    for ((i=0; i<count; i++)); do
        if [ "$running_cnt" -lt "$MAX_CONCURRENT" ] && [ "${JOB_STATUS[$i]}" -eq 0 ]; then
            # Start Job
            JOB_STATUS[$i]=1
            ((running_cnt++))
            (
                curl -L -s -f --retry 3 --retry-all-errors -o "${FILES[$i]}" "${URLS[$i]}"
                if [ $? -eq 0 ]; then touch "${TEMP_WORK_DIR}/job_${i}/.done"; else touch "$ERROR_FLAG_FILE" "${TEMP_WORK_DIR}/job_${i}/.done"; fi
            ) &
        fi
    done

    # 3. Render Buffer
    FRAME_BUF=""
    for ((i=0; i<count; i++)); do
        stat=${JOB_STATUS[$i]}
        tot=${TOTALS[$i]}

        # IO Check
        curr=0
        if [ "$stat" -eq 0 ]; then
            status_txt="${GRAY}Waiting...${NC}"
            bar="${BLUE}[${GRAY}---------------${BLUE}]${NC}"
        else
            [ -f "${FILES[$i]}" ] && curr=$(wc -c < "${FILES[$i]}" | tr -d ' ')
            # Calc Speed
            if [ "$upd_txt" = true ] && [ "$stat" -eq 1 ]; then
                dt=$((now_ms - LAST_TIMES[$i]))
                if [ "$dt" -gt 0 ]; then
                    spd=$(( (curr - PREV_SIZES[$i]) * 1000 / dt ))
                    PREV_SIZES[$i]=$curr
                    SPEEDS[$i]=$spd
                    LAST_TIMES[$i]=$now_ms
                    # ETA
                    if [ "$spd" -gt 0 ] && [ "$tot" -gt 0 ]; then
                        rem=$((tot - curr)); et=$((rem/spd))
                        ETAS[$i]=$(format_time "$et")
                    fi
                fi
            fi
        fi

        # Visuals
        perc=0; [ "$tot" -gt 0 ] && perc=$((curr * 100 / tot)); [ "$perc" -gt 100 ] && perc=100

        if [ "$stat" -ne 0 ]; then
             bar=$(get_progress_bar_str "$perc")
             cur_s=$(format_size "$curr"); tot_s=$(format_size "$tot")
        fi

        if [ "$stat" -eq 2 ]; then
            status_txt="${GREEN}Completed${NC}"
            bar="${BLUE}[${GREEN}###############${BLUE}]${NC}"
        elif [ "$stat" -eq 1 ]; then
            status_txt="${cur_s}/${tot_s} | ${YELLOW}$(format_size ${SPEEDS[$i]})/s${NC} | ETA ${CYAN}${ETAS[$i]}${NC}"
        fi

        pad_plat=$(printf "%-18s" "${PLATFORMS[$i]}")
        FRAME_BUF+="${pad_plat} ${bar} ${WHITE}${perc}%${NC} | ${status_txt}\033[K\n"
    done

    printf "%b" "$FRAME_BUF"

    if [ "$completed_jobs" -lt "$count" ]; then
        cursor_up "$count"
        sleep 0.05
    fi
done

cursor_show
echo "---------------------------------------------------------------------------------"
log_success "All Downloads Finished."
echo ""

# 5. Deploy
log_info "Deploying..."
for ((i=0; i<count; i++)); do
    [ -f "${FILES[$i]}" ] && deploy_platform "${FILES[$i]}" "${PLATFORMS[$i]}"
    echo ""
done

if [ -f "$ERROR_FLAG_FILE" ]; then
    log_error "Completed with errors."
    exit 1
else
    log_header "Success"
    echo -e "Location: ${PLATFORMS_ROOT}/"
    rm -rf "$TEMP_WORK_DIR"
    exit 0
fi
