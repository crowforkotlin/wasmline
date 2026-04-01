#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
PROFILE_FILES=("$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile")

red='\033[1;31m'
green='\033[1;32m'
yellow='\033[1;33m'
blue='\033[1;34m'
cyan='\033[1;36m'
reset='\033[0m'

info() { printf "${blue}[INFO]${reset} %s\n" "$1"; }
success() { printf "${green}[OK]${reset}   %s\n" "$1"; }
warn() { printf "${yellow}[WARN]${reset} %s\n" "$1"; }
fail() { printf "${red}[STOP]${reset} %s\n" "$1"; }
section() { printf "\n${cyan}== %s ==${reset}\n" "$1"; }

java_home_summary() {
    local java_home="$1"
    if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
        return 1
    fi

    local version_line release_file implementor runtime_version
    version_line="$("$java_home/bin/java" -version 2>&1 | head -n 1 || true)"
    release_file="$java_home/release"
    implementor=""
    runtime_version=""
    if [ -f "$release_file" ]; then
        implementor="$(grep '^IMPLEMENTOR=' "$release_file" | head -n 1 | cut -d'=' -f2- | tr -d '"' || true)"
        runtime_version="$(grep '^JAVA_RUNTIME_VERSION=' "$release_file" | head -n 1 | cut -d'=' -f2- | tr -d '"' || true)"
    fi
    printf '%s|%s|%s\n' "$version_line" "$implementor" "$runtime_version"
}

is_jbr21_home() {
    local java_home="$1"
    if [ -z "$java_home" ] || [ ! -x "$java_home/bin/java" ]; then
        return 1
    fi

    local summary version_line implementor runtime_version
    summary="$(java_home_summary "$java_home")"
    version_line="${summary%%|*}"
    local rest="${summary#*|}"
    implementor="${rest%%|*}"
    runtime_version="${summary##*|}"

    case "$version_line $implementor $runtime_version $java_home" in
        *21*JBR*|*21*JetBrains*|*21*jbr*|*21*jbrsdk*) return 0 ;;
        *) return 1 ;;
    esac
}

collect_profile_hits() {
    local file
    for file in "${PROFILE_FILES[@]}"; do
        if [ -f "$file" ]; then
            printf '[%s]\n' "$file"
            grep -nE 'JAVA_HOME|jbr|JetBrainsRuntime|jbrsdk' "$file" || true
            printf '\n'
        else
            printf '[%s]\n<missing>\n\n' "$file"
        fi
    done
}

collect_candidate_paths() {
    local file
    for file in "${PROFILE_FILES[@]}"; do
        [ -f "$file" ] || continue
        sed -nE 's/.*=("?)([^"#]*(jbr|jbrsdk)[^"#]*)\1.*/\2/p' "$file" 2>/dev/null || true
    done | while IFS= read -r candidate; do
        [ -n "$candidate" ] || continue
        candidate="${candidate//\$HOME/$HOME}"
        candidate="${candidate//\${HOME\}/$HOME}"
        printf '%s\n' "$candidate"
    done | awk '!seen[$0]++'
}

detect_java_home_from_path() {
    command -v java >/dev/null 2>&1 || return 1

    local detected
    detected="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^[[:space:]]*java\.home = / { print $2; exit }')"
    [ -n "$detected" ] || return 1
    [ -x "$detected/bin/java" ] || return 1

    printf '%s\n' "$detected"
}

check_platform_assets() {
    local count
    count="$(find "$ROOT_DIR/platforms" -type d \( -name lib -o -name include \) 2>/dev/null | wc -l | tr -d ' ')"
    if [ "${count:-0}" -gt 0 ]; then
        success "检测到 platforms 运行时资产，若作者已提前准备，可跳过 ./scripts/init.sh"
    else
        warn "未检测到可用的 platforms 运行时资产，编译/测试前应先执行: sh ./scripts/init.sh"
        return 1
    fi
}

