package crow.wasmline.internal.runtime

import crow.wasmline.CoreWasmModule
import crow.wasmline.Wasmline
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.coreFailure
import crow.wasmline.internal.core.CoreWasmModuleImpl
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

/** Owns the Core Wasm facade and close transition for one loaded handle. */
internal class WasmlineHandleState(private val owner: Wasmline) {
    private val lock = WasmlineRuntimeLock()
    private var closed = false
    private var coreModule: CoreWasmModule? = null

    val isClosed: Boolean get() = lock.withLock { closed }

    fun coreModule(): WasmlineCallResult<CoreWasmModule> = lock.withLock {
        if (closed) return@withLock coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Wasmline handle is closed.")
        coreModule?.let { return@withLock WasmlineCallResult.Success(it) }
        val descriptor = owner.descriptor
        if (descriptor.executionModel != WasmlineExecutionModel.CORE_WASM ||
            descriptor.invocationProtocol != WasmlineInvocationProtocol.RAW_EXPORT
        ) {
            return@withLock coreFailure(
                WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
                "CoreWasmModule requires CORE_WASM with RAW_EXPORT.",
            )
        }
        when (val backend = owner.createCoreWasmBackend()) {
            is WasmlineCallResult.Failure -> backend

            is WasmlineCallResult.Success -> {
                val module = CoreWasmModuleImpl(owner, backend.value)
                coreModule = module
                WasmlineCallResult.Success(module)
            }
        }
    }

    fun beginClose(): Boolean = lock.withLock {
        if (closed) {
            false
        } else {
            closed = true
            true
        }
    }

    fun closeCoreModule() {
        val module = lock.withLock { coreModule.also { coreModule = null } }
        module?.close()
    }
}
