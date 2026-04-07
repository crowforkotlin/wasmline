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
         * @param filepath 预编译产物路径，仅支持 .cwasm 或 .pwasm
         */
        actual fun load(filepath: String, threadSafe: Boolean): WasmlineLoadState {
            return WasmlineRuntimeLoader.load(
                request = WasmlineLocalLoadRequest(
                    artifactPath = filepath,
                    threadSafe = threadSafe,
                ),
                platform = object : WasmlinePlatformLoader {
                    override fun createWasmline(key: String): Wasmline = Wasmline(key)

                    override fun normalizePath(path: String): String = File(path).absolutePath

                    override fun fileExists(path: String): Boolean = File(path).exists()

                    override fun loadPrecompiled(key: String, path: String): Boolean {
                        return if (threadSafe) nativeLoadAot(key = key, path = path)
                        else nativeLoadAotUnsafe(key = key, path = path)
                    }
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
        @JvmStatic private external fun nativeLoadAot(key: String, path: String): Boolean
        @JvmStatic private external fun nativeLoadAotUnsafe(key: String, path: String): Boolean
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

