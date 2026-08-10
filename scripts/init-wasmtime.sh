#!/bin/bash

# ==============================================================================
# Wasmtime SDK Init Script
# Download and deploy Wasmtime C-API platform assets with concurrent downloads
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
        printf "${YELLOW}[TIP] If slow, use: bash $0 127.0.0.1:7890${NC}\n"
    fi
}

format_time() {
    local s=$1
    if [ "$s" -ge 3600 ]; then echo ">1h"; else printf "%02d:%02d" $((s/60)) $((s%60)); fi
}

get_time_ms() {
    if [[ "$OSTYPE" == "darwin"* ]]; then date +%s000; else echo $(($(date +%s%N)/1000000)); fi
}

# Ordered target definitions: key | filter_id | display_name | install_path
# Grouped by platform family for clean menu rendering.
TARGET_KEYS=(1 2 3 4 5 6 7 8 9 0 x a)
TARGET_FILTERS=(
    "aarch64-android"
    "armv7-android"
    "x86-android"
    "x86_64-android"
    "aarch64-ios-pulley-min-c-api"
    "aarch64-ios-sim-pulley-min-c-api"
    "aarch64-linux"
    "x86_64-linux"
    "aarch64-macos"
    "x86_64-macos"
    "x86_64-windows"
    "all"
)
TARGET_NAMES=(
    "Android   arm64-v8a"
    "Android   armeabi-v7a  [pulley only]"
    "Android   x86          [pulley only]"
    "Android   x86_64"
    "iOS       arm64 (Device)   [pulley only]"
    "iOS       arm64 (Simulator)[pulley only]"
    "Linux     aarch64"
    "Linux     x64"
    "macOS     aarch64"
    "macOS     x64"
    "Windows   x64"
    "All Platforms (cranelift + pulley)"
)
TARGET_PATHS=(
    "build/platforms/android/arm64-v8a"
    "build/platforms/android/armeabi-v7a"
    "build/platforms/android/x86"
    "build/platforms/android/x86_64"
    "build/platforms/ios/arm64"
    "build/platforms/ios/simulator-arm64"
    "build/platforms/linux/aarch64"
    "build/platforms/linux/x64"
    "build/platforms/mac/aarch64"
    "build/platforms/mac/x64"
    "build/platforms/windows/x64"
    "—"
)
# Group boundaries: each entry is "group_label|start_index|count"
TARGET_GROUPS=(
    "Android|0|4"
    "iOS|4|2"
    "Linux|6|2"
    "macOS|8|2"
    "Windows|10|1"
    "Other|11|1"
)

target_summary() {
    local filter="$1"
    for i in "${!TARGET_FILTERS[@]}"; do
        if [ "${TARGET_FILTERS[$i]}" = "$filter" ]; then
            printf '%s' "${TARGET_NAMES[$i]}"
            return
        fi
    done
    printf '%s' "$filter"
}

