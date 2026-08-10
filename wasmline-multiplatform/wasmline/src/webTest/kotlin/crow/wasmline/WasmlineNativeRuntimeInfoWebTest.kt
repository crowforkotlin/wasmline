package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertNull

class WasmlineNativeRuntimeInfoWebTest {
    @Test
    fun browserHasNoNativeRuntimeIdentity() {
        assertNull(wasmlineNativeRuntimeInfo())
        assertNull(wasmlineNativeBackend())
    }
}
