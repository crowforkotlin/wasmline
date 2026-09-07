// WITH_STDLIB

package test.ir.complextypes

import crow.wasmline.WasmlineService

/**
 * Service with complex type parameters.
 * Tests IR handling of multiple primitive and wrapper types.
 */
interface ComplexTypeService : WasmlineService {
    fun processNumbers(a: Int, b: Long): Long
    fun processString(text: String, count: Int): String
}

fun box(): String {
    val bridgeClass = runCatching {
        Class.forName("test.ir.complextypes.ComplexTypeService_WasmlineBridge")
    }.getOrNull() ?: return "Fail: Bridge not generated"

    if (!ComplexTypeService::class.java.isAssignableFrom(bridgeClass)) {
        return "Fail: Bridge doesn't implement ComplexTypeService"
    }

    val numbersMethod = try {
        bridgeClass.getMethod("processNumbers", Int::class.java, Long::class.java)
    } catch (e: NoSuchMethodException) {
        return "Fail: processNumbers(Int, Long) method not found"
    }

    val stringMethod = try {
        bridgeClass.getMethod("processString", String::class.java, Int::class.java)
    } catch (e: NoSuchMethodException) {
        return "Fail: processString(String, Int) method not found"
    }

    if (numbersMethod == null || stringMethod == null) {
        return "Fail: Required methods missing"
    }

    return "OK"
}
