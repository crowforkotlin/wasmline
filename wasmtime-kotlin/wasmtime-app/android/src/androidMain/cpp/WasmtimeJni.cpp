#include <jni.h>
#include <string>
#include "WasmManager.h"
#include "WasmSession.h"
#include "FileUtils.h"
#include "WasmLog.h"

#pragma clang diagnostic push
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
Java_crow_wasmtime_WasmModule_nativeJsonCall(JNIEnv *env, jclass thiz, jstring keyStr, jstring action, jstring json) {
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
            result = session->callJson(act, (size_t)actLen, jsn, (size_t)jsnLen);
        } else {
            result = "{\"error\":\"Session creation failed (Module not found?)\"}";
        }
    }
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(action, act);
    env->ReleaseStringUTFChars(json, jsn);

    return env->NewStringUTF(result.c_str());
}


// [核心优化] Native Call
JNIEXPORT jbyteArray JNICALL
Java_crow_wasmtime_WasmModule_nativeProtobufCall(JNIEnv *env, jclass thiz, jstring keyStr, jstring action, jbyteArray bytes) {
    // 1. 获取 Key 和 Action 字符串
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* act = env->GetStringUTFChars(action, nullptr);

    // 性能优化：直接从 JVM 获取长度，复杂度 O(1)，避免 strlen 的 O(N)
    jsize actLen = env->GetStringUTFLength(action);

    // 2. 获取二进制数据 (Protobuf)
    // 性能优化：获取原始指针，可能产生 pinning 避免复制
    jbyte* dataPtr = env->GetByteArrayElements(bytes, nullptr);
    jsize dataLen = env->GetArrayLength(bytes);

    std::string result;
    jbyteArray retArr = nullptr;

    {
        // 3. 执行调用
        WasmSession* session = WasmManager::getInstance().getOrCreateSession(key);
        if (session) {
            // 注意：这里调用的是 callProtobuf (需要你在 WasmSession 中新增，见下文)
            // 将 jbyte* (signed char*) 强转为 char* 传递给 C++
            result = session->callProtobuf(act, (size_t)actLen, (const char*)dataPtr, (size_t)dataLen);
        } else {
            // Session 不存在，返回空或错误处理
            result = "";
        }
    }

    // 4. 释放资源
    // 性能优化：使用 JNI_ABORT，告诉 JVM 我们没有修改 dataPtr 的内容，不需要回写到 Java 堆，节省一次内存拷贝
    env->ReleaseByteArrayElements(bytes, dataPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(action, act);
    env->ReleaseStringUTFChars(keyStr, key);

    // 5. 构造返回结果
    if (!result.empty()) {
        retArr = env->NewByteArray((jsize)result.size());
        // 内存拷贝：C++ std::string -> Java byte[]
        env->SetByteArrayRegion(retArr, 0, (jsize)result.size(), (const jbyte*)result.data());
    } else {
        retArr = env->NewByteArray(0);
    }

    return retArr;
}



} // extern "C"
#pragma clang diagnostic pop