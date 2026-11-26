package crow.wasmline

import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WasmlineFailureEqualityTest {
    @Test
    fun `failure details compare by content`() {
        val first = WasmlineFailure(WasmlineErrorCode.HANDLER_FAILED, "boom", byteArrayOf(1, 2))
        val second = WasmlineFailure(WasmlineErrorCode.HANDLER_FAILED, "boom", byteArrayOf(1, 2))
        val different = WasmlineFailure(WasmlineErrorCode.HANDLER_FAILED, "boom", byteArrayOf(1, 3))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, different)
    }

    @Test
    fun `load failure details compare by content`() {
        val first = WasmlineLoadFailure(
            stage = WasmlineLoadStage.MODULE_CREATION,
            code = WasmlineErrorCode.MODULE_FORMAT_INVALID,
            message = "boom",
            details = byteArrayOf(1, 2),
        )
        val second = WasmlineLoadFailure(
            stage = WasmlineLoadStage.MODULE_CREATION,
            code = WasmlineErrorCode.MODULE_FORMAT_INVALID,
            message = "boom",
            details = byteArrayOf(1, 2),
        )
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
