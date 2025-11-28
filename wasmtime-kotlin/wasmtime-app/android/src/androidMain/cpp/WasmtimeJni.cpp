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
Java_crow_wasmtime_WasmModule_nativeLoadSource(JNIEnv *env, jobject thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);

    auto data = FileUtils::readFile(path);
    bool success = false;

    if (!data.empty()) {
        auto* mod = WasmManager::getInstance().loadModule(key, data, true);
        success = (mod != nullptr);
    } else {
        LOGE("Failed to read file: %s", path);
    }

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeLoadCache(JNIEnv *env, jobject thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);

    auto data = FileUtils::readFile(path);
    bool success = false;

    if (!data.empty()) {
        auto* mod = WasmManager::getInstance().loadModule(key, data, false);
        success = (mod != nullptr);
    }

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeSaveCache(JNIEnv *env, jobject thiz, jstring keyStr, jstring outPathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* outPath = env->GetStringUTFChars(outPathStr, nullptr);
    bool success = false;

    // 获取的已经是 wasmtime_module_t*
    auto* module = WasmManager::getInstance().getModule(key);
    if (module) {
        wasm_byte_vec_t serialized;
        // [修复] 参数类型匹配了
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

    std::string result = "{\"error\":\"Module not found\"}";

    auto* module = WasmManager::getInstance().getModule(key);
    if (module) {
        // [修复] 构造函数参数匹配
        WasmSession session(WasmManager::getInstance().getEngine(), module);
        session.registerHostFunctions();
        result = session.call(act, jsn);
    }

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(action, act);
    env->ReleaseStringUTFChars(json, jsn);

    return env->NewStringUTF(result.c_str());
}

} // extern "C"