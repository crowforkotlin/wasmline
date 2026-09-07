/**
 * Defines the JNI typed Component Model host handler.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */

#pragma once

#include <jni.h>

#include "wasmline/runtime/ComponentHostHandler.h"

/** Forwards typed Component imports to a Kotlin registry dispatcher. */
class JniComponentHostHandler : public wasmline::ComponentHostHandler {
public:
  /** Creates a handler for one JVM dispatcher object. */
  JniComponentHostHandler(JNIEnv *env, jobject dispatcher);

  /** Releases the JVM reference owned by this handler. */
  ~JniComponentHostHandler() override;

  /** Dispatches one typed Component import to Kotlin. */
  wasmline::InvocationResult onComponentHostInvoke(
      std::string_view interfaceName, std::string_view functionName,
      const std::vector<wasmline::ComponentValue> &arguments) override;

  /** Reports whether the JVM dispatcher and its method were resolved. */
  bool isValid() const noexcept;

private:
  JavaVM *jvm = nullptr;
  jobject javaDispatcherRef = nullptr;
  jmethodID dispatchMethodId = nullptr;
};
