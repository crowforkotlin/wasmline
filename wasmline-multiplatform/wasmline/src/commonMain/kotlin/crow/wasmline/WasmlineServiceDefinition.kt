package crow.wasmline

import kotlin.reflect.KClass

/**
 * Runtime-facing description produced by generated code for one service contract.
 *
 * Most application code should use higher-level entry points such as `link<T>()`
 * and `bindServices { ... }`. This interface mainly exists so IR-generated glue
 * has a stable runtime contract to implement.
 *
 * In other words: this is part of Wasmline's generated-code SPI, not the primary
 * API that typical application code should build against directly.
 */
interface WasmlineServiceDefinition<T : WasmlineService> {
    val contract: KClass<T>
    val serviceId: WasmlineServiceId

    /** Create a local proxy that forwards calls to [endpoint]. */
    fun link(endpoint: WasmlineEndpoint): T

    /** Install local handlers for [implementation] into [scope]. */
    fun bind(implementation: T, scope: WasmlineBindingScope)
}

