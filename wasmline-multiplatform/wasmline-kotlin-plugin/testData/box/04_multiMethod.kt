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
    
    // Verify all three methods exist
    val methodsToCheck = listOf(
        "methodA" to emptyArray<Class<*>>() to { m -> m.returnType == Int::class.java },
        "methodB" to arrayOf(Boolean::class.java) to { m -> m.returnType == String::class.java },
        "methodC" to arrayOf(ByteArray::class.java) to { m -> m.returnType == ByteArray::class.java }
    )
    
    for ((methodName, paramTypes, validator) in methodsToCheck) {
        try {
            val method = bridgeClass.getMethod(methodName, *paramTypes)
            if (!validator(method)) {
                return "Fail: Method $methodName has incorrect return type"
            }
        } catch (e: NoSuchMethodException) {
            return "Fail: Method $methodName not found"
        }
    }
    
    return "OK"
}
