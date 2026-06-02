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
            config: WasmlineConfig = WasmlineConfig(),
        ): WasmlineLoadState

        /**
         * Prepare the runtime bridge for host usage.
         *
         * On JVM/Android this ensures the native library is loaded. It does not
         * eagerly create a Wasmtime engine. Engine creation remains lazy by
         * default and happens during `load(...)` unless callers opt into
         * [warmup].
         */
        fun bootstrap()

        /**
         * Optional eager warmup for a specific Wasmtime backend.
         *
         * This shifts engine creation cost earlier in the app lifecycle without
         * changing the default artifact-based backend selection used by
         * [load]. Browser hosts ignore this call.
         */
        fun warmup(mode: WasmlineWarmupMode)

        /**
         * Releases the global engine and clears cached modules.
         *
         * Call this when the current process is done using Wasmline, such as
         * from an application shutdown hook or platform teardown callback.
         */
        fun shutdown()
    }

    /**
     * Registers the outbound host dispatcher for this module instance.
     *
     * The runtime retains the dispatcher, so prefer a singleton or another
     * long-lived implementation.
     */
    internal fun setOutbound(dispatcher: WasmlineHostDispatcher)

    /**
     * Invokes the module inbound entrypoint with the provided payload.
     *
     * Concurrent calls are supported. The runtime creates independent sessions
     * as needed.
     */
    internal fun call(action: String, inputBytes: ByteArray): ByteArray

    /**
     * Releases the current module instance.
     *
     * This does not shut down the global engine, but it does free the
     * resources owned by this module.
     */
    fun close()
}