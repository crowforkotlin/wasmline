package crow.wasmtime.wasmline

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.Closeable

class WasmEngine private constructor(private val handle: Long) : Closeable {

    companion object {
        init { System.loadLibrary("wasmline") }

        /**
         * 加载 Wasm 引擎
         * 自动处理 AOT 缓存和 JIT 编译。
         */
        fun load(sourceFile: File, cacheFile: File? = null): WasmEngine {
            // 1. 尝试 AOT
            if (cacheFile != null && cacheFile.exists()) {
                val handle = nativeInitPath(cacheFile.absolutePath)
                if (handle != 0L) return WasmEngine(handle)
                cacheFile.delete() // 缓存损坏
            }

            // 2. 尝试 JIT
            if (!sourceFile.exists()) throw RuntimeException("Source not found: ${sourceFile.absolutePath}")
            val handle = nativeInitSourcePath(sourceFile.absolutePath)
            if (handle == 0L) throw RuntimeException("Compile failed: ${sourceFile.absolutePath}")

            // 3. 保存缓存
            if (cacheFile != null) {
                nativeSaveCache(handle, cacheFile.absolutePath)
            }
            return WasmEngine(handle)
        }

        /**
         * 从 Assets 加载 (防 OOM 优化版)
         */
        fun loadFromAssets(context: Context, assetName: String, cacheName: String? = null): WasmEngine {
            val finalCacheFile = if (cacheName != null) File(context.cacheDir, cacheName) else null

            // 快速路径：有缓存直接用，不需要拷贝源码
            if (finalCacheFile != null && finalCacheFile.exists()) {
                val handle = nativeInitPath(finalCacheFile.absolutePath)
                if (handle != 0L) return WasmEngine(handle)
                finalCacheFile.delete()
            }

            // 慢速路径：流式拷贝到临时文件 -> C++ 读取
            val tempSource = File(context.cacheDir, "$assetName.tmp")
            try {
                context.assets.open(assetName).use { input ->
                    FileOutputStream(tempSource).use { output -> input.copyTo(output) }
                }
                return load(tempSource, finalCacheFile)
            } finally {
                if (tempSource.exists()) tempSource.delete()
            }
        }

        fun clearCache(context: Context, cacheName: String) {
            val file = File(context.cacheDir, cacheName)
            nativeClearCache(file.absolutePath)
        }

        fun freeAllResources() {
            nativeFreeAllResources()
        }

        @JvmStatic private external fun nativeInitPath(path: String): Long
        @JvmStatic private external fun nativeInitSourcePath(path: String): Long
        @JvmStatic private external fun nativeInitBytes(bytes: ByteArray): Long
        @JvmStatic private external fun nativeSaveCache(handle: Long, path: String): Boolean
        @JvmStatic private external fun nativeClearCache(path: String)
        @JvmStatic private external fun nativeFreeAllResources()
    }

    fun call(action: String, json: String): String = nativeCall(handle, action, json)
    override fun close() = nativeRelease(handle)

    private external fun nativeCall(h: Long, a: String, j: String): String
    private external fun nativeRelease(h: Long)
}