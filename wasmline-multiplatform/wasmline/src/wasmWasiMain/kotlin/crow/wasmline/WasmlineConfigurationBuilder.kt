package crow.wasmline

import crow.wasmline.serialization.WasmlineSerializationFactory

class WasmlineConfigurationBuilder internal constructor(
    var serializationFactory: WasmlineSerializationFactory,
) {
    fun serialization(factory: WasmlineSerializationFactory) {
        serializationFactory = factory
    }
}