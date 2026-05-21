@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

/**
 * Host-side runtime handle for a loaded module.
 *
 * The companion object exposes process-wide engine lifecycle and the direct
 * local-artifact runtime bridge. Host-facing package/manifest/download
 * workflows should still go through the `wasmline-loader` module first.
 */
expect class Wasmline internal constructor(moduleKey: String, config: WasmlineConfig) {

    val config: WasmlineConfig

    companion object {

        /**
         * Runtime-level local artifact loading entrypoint.
         *
         * Host-facing package/manifest/download workflows should prefer the
         * `wasmline-loader` module, while this API remains the direct runtime
         * bridge for prepared local artifacts accepted by the current host.
         * Browser hosts load raw `.wasm`, while Wasmtime-based hosts require
         * precompiled `.cwasm` or `.pwasm`.
         *
         * @param filepath Local module artifact path for the current host runtime
         */
        fun load(
            filepath: String,
            threadSafe: Boolean = false,
            config: WasmlineConfig = WasmlineConfig(),
        ): WasmlineLoadState

        /**
         * Initialize the global Engine.
         * It is recommended to call it in Application onCreate.
         */
        fun init()

        /**
         * Release the global Engine and all cached Modules.
         * It is recommended to call Wasm when you are sure you are no longer using it, or Activity onDestroy.
         */
        fun shutdown()
    }

    /**
     * Set callback
     * The dispatcher will be retained by the runtime, so it is recommended to use a singleton or static instance.
     */
    internal fun setOutbound(dispatcher: WasmlineHostDispatcher)

    /**
     * Execute Wasm function
     * Supports concurrent calls, the bottom layer will automatically create an independent Session
     */
    internal fun call(action: String, inputBytes: ByteArray): ByteArray

    /**
     * Release the current module
     * Will not affect the Engine, but will free the memory occupied by this module
     */
    fun close()
}