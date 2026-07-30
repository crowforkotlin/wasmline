#!/usr/bin/env bash
#
# build-native-assets.sh
#
# Compile the libwasmline.so (including the wasmtime engine) and deploy it to the jniLibs / resources directory of the engine module.
# Used for native asset padding before publishing Maven products.
#
# Usage:
#   bash scripts/build-native-assets.sh [pulley|cranelift|all]
#
# Prerequisites:
#   1. Run init-wasmtime.sh to download wasmtime assets to build/platforms/
#   2. Android NDK installed (ANDROID_NDK_HOME or ndk.dir configured)
#   3. zig installed (for JVM desktop builds)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MULTIPLATFORM_ROOT="$REPO_ROOT/wasmline-multiplatform"
PLATFORMS_ROOT="$REPO_ROOT/build/platforms"

# ── Read the version ──────────────────────────────────────────────
read_version() {
  python3 -c "import json;print(json.load(open('$SCRIPT_DIR/versions.json'))['versions']['wasmtime_version'])" 2>/dev/null ||
    grep -oP 'wasmtime\.version\s*=\s*\K[^\s]+' "$MULTIPLATFORM_ROOT/gradle.properties" ||
    echo "45.0.5"
}

WASMTIME_VERSION="$(read_version)"
WASMTIME_TAG="release-v$WASMTIME_VERSION"
echo "==> Wasmtime version: $WASMTIME_TAG"

# ── Parse parameters ──────────────────────────────────────────────
VARIANT="${1:-all}"

# ── Android ABIs per variant ──────────────────────────────
get_android_abis() {
  local v="$1"
  if [[ "$v" == "cranelift" ]]; then
    echo "arm64-v8a x86_64"
  else
    echo "arm64-v8a armeabi-v7a x86 x86_64"
  fi
}

# ── Android NDK detection ──────────────────────────────────────
detect_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    echo "$ANDROID_NDK_HOME"
    return
  fi
  # Read from local.properties
  local local_props="$MULTIPLATFORM_ROOT/local.properties"
  if [[ -f "$local_props" ]]; then
    local ndk_dir
    ndk_dir=$(grep -oP 'ndk\.dir\s*=\s*\K.*' "$local_props" 2>/dev/null || true)
    if [[ -n "$ndk_dir" && -d "$ndk_dir" ]]; then
      echo "$ndk_dir"
      return
    fi
  fi
  echo "ERROR: ANDROID_NDK_HOME is not set and no ndk.dir found in local.properties" >&2
  echo "Set ANDROID_NDK_HOME or add ndk.dir to local.properties" >&2
  exit 1
}

# ── 1. Android build (via CMake) ──────────────────────────
build_android() {
  local variant="$1"
  local abis
  abis=$(get_android_abis "$variant")
  local ndk_dir
  ndk_dir=$(detect_ndk)
  local cmake_toolchain="$ndk_dir/build/cmake/android.toolchain.cmake"

  if [[ ! -f "$cmake_toolchain" ]]; then
    echo "ERROR: NDK toolchain file not found: $cmake_toolchain" >&2
    exit 1
  fi

  local platforms_dir="$PLATFORMS_ROOT/$WASMTIME_TAG/$variant/android"
  if [[ ! -d "$platforms_dir" ]]; then
    echo "SKIP: $platforms_dir not found; run init-wasmtime.sh first"
    return
  fi

  local cmake_src="$MULTIPLATFORM_ROOT/wasmline-android/src/androidMain"
  local build_root="$MULTIPLATFORM_ROOT/wasmline-android/build/native-assets/$variant"

  for abi in $abis; do
    local api_level=23
    if [[ "$abi" == "arm64-v8a" || "$abi" == "x86_64" ]]; then
      api_level=21
    fi

    echo "  [Android] $variant/$abi ..."
    local build_dir="$build_root/$abi"
    # Clean stale CMake cache to ensure lib path is re-validated
    rm -rf "$build_dir/CMakeCache.txt" "$build_dir/CMakeFiles"
    mkdir -p "$build_dir"

    cmake -S "$cmake_src" -B "$build_dir" \
      -DCMAKE_TOOLCHAIN_FILE="$cmake_toolchain" \
      -DANDROID_ABI="$abi" \
      -DANDROID_PLATFORM="android-$api_level" \
      -DWASMTIME_VERSION="$WASMTIME_TAG" \
      -DWASMTIME_VARIANT="$variant" \
      -DCMAKE_BUILD_TYPE=Release

    cmake --build "$build_dir" --target wasmline -- -j"$(nproc 2>/dev/null || echo 4)"

    local so_file="$build_dir/libwasmline.so"
    if [[ ! -f "$so_file" ]]; then
      echo "  ERROR: Compilation failed; $so_file not found" >&2
      exit 1
    fi

    # Deploy to engine module
    local dest_dir="$MULTIPLATFORM_ROOT/wasmline-engine-$variant/src/androidMain/jniLibs/$abi"
    mkdir -p "$dest_dir"
    cp "$so_file" "$dest_dir/libwasmline.so"
    # Remove .gitkeep (no longer needed when real .so exists)
    rm -f "$dest_dir/.gitkeep"
    echo "  ✓ $dest_dir/libwasmline.so ($(du -h "$so_file" | cut -f1))"
  done
}

