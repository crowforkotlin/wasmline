plugins {
    alias(libs.plugins.app.base.library)
    alias(libs.plugins.maven.publish)
}

// NDK/CMake 已移除：libwasmline.so 由 engine 模块（wasmline-engine-pulley / cranelift）
// 预编译后通过 jniLibs 提供，消费者无需本地编译 native 代码。
//
// 如需重新编译 libwasmline.so（开发/CI），使用以下任一方式：
//   - Android: bash scripts/build-native-android.sh
//   - Desktop: zig build (在 wasmline/ 目录下)
