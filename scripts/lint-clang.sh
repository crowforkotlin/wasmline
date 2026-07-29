#!/bin/bash

# ==============================================================================
# lint-clang.sh — Run clang-format check on wasmline-core
# Usage: bash scripts/lint-clang.sh [--format]
#   --format    Auto-fix formatting in-place
# ==============================================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
set +u
source "$SCRIPT_DIR/style.sh"
set -u

# --- Check clang-format installation ---
if ! command -v clang-format &>/dev/null; then
    log_error "clang-format not found. Install it first:"
    log_detail "sudo pacman -S clang    # Arch Linux"
    log_detail "sudo apt-get install -y clang-format    # Debian/Ubuntu"
    exit 1
fi

# --- Parse arguments ---
MODE="check"
if [[ "${1:-}" == "--format" || "${1:-}" == "-F" ]]; then
    MODE="format"
fi

# --- Collect source files ---
cd "$ROOT_DIR"
FILES=$(find wasmline-core/src wasmline-core/include -name '*.cpp' -o -name '*.h')

if [[ -z "$FILES" ]]; then
    log_warn "No C++ source files found."
    exit 0
fi

FILE_COUNT=$(echo "$FILES" | wc -l)

# --- Run clang-format ---
if [[ "$MODE" == "format" ]]; then
    log_header "clang-format --format"
    log_info "Auto-fixing formatting for ${FILE_COUNT} files..."
    echo "$FILES" | xargs clang-format -i
else
    log_header "clang-format check"
    log_info "Checking formatting for ${FILE_COUNT} files..."
    echo "$FILES" | xargs clang-format --dry-run --Werror
fi

log_success "lint-clang passed."
