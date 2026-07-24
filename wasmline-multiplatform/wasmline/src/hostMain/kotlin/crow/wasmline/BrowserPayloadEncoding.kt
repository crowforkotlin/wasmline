@file:OptIn(ExperimentalEncodingApi::class)

package crow.wasmline

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun ByteArray.encodeBase64Payload(): String = if (isEmpty()) "" else Base64.Default.encode(this)

internal fun String.decodeBase64Payload(): ByteArray = if (isEmpty()) ByteArray(0) else Base64.Default.decode(this)
