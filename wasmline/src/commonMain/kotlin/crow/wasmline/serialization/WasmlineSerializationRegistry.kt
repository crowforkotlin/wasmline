package crow.wasmline.serialization

object WasmlineSerializationRegistry {
    private var factories: Map<String, WasmlineSerializationFactory> = mapOf(
        WasmlineRawBytesSerializationFactory.id to WasmlineRawBytesSerializationFactory,
        WasmlineProtobufSerializationFactory.id to WasmlineProtobufSerializationFactory,
    )

    /** Registers or replaces [factory] in the current snapshot. */
    fun register(factory: WasmlineSerializationFactory) {
        factories = factories + (factory.id to factory)
    }

    fun factoryOrNull(id: String): WasmlineSerializationFactory? = factories[id]

    fun requireFactory(id: String): WasmlineSerializationFactory = requireNotNull(factoryOrNull(id)) {
        "No Wasmline serialization factory is registered for '$id'."
    }
}
