#!/usr/bin/env bash

# Builds the Kotlin/Native Wasmline bridge and the Wasmline core into a static
# archive for one Kotlin/Native target.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../../lib/shell/paths.sh"
source "${SCRIPT_DIR}/../../lib/shell/output.sh"
source "${SCRIPT_DIR}/../../lib/shell/targets.sh"
source "${SCRIPT_DIR}/../../lib/shell/versions.sh"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/wasmline kotlin-native build --target <target|all> [--engine <engine>]

Targets:
  iosArm64
  iosSimulatorArm64
  macosArm64
  macosX64
  linuxArm64
  linuxX64
  mingwX64

Engines:
  pulley       Build the Pulley interpreter bridge
  cranelift    Build the Cranelift bridge

Special target:
  all          Build both engines for every target supported by the current host
               Specify an engine to build that engine only

Examples:
  ./scripts/wasmline kotlin-native build --target linuxX64 --engine pulley
  ./scripts/wasmline kotlin-native build --target iosSimulatorArm64 --engine pulley
  ./scripts/wasmline kotlin-native build --target all
EOF
}

usage_error() {
  wasmline_status ERR "Argument" "$1"
  usage >&2
  exit 2
}

log_info() {
  wasmline_status INFO "" "$*"
}

if [[ "$#" -eq 0 ]]; then
  usage_error "target name is required"
fi

for argument in "$@"; do
  case "${argument}" in
  -h | --help)
    usage
    exit 0
    ;;
  esac
done

if [[ "$#" -gt 2 ]]; then
  usage_error "expected a target and an optional engine, got $# arguments"
fi

case "$1" in
all | --all)
  TARGET="all"
  ;;
*)
  TARGET="$1"
  ;;
esac
if [[ "${TARGET}" == -* ]]; then
  usage_error "unsupported option: ${TARGET}"
fi
ENGINE="${2:-}"
if [[ "${TARGET}" == "all" && -z "${ENGINE}" ]]; then
  ENGINES=(pulley cranelift)
else
  ENGINE="${ENGINE:-pulley}"
  case "${ENGINE}" in
  pulley | cranelift) ;;
  *) usage_error "unsupported engine: ${ENGINE}" ;;
  esac
  ENGINES=("${ENGINE}")
fi

REPO_ROOT="${WASMLINE_PROJECT_ROOT}"
WASMTIME_RELEASE_VERSION="$(wasmline_version wasmtime_release_version)"
WASMTIME_TAG="v${WASMTIME_RELEASE_VERSION}"

# Kotlin/Native ships a target-specific C/C++ toolchain under KONAN_DATA_DIR.
# Using the host compiler here leaks the host libstdc++ and glibc ABI into the
# archive, which cannot be linked by Kotlin/Native's target linker.
KONAN_DATA_DIR="${KONAN_DATA_DIR:-${HOME}/.konan}"
HOST_OS="$(uname -s)"
HOST_ARCH="$(uname -m)"
TARGET_TRIPLE=""
TARGET_SYSROOT=""
AR="${AR:-}"
RANLIB="${RANLIB:-}"

if [[ "${TARGET}" == "all" ]]; then
  BUILD_MATRIX=()
  for engine in "${ENGINES[@]}"; do
    while IFS='|' read -r target platform_path; do
      [[ -n "${target}" ]] || continue
      asset_root="${WASMLINE_PLATFORMS_ROOT}/${WASMTIME_TAG}/${engine}/${platform_path}"
      if [[ ! -f "${asset_root}/include/wasmtime.h" || ! -f "${asset_root}/lib/libwasmtime.a" ]]; then
        wasmline_status ERR "Wasmtime files" "Missing ${engine}/${platform_path}: ${asset_root}"
        exit 3
      fi
      BUILD_MATRIX+=("${target}|${engine}")
    done < <(wasmline_published_kotlin_native_targets "${engine}" "${HOST_OS}")
  done

  log_info "Host: ${HOST_OS}"
  log_info "Archives: ${#BUILD_MATRIX[@]}"
  for entry in "${BUILD_MATRIX[@]}"; do
    IFS='|' read -r target engine <<<"${entry}"
    printf '\n' >&2
    bash "${SCRIPT_DIR}/build-kotlin-native.sh" "${target}" "${engine}"
  done
  exit 0
fi

