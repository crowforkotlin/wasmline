package crow.wasmtime

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WasmLine(private val moduleKey: String) {

    companion object Companion {

        init { System.loadLibrary("wasmline") }

        /**
         * 加载模块
         * @param file .wasm (源码) 或 .cwasm (缓存)
         */
        suspend fun load(file: File, cacheFile: File? = null): WasmLine = withContext(Dispatchers.IO) {
            val key = file.absolutePath

            // 1. 如果有缓存文件，先尝试加载缓存 (AOT)
            if (cacheFile != null && cacheFile.exists()) {
                if (nativeLoadCache(key, cacheFile.absolutePath)) {
                    return@withContext WasmLine(key)
                }
                // 缓存加载失败，删除坏文件
                cacheFile.delete()
            }

            // 2. 加载源码 (JIT)
            if (!file.exists()) throw RuntimeException("Source file not found: $key")

            if (!nativeLoadSource(key, file.absolutePath)) {
                throw RuntimeException("Failed to load source: $key")
            }

            // 3. 编译成功后，如果指定了缓存路径，保存下来
            if (cacheFile != null) {
                nativeSaveCache(key, cacheFile.absolutePath)
            }

            return@withContext WasmLine(key)
        }

        /**
         * 初始化全局 Engine。
         * 建议在 Application onCreate 中调用。
         */
        fun init() { nativeInit() }

        /**
         * 释放全局 Engine 和所有缓存的 Module。
         * 建议在确定不再使用 Wasm 时调用，或者 Activity onDestroy。
         */
        fun release() { nativeReleaseEngine() }

        // JNI Methods
        @JvmStatic private external fun nativeLoadSource(key: String, path: String): Boolean
        @JvmStatic private external fun nativeLoadCache(key: String, path: String): Boolean
        @JvmStatic private external fun nativeSaveCache(key: String, path: String): Boolean
        @JvmStatic private external fun nativeReleaseModule(key: String)
        @JvmStatic private external fun nativeCall(key: String, action: String, protobufBytes: ByteArray): ByteArray
        @JvmStatic private external fun nativeInit()
        @JvmStatic private external fun nativeReleaseEngine()
    }

    /**
     * 执行 Wasm 函数
     * 支持并发调用，底层会自动创建独立的 Session
     */
    suspend fun call(action: String, protobufBytes: ByteArray) = withContext(Dispatchers.Default) { nativeCall(moduleKey, action, protobufBytes) }
    // suspend fun call(action: String, json: String): String = withContext(Dispatchers.Default) { nativeCall(moduleKey, action, json) }

    /**
     * 释放当前模块
     * 不会影响 Engine，但会释放此模块占用的内存
     */
    fun release() { nativeReleaseModule(moduleKey) }
}