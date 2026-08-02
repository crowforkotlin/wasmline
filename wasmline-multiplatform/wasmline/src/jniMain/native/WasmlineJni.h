/**
 * Defines the JNI declarations for the Wasmline native API.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include <jni.h>
#include <string>
#include "wasmline/api/Api.h"
#include "logging/NativeLogger.h"
#include "JniHostHandler.h"

static jboolean loadPrecompiledModuleCommon(JNIEnv *env, jstring keyStr, jstring pathStr, bool unsafe) {
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *path = env->GetStringUTFChars(pathStr, nullptr);
    bool success = unsafe ? wasmline::Api::loadModuleUnsafe(key, path) : wasmline::Api::loadModule(key, path);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}
