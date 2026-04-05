// RUN_PIPELINE_TILL: BACKEND

package test.diagnostics

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineService
import crow.wasmline.bind

interface AlphaService : WasmlineService {
    fun alpha(payload: ByteArray): ByteArray
}

interface BetaService : WasmlineService {
    fun beta(payload: ByteArray): ByteArray
}

class MultiServiceImpl : AlphaService, BetaService {
    override fun alpha(payload: ByteArray): ByteArray = payload

    override fun beta(payload: ByteArray): ByteArray = payload
}

fun trigger(wasmline: Wasmline, implementation: MultiServiceImpl) {
    wasmline.bind(implementation)
}

