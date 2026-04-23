@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint

/**
 * Plugin-side runtime handle.
 *
 * `Wasmline.current` represents the current Wasmline execution context inside the running plugin,
 * not a process-wide global engine singleton.
 */
class Wasmline private constructor() {
    internal fun call(action: String, inputBytes: ByteArray): ByteArray {
        return WasmlineWasmBridge.callHost(action = action, payload = inputBytes)
    }

    fun close() = Unit

    companion object {
        private val currentHandle = Wasmline()

        val current: Wasmline
            get() = currentHandle
    }
}

@PublishedApi
internal class GeneratedWasmlineHostEndpoint(
    private val wasmline: Wasmline,
) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        return wasmline.call(action, payload)
    }
}

