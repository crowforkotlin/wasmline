#include <jni.h>
#include <android/log.h>
#include <vector>

// 引入核心逻辑
#include "WasmtimeCore.h"

#define TAG "KotlinWasm"

// ============================================================================
// Android 特有日志回调
// ============================================================================
void android_logger(const std::string& msg) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", msg.c_str());
}

// ============================================================================
// JNI 接口实现
// ============================================================================
extern "C" JNIEXPORT jint JNICALL
Java_crow_wasmtime_wasmline_MainActivity_runWasmAdd(JNIEnv *env, jobject thiz, jbyteArray wasmBytes, jboolean enableLogs) {
    
    // 1. 转换 Java 字节数组到 C++ vector
    jsize len = env->GetArrayLength(wasmBytes);
    jbyte *bytes = env->GetByteArrayElements(wasmBytes, nullptr);
    if (!bytes) return -1;

    std::vector<uint8_t> wasm_data(bytes, bytes + len);
    env->ReleaseByteArrayElements(wasmBytes, bytes, JNI_ABORT);

    // 2. 配置 WasmtimeCore (针对 Android 的“安全模式”配置)
    WasmConfig config;
    
    // Kotlin/Wasm 必须项
    config.enableGc = true;
    config.enableExceptionHandling = true;
    config.enableFunctionReferences = true;
    
    // Android 必须项 (防止 SIGILL)
    config.enableSimd = false;              // 关闭 SIMD
    config.enableSignalsBasedTraps = false; // 关闭信号 Trap，改用显式检查
    config.enableCraneliftOpt = false;      // 关闭 JIT 优化

    // 日志配置
    config.enableWasiOutput = enableLogs;

    // 3. 实例化 Core 并运行
    WasmtimeCore core(config);
    
    // 注册 Android 日志回调
    if (enableLogs) {
        core.setLogCallback(android_logger);
    }

    // 4. 执行业务逻辑
    // 这里演示传入 1 + 2
    int32_t result = core.runAddFunction(wasm_data, 1, 2);

    return result;
}