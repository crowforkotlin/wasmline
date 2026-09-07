package crow.wasmline.internal.bridge

import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult

/** Low-level action/payload transport endpoint used by generated bridge code. */
@PublishedApi
internal interface WasmlineEndpoint {
    fun invoke(action: String, payload: ByteArray): ByteArray

    fun invokeResult(action: String, payload: ByteArray): WasmlineCallResult<ByteArray> =
        WasmlineResponseCodec.decodeLegacyCompatible(invoke(action, payload))
}
