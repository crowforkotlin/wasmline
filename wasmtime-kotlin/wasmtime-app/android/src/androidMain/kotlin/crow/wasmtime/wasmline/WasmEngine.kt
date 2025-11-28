package crow.wasmtime.wasmline

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.Closeable

class WasmEngine private constructor(private val handle: Long) : Closeable {

    companion object {
        init { System.loadLibrary("wasmline") }

        /**
         * 从文件系统加载 (通用入口)
         * 适用于：SD卡文件、下载的文件、或者已经拷贝到 internal storage 的文件。
         *
         * 特点：全程 C++ 读取，Java 层零内存消耗，支持超大文件。
         *
         * @param sourceFile 源码文件 (.wasm)
         * @param cacheFile  缓存文件 (.cwasm)，如果为 null，则不使用磁盘缓存
         */
        fun load(sourceFile: File, cacheFile: File? = null): WasmEngine {
            // 1. 尝试 AOT (缓存命中)
            if (cacheFile != null && cacheFile.exists()) {
                val handle = nativeInitPath(cacheFile.absolutePath)
                if (handle != 0L) return WasmEngine(handle)
                // 缓存损坏，删除
                cacheFile.delete()
            }

            // 2. 尝试 JIT (从源码路径编译)
            // 这里调用新加的 nativeInitSourcePath，C++ 直接读盘
            if (!sourceFile.exists()) {
                throw RuntimeException("Source file not found: ${sourceFile.absolutePath}")
            }

            val handle = nativeInitSourcePath(sourceFile.absolutePath)
            if (handle == 0L) {
                throw RuntimeException("Failed to compile wasm from file: ${sourceFile.absolutePath}")
            }

            // 3. 编译成功后，保存缓存
            if (cacheFile != null) {
                nativeSaveCache(handle, cacheFile.absolutePath)
            }

            return WasmEngine(handle)
        }

        /**
         * 从 Assets 加载
         *
         * 优化策略：
         * 1. 如果文件很大，流式拷贝到缓存目录的临时文件，然后走 C++ 文件加载 (防 OOM)。
         * 2. 如果文件很小，为了速度可以直接走内存 (可选，但为了统一逻辑，建议都走临时文件更稳健)。
         */
        fun loadFromAssets(context: Context, assetName: String, cacheName: String? = null): WasmEngine {
            // 确定缓存路径
            // 如果用户没传 cacheName，我们默认生成一个 .cwasm
            val finalCacheFile = if (cacheName != null) {
                File(context.cacheDir, cacheName)
            } else {
                File(context.cacheDir, "$assetName.cwasm")
            }

            // 1. 检查缓存是否可用 (AOT)
            if (finalCacheFile.exists()) {
                val handle = nativeInitPath(finalCacheFile.absolutePath)
                if (handle != 0L) return WasmEngine(handle)
                finalCacheFile.delete()
            }

            // 2. 缓存未命中：需要从 Assets 读取源码
            // 为了解决大文件 OOM，我们将 Asset 拷贝到一个临时文件 (.tmp.wasm)
            // 这样 C++ 就可以通过路径读取了
            val tempSourceFile = File(context.cacheDir, "$assetName.tmp.wasm")

            try {
                // 流式拷贝，内存占用极低
                context.assets.open(assetName).use { input ->
                    FileOutputStream(tempSourceFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // 3. 调用通用的文件加载逻辑
                // 此时 tempSourceFile 是物理文件，C++ 读取无压力
                return load(tempSourceFile, finalCacheFile)

            } finally {
                // 4. 清理临时源码文件 (编译完就不需要源码了，因为已经有 .cwasm 缓存了，或者内存里已经有 module 了)
                if (tempSourceFile.exists()) {
                    tempSourceFile.delete()
                }
            }
        }

        @JvmStatic private external fun nativeInitPath(path: String): Long       // AOT (.cwasm)
        @JvmStatic private external fun nativeInitSourcePath(path: String): Long // JIT (.wasm from file)
        @JvmStatic private external fun nativeInitBytes(bytes: ByteArray): Long  // JIT (.wasm from memory)
        @JvmStatic private external fun nativeSaveCache(handle: Long, path: String): Boolean
    }

    fun call(action: String, json: String): String = nativeCall(handle, action, json)
    override fun close() = nativeRelease(handle)

    private external fun nativeCall(h: Long, a: String, j: String): String
    private external fun nativeRelease(h: Long)
}