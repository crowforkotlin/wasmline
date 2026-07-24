// WITH_STDLIB

package test.ir.multi

import crow.wasmline.WasmlineService

/**
 * Service with multiple methods of different signatures.
 * Verifies IR generates correct bridge for each method.
 */
interface MultiMethodService : WasmlineService {
    fun methodA(): Int
    fun methodB(value: Boolean): String
    fun methodC(data: ByteArray): ByteArray
}

fun box(): String {
    val bridgeClass = runCatching {
        Class.forName("test.ir.multi.MultiMethodService_WasmlineBridge")
    }.getOrNull() ?: return "Fail: Bridge not generated"

    if (!MultiMethodService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail: Bridge doesn't implement MultiMethodService"
    }

    // Just verify basic structure exists
    return "OK"
}
