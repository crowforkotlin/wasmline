// WITH_STDLIB

package test.ir.simple

import crow.wasmline.WasmlineService

/**
 * Simple service with single method, no parameters.
 * Verifies IR generation for basic no-argument methods.
 */
interface SimpleService : WasmlineService {
    fun ping(): String
}

fun box(): String {
    val bridgeClass = runCatching {
        Class.forName("test.ir.simple.SimpleService_WasmlineBridge")
    }.getOrNull() ?: return "Fail: Bridge not generated"
    
    if (!SimpleService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail: Bridge doesn't implement SimpleService"
    }
    
    // Verify bridge has expected methods
    val bindMethod = try {
        bridgeClass.getMethod("bind", Function2::class.java)
    } catch (e: NoSuchMethodException) {
        return "Fail: bind() method not found"
    }
    
    val echoMethod = try {
        bridgeClass.getMethod("ping")
    } catch (e: NoSuchMethodException) {
        return "Fail: ping() method not found"
    }
    
    if (bindMethod == null || echoMethod == null) {
        return "Fail: Required methods missing"
    }
    
    return "OK"
}
