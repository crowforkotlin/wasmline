package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WasmlineNativeRuntimeInfoWebTest {
    @Test
    fun browserHasNoNativeRuntimeIdentity() {
        assertNull(WasmlineRuntime.nativeInfo())
        assertFailsWith<UnsupportedOperationException> {
            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)
        }
    }
}
