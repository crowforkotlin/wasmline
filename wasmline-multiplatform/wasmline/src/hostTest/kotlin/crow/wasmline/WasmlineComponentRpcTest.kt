/**
 * Tests the fixed wasmline:rpc Component envelope adapter.
 *
 * Date: 2026-08-05
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WasmlineComponentRpcTest {
    @Test
    fun serviceCallsRejectTypedComponentsBeforeInvokingTheCarrier() {
        val wasmline = componentHandle(WasmlineInvocationProtocol.COMPONENT_EXPORT)

        val result = assertIs<WasmlineCallResult.Failure>(wasmline.callResult("service.action"))

        assertEquals(WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH, result.error.code)
    }

    @Test
    fun decodesSuccessfulBytePayload() {
        val payload = byteArrayOf(0, 1, -1, 42)
        val result = WasmlineComponentRpc.decode(
            WasmlineComponentCallResult(
                listOf(WasmlineComponentValue.ResultValue(isOk = true, value = payload.componentBytes())),
            ),
        )

        assertContentEquals(payload, assertIs<WasmlineCallResult.Success<ByteArray>>(result).value)
    }

    @Test
    fun decodesNumericRpcError() {
        val result = WasmlineComponentRpc.decode(
            WasmlineComponentCallResult(
                listOf(
                    WasmlineComponentValue.ResultValue(
                        isOk = false,
                        value = rpcError("1002", "missing", byteArrayOf(7, 8)),
                    ),
                ),
            ),
        )

        val failure = assertIs<WasmlineCallResult.Failure>(result)
        assertEquals(WasmlineErrorCode.UNKNOWN_ACTION, failure.error.code)
        assertEquals(1002, failure.error.rawCode)
        assertEquals("missing", failure.error.message)
        assertContentEquals(byteArrayOf(7, 8), failure.error.details)
    }

    @Test
    fun decodesSymbolicRpcError() {
        val result = WasmlineComponentRpc.decode(
            WasmlineComponentCallResult(
                listOf(
                    WasmlineComponentValue.ResultValue(
                        isOk = false,
                        value = rpcError("handler-failed", "failed", byteArrayOf()),
                    ),
                ),
            ),
        )

        val failure = assertIs<WasmlineCallResult.Failure>(result)
        assertEquals(WasmlineErrorCode.HANDLER_FAILED, failure.error.code)
        assertEquals(WasmlineErrorCode.HANDLER_FAILED.value, failure.error.rawCode)
    }

    @Test
    fun rejectsMalformedComponentResult() {
        val result = WasmlineComponentRpc.decode(WasmlineComponentCallResult(emptyList()))

        val failure = assertIs<WasmlineCallResult.Failure>(result)
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, failure.error.code)
    }

    private fun rpcError(code: String, message: String, details: ByteArray): WasmlineComponentValue.RecordValue =
        WasmlineComponentValue.RecordValue(
            listOf(
                WasmlineComponentValue.RecordField("code", WasmlineComponentValue.StringValue(code)),
                WasmlineComponentValue.RecordField("message", WasmlineComponentValue.StringValue(message)),
                WasmlineComponentValue.RecordField("details", details.componentBytes()),
            ),
        )

    private fun ByteArray.componentBytes(): WasmlineComponentValue.ListValue = WasmlineComponentValue.ListValue(
        map { WasmlineComponentValue.U8(it.toUByte()) },
    )

    private fun componentHandle(protocol: WasmlineInvocationProtocol): Wasmline = Wasmline(
        moduleKey = "component-rpc-test",
        config = WasmlineConfig(),
        descriptor = WasmlineArtifactDescriptor(
            path = "component.cwasm",
            artifactFormat = WasmlineArtifactFormat.CWASM,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = protocol,
        ),
    )
}
