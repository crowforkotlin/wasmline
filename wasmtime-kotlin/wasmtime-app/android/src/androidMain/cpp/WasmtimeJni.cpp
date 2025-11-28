#include <jni.h>
#include <string>
#include <malloc.h>
#include "WasmModule.h"
#include "WasmSession.h"

extern "C" {

// 接收智能指针
static jlong createSession(WasmModule::ModulePtr modulePtr) {
    if (!modulePtr) return 0;

    auto enginePtr = WasmModule::getGlobalEngine();
    // 创建 Session，传入两个智能指针
    WasmSession* session = new WasmSession(enginePtr, modulePtr);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jlong JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeInitPath(JNIEnv *env, jobject, jstring pathStr) {
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    auto modulePtr = WasmModule::loadFromPath(path);
    env->ReleaseStringUTFChars(pathStr, path);
    return createSession(modulePtr);
}

JNIEXPORT jlong JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeInitSourcePath(JNIEnv *env, jobject, jstring pathStr) {
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    auto modulePtr = WasmModule::loadFromSourcePath(path);
    env->ReleaseStringUTFChars(pathStr, path);
    return createSession(modulePtr);
}

JNIEXPORT jlong JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeInitBytes(JNIEnv *env, jobject, jbyteArray bytes) {
    if (!bytes) return 0;
    jsize len = env->GetArrayLength(bytes);
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    std::vector<uint8_t> vec(data, data + len);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    auto modulePtr = WasmModule::loadFromSource(vec);
    return createSession(modulePtr);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeSaveCache(JNIEnv *env, jobject, jlong handle, jstring pathStr) {
    auto* session = reinterpret_cast<WasmSession*>(handle);
    // 这里调用 getModulePtr()，现在 WasmSession.h 里已经有了
    if (!session || !session->getModulePtr()) return false;

    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    bool success = WasmModule::saveCacheToPath(session->getModulePtr(), path);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}

JNIEXPORT jstring JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeCall(JNIEnv *env, jobject, jlong handle, jstring action, jstring json) {
    auto* session = reinterpret_cast<WasmSession*>(handle);
    if (!session) return env->NewStringUTF("{\"error\": \"Invalid Handle\"}");
    const char* a = env->GetStringUTFChars(action, nullptr);
    const char* j = env->GetStringUTFChars(json, nullptr);
    std::string res = session->call(a ? a : "", j ? j : "");
    if (a) env->ReleaseStringUTFChars(action, a);
    if (j) env->ReleaseStringUTFChars(json, j);
    return env->NewStringUTF(res.c_str());
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeRelease(JNIEnv *env, jobject, jlong handle) {
    auto* session = reinterpret_cast<WasmSession*>(handle);
    if (session) delete session;
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeClearCache(JNIEnv *env, jobject, jstring pathStr) {
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    WasmModule::removeCache(path);
    env->ReleaseStringUTFChars(pathStr, path);
}

JNIEXPORT void JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeFreeAllResources(JNIEnv *env, jobject) {
    WasmModule::freeAllResources();
//    malloc_trim(0);
}

} // extern C