select_target() {
    echo ""
    log_header "Platform & Architecture Selection"
    echo ""

    # Calculate column widths for aligned output
    local key_w=3
    local name_w=0
    for n in "${TARGET_NAMES[@]}"; do
        (( ${#n} > name_w )) && name_w=${#n}
    done
    (( name_w < 22 )) && name_w=22

    local group_idx=0
    local global_idx=0

    while [ $group_idx -lt ${#TARGET_GROUPS[@]} ]; do
        IFS='|' read -r g_label g_start g_count <<< "${TARGET_GROUPS[$group_idx]}"

        # Print group header
        printf "  ${GRAY}── %s ──${NC}\n" "$g_label"

        local j=0
        while [ $j -lt "$g_count" ]; do
            local idx=$((g_start + j))
            local key="${TARGET_KEYS[$idx]}"
            local name="${TARGET_NAMES[$idx]}"
            local path="${TARGET_PATHS[$idx]}"

            # Pad name to fixed width
            local padded_name
            padded_name=$(printf "%-${name_w}s" "$name")

            printf "  ${WHITE}%s)${NC} %s  ${GRAY}→ %s${NC}\n" "$key" "$padded_name" "$path"

            j=$((j + 1))
            global_idx=$((global_idx + 1))
        done

        group_idx=$((group_idx + 1))
        echo ""
    done

    local valid=false
    while [ "$valid" = false ]; do
        printf "  ${CYAN}Enter choice [1-9, 0, x, a]:${NC} "
        read c
        case "$c" in
            1) USER_FILTER="aarch64-android"; valid=true ;;
            2) USER_FILTER="armv7-android";    valid=true ;;
            3) USER_FILTER="x86-android";      valid=true ;;
            4) USER_FILTER="x86_64-android";   valid=true ;;
            5) USER_FILTER="aarch64-ios-pulley-min-c-api"; valid=true ;;
            6) USER_FILTER="aarch64-ios-sim-pulley-min-c-api"; valid=true ;;
            7) USER_FILTER="aarch64-linux";    valid=true ;;
            8) USER_FILTER="x86_64-linux";     valid=true ;;
            9) USER_FILTER="aarch64-macos";    valid=true ;;
            0) USER_FILTER="x86_64-macos";     valid=true ;;
            x|X) USER_FILTER="x86_64-windows"; valid=true ;;
            a|A) USER_FILTER="all";            valid=true ;;
            *) printf "  ${RED}Invalid input, please try again.${NC}\n" ;;
        esac
    done

    echo ""
    log_success "Target: ${WHITE}$(target_summary "$USER_FILTER")${NC}"
}

# Runtime variant selection (Cranelift vs Pulley)
# Cranelift-min is a superset of Pulley-min: supports .cwasm AOT + .pwasm.
# Pulley-min is smaller but only supports .pwasm interpretation.
select_variant() {
    echo ""
    log_header "Runtime Variant Selection"

    # Determine if variant selection is available
    local can_choose=false
    case "$USER_FILTER" in
        all)
            VARIANT="both"
            log_info "All Platforms: downloading all runtime variants (Cranelift + Pulley)."
            ;;
        aarch64-ios-pulley-min-c-api|aarch64-ios-sim-pulley-min-c-api|armv7-android|x86-android)
            VARIANT="pulley"
            log_info "Platform requires Pulley runtime; use matching-bitness PWASM."
            ;;
        *)
            can_choose=true
            VARIANT="cranelift"
            ;;
    esac

    if [ "$can_choose" = true ]; then
        printf "  ${WHITE}1)${NC} Cranelift — .pwasm + .cwasm AOT  ${GRAY}(default, larger binary)${NC}\n"
        printf "  ${WHITE}2)${NC} Pulley    — .pwasm only            ${GRAY}(smaller binary)${NC}\n"
        echo ""
        local valid=false
        while [ "$valid" = false ]; do
            printf "  ${CYAN}Choice [1/2] (default: 1):${NC} "
            read v
            case "$v" in
                ""|1) VARIANT="cranelift"; valid=true ;;
                2)    VARIANT="pulley";    valid=true ;;
                *) printf "  ${RED}Invalid input, please try again.${NC}\n" ;;
            esac
        done
    fi

    echo ""
    log_success "Variant: ${WHITE}${VARIANT}${NC}"
}

