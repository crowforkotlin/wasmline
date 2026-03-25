@file:Suppress("unused", "OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")

package crow.wasmline

import crow.wasmline.extensions.loadNativeLibrary
import crow.wasmline.spi.WasmlineHostDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.File

fun testAAA() {

}

actual class Wasmline actual constructor(val moduleKey: String) {

    actual companion object {

        init { loadNativeLibrary() }

        /**
         * 加载模块
         * @param filepath .wasm (源码路径) 或 .cwasm (缓存路径)
         */
        actual suspend fun load(filepath: String, cacheFilepath: String?, threadSafe: Boolean): WasmlineLoadState = withContext(Dispatchers.IO) {
            var isSuccess: Boolean
            val file = File(filepath)
            val cacheFile = if (cacheFilepath == null) null else File(cacheFilepath)
            val key: String = file.absolutePath

            // 1. Use aot to load cache file (.cwasm)
            if (cacheFile?.exists() == true) {
                isSuccess = if (threadSafe) {
                    nativeLoadAot(key = key, path = cacheFile.absolutePath)
                } else {
                    nativeLoadAotUnsafe(key = key, path = cacheFile.absolutePath)
                }
                if (isSuccess) {
                    return@withContext WasmlineLoadState.Success(code = WasmlineLoadState.CODE_SUCCESS_AOT, wasmline = Wasmline(
                        moduleKey = key
                    )
                    )
                }  else {
                    cacheFile.delete()
                }
            }

            // 2. wasm file not exits
            if (!file.exists()) {
                return@withContext WasmlineLoadState.Failure(code = WasmlineLoadState.CODE_FAILURE, cause = "[Wasmline] Load failure, file not found: ${file.absolutePath}")
            }

            // 3. Use jit to load file (.wasm)
            isSuccess = if (threadSafe) {
                nativeLoadJit(key = key, path = file.absolutePath)
            } else {
                nativeLoadJitUnsafe(key = key, path = file.absolutePath)
            }
            if (!isSuccess) {
                return@withContext WasmlineLoadState.Failure(code = WasmlineLoadState.CODE_FAILURE, cause = "[Wasmline] Load failure, because native load return false, file path is :  ${file.absolutePath}")
            }

            // 4. jit compile success, cache (.cwasm) on local storage
            if (cacheFile != null) {
                if (threadSafe) {
                    nativeSaveCache(key = key, path = cacheFile.absolutePath)
                } else {
                    nativeSaveCacheUnsafe(key = key, path = cacheFile.absolutePath)
                }
            }

            return@withContext WasmlineLoadState.Success(code = WasmlineLoadState.CODE_SUCCESS_JIT, wasmline = Wasmline(
                moduleKey = key
            )
            )
        }

        /**
         * 初始化全局 Engine。
         * 建议在 Application onCreate 中调用。
         */
        actual fun init() { nativeInit() }

        /**
         * 释放全局 Engine 和所有缓存的 Module。
         * 建议在确定不再使用 Wasm 时调用，或者 Activity onDestroy。
         */
        actual fun release() { nativeReleaseEngine() }

        // JNI Methods
        @JvmStatic private external fun nativeLoadJit(key: String, path: String): Boolean
        @JvmStatic private external fun nativeLoadJitUnsafe(key: String, path: String): Boolean
        @JvmStatic private external fun nativeLoadAot(key: String, path: String): Boolean
        @JvmStatic private external fun nativeLoadAotUnsafe(key: String, path: String): Boolean
        @JvmStatic private external fun nativeSaveCache(key: String, path: String): Boolean
        @JvmStatic private external fun nativeSaveCacheUnsafe(key: String, path: String): Boolean
        @JvmStatic private external fun nativeReleaseModule(key: String)
        @JvmStatic private external fun nativeSetOutboundHandler(key: String, dispatcher: WasmlineHostDispatcher)
        @JvmStatic private external fun nativeInvokeInbound(key: String, action: String, protobufBytes: ByteArray): ByteArray
        @JvmStatic private external fun nativeInit()
        @JvmStatic private external fun nativeReleaseEngine()
    }

    actual internal suspend fun setOutbound(dispatcher: WasmlineHostDispatcher) = withContext(Dispatchers.Default) { nativeSetOutboundHandler(moduleKey, dispatcher) }

    /**
     * 执行 Wasm 函数
     * 支持并发调用，底层会自动创建独立的 Session
     */
    actual internal suspend fun call(action: String, inputBytes: ByteArray) : ByteArray = nativeInvokeInbound(moduleKey, action, inputBytes)
    // suspend fun call(action: String, json: String): String = withContext(Dispatchers.Default) { nativeCall(moduleKey, action, json) }

    /**
     * 释放当前模块
     * 不会影响 Engine，但会释放此模块占用的内存
     */
    actual fun release() { nativeReleaseModule(moduleKey) }
}