check_zig() {
    if ! command -v zig >/dev/null 2>&1; then
        warn "未检测到 zig。只有在需要 Compose Desktop/native 构建时才是硬阻塞。要求版本为 0.15.1。"
        return 1
    fi

    local version
    version="$(zig version 2>/dev/null || true)"
    if [ "$version" = "0.15.1" ]; then
        success "检测到 Zig 0.15.1"
    else
        warn "检测到 Zig $version，仓库文档要求 0.15.1；如果要跑 Compose Desktop/native，建议切换后再继续。"
        return 1
    fi

    if find "$ROOT_DIR/wasmline-multiplatform/wasmline/src/jvmMain/resources" -type f \( -name '*.dylib' -o -name '*.so' -o -name '*.dll' \) 2>/dev/null | grep -q .; then
        success "检测到 wasmline 桌面侧 JNI/native 产物"
    else
        warn "尚未检测到 wasmline 桌面侧 JNI/native 产物；如需 Compose Desktop，可在 wasmline-multiplatform/wasmline 下执行 zig build"
    fi
}

print_platform_hint() {
    case "$(uname -s)" in
        Darwin)
            info "当前系统为 macOS，项目约定建议使用 brew + ghostty。"
            ;;
        MINGW*|MSYS*|CYGWIN*)
            info "当前系统为 Windows/Git Bash 风格环境，项目约定建议使用 Git Bash + MSYS，并优先通过 pacman 管理依赖。"
            ;;
        *)
            info "当前系统不是 macOS/Windows 预设环境，请自行比对依赖与脚本兼容性。"
            ;;
    esac
}

main() {
    local wants_compose=0
    if [ "${1:-}" = "--compose-desktop" ]; then
        wants_compose=1
    fi

    section "Wasmline 预检"
    print_platform_hint

    section "JBR 21 检查"
    local current_java_home="${JAVA_HOME:-}"
    if [ -z "$current_java_home" ]; then
        current_java_home="$(detect_java_home_from_path || true)"
        if [ -n "$current_java_home" ]; then
            info "当前 JAVA_HOME 未设置，已从 java 命令推断 java.home: $current_java_home"
        fi
    fi

    if [ -n "$current_java_home" ]; then
        info "当前 JAVA_HOME: $current_java_home"
        if is_jbr21_home "$current_java_home"; then
            success "当前会话已经是可用的 JBR 21，可以继续进行 Gradle/测试相关操作。"
        else
            warn "当前 JAVA_HOME 不是 JBR 21。"
        fi
    else
        warn "当前 JAVA_HOME 未设置。"
    fi

    info "只读扫描 shell 配置中的 JBR/JAVA_HOME 线索："
    collect_profile_hits

    local candidate_paths found_usable=0 preferred_jbr_home=""
    candidate_paths="$(collect_candidate_paths || true)"
    if [ -n "$candidate_paths" ]; then
        info "从 shell 配置中发现的候选 JBR 路径："
        while IFS= read -r candidate; do
            [ -n "$candidate" ] || continue
            if [ -d "$candidate" ]; then
                printf '  - %s\n' "$candidate"
                if is_jbr21_home "$candidate"; then
                    found_usable=1
                    if [ -z "$preferred_jbr_home" ]; then
                        preferred_jbr_home="$candidate"
                    fi
                fi
            fi
        done <<< "$candidate_paths"
    fi

    if ! is_jbr21_home "${current_java_home:-}"; then

        fail "当前会话未切到 JBR 21。按仓库约束，这里应停止后续 Gradle 编译/测试。"
        if [ "$found_usable" -eq 1 ]; then
            printf '\n建议先在当前 shell 手动切换，再继续：\n\n'
            printf 'export JAVA_HOME="%s"\n' "$preferred_jbr_home"
            printf '"$JAVA_HOME/bin/java" -version\n\n'
        else
            printf '\n未找到可确认可用的 JBR 21。请先让用户提供或安装 JBR 21，再继续。\n\n'
        fi
        exit 2
    fi

    section "运行时资产检查"
    check_platform_assets || true

    section "Compose Desktop / Native 检查"
    if [ "$wants_compose" -eq 1 ]; then
        check_zig || exit 3
    else
        check_zig || true
    fi

    printf '\n'
    success "预检完成。当前至少已满足 JBR 21 这一条硬前置条件。"
}

main "$@"
