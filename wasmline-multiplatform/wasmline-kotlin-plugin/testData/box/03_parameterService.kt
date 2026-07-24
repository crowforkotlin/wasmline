// WITH_STDLIB

package test.ir.param

import crow.wasmline.WasmlineService

/**
 * Service with parameters and return value.
 * Tests IR serialization/deserialization code generation.
 */
interface ParamService : WasmlineService {
    fun echo(message: String): String
}

fun box(): String {
    val bridgeClass = runCatching {
        Class.forName("test.ir.param.ParamService_WasmlineBridge")
    }.getOrNull() ?: return "Fail: Bridge not generated"
    
    if (!ParamService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail: Bridge doesn't implement ParamService"
    }
    
    // Verify parameter method exists
    val echoMethod = try {
        bridgeClass.getMethod("echo", String::class.java)
    } catch (e: NoSuchMethodException) {
        return "Fail: echo(String) method not found"
    }
    
    if (echoMethod == null) {
        return "Fail: Required methods missing"
    }
    
    return "OK"
}
