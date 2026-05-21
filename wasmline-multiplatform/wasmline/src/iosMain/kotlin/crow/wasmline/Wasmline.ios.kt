@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline

import crow.wasmline.native.c.*
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import kotlinx.cinterop.*
import platform.Foundation.NSFileManager

// 必须导入我们在 common 定义的类
// 假设 WasmlineLoadState 和 WasmlineHostDispatcher 在 commonMain 定义了

actual class Wasmline actual internal constructor(
    private val moduleKey: String,
    actual val config: WasmlineConfig,
) {

    actual companion object {
        actual fun init() {
            wasmline_init_engine()
        }

        actual fun shutdown() {
            wasmline_release_engine()
        }

        /**
         * 加载模块 (iOS 实现)
         */
        actual fun load(
            filepath: String,
            threadSafe: Boolean,
            config: WasmlineConfig,
        ): WasmlineLoadState {
            val fileManager = NSFileManager.defaultManager
            val isUnsafe = !threadSafe
            return WasmlineLocalArtifactBridge.load(
                artifactPath = filepath,
                config = config,
                platform = object : WasmlinePlatformArtifactBridge {
                    override fun createWasmline(moduleKey: String, config: WasmlineConfig): Wasmline {
                        return Wasmline(moduleKey, config)
                    }

                    override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? {
                        if (!fileManager.fileExistsAtPath(path)) return null
                        return ResolvedPrecompiledArtifact(
                            artifactPath = path,
                            moduleKey = path,
                        )
                    }

                    override fun loadPrecompiled(moduleKey: String, path: String): Boolean {
                        return wasmline_load_module(moduleKey, path, isUnsafe)
                    }

                    override fun loadFailureMessage(path: String): String {
                        return "[Wasmline] Native artifact load failed for: $path"
                    }
                },
            )
        }
    }

    /**
     * 设置回调
     * iOS 需要传递静态 C 函数指针
     */

    actual internal fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        // 保存 dispatcher 到全局映射中，以便 C 回调时能找到
        WasmlineCallbackRegistry.register(moduleKey, dispatcher)

        // 传递静态函数指针给 C
        // staticCFunction 要求函数必须是顶层函数或对象里的函数
        wasmline_set_outbound_handler(moduleKey, staticCFunction(::iosStaticOutboundCallback))
    }

    /**
     * 执行 Wasm 函数
     */
    actual internal fun call(action: String, inputBytes: ByteArray): ByteArray = memScoped {
        val keyCstr = moduleKey
        val actionCstr = action
        val dataSize = inputBytes.size.toULong()

        val outLen = alloc<ULongVar>()

        inputBytes.usePinned { pinned ->
            val dataPtr = if (inputBytes.isNotEmpty()) pinned.addressOf(0) else null
            val resultPtr = wasmline_invoke_inbound(
                keyCstr,
                actionCstr,
                action.length.toULong(),
                dataPtr,
                dataSize,
                outLen.ptr,
            )

            if (resultPtr == null) {
                return@memScoped byteArrayOf()
            }

            val length = outLen.value.toInt()
            if (length == 0) {
                wasmline_free_memory(resultPtr)
                return@memScoped byteArrayOf()
            }

            val resultArray = resultPtr.readBytes(length)
            wasmline_free_memory(resultPtr)
            resultArray
        }
    }

    actual fun close() {
        WasmlineCallbackRegistry.unregister(moduleKey)
        wasmline_release_module(moduleKey)
    }
}

// ==========================================
// 辅助工具：处理 C -> Kotlin 的回调
// ==========================================

/**
 * 全局注册表：用于在静态 C 回调中找回 Kotlin 对象
 */
private object WasmlineCallbackRegistry {
    private val dispatchers = mutableMapOf<String, WasmlineHostDispatcher>() // 需注意线程安全

    fun register(key: String, dispatcher: WasmlineHostDispatcher) {
        // 在 Native 多线程模型下，建议使用 AtomicReference 或 Stately/Collections
        // 这里简化演示，实际使用请注意并发锁
        dispatchers[key] = dispatcher
    }

    fun unregister(key: String) {
        dispatchers.remove(key)
    }

    fun get(key: String): WasmlineHostDispatcher? = dispatchers[key]

    // 临时方案：因为目前的 C 回调接口没有传回 key，我们暂时只能拿第一个
    // 或者你需要修改 C 接口，把 key 传回来
    fun findAny(): WasmlineHostDispatcher? = dispatchers.values.firstOrNull()
}

/**
 * 这是一个顶层的静态函数，专门给 C 调用
 * 对应 C 定义: typedef char* (*OutboundCallback)(const char* action, size_t actionLen, const char* payload, size_t payloadLen);
 */
fun iosStaticOutboundCallback(
    action: CPointer<ByteVar>?,
    actionLen: ULong,
    payload: CPointer<ByteVar>?,
    payloadLen: ULong
): CPointer<ByteVar>? {
    val actionStr = action?.toKString() ?: ""
    val payloadBytes = payload?.readBytes(payloadLen.toInt()) ?: byteArrayOf()

    // 【痛点】目前的 C 接口回调没有把 moduleKey 传回来
    // 所以我们不知道是哪个模块调用的。
    // 暂时方案：假设只有一个模块，或者修改 C++ IosOutboundHandler 传回 key。
    val dispatcher = WasmlineCallbackRegistry.findAny()

    if (dispatcher != null) {
        // 这里需要运行 blocking 代码，因为 C 函数不能挂起
        // 注意：在主线程或 C 线程中直接运行
        // 实际返回值根据你的 Dispatcher 定义，这里假设返回 ByteArray
        val result = "TODO: Result from dispatcher".encodeToByteArray()

        // 返回给 C 的数据需要是 C 堆内存 (malloc)，因为 C 那边会 free 它
        // 这里只是演示返回 null
        return null
    }

    return null
}