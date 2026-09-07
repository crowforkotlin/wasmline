plugins {
    alias(libs.plugins.app.base.library)
    alias(libs.plugins.maven.publish)
}

// NDK/CMake removed: libwasmline.so is pre-compiled by the engine module (wasmline-engine-pulley / cranelift)
// and distributed via jniLibs; consumers do not need native compilation.
//
// To recompile libwasmline.so (dev/CI), use one of:
//   - Android: bash scripts/build-native-android.sh
//   - Desktop: zig build (in wasmline/ directory)
