@file:Suppress("unused")

package crow.wasmline.spi

/**
 * 分发器接口
 *
 * 2026-01-02 19:30:30 周五 下午
 * @author crowforkotlin
 */
fun interface WasmlineHostDispatcher {
    fun dispatch(action: String, payload: ByteArray): ByteArray
}


