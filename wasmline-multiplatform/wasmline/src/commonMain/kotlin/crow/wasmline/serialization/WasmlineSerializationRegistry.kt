package crow.wasmline.serialization

object WasmlineSerializationRegistry {
    private val factories = linkedMapOf<String, WasmlineSerializationFactory>(
        WasmlineRawBytesSerializationFactory.id to WasmlineRawBytesSerializationFactory,
        WasmlineProtobufSerializationFactory.id to WasmlineProtobufSerializationFactory,
    )

    fun register(factory: WasmlineSerializationFactory) {
        factories[factory.id] = factory
    }

    fun factoryOrNull(id: String): WasmlineSerializationFactory? = factories[id]

    fun requireFactory(id: String): WasmlineSerializationFactory {
        return requireNotNull(factoryOrNull(id)) {
            "No Wasmline serialization factory is registered for '$id'."
        }
    }
}
