package crow.wasmline.test.wasmtime

import crow.wasmline.callResult
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests recoverable invocation failures from an assembled plugin.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
class NativePluginInvocationResultTest {

    /**
     * Tests that an unknown action returns a typed failure result.
     */
    @Test
    fun returnsFailureForUnknownAction() {
        NativePluginTestSupport.withLoadedPlugin { wasmline ->
            val result = wasmline.callResult("crow.wasmline.test.plugin.MissingService#missing")
            val failure = assertIs<WasmlineCallResult.Failure>(result)

            assertEquals(WasmlineErrorCode.UNKNOWN_ACTION, failure.error.code)
            assertNull(result.getOrNull())
            assertNotNull(result.errorOrNull())
        }
    }

    /**
     * Tests that a handler failure is returned without escaping the native call.
     */
    @Test
    fun returnsFailureForInvalidHandlerPayload() {
        NativePluginTestSupport.withLoadedPlugin { wasmline ->
            val result = wasmline.callResult("crow.wasmline.test.plugin.CalculatorService#calculate")
            val failure = assertIs<WasmlineCallResult.Failure>(result)

            assertEquals(WasmlineErrorCode.HANDLER_FAILED, failure.error.code)
            assertNull(result.getOrNull())
            assertNotNull(result.errorOrNull())
        }
    }
}
