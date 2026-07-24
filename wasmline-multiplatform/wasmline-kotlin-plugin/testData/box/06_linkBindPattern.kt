// WITH_STDLIB

package test.ir.link.bind

import crow.wasmline.WasmlineService

/**
 * Link and Bind usage pattern.
 * Tests the typical pattern of linking an endpoint to implementation.
 */
interface LinkedService : WasmlineService {
    fun linkedCall(data: String): String
}

fun box(): String {
    val bridgeClass = runCatching {
        Class.forName("test.ir.link.bind.LinkedService_WasmlineBridge")
    }.getOrNull() ?: return "Fail: Bridge not generated"

    if (!LinkedService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail: Bridge doesn't implement LinkedService"
    }

    return "OK"
}
