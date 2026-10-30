plugins {
    alias(libs.plugins.app.base.library)
}

// This project holds native build inputs only. Engine modules publish the JNI
// libraries built by './scripts/wasmline jni build --engine all'. The Maven
// coordinate wasmline-android belongs to the Android target of :wasmline.
