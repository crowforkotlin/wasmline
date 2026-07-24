package crow.wasmline

import crow.wasmline.serialization.WasmlineProtobufSerializationFactory
import crow.wasmline.serialization.WasmlineRawBytesSerializationFactory
import crow.wasmline.serialization.WasmlineSerializationConfig
import crow.wasmline.serialization.WasmlineSerializationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WasmlineSerializationConfigTest {

    @Test
    fun protobufConfigCarriesFactoryIdAndOptions() {
        val config = WasmlineSerializationConfig.protobuf(
            options = mapOf("schema" to "v1"),
        )

        assertEquals(WasmlineProtobufSerializationFactory.id, config.factoryId)
        assertEquals(mapOf("schema" to "v1"), config.options)
    }

    @Test
    fun wasmlineConfigDefaultsToProtobuf() {
        assertEquals(
            WasmlineProtobufSerializationFactory.id,
            WasmlineConfig().serialization.factoryId,
        )
    }

    @Test
    fun builtInFactoriesAreRegistered() {
        assertSame(
            WasmlineRawBytesSerializationFactory,
            WasmlineSerializationRegistry.requireFactory(WasmlineRawBytesSerializationFactory.id),
        )
        assertSame(
            WasmlineProtobufSerializationFactory,
            WasmlineSerializationRegistry.requireFactory(WasmlineProtobufSerializationFactory.id),
        )
    }
}
