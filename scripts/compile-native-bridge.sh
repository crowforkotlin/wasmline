#!/usr/bin/env bash

# Builds the Kotlin/Native Wasmline bridge and the Wasmline core into a static
# archive for one Kotlin/Native target.
#
# Author: crowforkotlin
# Date: 2026-08-19

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  bash scripts/compile-native-bridge.sh <target> [engine]

Targets:
  iosArm64
  iosSimulatorArm64
  macosArm64
  macosX64
  linuxArm64
  linuxX64
  mingwX64

Engines:
  pulley       Build the Pulley interpreter bridge (default)
  cranelift    Build the Cranelift bridge

Examples:
  bash scripts/compile-native-bridge.sh linuxX64 pulley
  bash scripts/compile-native-bridge.sh iosSimulatorArm64 pulley
EOF
}

usage_error() {
  echo "Error: $1" >&2
  echo >&2
  usage >&2
  exit 2
}

log_info() {
  printf '[wasmline-native] %s\n' "$*" >&2
}

if [[ "$#" -eq 0 ]]; then
  usage_error "target name is required"
fi

for argument in "$@"; do
  case "${argument}" in
    -h|--help)
      usage
      exit 0
      ;;
  esac
done

if [[ "$#" -gt 2 ]]; then
  usage_error "expected a target and an optional engine, got $# arguments"
fi

TARGET="$1"
if [[ "${TARGET}" == -* ]]; then
  usage_error "unsupported option: ${TARGET}"
fi
ENGINE="${2:-pulley}"
case "${ENGINE}" in
  pulley|cranelift) ;;
  *) usage_error "unsupported engine: ${ENGINE}" ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VERSION="$(python3 -c "import json; print(json.load(open('${REPO_ROOT}/scripts/versions.json'))['versions']['wasmtime_version'])")"
WASMTIME_TAG="release-v${VERSION}"

# Kotlin/Native ships a target-specific C/C++ toolchain under KONAN_DATA_DIR.
# Using the host compiler here leaks the host libstdc++ and glibc ABI into the
# archive, which cannot be linked by Kotlin/Native's target linker.
KONAN_DATA_DIR="${KONAN_DATA_DIR:-${HOME}/.konan}"
TARGET_TRIPLE=""
TARGET_SYSROOT=""
AR="${AR:-}"
RANLIB="${RANLIB:-}"

