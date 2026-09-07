/**
 * Defines the JNI declarations for the Wasmline native API.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include <jni.h>
#include "wasmline/api/Api.h"
#include "JniComponentHostHandler.h"
#include "JniHostHandler.h"

static jbyteArray loadPrecompiledModuleWithFormatCommon(JNIEnv *env, jstring keyStr, jstring pathStr, jint formatCode, bool unsafe);
static jbyteArray loadPrecompiledComponentWithFormatCommon(JNIEnv *env, jstring keyStr, jstring pathStr, jint formatCode, bool unsafe);
