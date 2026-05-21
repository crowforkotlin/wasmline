@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.serialization.WasmlineSerializationFactory
import crow.wasmline.serialization.WasmlineProtobufSerializationFactory

/**
 * Plugin-side runtime handle.
 *
 * `Wasmline.current` represents the current Wasmline execution context inside the running plugin,
 * not a process-wide global engine singleton.
 */
class Wasmline private constructor() {
    var serializationFactory: WasmlineSerializationFactory = WasmlineProtobufSerializationFactory

    var convertFactory: WasmlineSerializationFactory
        get() = serializationFactory
        set(value) {
            serializationFactory = value
        }

    internal fun call(action: String, inputBytes: ByteArray): ByteArray {
        return WasmlineWasmBridge.callHost(action = action, payload = inputBytes)
    }

    fun configure(block: WasmlineConfigurationBuilder.() -> Unit): Wasmline {
        val builder = WasmlineConfigurationBuilder(serializationFactory)
        builder.block()
        serializationFactory = builder.serializationFactory
        return this
    }

    fun close() = Unit

    companion object {
        private val currentHandle = Wasmline()

        val current: Wasmline
            get() = currentHandle
    }
}

class WasmlineConfigurationBuilder internal constructor(
    var serializationFactory: WasmlineSerializationFactory,
) {
    fun serialization(factory: WasmlineSerializationFactory) {
        serializationFactory = factory
    }
}

@PublishedApi
internal class GeneratedWasmlineHostEndpoint(
    private val wasmline: Wasmline,
) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        return wasmline.call(action, payload)
    }
}