# Version selection — pick a specific Wasmtime release
# Simple input: type number or version string, Enter = latest
select_version() {
    local all_resp="$1"
    log_header "Version Selection"

    # Extract up to 10 recent version tags
    local tags
    tags=$(echo "$all_resp" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' | head -10)
    local count=0
    local -a tag_list
    while IFS= read -r t; do
        [ -n "$t" ] && tag_list+=("$t") && count=$((count + 1))
    done <<< "$tags"

    if [ "$count" -le 1 ]; then
        SELECTED_VERSION="${tag_list[0]:-unknown}"
        log_info "Only one version available: ${GREEN}${SELECTED_VERSION}${NC}"
        return
    fi

    # List versions
    echo ""
    echo "  Available versions:"
    local i=0
    while [ $i -lt $count ]; do
        echo "    $((i + 1))) ${tag_list[$i]}"
        i=$((i + 1))
    done

    echo ""
    printf "  ${CYAN}Choice [1-%d or version, Enter=latest]:${NC} " "$count"
    read -r input

    if [ -z "$input" ]; then
        SELECTED_VERSION="${tag_list[0]}"
    elif [[ "$input" =~ ^[1-9][0-9]*$ ]] && [ "$input" -ge 1 ] && [ "$input" -le "$count" ]; then
        SELECTED_VERSION="${tag_list[$((input - 1))]}"
    else
        # Try as direct version string match
        local found=false
        local j=0
        while [ $j -lt $count ]; do
            if [ "${tag_list[$j]}" = "$input" ]; then
                SELECTED_VERSION="$input"
                found=true
                break
            fi
            j=$((j + 1))
        done
        if [ "$found" = false ]; then
            log_warn "Input not matched, using latest: ${GREEN}${tag_list[0]}${NC}"
            SELECTED_VERSION="${tag_list[0]}"
        fi
    fi

    echo ""
    log_success "Version: ${WHITE}${SELECTED_VERSION}${NC}"
}

# Configure concurrency
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
    local variant=$3
    local fname=$(basename "$arc")
    local t_dir=$(dirname "$arc")
    local ex_dir="$t_dir/extracted"
    local f_path="${PLATFORMS_ROOT}/${SELECTED_VERSION}/${variant}/${plat}"

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

# GitHub API authentication (avoids rate limit on CI runners)
CURL_AUTH_OPTS=""
if [ -n "${GITHUB_TOKEN:-}" ]; then
    CURL_AUTH_OPTS="-H \"Authorization: Bearer ${GITHUB_TOKEN}\""
    log_info "Using authenticated GitHub API requests."
fi

# 1. Fetch releases
log_info "Fetching releases..."
ALL_RELEASES=$(eval curl -s --retry 3 --connect-timeout 10 $CURL_AUTH_OPTS "https://api.github.com/repos/$REPO/releases?per_page=10")
if [ -z "$ALL_RELEASES" ] || echo "$ALL_RELEASES" | grep -q '"message":.*Not Found'; then
    log_error "Fetch failed."
    exit 1
fi
if echo "$ALL_RELEASES" | grep -q '"message":.*API rate limit'; then
    log_error "GitHub API rate limit exceeded. Set GITHUB_TOKEN to authenticate."
    exit 1
fi

# 2. Interactions
select_version "$ALL_RELEASES"

# Fetch the specific version's release details for asset URLs
RESP=$(eval curl -s --retry 3 --connect-timeout 10 $CURL_AUTH_OPTS "https://api.github.com/repos/$REPO/releases/tags/${SELECTED_VERSION}")
if [ -z "$RESP" ] || echo "$RESP" | grep -q '"message":.*Not Found'; then
    log_error "Failed to fetch release: ${SELECTED_VERSION}"
    exit 1
fi
log_info "Version: ${GREEN}${SELECTED_VERSION}${NC}"

select_target
select_variant
configure_settings

# 3. Analyze (Async Probing)
log_info "Analyzing targets..."
# All min-c-api assets (both pulley and cranelift match "-min-c-api").
# For cranelift: exclude pulley variants. For pulley: only pulley variants. For both: all.
if [ "$VARIANT" = "both" ]; then
    D_URLS=$(echo "$RESP" | grep '"browser_download_url":' | grep '\-min-c-api' | sed -E 's/.*"([^"]+)".*/\1/')
elif [ "$VARIANT" = "pulley" ]; then
    D_URLS=$(echo "$RESP" | grep '"browser_download_url":' | grep '\-pulley-min-c-api' | sed -E 's/.*"([^"]+)".*/\1/')
else
    D_URLS=$(echo "$RESP" | grep '"browser_download_url":' | grep '\-min-c-api' | grep -v '\-pulley-min-c-api' | sed -E 's/.*"([^"]+)".*/\1/')
fi

declare -a URLS FILES PLATFORMS VARIANTS TOTALS CURRENTS PREV_SIZES LAST_TIMES SPEEDS ETAS JOB_STATUS
# JOB_STATUS: 0=Pending, 1=Running, 2=Done
count=0

# Dispatch Probes
for url in $D_URLS; do
    fname=$(basename "$url")
    
    # --- Filter logic ---
    if [ "$USER_FILTER" != "all" ]; then
        # Use short filter IDs that are substrings of both pulley and cranelift asset names
        case "$USER_FILTER" in
            aarch64-ios-pulley-min-c-api)     short_filter="aarch64-ios" ;;
            aarch64-ios-sim-pulley-min-c-api) short_filter="aarch64-ios" ;;
            *)                                short_filter="$USER_FILTER" ;;
        esac
        if [[ "$fname" != *"$short_filter"* ]]; then continue; fi
        # iOS: exclude simulator when selecting device, and vice versa
        if [ "$USER_FILTER" = "aarch64-ios-pulley-min-c-api" ] && [[ "$fname" == *"sim"* ]]; then continue; fi
        if [ "$USER_FILTER" = "aarch64-ios-sim-pulley-min-c-api" ] && [[ "$fname" != *"sim"* ]]; then continue; fi
    fi

    # --- Path mapping ---
    # Strip variant suffix to get a variant-agnostic core name for matching.
    #   cranelift: ...-min-c-api.tar.xz  → strip "-min-c-api"
    #   pulley:    ...-pulley-min-c-api.tar.xz → strip "-pulley-min-c-api"
    core_name=$(echo "$fname" | sed -E 's/(-pulley)?-min-c-api.*//')
    plat=""
    case "$core_name" in
        *armv7-android*)        plat="android/armeabi-v7a" ;;
        *x86_64-android*)       plat="android/x86_64" ;;
        *x86-android*)          plat="android/x86" ;;
        *aarch64-android*)      plat="android/arm64-v8a" ;;
        *aarch64-ios-sim*)      plat="ios/simulator-arm64" ;;
        *aarch64-ios*)          plat="ios/arm64" ;;
        *aarch64-linux*)        plat="linux/aarch64" ;;
        *x86_64-linux*)         plat="linux/x64" ;;
        *aarch64-macos*)        plat="mac/aarch64" ;;
        *x86_64-macos*)         plat="mac/x64" ;;
        *x86_64-windows*)       plat="windows/x64" ;;
    esac

    if [ -n "$plat" ]; then
        # Determine per-asset variant from filename
        if [[ "$fname" == *"-pulley-min-c-api"* ]]; then
            asset_variant="pulley"
        else
            asset_variant="cranelift"
        fi
        URLS[$count]=$url
        FILES[$count]="${TEMP_WORK_DIR}/job_${count}/${fname}"
        PLATFORMS[$count]=$plat
        VARIANTS[$count]=$asset_variant
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

        pad_plat=$(printf "%-28s" "${VARIANTS[$i]}/${PLATFORMS[$i]}")
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
    [ -f "${FILES[$i]}" ] && deploy_platform "${FILES[$i]}" "${PLATFORMS[$i]}" "${VARIANTS[$i]}"
    echo ""
done

if [ -f "$ERROR_FLAG_FILE" ]; then
    log_error "Completed with errors."
    exit 1
else
    log_header "Success"
    echo -e "Location: ${PLATFORMS_ROOT}/${SELECTED_VERSION}/"
    rm -rf "$TEMP_WORK_DIR"
    exit 0
fi
