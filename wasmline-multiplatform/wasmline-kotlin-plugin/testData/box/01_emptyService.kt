// WITH_STDLIB

package test.ir.empty

import crow.wasmline.WasmlineService

/**
 * Empty service with no methods.
 * Verifies that the IR plugin generates a bridge even for empty interfaces.
 */
interface EmptyService : WasmlineService

fun box(): String {
    val bridgeClass = runCatching {
        Class.forName("test.ir.empty.EmptyService_WasmlineBridge")
    }.getOrNull() ?: return "Fail: Bridge not generated"

    if (!EmptyService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail: Bridge doesn't implement EmptyService"
    }

    return "OK"
}
