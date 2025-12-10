#include <jni.h>
#include <string>
#include <string>
#include "WasmLogger.h"
#include "WasmApi.h"
#include "extensions/FileUtils.h"
#include "AndroidHostHandler.h"

static jboolean saveCacheCommon(JNIEnv *env, jstring keyStr, jstring outPathStr, bool unsafe) {
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *outPath = env->GetStringUTFChars(outPathStr, nullptr);
    bool success = unsafe ? WasmApi::saveModuleCacheUnsafe(key, outPath) : WasmApi::saveModuleCache(key, outPath);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(outPathStr, outPath);
    return success ? JNI_TRUE : JNI_FALSE;
}

static jboolean loadModuleCommon(JNIEnv *env, jstring keyStr, jstring pathStr, bool isJit,  bool unsafe) {
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *path = env->GetStringUTFChars(pathStr, nullptr);
    bool success = unsafe ? WasmApi::loadModuleUnsafe(key, path, isJit) : WasmApi::loadModule(key, path, isJit);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}