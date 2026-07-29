#!/bin/bash

# ==============================================================================
# lint-kotlin.sh — Run ktlint check on wasmline-multiplatform
# Usage: bash scripts/lint-kotlin.sh [--format]
#   --format    Auto-fix style violations in-place
# ==============================================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
set +u
source "$SCRIPT_DIR/style.sh"
set -u

# --- Check ktlint installation ---
if ! command -v ktlint &>/dev/null; then
    log_error "ktlint not found. Install it first:"
    log_detail "curl -sSLO https://github.com/pinterest/ktlint/releases/download/1.5.0/ktlint"
    log_detail "chmod +x ktlint && sudo mv ktlint /usr/local/bin/"
    exit 1
fi

# --- Parse arguments ---
MODE="check"
if [[ "${1:-}" == "--format" || "${1:-}" == "-F" ]]; then
    MODE="format"
fi

# --- Run ktlint ---
cd "$ROOT_DIR/wasmline-multiplatform"

if [[ "$MODE" == "format" ]]; then
    log_header "ktlint --format"
    log_info "Auto-fixing Kotlin style violations..."
    ktlint --relative --format \
        '!**/wasmline-build-logic/**' \
        '!**/iosMain/**' \
        '!**/build/**'
else
    log_header "ktlint check"
    log_info "Checking Kotlin style..."
    ktlint --relative \
        '!**/wasmline-build-logic/**' \
        '!**/iosMain/**' \
        '!**/build/**'
fi

log_success "lint-kotlin passed."
