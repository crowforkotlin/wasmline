#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../../lib/shell/paths.sh"
source "${SCRIPT_DIR}/../../lib/shell/output.sh"
source "${SCRIPT_DIR}/../../lib/shell/process.sh"
source "${SCRIPT_DIR}/../../lib/shell/targets.sh"
source "${SCRIPT_DIR}/../../lib/shell/versions.sh"

ENGINE="${1:-all}"
case "${ENGINE}" in
  pulley|cranelift|all) ;;
  *) wasmline_die "Unknown engine '${ENGINE}'. Expected pulley, cranelift, or all." 2 ;;
esac

MULTIPLATFORM_ROOT="${WASMLINE_PROJECT_ROOT}/wasmline-multiplatform"
WASMTIME_VERSION="$(wasmline_version wasmtime_version)"
WASMTIME_TAG="release-v${WASMTIME_VERSION}"

detect_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    printf '%s\n' "${ANDROID_NDK_HOME}"
    return
  fi

  local local_properties="${MULTIPLATFORM_ROOT}/local.properties"
  if [[ -f "${local_properties}" ]]; then
    local configured
    configured="$(sed -nE 's/^[[:space:]]*ndk\.dir[[:space:]]*=[[:space:]]*(.*)$/\1/p' "${local_properties}" | head -n 1)"
    if [[ -n "${configured}" && -d "${configured}" ]]; then
      printf '%s\n' "${configured}"
      return
    fi
  fi
  wasmline_die "Set ANDROID_NDK_HOME or ndk.dir in wasmline-multiplatform/local.properties."
}

build_android() {
  local engine="$1"
  wasmline_require_command cmake

  local ndk_dir
  ndk_dir="$(detect_ndk)"
  local cmake_toolchain="${ndk_dir}/build/cmake/android.toolchain.cmake"
  [[ -f "${cmake_toolchain}" ]] || wasmline_die "Android NDK toolchain was not found: ${cmake_toolchain}"

  local cmake_source="${MULTIPLATFORM_ROOT}/wasmline-android/src/androidMain"
  local build_root="${MULTIPLATFORM_ROOT}/wasmline-android/build/native-assets/${engine}"
  local staging="${build_root}/deploy"
  rm -rf "${staging}"
  mkdir -p "${staging}"
  local abi install_path
  while IFS='|' read -r abi install_path; do
    [[ -n "${abi}" ]] || continue
    local input="${WASMLINE_PLATFORMS_ROOT}/${WASMTIME_TAG}/${engine}/${install_path}"
    [[ -d "${input}" ]] || wasmline_die "Wasmtime files are missing: ${engine}/${install_path}"

    local api_level=23
    if [[ "${abi}" == "arm64-v8a" || "${abi}" == "x86_64" ]]; then
      api_level=21
    fi

    local build_dir="${build_root}/${abi}"
    rm -rf "${build_dir}/CMakeCache.txt" "${build_dir}/CMakeFiles"
    mkdir -p "${build_dir}"
    wasmline_status INFO "Android ${abi}" "Building ${engine}."
    cmake -S "${cmake_source}" -B "${build_dir}" \
      -DCMAKE_TOOLCHAIN_FILE="${cmake_toolchain}" \
      -DANDROID_ABI="${abi}" \
      -DANDROID_PLATFORM="android-${api_level}" \
      -DWASMTIME_VERSION="${WASMTIME_TAG}" \
      -DWASMTIME_VARIANT="${engine}" \
      -DCMAKE_BUILD_TYPE=Release
    cmake --build "${build_dir}" --target wasmline -- -j"$(wasmline_cpu_count)"

    local library="${build_dir}/libwasmline.so"
    [[ -f "${library}" ]] || wasmline_die "Android output is missing: ${library}"
    local destination="${staging}/${abi}"
    mkdir -p "${destination}"
    cp "${library}" "${destination}/libwasmline.so"
  done < <(wasmline_jni_targets "${engine}" android)

  local output="${MULTIPLATFORM_ROOT}/wasmline-engine-${engine}/src/androidMain/jniLibs"
  wasmline_replace_directory "${staging}" "${output}"
  wasmline_status OK "Android" "${engine} libraries installed in ${output}."
}

build_jvm() {
  local engine="$1"
  wasmline_require_command zig

  local zig_project="${MULTIPLATFORM_ROOT}/wasmline"
  local resources="${MULTIPLATFORM_ROOT}/wasmline-engine-${engine}/src/jvmMain/resources/jni"
  local staging="${MULTIPLATFORM_ROOT}/wasmline/build/native-assets/${engine}/jvm-resources"
  rm -rf "${staging}"
  mkdir -p "${staging}"

  local zig_target install_path platform_name arch extension
  while IFS='|' read -r zig_target install_path platform_name arch extension; do
    [[ -n "${zig_target}" ]] || continue
    local input="${WASMLINE_PLATFORMS_ROOT}/${WASMTIME_TAG}/${engine}/${install_path}"
    [[ -d "${input}" ]] || wasmline_die "Wasmtime files are missing: ${engine}/${install_path}"

    wasmline_status INFO "JVM ${platform_name}/${arch}" "Building ${engine}."
    if ! (
      cd "${zig_project}"
      zig build \
        -Dtarget="${zig_target}" \
        -Dwasmtime-version="${WASMTIME_TAG}" \
        -Dwasmtime-variant="${engine}" \
        --release=small
    ); then
      wasmline_die "Zig build failed for ${zig_target}."
    fi

    local library="${zig_project}/zig-out/jni/${arch}/libwasmline.${extension}"
    [[ -f "${library}" ]] || wasmline_die "JVM output is missing: ${library}"
    local destination="${staging}/${platform_name}/${arch}"
    mkdir -p "${destination}"
    cp "${library}" "${destination}/libwasmline.${extension}"
  done < <(wasmline_jni_targets "${engine}" jvm)

  wasmline_replace_directory "${staging}" "${resources}"
  wasmline_status OK "JVM" "${engine} libraries installed in ${resources}."
}

build_engine() {
  local engine="$1"
  build_android "${engine}"
  build_jvm "${engine}"
}

validate_engine_inputs() {
  local engine="$1"
  local install_path
  while IFS= read -r install_path; do
    [[ -n "${install_path}" ]] || continue
    local input="${WASMLINE_PLATFORMS_ROOT}/${WASMTIME_TAG}/${engine}/${install_path}"
    if [[ ! -f "${input}/include/wasmtime.h" || ! -f "${input}/lib/libwasmtime.a" ]]; then
      wasmline_die "Wasmtime files are missing: ${engine}/${install_path}"
    fi
  done < <(wasmline_engine_install_paths "${engine}")
}

wasmline_title "JNI library build"
wasmline_status INFO "Version" "${WASMTIME_TAG}"
wasmline_status INFO "Engine" "${ENGINE}"

if [[ "${ENGINE}" == "all" ]]; then
  validate_engine_inputs pulley
  validate_engine_inputs cranelift
  build_engine pulley
  build_engine cranelift
else
  validate_engine_inputs "${ENGINE}"
  build_engine "${ENGINE}"
fi
