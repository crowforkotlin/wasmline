// WITH_STDLIB

package test.ir.link.bind

import crow.wasmline.WasmlineService
import crow.wasmline.internal.bridge.WasmlineEndpoint

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

    // Create mock endpoint
    val endpoint = object : WasmlineEndpoint {
        override fun invoke(action: String, payload: ByteArray): ByteArray {
            return byteArrayOf()
        }
    }

    // Create instance with endpoint
    var implInstance: Any? = null
    try {
        val ctorWithEndpoint = bridgeClass.getConstructors().find {
            it.parameterCount == 3 &&
            WasmlineEndpoint::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return "Fail: Constructor with Endpoint not found"
        
        implInstance = ctorWithEndpoint.newInstance(endpoint, null, null)
    } catch (e: Exception) {
        return "Fail: Cannot create bridge with endpoint: ${e.message}"
    }

    // Verify bind method exists
    val bindMethod = try {
        bridgeClass.getMethod("bind", Function2::class.java)
    } catch (e: NoSuchMethodException) {
        return "Fail: bind() method not found"
    }

    // Test link pattern - call link to connect endpoint
    val actionHandler = { _: String, payload: ByteArray -> payload }
    
    try {
        bindMethod.invoke(implInstance, actionHandler)
    } catch (e: Exception) {
        return "Fail: Cannot call bind(): ${e.message}"
    }

    return "OK"
}
