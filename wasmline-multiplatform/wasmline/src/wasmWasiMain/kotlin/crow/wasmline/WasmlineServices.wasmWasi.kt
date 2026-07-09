package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint

@PublishedApi
internal class GeneratedWasmlineHostEndpoint(
    private val wasmline: Wasmline,
) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        return wasmline.call(action, payload)
    }
}