# The Linux and Windows GCC files downloaded by Kotlin/Native are not macOS
# executables. Use Kotlin/Native's macOS LLVM tools with the target sysroot.
find_macos_llvm_root() {
  local llvm_architecture
  case "${HOST_ARCH}" in
  arm64 | aarch64)
    llvm_architecture="aarch64"
    ;;
  x86_64 | amd64)
    llvm_architecture="x86_64"
    ;;
  *)
    wasmline_status ERR "Host architecture" "Unsupported macOS architecture: ${HOST_ARCH}"
    exit 4
    ;;
  esac

  local selected=""
  local selected_version=-1
  local selected_build=-1
  local candidate
  for candidate in "${KONAN_DATA_DIR}"/dependencies/llvm-*-${llvm_architecture}-macos-essentials-*; do
    if [[ -x "${candidate}/bin/clang++" && -x "${candidate}/bin/llvm-ar" ]]; then
      local name="${candidate##*/}"
      if [[ "${name}" =~ ^llvm-([0-9]+)-.*-macos-essentials-([0-9]+)$ ]]; then
        local version="${BASH_REMATCH[1]}"
        local build="${BASH_REMATCH[2]}"
        if ((version > selected_version || (version == selected_version && build > selected_build))); then
          selected="${candidate}"
          selected_version="${version}"
          selected_build="${build}"
        fi
      fi
    fi
  done
  if [[ -z "${selected}" ]]; then
    wasmline_status ERR "Kotlin/Native LLVM" "Not found under ${KONAN_DATA_DIR}/dependencies."
    exit 4
  fi
  printf '%s\n' "${selected}"
}

configure_macos_llvm() {
  local llvm_root
  llvm_root="$(find_macos_llvm_root)"
  CXX="${CXX:-${llvm_root}/bin/clang++}"
  AR="${AR:-${llvm_root}/bin/llvm-ar}"
  # llvm-ar creates the archive index with the rcs flags. macOS ranlib cannot
  # process an archive that contains Linux or Windows object files.
  RANLIB="${RANLIB:-}"
}

configure_linux_toolchain() {
  if [[ "${HOST_OS}" == "Darwin" ]]; then
    configure_macos_llvm
    SYSROOT_ARGS=(
      "--target=${TARGET_TRIPLE}"
      "--sysroot=${TARGET_SYSROOT}"
      "--gcc-toolchain=${TOOLCHAIN_ROOT}"
    )
  else
    CXX="${CXX:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-g++}"
    AR="${AR:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-ar}"
    RANLIB="${RANLIB:-${TOOLCHAIN_ROOT}/bin/${TARGET_TRIPLE}-ranlib}"
    SYSROOT_ARGS=("--sysroot=${TARGET_SYSROOT}")
  fi
}

case "${TARGET}" in
linuxX64)
  ARCH="x86_64"
  TARGET_TRIPLE="x86_64-unknown-linux-gnu"
  TOOLCHAIN_ROOT="${KONAN_DATA_DIR}/dependencies/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2"
  TARGET_SYSROOT="${TOOLCHAIN_ROOT}/${TARGET_TRIPLE}/sysroot"
  configure_linux_toolchain
  ;;
linuxArm64)
  ARCH="aarch64"
  TARGET_TRIPLE="aarch64-unknown-linux-gnu"
  TOOLCHAIN_ROOT="${KONAN_DATA_DIR}/dependencies/aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2"
  TARGET_SYSROOT="${TOOLCHAIN_ROOT}/${TARGET_TRIPLE}/sysroot"
  configure_linux_toolchain
  ;;
macosArm64)
  ARCH="arm64"
  CXX="${CXX:-clang++}"
  AR="${AR:-ar}"
  RANLIB="${RANLIB:-ranlib}"
  SYSROOT_ARGS=(-arch arm64 -isysroot "$(xcrun --sdk macosx --show-sdk-path)")
  ;;
macosX64)
  ARCH="x86_64"
  CXX="${CXX:-clang++}"
  AR="${AR:-ar}"
  RANLIB="${RANLIB:-ranlib}"
  SYSROOT_ARGS=(-arch x86_64 -isysroot "$(xcrun --sdk macosx --show-sdk-path)")
  ;;
iosArm64)
  ARCH="arm64"
  CXX="${CXX:-clang++}"
  AR="${AR:-ar}"
  RANLIB="${RANLIB:-ranlib}"
  SYSROOT_ARGS=(-arch arm64 -isysroot "$(xcrun --sdk iphoneos --show-sdk-path)")
  ;;
iosSimulatorArm64)
  ARCH="arm64"
  CXX="${CXX:-clang++}"
  AR="${AR:-ar}"
  RANLIB="${RANLIB:-ranlib}"
  SYSROOT_ARGS=(-arch arm64 -isysroot "$(xcrun --sdk iphonesimulator --show-sdk-path)")
  ;;
