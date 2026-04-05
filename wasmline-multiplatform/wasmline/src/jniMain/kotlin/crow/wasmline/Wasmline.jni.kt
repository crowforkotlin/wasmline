@file:Suppress("unused", "OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")

package crow.wasmline

import crow.wasmline.extensions.loadNativeLibrary
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import java.io.File

actual class Wasmline actual constructor(val moduleKey: String) {

    actual companion object {

        init { loadNativeLibrary() }

        /**
         * 加载模块
         * @param filepath .wasm (源码路径) 或 .cwasm (缓存路径)
         */
        actual fun load(filepath: String, cacheFilepath: String?, threadSafe: Boolean): WasmlineLoadState {
            val sourceFile = File(filepath)
            val cacheFile = cacheFilepath?.let(::File)
            val sourcePath = sourceFile.absolutePath
            val cachePath = cacheFile?.absolutePath
            val key = sourcePath

            return loadWasmlineModule(
                sourcePath = sourcePath,
                cachePath = cachePath,
                key = key,
                createWasmline = ::Wasmline,
                fileExists = { path -> File(path).exists() },
                deleteFile = { path -> File(path).delete() },
                loadAot = { moduleKey, path ->
                    if (threadSafe) nativeLoadAot(key = moduleKey, path = path)
                    else nativeLoadAotUnsafe(key = moduleKey, path = path)
                },
                loadJit = { moduleKey, path ->
                    if (threadSafe) nativeLoadJit(key = moduleKey, path = path)
                    else nativeLoadJitUnsafe(key = moduleKey, path = path)
                },
                saveCache = { moduleKey, path ->
                    if (threadSafe) nativeSaveCache(key = moduleKey, path = path)
                    else nativeSaveCacheUnsafe(key = moduleKey, path = path)
                },
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
        actual fun shutdown() { nativeReleaseEngine() }

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

    actual internal fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        nativeSetOutboundHandler(moduleKey, dispatcher)
    }

    /**
     * 执行 Wasm 函数
     * 支持并发调用，底层会自动创建独立的 Session
     */
    actual internal fun call(action: String, inputBytes: ByteArray) : ByteArray = nativeInvokeInbound(moduleKey, action, inputBytes)
    // suspend fun call(action: String, json: String): String = withContext(Dispatchers.Default) { nativeCall(moduleKey, action, json) }

    /**
     * 释放当前模块
     * 不会影响 Engine，但会释放此模块占用的内存
     */
    actual fun close() { nativeReleaseModule(moduleKey) }
}