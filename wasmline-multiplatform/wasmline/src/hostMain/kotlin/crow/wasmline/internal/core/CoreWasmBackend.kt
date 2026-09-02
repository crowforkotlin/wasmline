package crow.wasmline.internal.core

import crow.wasmline.CoreWasmCapabilities
import crow.wasmline.CoreWasmSessionOptions
import crow.wasmline.RawExport
import crow.wasmline.RawImport
import crow.wasmline.RawValue
import crow.wasmline.RawValueType
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineFailure

/**
 * Defines the module operations implemented by each Core Wasm backend.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
internal interface CoreWasmBackendModule : AutoCloseable {
    val exports: List<RawExport>
    val capabilities: CoreWasmCapabilities

    fun instantiate(
        sessionKey: String,
        options: CoreWasmSessionOptions,
        dispatcher: CoreWasmImportDispatcher,
    ): WasmlineCallResult<CoreWasmBackendSession>

    override fun close()
}

/**
 * Defines the live session operations implemented by each Core Wasm backend.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
internal interface CoreWasmBackendSession : AutoCloseable {
    val memory: CoreWasmBackendMemory?

    fun invoke(exportName: String, arguments: List<RawValue>, resultTypes: List<RawValueType>): WasmlineCallResult<List<RawValue>>

    override fun close()
}

/**
 * Defines the refreshed linear-memory operations required from a backend session.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
internal interface CoreWasmBackendMemory {
    val byteSize: Long
    val pageCount: Long

    fun readInto(destination: ByteArray, destinationOffset: Int, sourceOffset: Long, length: Int)
    fun writeFrom(source: ByteArray, sourceOffset: Int, destinationOffset: Long, length: Int)
    fun grow(deltaPages: Long): Long
}

/**
 * Carries a stable backend failure through the scalar backend interface.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property failure Canonical structured failure.
 */
internal class CoreWasmBackendFailure(val failure: WasmlineFailure) : RuntimeException(failure.message)

/**
 * Dispatches a synchronous host import from a backend into the public handler.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
internal fun interface CoreWasmImportDispatcher {
    fun dispatch(import: RawImport, arguments: List<RawValue>, memory: CoreWasmBackendMemory?): WasmlineCallResult<List<RawValue>>
}