mingwX64)
  ARCH="x86_64"
  TARGET_TRIPLE="x86_64-w64-windows-gnu"
  TOOLCHAIN_ROOT="${KONAN_DATA_DIR}/dependencies/msys2-mingw-w64-x86_64-2"
  TARGET_SYSROOT="${TOOLCHAIN_ROOT}/x86_64-w64-mingw32"
  if [[ "${HOST_OS}" == "Darwin" ]]; then
    configure_macos_llvm
    SYSROOT_ARGS=("--target=${TARGET_TRIPLE}" "--sysroot=${TARGET_SYSROOT}")
    USE_MINGW_GCC_HEADERS="1"
  else
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
  fi
  ;;
*) usage_error "unsupported Kotlin/Native target: ${TARGET}" ;;
esac

PLATFORM="$(wasmline_kotlin_native_install_path "${TARGET}" "${ENGINE}")"
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
  wasmline_status ERR "Wasmtime files" "Missing ${ENGINE}/${PLATFORM}: ${ASSET_ROOT}"
  exit 3
fi

if ! command -v "${CXX}" >/dev/null 2>&1 && [[ ! -x "${CXX}" ]]; then
  wasmline_status ERR "C++ compiler" "Not available: ${CXX}"
  exit 4
fi
if ! command -v "${AR}" >/dev/null 2>&1 && [[ ! -x "${AR}" ]]; then
  wasmline_status ERR "Archiver" "Not available: ${AR}"
  exit 4
fi
if [[ -n "${RANLIB}" ]]; then
  if ! command -v "${RANLIB}" >/dev/null 2>&1 && [[ ! -x "${RANLIB}" ]]; then
    wasmline_status ERR "Archive indexer" "Not available: ${RANLIB}"
    exit 4
  fi
fi

mkdir -p "${OUTPUT_DIR}"
STAGING_DIR="$(mktemp -d "${OUTPUT_DIR}/.build.XXXXXX")"
trap 'rm -rf "${STAGING_DIR}"' EXIT
ARCHIVE_BUILD_PATH="${STAGING_DIR}/libwasmline_native.a"

log_info "Compiler: ${CXX}"
log_info "Archiver: ${AR}"

SOURCES=(
  "${CORE_ROOT}/src/api/Api.cpp"
  "${CORE_ROOT}/src/runtime/Component.cpp"
  "${CORE_ROOT}/src/runtime/ComponentSession.cpp"
  "${CORE_ROOT}/src/runtime/ComponentSessionRegistry.cpp"
  "${CORE_ROOT}/src/runtime/Engine.cpp"
  "${CORE_ROOT}/src/runtime/Module.cpp"
  "${CORE_ROOT}/src/runtime/NativeRuntime.cpp"
  "${CORE_ROOT}/src/runtime/RawModuleSession.cpp"
  "${CORE_ROOT}/src/runtime/RawSessionRegistry.cpp"
  "${CORE_ROOT}/src/runtime/Session.cpp"
  "${CORE_ROOT}/src/runtime/ServiceSessionRegistry.cpp"
  "${CORE_ROOT}/src/value/ComponentValue.cpp"
  "${CORE_ROOT}/src/invocation/InvocationResult.cpp"
  "${CORE_ROOT}/src/invocation/CoreWasmBridgeCodec.cpp"
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
  object="${STAGING_DIR}/${source_name%.*}.o"
  log_info "[${SOURCE_INDEX}/${SOURCE_COUNT}] ${source_name}"
  "${CXX}" -c "${COMMON_ARGS[@]}" "${SYSROOT_ARGS[@]}" "${INCLUDE_ARGS[@]}" "${source}" -o "${object}"
  OBJECTS+=("${object}")
done

# Embed the selected Wasmtime archive so a published engine KLIB has one
# self-contained native archive and never falls back to a host libwasmtime.so.
log_info "Embedding Wasmtime static archive"
WASMTIME_OBJECT_DIR="${STAGING_DIR}/wasmtime"
mkdir -p "${WASMTIME_OBJECT_DIR}"
(
  cd "${WASMTIME_OBJECT_DIR}"
  "${AR}" x "${WASMTIME_LIB}"
)
WASMTIME_OBJECTS=("${WASMTIME_OBJECT_DIR}"/*.o)
"${AR}" rcs "${ARCHIVE_BUILD_PATH}" "${OBJECTS[@]}" "${WASMTIME_OBJECTS[@]}"
if [[ -n "${RANLIB}" ]]; then
  "${RANLIB}" "${ARCHIVE_BUILD_PATH}"
fi
mv "${ARCHIVE_BUILD_PATH}" "${ARCHIVE_PATH}"
wasmline_status OK "" "${ARCHIVE_PATH}"
