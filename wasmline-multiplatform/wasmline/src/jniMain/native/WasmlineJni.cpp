/**
 * WasmtimeJni.cpp
 * JNI Bridge for Wasmtime Integration.
 * Forwards Java calls to Api.
 *
 * 2025-12-03
 * @author crowforkotlin
 */

/*
 * Fix zig build on windows error, because rust-produced static library (wasmtime) 
 * requires floating-point initialization symbol, which is often missing in 
 * non-MSVC environments (like zig's gnu target).
 */
#if defined(_WIN32) || defined(_WIN64)
extern "C" {
    int _fltused = 0;
}
#endif

#include "WasmlineJni.h"
#include <sstream>
#include <iomanip>
#include "Logger.h"
extern "C" {

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeWarmup(JNIEnv *env, jclass thiz, jboolean usePulley) {
    wasmline::Api::warmupEngine(usePulley == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeReleaseEngine(JNIEnv *env, jclass thiz) {
    wasmline::Api::releaseEngine();
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadAot(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    return loadPrecompiledModuleCommon(env, keyStr, pathStr, false);
}

JNIEXPORT jboolean JNICALL
Java_crow_wasmline_Wasmline_nativeLoadAotUnsafe(JNIEnv *env, jclass thiz, jstring keyStr, jstring pathStr) {
    return loadPrecompiledModuleCommon(env, keyStr, pathStr, true);
}

JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeReleaseModule(JNIEnv *env, jclass thiz, jstring keyStr) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    wasmline::Api::releaseModule(key);
    env->ReleaseStringUTFChars(keyStr, key);
}

JNIEXPORT jbyteArray JNICALL
Java_crow_wasmline_Wasmline_nativeInvokeInbound(JNIEnv *env, jclass thiz, jstring keyStr, jstring actionStr, jbyteArray inputBytes) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    const char* action = env->GetStringUTFChars(actionStr, nullptr);
    jsize actionLen = env->GetStringUTFLength(actionStr);

    jbyte* dataPtr = env->GetByteArrayElements(inputBytes, nullptr);
    jsize dataLen = env->GetArrayLength(inputBytes);

    // Perform invokeInbound via API.
    std::string resultData = wasmline::Api::invokeInbound(key, action, (size_t)actionLen, (const char *)dataPtr, (size_t)dataLen);

    // Clean up input buffers.
    env->ReleaseByteArrayElements(inputBytes, dataPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(actionStr, action);
    env->ReleaseStringUTFChars(keyStr, key);

    // Log the raw native result bytes for debugging.
    size_t size = resultData.size();
    std::stringstream ss;
    ss << std::hex << std::setfill('0');
    for (size_t i = 0; i < size; ++i) {
        // Cast to unsigned int to avoid sign extension in the hex dump.
        ss << std::setw(2) << (static_cast<unsigned int>(resultData[i]) & 0xFF);
    }
    std::string hexStr = ss.str();

    wasmline::NativeLogI("\n[JNI DEBUG] Result Size: %zu\n", size);
    wasmline::NativeLogI("[JNI DEBUG] Result Hex : %s\n\n", hexStr.c_str());

    // Return the result.
    if (!resultData.empty()) {
        jbyteArray retArr = env->NewByteArray((jsize)resultData.size());
        env->SetByteArrayRegion(retArr, 0, (jsize)resultData.size(), (const jbyte*)resultData.data());
        return retArr;
    } else {
        return env->NewByteArray(0);
    }
}

// Register the outbound dispatcher.
JNIEXPORT void JNICALL
Java_crow_wasmline_Wasmline_nativeSetOutboundHandler(JNIEnv *env, jclass thiz, jstring keyStr, jobject jDispatcher) {
    const char* key = env->GetStringUTFChars(keyStr, nullptr);
    auto handler = std::make_unique<JniHostHandler>(env, jDispatcher);
    wasmline::Api::setOutboundHandler(key, std::move(handler));
    env->ReleaseStringUTFChars(keyStr, key);
}
} // extern "C"
