package crow.wasmline

expect class Wasmline(moduleKey: String) {


    companion object {

        /**
         * 加载模块
         * @param filepath .wasm (源码) 或 .cwasm (缓存)
         */
        suspend fun load(filepath: String, cacheFilepath: String? = null, threadSafe: Boolean = false): WasmlineLoadState

        /**
         * 初始化全局 Engine。
         * 建议在 Application onCreate 中调用。
         */
        fun init()

        /**
         * 释放全局 Engine 和所有缓存的 Module。
         * 建议在确定不再使用 Wasm 时调用，或者 Activity onDestroy。
         */
        fun release()

    }

    suspend fun setOutbound(dispatcher: WasmlineHostDispatcher)

    /**
     * 执行 Wasm 函数
     * 支持并发调用，底层会自动创建独立的 Session
     */
    suspend fun call(action: String, inputBytes: ByteArray): ByteArray

    /**
     * 释放当前模块
     * 不会影响 Engine，但会释放此模块占用的内存
     */
    fun release()
}