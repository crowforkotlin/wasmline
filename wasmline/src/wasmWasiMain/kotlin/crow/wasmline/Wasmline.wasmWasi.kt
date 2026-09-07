@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.serialization.WasmlineProtobufSerializationFactory
import crow.wasmline.serialization.WasmlineSerializationFactory

/**
 * Plugin-side runtime handle.
 *
 * `Wasmline.get()` returns the current Wasmline execution context inside the running plugin.
 * Since the plugin IS the wasm module, no loading is needed — the instance is always available.
 */
class Wasmline private constructor() {
    var serializationFactory: WasmlineSerializationFactory = WasmlineProtobufSerializationFactory

    var convertFactory: WasmlineSerializationFactory
        get() = serializationFactory
        set(value) {
            serializationFactory = value
        }

    internal fun call(action: String, inputBytes: ByteArray): ByteArray = WasmlineWasmBridge.callHost(action = action, payload = inputBytes)

    fun configure(block: WasmlineConfigurationBuilder.() -> Unit): Wasmline {
        val builder = WasmlineConfigurationBuilder(serializationFactory)
        builder.block()
        serializationFactory = builder.serializationFactory
        return this
    }

    fun close() = Unit

    companion object {
        private val instance = Wasmline()

        fun get(): Wasmline = instance
    }
}
