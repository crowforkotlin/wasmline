#include <jni.h>
#include <string>
#include <vector>
#include "WasmModule.h"

extern "C" {

// 1. 尝试从文件路径加载 (AOT)
// 返回: handle 指针 (long), 0 表示失败
JNIEXPORT jlong JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeInitPath(JNIEnv *env, jobject thiz, jstring pathStr) {
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    WasmModule* module = WasmModule::loadFromPath(path);
    env->ReleaseStringUTFChars(pathStr, path);
    return reinterpret_cast<jlong>(module);
}

// 2. 从内存字节加载 (JIT)
// 返回: handle 指针
JNIEXPORT jlong JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeInitBytes(JNIEnv *env, jobject thiz, jbyteArray bytes) {
    if (!bytes) return 0;
    jsize len = env->GetArrayLength(bytes);
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);

    std::vector<uint8_t> vec(data, data + len);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);

    WasmModule* module = WasmModule::loadFromSource(vec);
    return reinterpret_cast<jlong>(module);
}

// 从文件路径加载源码进行 JIT 编译
JNIEXPORT jlong JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeInitSourcePath(JNIEnv *env, jobject thiz, jstring pathStr) {
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    WasmModule* module = WasmModule::loadFromSourcePath(path);
    env->ReleaseStringUTFChars(pathStr, path);
    return reinterpret_cast<jlong>(module);
}

// 3. 将当前模块序列化并保存到路径 (C++ 直接写文件)
JNIEXPORT jboolean JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeSaveCache(JNIEnv *env, jobject thiz, jlong handle, jstring pathStr) {
    auto* module = reinterpret_cast<WasmModule*>(handle);
    if (!module) return false;

    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    bool success = module->saveCacheToPath(path);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}

// 4. 执行调用
JNIEXPORT jstring JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeCall(JNIEnv *env, jobject thiz, jlong handle, jstring action, jstring json) {
    auto* module = reinterpret_cast<WasmModule*>(handle);
    if (!module) return env->NewStringUTF("{\"error\": \"Invalid Handle\"}");

    const char* a = env->GetStringUTFChars(action, nullptr);
    const char* j = env->GetStringUTFChars(json, nullptr);

    // 执行
    std::string result = module->call(a ? a : "", j ? j : "");

    if (a) env->ReleaseStringUTFChars(action, a);
    if (j) env->ReleaseStringUTFChars(json, j);

    return env->NewStringUTF(result.c_str());
}

// 5. 释放资源
JNIEXPORT void JNICALL
Java_crow_wasmtime_wasmline_WasmEngine_nativeRelease(JNIEnv *env, jobject thiz, jlong handle) {
    auto* module = reinterpret_cast<WasmModule*>(handle);
    if (module) delete module;
}

} // extern C