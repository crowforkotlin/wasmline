package crow.wasmline.internal.component

import crow.wasmline.internal.invocation.WasmlineTypedInvocationCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Reports whether one Component resource drop consumed its carrier.
 *
 * A rejected drop kept the native entry, so the carrier remains usable. A
 * consumed cleanup failure removed the entry permanently but still reports the
 * original destructor diagnostic.
 *
 * Date: 2026-10-22
 * Author: crowforkotlin
 */
internal sealed interface WasmlineComponentResourceDropResult {
    /** The carrier was rejected before consumption and the resource is still held. */
    data class Rejected(val failure: WasmlineFailure) : WasmlineComponentResourceDropResult

    /** The resource was dropped without a cleanup failure. */
    object Dropped : WasmlineComponentResourceDropResult

    /** The resource was consumed and its destructor reported this failure afterwards. */
    data class ConsumedCleanupFailed(val failure: WasmlineFailure) : WasmlineComponentResourceDropResult
}

/**
 * Marks a typed failure carrier as a drop that already consumed the resource.
 *
 * Bridges reuse the existing typed Component result encoding, so the marker
 * travels in the failure details of an ordinary result carrier.
 */
private const val CONSUMED_CLEANUP_DETAIL: Byte = 1

/** Decodes the typed Component carrier returned by a platform resource drop. */
internal fun decodeComponentResourceDropCarrier(carrier: ByteArray?): WasmlineComponentResourceDropResult {
    if (carrier == null) {
        return WasmlineComponentResourceDropResult.Rejected(
            WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native Component resource drop returned no response."),
        )
    }
    return when (val decoded = WasmlineTypedInvocationCodec.decodeComponentResult(carrier)) {
        is WasmlineCallResult.Success -> WasmlineComponentResourceDropResult.Dropped

        is WasmlineCallResult.Failure -> {
            val consumed = decoded.failure.details?.contentEquals(byteArrayOf(CONSUMED_CLEANUP_DETAIL)) == true
            if (consumed) {
                WasmlineComponentResourceDropResult.ConsumedCleanupFailed(decoded.failure)
            } else {
                WasmlineComponentResourceDropResult.Rejected(decoded.failure)
            }
        }
    }
}
