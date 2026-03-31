@file:Suppress("unused")

package crow.wasmline.internal.bridge

/** Helper used by generated Wasmline IR for zero-argument phase-one calls. */
@Deprecated("Wasmline compiler internal API", level = DeprecationLevel.HIDDEN)
fun emptyPayload(): ByteArray = ByteArray(0)

