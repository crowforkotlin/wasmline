@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline

import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.collections.get

internal fun interface Callback {
    fun callback(params: ByteArray?): ByteArray?
}

internal object WasmlineRouter {
    private val handlers = mutableMapOf<String, Callback>()
    internal fun register(action: String, callback: Callback) {
        handlers[action] = callback
    }
    internal fun dispatch(action: String?, args: ByteArray?): ByteArray? {
        val handler = handlers[action]
        return handler?.callback(args)
    }
}
