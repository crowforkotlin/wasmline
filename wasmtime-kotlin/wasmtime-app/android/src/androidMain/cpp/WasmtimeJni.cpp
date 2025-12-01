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

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeLoadSource(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    auto* mod = WasmManager::getInstance().getOrLoadModule(key, path, true);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return (mod != nullptr);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeLoadCache(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    auto* mod = WasmManager::getInstance().getOrLoadModule(key, path, false);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return (mod != nullptr);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeSaveCache(JNIEnv *env, jclass thiz, jstring keyStr, jstring outPathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* outPath = env->GetStringUTFChars(outPathStr, nullptr);
    bool success = false;
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

// [修改] 释放 Session 和 Module
JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmModule_nativeRelease(JNIEnv *env, jclass thiz, jstring keyStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    WasmManager::getInstance().releaseModule(key); // 内部会自动释放 Session
    env->ReleaseStringUTFChars(keyStr, key);
}

// [新增] 单独释放 Session (如果你只想重置状态但不卸载模块)
JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmModule_nativeReleaseSession(JNIEnv *env, jclass thiz, jstring keyStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    WasmManager::getInstance().releaseSession(key);
    env->ReleaseStringUTFChars(keyStr, key);
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmModule_nativeReleaseEngine(JNIEnv * env, jclass thiz) {
    WasmManager::getInstance().releaseEngine();
}

// [核心优化] Native Call
JNIEXPORT jstring JNICALL
Java_crow_wasmtime_WasmModule_nativeCall(JNIEnv *env, jclass thiz, jstring keyStr, jstring action, jstring json) {
    // 1. 获取指针
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* act = env->GetStringUTFChars(action, nullptr);
    const char* jsn = env->GetStringUTFChars(json, nullptr);

    // 2. 获取长度 (这是 O(1) 操作，比 strlen 快，直接从 JVM 元数据拿)
    jsize actLen = env->GetStringUTFLength(action);
    jsize jsnLen = env->GetStringUTFLength(json);

    std::string result;

    {
        // 1. 从缓存获取 Session (如果第一次调用，会自动初始化)
        WasmSession* session = WasmManager::getInstance().getOrCreateSession(key);
        if (session) {
            // 3. 传入指针和长度，零拷贝！
            result = session->call(act, (size_t)actLen, jsn, (size_t)jsnLen);
        } else {
            result = "{\"error\":\"Session creation failed (Module not found?)\"}";
        }
    }
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(action, act);
    env->ReleaseStringUTFChars(json, jsn);

    return env->NewStringUTF(result.c_str());
}

} // extern "C"