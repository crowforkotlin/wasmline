package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertEquals

class WasmlineArtifactFormatNativeBridgeCodeTest {

    @Test
    fun usesStableNonOrdinalNativeBridgeCodes() {
        assertEquals(1, WasmlineArtifactFormat.RAW_WASM.nativeBridgeCode())
        assertEquals(2, WasmlineArtifactFormat.CWASM.nativeBridgeCode())
        assertEquals(3, WasmlineArtifactFormat.PWASM.nativeBridgeCode())
    }
}
