/**
 * JNI Native Bridge for Wasmtime Integration.
 *
 * Date: 2025-12-02
 * Author: crowforkotlin
 */

#include <jni.h>
#include <string>
#include "WasmRuntime.h"
#include "WasmFileUtils.h"
#include "WasmLogger.h"

extern "C" {

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmRuntime_nativeInit(JNIEnv *env, jobject thiz) {
    WasmRuntime::getInstance().initEngine();
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_WasmRuntime_nativeRelease(JNIEnv *env, jobject thiz) {
    WasmRuntime::getInstance().releaseEngine();
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeLoadSource(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    
    auto* mod = WasmRuntime::getInstance().loadModule(key, path, true); // true = JIT/Source
    
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return (mod != nullptr);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeLoadCache(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    
    auto* mod = WasmRuntime::getInstance().loadModule(key, path, false); // false = Cache
    
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return (mod != nullptr);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_WasmModule_nativeSaveCache(JNIEnv *env, jclass thiz, jstring keyStr, jstring outPathStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* outPath = env->GetStringUTFChars(outPathStr, nullptr);
    bool success = false;

    auto* module = WasmRuntime::getInstance().getModule(key);
    if (module) {
        wasm_byte_vec_t serialized;
        wasmtime_error_t* err = wasmtime_module_serialize(module, &serialized);

        if (!err) {
            // Write directly using pointer to avoid copying to vector
            success = Utils::writeFile(outPath, reinterpret_cast<const uint8_t*>(serialized.data), serialized.size);
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
Java_crow_wasmtime_WasmModule_nativeRelease(JNIEnv *env, jclass thiz, jstring keyStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    WasmRuntime::getInstance().releaseModule(key);
    env->ReleaseStringUTFChars(keyStr, key);
}

// Unified call for both JSON and Protobuf (bytes)
// Action is passed as string, Input is passed as byte array
JNIEXPORT jbyteArray JNICALL
Java_crow_wasmtime_WasmModule_nativeCall(JNIEnv *env, jclass thiz, jstring keyStr, jstring actionStr, jbyteArray inputBytes) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* action = env->GetStringUTFChars(actionStr, nullptr);
    jsize actionLen = env->GetStringUTFLength(actionStr);

    jbyte* dataPtr = env->GetByteArrayElements(inputBytes, nullptr);
    jsize dataLen = env->GetArrayLength(inputBytes);

    std::string resultData;
    jbyteArray retArr = nullptr;

    WasmSession* session = WasmRuntime::getInstance().getSession(key);
    if (session) {
        // Zero-copy passing of pointers
        resultData = session->call(action, (size_t)actionLen, (const char*)dataPtr, (size_t)dataLen);
    } else {
        LOGE("Session not found for call: %s", key);
    }

    // Release JNI resources. JNI_ABORT avoids writing back to Java array if not modified
    env->ReleaseByteArrayElements(inputBytes, dataPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(actionStr, action);
    env->ReleaseStringUTFChars(keyStr, key);

    // Convert result back to Java byte array
    if (!resultData.empty()) {
        retArr = env->NewByteArray((jsize)resultData.size());
        env->SetByteArrayRegion(retArr, 0, (jsize)resultData.size(), (const jbyte*)resultData.data());
    } else {
        retArr = env->NewByteArray(0);
    }

    return retArr;
}

} // extern "C"