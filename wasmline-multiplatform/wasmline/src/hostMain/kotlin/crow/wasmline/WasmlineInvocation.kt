/**
 * Provides result-based Wasmline invocation.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult

fun Wasmline.callResult(action: String, payload: ByteArray = ByteArray(0)): WasmlineCallResult<ByteArray> =
    when (descriptor.executionModel) {
        WasmlineExecutionModel.CORE_WASM -> WasmlineResponseCodec.decodeLegacyCompatible(call(action, payload))
        WasmlineExecutionModel.COMPONENT_MODEL -> WasmlineComponentRpc.invoke(this, action, payload)
    }