case "${TARGET}" in
  linuxX64)
    PLATFORM="linux/x64"
    ARCH="x86_64"
    TARGET_TRIPLE="x86_64-unknown-linux-gnu"
    TOOLCHAIN_ROOT="${KONAN_DATA_DIR}/dependencies/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2"
    CXX="${CXX:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-g++}"
    AR="${AR:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-ar}"
    RANLIB="${RANLIB:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-ranlib}"
    TARGET_SYSROOT="${TOOLCHAIN_ROOT}/${TARGET_TRIPLE}/sysroot"
    SYSROOT_ARGS=("--sysroot=${TARGET_SYSROOT}")
    ;;
  linuxArm64)
    PLATFORM="linux/aarch64"
    ARCH="aarch64"
    TARGET_TRIPLE="aarch64-unknown-linux-gnu"
    TOOLCHAIN_ROOT="${KONAN_DATA_DIR}/dependencies/aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2"
    CXX="${CXX:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-g++}"
    AR="${AR:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-ar}"
    RANLIB="${RANLIB:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-ranlib}"
    TARGET_SYSROOT="${TOOLCHAIN_ROOT}/${TARGET_TRIPLE}/sysroot"
    SYSROOT_ARGS=("--sysroot=${TARGET_SYSROOT}")
    ;;
  macosArm64)
    PLATFORM="mac/aarch64"
    ARCH="arm64"
    CXX="${CXX:-clang++}"
    AR="${AR:-ar}"
    RANLIB="${RANLIB:-ranlib}"
    SYSROOT_ARGS=(-arch arm64 -isysroot "$(xcrun --sdk macosx --show-sdk-path)")
    ;;
  macosX64)
    PLATFORM="mac/x64"
    ARCH="x86_64"
    CXX="${CXX:-clang++}"
    AR="${AR:-ar}"
    RANLIB="${RANLIB:-ranlib}"
    SYSROOT_ARGS=(-arch x86_64 -isysroot "$(xcrun --sdk macosx --show-sdk-path)")
    ;;
  iosArm64)
    PLATFORM="ios/arm64"
    ARCH="arm64"
    CXX="${CXX:-clang++}"
    AR="${AR:-ar}"
    RANLIB="${RANLIB:-ranlib}"
    SYSROOT_ARGS=(-arch arm64 -isysroot "$(xcrun --sdk iphoneos --show-sdk-path)")
    ;;
  iosSimulatorArm64)
    PLATFORM="ios/simulator-arm64"
    ARCH="arm64"
    CXX="${CXX:-clang++}"
    AR="${AR:-ar}"
    RANLIB="${RANLIB:-ranlib}"
    SYSROOT_ARGS=(-arch arm64 -isysroot "$(xcrun --sdk iphonesimulator --show-sdk-path)")
    ;;
  mingwX64)
    PLATFORM="windows/x64"
    ARCH="x86_64"
    TARGET_TRIPLE="x86_64-w64-windows-gnu"
    TOOLCHAIN_ROOT="${KONAN_DATA_DIR}/dependencies/msys2-mingw-w64-x86_64-2"
    TARGET_SYSROOT="${TOOLCHAIN_ROOT}/x86_64-w64-mingw32"
    if [[ -z "${CXX:-}" ]]; then
      if command -v x86_64-w64-mingw32-g++ >/dev/null 2>&1; then
        CXX="$(command -v x86_64-w64-mingw32-g++)"
      else
        CXX="clang++"
      fi
    fi
    if "${CXX}" --version 2>/dev/null | head -1 | grep -qi clang; then
      SYSROOT_ARGS=("--target=${TARGET_TRIPLE}" "--sysroot=${TARGET_SYSROOT}")
      USE_MINGW_GCC_HEADERS="1"
    else
      SYSROOT_ARGS=()
      USE_MINGW_GCC_HEADERS="0"
    fi
    if [[ -z "${AR}" ]]; then
      if command -v x86_64-w64-mingw32-ar >/dev/null 2>&1; then
        AR="$(command -v x86_64-w64-mingw32-ar)"
      else
        AR="${TOOLCHAIN_ROOT}/x86_64-w64-mingw32/bin/ar.exe"
      fi
    fi
    if [[ -z "${RANLIB}" ]]; then
      if command -v x86_64-w64-mingw32-ranlib >/dev/null 2>&1; then
        RANLIB="$(command -v x86_64-w64-mingw32-ranlib)"
      else
        RANLIB="${TOOLCHAIN_ROOT}/x86_64-w64-mingw32/bin/ranlib.exe"
      fi
    fi
    ;;
  *) usage_error "unsupported Kotlin/Native target: ${TARGET}" ;;
esac

ASSET_ROOT="${REPO_ROOT}/build/platforms/${WASMTIME_TAG}/${ENGINE}/${PLATFORM}"
CORE_ROOT="${REPO_ROOT}/wasmline-core"
BRIDGE_ROOT="${REPO_ROOT}/wasmline-multiplatform/wasmline/src/nativeMain/native"
OUTPUT_DIR="${REPO_ROOT}/wasmline-multiplatform/wasmline/build/native/${TARGET}/${ENGINE}"
HEADER_DIR="${ASSET_ROOT}/include"
WASMTIME_LIB="${ASSET_ROOT}/lib/libwasmtime.a"
ARCHIVE_PATH="${OUTPUT_DIR}/libwasmline_native.a"

log_info "Target: ${TARGET} (${PLATFORM})"
log_info "Engine: ${ENGINE}"
log_info "Configuration: release (-O2, NDEBUG)"
log_info "Wasmtime: ${WASMTIME_TAG}"
log_info "Assets: ${ASSET_ROOT}"

if [[ ! -f "${HEADER_DIR}/wasmtime.h" || ! -f "${WASMTIME_LIB}" ]]; then
  echo "Missing Wasmtime assets for ${ENGINE}/${PLATFORM}: ${ASSET_ROOT}" >&2
  exit 3
fi

