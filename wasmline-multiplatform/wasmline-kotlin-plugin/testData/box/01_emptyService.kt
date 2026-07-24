// WITH_STDLIB

package test.ir.empty

import crow.wasmline.WasmlineService

/**
 * Empty service with no methods.
 * Verifies that the IR plugin generates a bridge even for empty interfaces.
 */
interface EmptyService : WasmlineService

fun box(): String {
    // Verify that Bridge class was generated
    val bridgeClass = runCatching {
        Class.forName("test.ir.empty.EmptyService_WasmlineBridge")
    }.getOrNull() ?: return "Fail: Bridge not generated"

    // Verify Bridge implements EmptyService
    if (!EmptyService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail: Bridge doesn't implement EmptyService"
    }

    return "OK"
}
