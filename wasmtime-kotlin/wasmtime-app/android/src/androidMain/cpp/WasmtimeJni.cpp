/**
 * WasmtimeJni.cpp
 * JNI Bridge for Wasmtime Integration.
 * Forwards Java calls to WasmApi.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

#include <jni.h>
#include <string>
#include "WasmApi.h"
#include "WasmFileUtils.h" // Needed for Utils if not encapsulated
#include "WasmLogger.h"
#include "AndroidHostHandler.h"

extern "C" {

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmLine_nativeInit(JNIEnv *env, jclass thiz) {
    WasmApi::initEngine();
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmLine_nativeReleaseEngine(JNIEnv *env, jclass thiz) {
    WasmApi::releaseEngine();
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmLine_nativeLoadSource(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);

    // Call API Facade -> JIT Loading (true)
    bool success = WasmApi::loadModule(key, path, true);

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmLine_nativeLoadCache(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);

    // Call API Facade -> Cache Loading (false)
    bool success = WasmApi::loadModule(key, path, false);

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmLine_nativeSaveCache(JNIEnv *env, jclass thiz, jstring keyStr, jstring outPathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* outPath = env->GetStringUTFChars(outPathStr, nullptr);

    bool success = WasmApi::saveModuleCache(key, outPath);

    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(outPathStr, outPath);
    return success;
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmLine_nativeReleaseModule(JNIEnv *env, jclass thiz, jstring keyStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    WasmApi::releaseModule(key);
    env->ReleaseStringUTFChars(keyStr, key);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmtime_WasmLine_nativeCall(JNIEnv *env, jclass thiz, jstring keyStr, jstring actionStr, jbyteArray inputBytes) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* action = env->GetStringUTFChars(actionStr, nullptr);
    jsize actionLen = env->GetStringUTFLength(actionStr);

    jbyte* dataPtr = env->GetByteArrayElements(inputBytes, nullptr);
    jsize dataLen = env->GetArrayLength(inputBytes);

    // Perform call via API
    std::string resultData = WasmApi::call(key, action, std::string((const char*)dataPtr, dataLen));

    // Cleanup input
    env->ReleaseByteArrayElements(inputBytes, dataPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(actionStr, action);
    env->ReleaseStringUTFChars(keyStr, key);

    // Return result
    if (!resultData.empty()) {
        jbyteArray retArr = env->NewByteArray((jsize)resultData.size());
        env->SetByteArrayRegion(retArr, 0, (jsize)resultData.size(), (const jbyte*)resultData.data());
        return retArr;
    } else {
        return env->NewByteArray(0);
    }
}


// [新增] 注册分发器
JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmLine_nativeRegisterDispatcher(JNIEnv *env, jclass thiz, jstring keyStr, jobject jDispatcher) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);

    // 创建 Android 实现并注入 Core
    auto handler = std::make_unique<AndroidHostHandler>(env, jDispatcher);
    WasmApi::registerHostHandler(key, std::move(handler));

    env->ReleaseStringUTFChars(keyStr, key);
}


} // extern "C"