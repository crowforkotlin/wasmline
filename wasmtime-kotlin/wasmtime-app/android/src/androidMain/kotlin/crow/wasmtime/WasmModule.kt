package crow.wasmtime

import java.io.File
import java.io.FileOutputStream
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WasmModule(private val moduleKey: String) {

    companion object {
        /**
         * 加载模块
         * @param file .wasm (源码) 或 .cwasm (缓存)
         * @param forceCompile 如果为 true，强制当作源码编译
         */
        suspend fun load(file: File, cacheFile: File? = null): WasmModule = withContext(Dispatchers.IO) {
            val key = file.absolutePath
            
            // 1. 如果有缓存文件，先尝试加载缓存 (AOT)
            if (cacheFile != null && cacheFile.exists()) {
                if (nativeLoadCache(key, cacheFile.absolutePath)) {
                    return@withContext WasmModule(key)
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

            return@withContext WasmModule(key)
        }
        
        // JNI Methods
        @JvmStatic private external fun nativeLoadSource(key: String, path: String): Boolean
        @JvmStatic private external fun nativeLoadCache(key: String, path: String): Boolean
        @JvmStatic private external fun nativeSaveCache(key: String, path: String): Boolean
        @JvmStatic private external fun nativeRelease(key: String)
        @JvmStatic private external fun nativeCall(key: String, action: String, json: String): String
    }

    /**
     * 执行 Wasm 函数
     * 支持并发调用，底层会自动创建独立的 Session
     */
    suspend fun call(action: String, json: String): String = withContext(Dispatchers.Default) {
        nativeCall(moduleKey, action, json)
    }

    /**
     * 释放当前模块
     * 不会影响 Engine，但会释放此模块占用的内存
     */
    fun release() {
        nativeRelease(moduleKey)
    }
}