mkdir -p "${OUTPUT_DIR}"
rm -f "${OUTPUT_DIR}"/*.o "${ARCHIVE_PATH}"

if ! command -v "${CXX}" >/dev/null 2>&1 && [[ ! -x "${CXX}" ]]; then
  echo "C++ compiler is not available: ${CXX}" >&2
  exit 4
fi
if ! command -v "${AR}" >/dev/null 2>&1 && [[ ! -x "${AR}" ]]; then
  echo "Archiver is not available: ${AR}" >&2
  exit 4
fi
if ! command -v "${RANLIB}" >/dev/null 2>&1 && [[ ! -x "${RANLIB}" ]]; then
  echo "Archive indexer is not available: ${RANLIB}" >&2
  exit 4
fi

log_info "Compiler: ${CXX}"
log_info "Archiver: ${AR}"

SOURCES=(
  "${CORE_ROOT}/src/api/Api.cpp"
  "${CORE_ROOT}/src/runtime/Component.cpp"
  "${CORE_ROOT}/src/runtime/ComponentSession.cpp"
  "${CORE_ROOT}/src/runtime/Engine.cpp"
  "${CORE_ROOT}/src/runtime/Module.cpp"
  "${CORE_ROOT}/src/runtime/RawModuleSession.cpp"
  "${CORE_ROOT}/src/runtime/Session.cpp"
  "${CORE_ROOT}/src/value/ComponentValue.cpp"
  "${CORE_ROOT}/src/invocation/InvocationResult.cpp"
  "${CORE_ROOT}/src/invocation/TypedInvocationCodec.cpp"
  "${CORE_ROOT}/src/protocol/WasmlineProtocol.cpp"
  "${CORE_ROOT}/src/io/FileIO.cpp"
  "${CORE_ROOT}/src/wasmtime/WasmtimeMessage.cpp"
  "${CORE_ROOT}/src/wasi/WasiConfig.cpp"
  "${BRIDGE_ROOT}/WasmlineNative.cpp"
  "${BRIDGE_ROOT}/NativeLogger.cpp"
)

COMMON_ARGS=(-std=c++17 -O2 -DNDEBUG -fPIC -ffunction-sections -fdata-sections -DLIBWASM_STATIC -DWASI_API_EXTERN= -DWASM_API_EXTERN=)
if [[ "${TARGET}" == "mingwX64" && "${USE_MINGW_GCC_HEADERS:-0}" == "1" ]]; then
  COMMON_ARGS+=(
    "-D_GCC_MAX_ALIGN_T"
    "-isystem${TOOLCHAIN_ROOT}/include/c++/9.2.0"
    "-isystem${TOOLCHAIN_ROOT}/include/c++/9.2.0/x86_64-w64-mingw32"
    "-isystem${TOOLCHAIN_ROOT}/lib/gcc/x86_64-w64-mingw32/9.2.0/include"
  )
fi
INCLUDE_ARGS=(
  "-I${CORE_ROOT}/include"
  "-I${CORE_ROOT}/src"
  "-I${BRIDGE_ROOT}"
  "-I${HEADER_DIR}"
)

OBJECTS=()
SOURCE_COUNT="${#SOURCES[@]}"
SOURCE_INDEX=0
log_info "Compiling ${SOURCE_COUNT} C++ sources"
for source in "${SOURCES[@]}"; do
  SOURCE_INDEX=$((SOURCE_INDEX + 1))
  source_name="${source##*/}"
  object="${OUTPUT_DIR}/${source_name%.*}.o"
  log_info "[${SOURCE_INDEX}/${SOURCE_COUNT}] ${source_name}"
  "${CXX}" -c "${COMMON_ARGS[@]}" "${SYSROOT_ARGS[@]}" "${INCLUDE_ARGS[@]}" "${source}" -o "${object}"
  OBJECTS+=("${object}")
done

# Embed the selected Wasmtime archive so a published engine KLIB has one
# self-contained native archive and never falls back to a host libwasmtime.so.
log_info "Embedding Wasmtime static archive"
WASMTIME_OBJECT_DIR="$(mktemp -d)"
trap 'rm -rf "${WASMTIME_OBJECT_DIR}"' EXIT
(
  cd "${WASMTIME_OBJECT_DIR}"
  "${AR}" x "${WASMTIME_LIB}"
)
WASMTIME_OBJECTS=("${WASMTIME_OBJECT_DIR}"/*.o)
"${AR}" rcs "${ARCHIVE_PATH}" "${OBJECTS[@]}" "${WASMTIME_OBJECTS[@]}"
"${RANLIB}" "${ARCHIVE_PATH}"
log_info "Build complete"
printf '%s\n' "${ARCHIVE_PATH}"
