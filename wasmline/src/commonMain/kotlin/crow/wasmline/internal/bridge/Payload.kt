@file:Suppress("unused")

package crow.wasmline.internal.bridge

/** Helper used by generated Wasmline IR for zero-argument calls. */
@PublishedApi
internal fun emptyPayload(): ByteArray = ByteArray(0)
