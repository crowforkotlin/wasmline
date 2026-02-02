#include <jni.h>
#include <string>
#include <string>
#include "Logger.h"
#include "Api.h"
#include "extensions/FileUtils.h"
#include "JniHostHandler.h"

static jboolean saveCacheCommon(JNIEnv *env, jstring keyStr, jstring outPathStr, bool unsafe) {
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *outPath = env->GetStringUTFChars(outPathStr, nullptr);
    bool success = unsafe ? wasmline::Api::saveModuleCacheUnsafe(key, outPath) : wasmline::Api::saveModuleCache(key, outPath);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(outPathStr, outPath);
    return success ? JNI_TRUE : JNI_FALSE;
}

static jboolean loadModuleCommon(JNIEnv *env, jstring keyStr, jstring pathStr, bool isJit,  bool unsafe) {
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *path = env->GetStringUTFChars(pathStr, nullptr);
    bool success = unsafe ? wasmline::Api::loadModuleUnsafe(key, path, isJit) : wasmline::Api::loadModule(key, path, isJit);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}