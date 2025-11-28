#include <jni.h>
#include <string>
#include "WasmManager.h"
#include "WasmSession.h"
#include "FileUtils.h"
#include "WasmLog.h"

using namespace crow;

extern "C" {

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmRuntime_nativeInit(JNIEnv *env, jobject thiz) {
    WasmManager::getInstance().initEngine();
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmRuntime_nativeRelease(JNIEnv *env, jobject thiz) {
    WasmManager::getInstance().releaseEngine();
}

// 修正：从源码加载 (JIT)
JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeLoadSource(JNIEnv *env, jobject thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);

    // 核心修改：直接传路径，由 Manager 决定是否需要读取
    auto* mod = WasmManager::getInstance().getOrLoadModule(key, path, true /* JIT */);

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return (mod != nullptr);
}

// 修正：从缓存加载 (AOT)
JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeLoadCache(JNIEnv *env, jobject thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);

    // 核心修改：直接传路径
    auto* mod = WasmManager::getInstance().getOrLoadModule(key, path, false /* AOT */);

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return (mod != nullptr);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeSaveCache(JNIEnv *env, jobject thiz, jstring keyStr, jstring outPathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* outPath = env->GetStringUTFChars(outPathStr, nullptr);
    bool success = false;

    // 这里使用 getModule，因为序列化前提是模块必须已经加载在内存里了
    auto* module = WasmManager::getInstance().getModule(key);
    if (module) {
        wasm_byte_vec_t serialized;
        wasmtime_error_t* err = wasmtime_module_serialize(module, &serialized);
        if (!err) {
            std::vector<uint8_t> data(serialized.data, serialized.data + serialized.size);
            success = FileUtils::writeFile(outPath, data);
            wasm_byte_vec_delete(&serialized);
        } else {
            wasmtime_error_delete(err);
        }
    }

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(outPathStr, outPath);
    return success;
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmModule_nativeRelease(JNIEnv *env, jobject thiz, jstring keyStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    WasmManager::getInstance().releaseModule(key);
    env->ReleaseStringUTFChars(keyStr, key);
}

JNIEXPORT jstring JNICALL
Java_crow_wasmtime_WasmModule_nativeCall(JNIEnv *env, jobject thiz, jstring keyStr, jstring action, jstring json) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* act = env->GetStringUTFChars(action, nullptr);
    const char* jsn = env->GetStringUTFChars(json, nullptr);

    std::string result;

    // 获取模块 (读锁，极快)
    auto* module = WasmManager::getInstance().getModule(key);

    auto start_time = std::chrono::high_resolution_clock::now();
    if (module) {
        // 创建 Session (栈对象，自动销毁，线程安全)
        WasmSession session(WasmManager::getInstance().getEngine(), module);
        session.registerHostFunctions();
        result = session.call(act, jsn);
    } else {
        result = "{\"error\":\"Module not found\"}";
    }
    long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now() - start_time).count();
    LOGI("Module [%s] loading time: %lld ms", key, ms);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(action, act);
    env->ReleaseStringUTFChars(json, jsn);

    return env->NewStringUTF(result.c_str());
}

} // extern "C"