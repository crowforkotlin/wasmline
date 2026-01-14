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
        echo -e "${YELLOW} [TIP] If slow, use: bash $0 127.0.0.1:7890${NC}"
    fi
}

format_time() {
    local s=$1
    if [ "$s" -ge 3600 ]; then echo ">1h"; else printf "%02d:%02d" $((s/60)) $((s%60)); fi
}

get_time_ms() {
    if [[ "$OSTYPE" == "darwin"* ]]; then date +%s000; else echo $(($(date +%s%N)/1000000)); fi
}

select_target() {
    echo ""
    log_header "Platform Selection"
    echo -e "Select target platform:"
    echo -e "  ${WHITE}1)${NC} Android ${GRAY}(aarch64)${NC}"
    echo -e "  ${WHITE}2)${NC} macOS   ${GRAY}(aarch64/x64)${NC}"
    echo -e "  ${WHITE}3)${NC} Linux   ${GRAY}(aarch64/x64)${NC}"
    echo -e "  ${WHITE}4)${NC} Windows ${GRAY}(x64)${NC}"
    echo -e "  ${WHITE}5)${NC} iOS     ${GRAY}(if available)${NC}"
    echo -e "  ${WHITE}a)${NC} All Platforms"
    echo ""

    local valid=false
    while [ "$valid" = false ]; do
        read -p "$(echo -e "${CYAN}Choice [1-5, a]: ${NC}")" c
        case "$c" in
            1) USER_FILTER="android"; valid=true ;;
            2) USER_FILTER="macos";   valid=true ;;
            3) USER_FILTER="linux";   valid=true ;;
            4) USER_FILTER="windows"; valid=true ;;
            5) USER_FILTER="ios";     valid=true ;;
            a|A) USER_FILTER="all";   valid=true ;;
            *) echo -e "${RED}Invalid input.${NC}" ;;
        esac
    done
    local d_name=$USER_FILTER
    [ "$USER_FILTER" == "all" ] && d_name="All Platforms"
    log_success "Target: ${WHITE}${d_name}${NC}"
}

# [NEW] Configure Concurrency
configure_settings() {
    echo ""
    log_header "Download Settings"
    echo -e "Set max concurrent downloads (Default: ${WHITE}3${NC}):"
    read -p "$(echo -e "${CYAN}Count > ${NC}")" input_limit

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

    local c_root=$(find "$ex_dir" -type d -name "include" 2>/dev/null | head -n 1)
    if [ -n "$c_root" ]; then
        c_root=$(dirname "$c_root")
        mv "$c_root/include" "$f_path/"
        mv "$c_root/lib" "$f_path/"
        log_success "Installed: ${plat}"
    else
        log_error "Invalid structure: $fname"
        touch "$ERROR_FLAG_FILE"
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
D_URLS=$(echo "$RESP" | grep '"browser_download_url":' | grep '\-c-api' | sed -E 's/.*"([^"]+)".*/\1/')

declare -a URLS FILES PLATFORMS TOTALS CURRENTS PREV_SIZES LAST_TIMES SPEEDS ETAS JOB_STATUS
# JOB_STATUS: 0=Pending, 1=Running, 2=Done
count=0

# Dispatch Probes
for url in $D_URLS; do
    fname=$(basename "$url")
    if [ "$USER_FILTER" != "all" ] && [[ "$fname" != *"$USER_FILTER"* ]]; then continue; fi

    plat=""
    case "$fname" in
        *android*) plat="android/arm64-v8a" ;;
        *linux-c-api*)   plat="linux/aarch64" ;;
        *macos*)   plat="mac/aarch64" ;;
        *windows*)  plat="windows/x64" ;;
        *ios*)      plat="ios/arm64" ;;
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
    echo -ne "   > Probing sizes: ${WHITE}[${done_count}/${count}]${NC}\r"
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

    echo -ne "$FRAME_BUF"

    if [ "$completed_jobs" -lt "$count" ]; then
        cursor_up "$count"
        if [[ "$OSTYPE" == "darwin"* ]]; then sleep 0.03; else sleep 0.03; fi
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
