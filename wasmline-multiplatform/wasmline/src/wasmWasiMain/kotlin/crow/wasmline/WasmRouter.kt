@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline

import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.collections.get

// --- 3. 路由注册中心 ---

fun interface Callback { fun callback(params: ByteArray?) : ByteArray? }
object WasmRouter {
    private val handlers = mutableMapOf<String, Callback>()

    fun register(action: String, callback: Callback) { handlers[action] = callback }

    internal fun dispatch(action: String?, args: ByteArray?): ByteArray? {
        val handler = handlers[action]
        return handler?.callback(args)
    }
}