package crow.wasmline.internal.bridge

/** Low-level action/payload transport endpoint used by generated bridge code. */
interface WasmlineEndpoint {
    fun invoke(action: String, payload: ByteArray): ByteArray
}
