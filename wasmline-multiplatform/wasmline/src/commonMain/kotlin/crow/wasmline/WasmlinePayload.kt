@file:Suppress("unused")

package crow.wasmline

/**
 * Helper used by generated Wasmline IR for zero-argument calls in phase one.
 * Application code should normally not need to call this directly.
 */
fun wasmlineEmptyPayload(): ByteArray = ByteArray(0)


