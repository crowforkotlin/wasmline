package crow.wasmline

import crow.wasmline.serialization.WasmlineProtobufSerializationFactory
import crow.wasmline.serialization.WasmlineRawBytesSerializationFactory
import crow.wasmline.serialization.WasmlineSerializationConfig
import crow.wasmline.serialization.WasmlineSerializationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Verifies built-in and custom host-side serialization configurations. */
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

    /** Selects the raw-byte factory and preserves caller options. */
    @Test
    fun rawBytesConfigCarriesFactoryIdAndOptions() {
        val config = WasmlineSerializationConfig.rawBytes(options = mapOf("compression" to "none"))

        assertEquals(WasmlineRawBytesSerializationFactory.id, config.factoryId)
        assertEquals(mapOf("compression" to "none"), config.options)
    }

    /** Allows applications to select a factory that is registered later. */
    @Test
    fun customConfigRetainsFactoryId() {
        val config = WasmlineSerializationConfig.custom("application.custom", mapOf("schema" to "v2"))

        assertEquals("application.custom", config.factoryId)
        assertEquals(mapOf("schema" to "v2"), config.options)
    }
}