# ── 2. JVM desktop build (via zig) ────────────────────────────
build_jvm() {
  local variant="$1"

  if ! command -v zig &>/dev/null; then
    echo "SKIP: zig not installed; skipping JVM desktop build"
    return
  fi

  local platforms_dir="$PLATFORMS_ROOT/$WASMTIME_TAG/$variant"
  local zig_dir="$MULTIPLATFORM_ROOT/wasmline"

  # All desktop targets: zig_target|asset_subdir|arch_name|extension
  local targets=(
    "x86_64-linux-gnu|linux/x64|x86_64|so"
    "aarch64-linux-gnu|linux/aarch64|aarch64|so"
    "aarch64-macos|mac/aarch64|aarch64|dylib"
    "x86_64-macos|mac/x64|x86_64|dylib"
    "x86_64-windows-gnu|windows/x64|x86_64|dll"
  )

  # Clean old JVM native resources (will be re-deployed to platform/$arch structure)
  local jvm_res="$MULTIPLATFORM_ROOT/wasmline-engine-$variant/src/jvmMain/resources/jni"
  rm -rf "$jvm_res"

  for entry in "${targets[@]}"; do
    IFS='|' read -r zig_target plat_subdir arch_name ext <<<"$entry"

    # Derive platform directory name (mac → darwin)
    local platform_name="${plat_subdir%%/*}"
    platform_name="${platform_name/mac/darwin}"

    # Skip if platform assets not downloaded
    if [[ ! -d "$platforms_dir/$plat_subdir" ]]; then
      echo "  SKIP: $variant/$plat_subdir (run init-wasmtime.sh to download)"
      continue
    fi

    echo "  [JVM] $variant/$plat_subdir (zig -Dtarget=$zig_target) ..."

    # Cross-compile via zig
    (cd "$zig_dir" && zig build \
      -Dtarget="$zig_target" \
      -Dwasmtime-version="$WASMTIME_TAG" \
      -Dwasmtime-variant="$variant" \
      --release=small 2>&1) || {
      echo "  WARN: zig build failed for $zig_target, continuing..." >&2
      continue
    }

    local zig_out="$zig_dir/zig-out/jni/$arch_name"
    local so_file="$zig_out/libwasmline.$ext"

    if [[ ! -f "$so_file" ]]; then
      echo "  WARN: zig output missing: $so_file" >&2
      continue
    fi

    # Deploy to engine module (platform/$arch structure for variant publishing)
    local dest_dir="$MULTIPLATFORM_ROOT/wasmline-engine-$variant/src/jvmMain/resources/jni/$platform_name/$arch_name"
    mkdir -p "$dest_dir"
    cp "$so_file" "$dest_dir/libwasmline.$ext"
    rm -f "$dest_dir/.gitkeep"
    echo "  ✓ $dest_dir/libwasmline.$ext ($(du -h "$so_file" | cut -f1))"
  done
}

# ── Main flow ─────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║  Wasmline Native Asset Builder                  ║"
echo "║  Variant: $VARIANT                              "
echo "║  Version: $WASMTIME_TAG                         "
echo "╚══════════════════════════════════════════════════╝"
echo ""

if [[ "$VARIANT" == "all" ]]; then
  echo "── Pulley ──"
  build_android "pulley"
  build_jvm "pulley"
  echo ""
  echo "── Cranelift ──"
  build_android "cranelift"
  build_jvm "cranelift"
elif [[ "$VARIANT" == "pulley" || "$VARIANT" == "cranelift" ]]; then
  build_android "$VARIANT"
  build_jvm "$VARIANT"
else
  echo "ERROR: Unknown variant '$VARIANT'; usage: $0 [pulley|cranelift|all]" >&2
  exit 1
fi

echo ""
echo "==> Done. Engine module jniLibs updated."
echo "    Run './gradlew publish' to publish to Maven."
