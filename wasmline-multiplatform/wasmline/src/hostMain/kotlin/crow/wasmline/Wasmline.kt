@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

expect class Wasmline internal constructor(moduleKey: String) {


    @Suppress("unused")
    companion object {

        /**
         * Runtime-level local artifact loading entrypoint.
         *
         * Host-facing package/manifest/download workflows should prefer the
         * `wasmline-loader` module, while this API remains the direct runtime
         * bridge for prepared local `.cwasm` / `.pwasm` artifacts.
         *
         * @param filepath 预编译产物路径，仅支持 `.cwasm` 或 `.pwasm`
         */
        fun load(filepath: String, threadSafe: Boolean = false): WasmlineLoadState

        /**
         * 初始化全局 Engine。
         * 建议在 Application onCreate 中调用。
         */
        fun init()

        /**
         * 释放全局 Engine 和所有缓存的 Module。
         * 建议在确定不再使用 Wasm 时调用，或者 Activity onDestroy。
         */
        fun shutdown()

    }

    @Suppress("unused")
    internal fun setOutbound(dispatcher: WasmlineHostDispatcher)

    /**
     * 执行 Wasm 函数
     * 支持并发调用，底层会自动创建独立的 Session
     */
    internal fun call(action: String, inputBytes: ByteArray): ByteArray

    /**
     * 释放当前模块
     * 不会影响 Engine，但会释放此模块占用的内存
     */
    @Suppress("unused")
    fun close()
}