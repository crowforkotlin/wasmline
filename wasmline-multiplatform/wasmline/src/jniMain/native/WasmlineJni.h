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
#include "JniComponentHostHandler.h"
#include "JniHostHandler.h"

#if WASM_LOGS_ENABLED
using wasmline::NativeLogE;
#endif

static jboolean loadPrecompiledModuleCommon(JNIEnv *env, jstring keyStr, jstring pathStr, bool unsafe,
                                            const wasmline::WasmlineArtifactFormat* artifactFormat = nullptr) {
    if (!artifactFormat) {
        LOGE("[Wasmline] JNI --> Native artifact loading requires an explicit format.");
        return JNI_FALSE;
    }
    if (!env || !keyStr || !pathStr) return JNI_FALSE;
    const char *key = env->GetStringUTFChars(keyStr, nullptr);
    const char *path = env->GetStringUTFChars(pathStr, nullptr);
    if (!key || !path) {
        if (path) env->ReleaseStringUTFChars(pathStr, path);
        if (key) env->ReleaseStringUTFChars(keyStr, key);
        return JNI_FALSE;
    }
    bool success = unsafe ? wasmline::Api::loadModuleUnsafe(key, path, *artifactFormat)
                          : wasmline::Api::loadModule(key, path, *artifactFormat);
    env->ReleaseStringUTFChars(keyStr, key);
    env->ReleaseStringUTFChars(pathStr, path);
    return success;
}

static jboolean loadPrecompiledModuleWithFormatCommon(JNIEnv *env, jstring keyStr, jstring pathStr, jint formatCode, bool unsafe) {
    wasmline::WasmlineArtifactFormat artifactFormat;
    if (!wasmline::Api::tryArtifactFormatFromCode(static_cast<int32_t>(formatCode), &artifactFormat)) {
        LOGE("[Wasmline] JNI --> Invalid native artifact format code: %d", static_cast<int>(formatCode));
        return JNI_FALSE;
    }
    return loadPrecompiledModuleCommon(env, keyStr, pathStr, unsafe, &artifactFormat);
}
