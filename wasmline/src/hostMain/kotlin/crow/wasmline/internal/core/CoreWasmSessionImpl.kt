package crow.wasmline.internal.core

import crow.wasmline.CoreWasmModule
import crow.wasmline.CoreWasmSession
import crow.wasmline.CoreWasmSessionOptions
import crow.wasmline.RawExport
import crow.wasmline.RawExportKind
import crow.wasmline.RawImport
import crow.wasmline.RawImportContext
import crow.wasmline.RawMemory
import crow.wasmline.RawValue
import crow.wasmline.RawValueType
import crow.wasmline.coreFailure
import crow.wasmline.internal.runtime.WasmlineRuntimeLock
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Enforces public session validation, concurrency, reentry, and lifecycle rules.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal class CoreWasmSessionImpl(
    override val module: CoreWasmModule,
    internal val options: CoreWasmSessionOptions,
    private val lock: WasmlineRuntimeLock,
) : CoreWasmSession {
    private var backend: CoreWasmBackendSession? = null
    private var closed = false
    private var invoking = false
    private var importCallback = false
    private var memoryAccess = false

    override val isClosed: Boolean get() = lock.withLock { closed }
    override val memory: RawMemory?
        get() = lock.withLock { backend?.memory }?.let { memory ->
            RawMemoryImpl(
                backend = memory,
                beginOperation = ::beginExternalMemoryAccess,
                endOperation = ::endExternalMemoryAccess,
            )
        }

    fun attach(session: CoreWasmBackendSession) {
        lock.withLock {
            check(backend == null) { "Core Wasm backend session is already attached." }
            backend = session
        }
    }

    override fun findExport(name: String): RawExport? {
        val export = module.findExport(name) ?: return null
        val signature = options.exportSignatures[name] ?: export.signature
        return if (signature == export.signature) export else export.copy(signature = signature)
    }

    override fun invoke(exportName: String, arguments: List<RawValue>): WasmlineCallResult<List<RawValue>> {
        if (lock.withLock { closed }) {
            return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Core Wasm session is closed.")
        }
        val export = findExport(exportName)
            ?: return coreFailure(WasmlineErrorCode.EXPORT_NOT_FOUND, "Core Wasm export '$exportName' was not found.")
        if (export.kind != RawExportKind.FUNCTION) {
            return coreFailure(WasmlineErrorCode.EXPORT_KIND_MISMATCH, "Core Wasm export '$exportName' is not a function.")
        }
        val signature = export.signature
            ?: return coreFailure(
                WasmlineErrorCode.EXPORT_SIGNATURE_MISSING,
                "Core Wasm export '$exportName' requires caller-provided or rawAbi signature metadata.",
            )
        validateValues(arguments, signature.parameters, "argument")?.let { return WasmlineCallResult.Failure(it) }

        var blocked: WasmlineCallResult.Failure? = null
        val activeBackend = lock.withLock {
            when {
                closed -> {
                    blocked = coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Core Wasm session is closed.")
                    null
                }

                importCallback -> {
                    blocked = coreFailure(WasmlineErrorCode.REENTRANT_CALL, "Core Wasm import callback cannot reenter its session.")
                    null
                }

                invoking -> {
                    blocked = coreFailure(WasmlineErrorCode.CONCURRENT_ACCESS, "Core Wasm session already has an active call.")
                    null
                }

                memoryAccess -> {
                    blocked = coreFailure(WasmlineErrorCode.CONCURRENT_ACCESS, "Core Wasm session already has an active memory operation.")
                    null
                }

                backend == null -> {
                    blocked = coreFailure(WasmlineErrorCode.INSTANTIATION_FAILED, "Core Wasm session is not initialized.")
                    null
                }

                else -> {
                    invoking = true
                    backend
                }
            }
        }
        blocked?.let { return it }
        if (activeBackend == null) return coreFailure(WasmlineErrorCode.INSTANTIATION_FAILED, "Core Wasm session is not initialized.")

        return try {
            when (val result = activeBackend.invoke(exportName, arguments, signature.results)) {
                is WasmlineCallResult.Failure -> result

                is WasmlineCallResult.Success -> {
                    val invalid = validateValues(result.value, signature.results, "result")
                    if (invalid == null) result else WasmlineCallResult.Failure(invalid)
                }
            }
        } catch (failure: Throwable) {
            coreFailure(
                WasmlineErrorCode.WASM_TRAP,
                "Core Wasm export '$exportName' failed: ${failure.message ?: failure}",
            )
        } finally {
            lock.withLock { invoking = false }
        }
    }

    fun dispatchImport(
        import: RawImport,
        arguments: List<RawValue>,
        backendMemory: CoreWasmBackendMemory?,
    ): WasmlineCallResult<List<RawValue>> {
        validateValues(arguments, import.signature.parameters, "import argument")?.let {
            return WasmlineCallResult.Failure(it.copy(code = WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH))
        }
        var blocked: WasmlineCallResult.Failure? = null
        lock.withLock {
            when {
                closed -> blocked = coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Core Wasm session is closed.")

                importCallback ->
                    blocked =
                        coreFailure(WasmlineErrorCode.REENTRANT_CALL, "Nested Core Wasm import callbacks are not supported.")

                else -> importCallback = true
            }
        }
        blocked?.let { return it }

        var active = true
        val callbackMemory = backendMemory?.let { memory ->
            RawMemoryImpl(
                backend = memory,
                beginOperation = {
                    when {
                        !active -> WasmlineFailure(WasmlineErrorCode.SESSION_CLOSED, "Core Wasm import memory is no longer available.")
                        isClosed -> WasmlineFailure(WasmlineErrorCode.SESSION_CLOSED, "Core Wasm session is closed.")
                        else -> null
                    }
                },
                endOperation = {},
            )
        }
        val context = object : RawImportContext {
            override val memory: RawMemory? = callbackMemory
            override val session: CoreWasmSession = this@CoreWasmSessionImpl
        }
        return try {
            when (val result = import.handler(context, arguments)) {
                is WasmlineCallResult.Failure -> coreFailure(
                    WasmlineErrorCode.IMPORT_HANDLER_FAILED,
                    result.failure.message,
                    result.failure.details,
                )

                is WasmlineCallResult.Success -> {
                    val invalid = validateValues(result.value, import.signature.results, "import result")
                    if (invalid == null) {
                        result
                    } else {
                        coreFailure(
                            WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH,
                            invalid.message,
                            invalid.details,
                        )
                    }
                }
            }
        } catch (failure: Throwable) {
            coreFailure(
                WasmlineErrorCode.IMPORT_HANDLER_FAILED,
                "Raw import '${import.module}.${import.name}' failed: ${failure.message ?: failure}",
            )
        } finally {
            active = false
            lock.withLock { importCallback = false }
        }
    }

    override fun close() {
        val active = lock.withLock {
            if (closed) {
                null
            } else {
                closed = true
                backend.also { backend = null }
            }
        } ?: return
        active.close()
    }

    private fun beginExternalMemoryAccess(): WasmlineFailure? = lock.withLock {
        when {
            closed -> WasmlineFailure(WasmlineErrorCode.SESSION_CLOSED, "Core Wasm session is closed.")

            importCallback || invoking || memoryAccess ->
                WasmlineFailure(WasmlineErrorCode.CONCURRENT_ACCESS, "Core Wasm session already has an active operation.")

            else -> {
                memoryAccess = true
                null
            }
        }
    }

    private fun endExternalMemoryAccess() {
        lock.withLock { memoryAccess = false }
    }
}

private fun validateValues(values: List<RawValue>, expected: List<RawValueType>, label: String): WasmlineFailure? {
    if (values.size != expected.size) {
        return WasmlineFailure(
            code = WasmlineErrorCode.ARGUMENT_COUNT_MISMATCH,
            message = "Core Wasm $label count ${values.size} does not match expected count ${expected.size}.",
        )
    }
    val mismatch = values.indices.firstOrNull { values[it].type != expected[it] } ?: return null
    return WasmlineFailure(
        code = WasmlineErrorCode.ARGUMENT_TYPE_MISMATCH,
        message = "Core Wasm $label $mismatch has type ${values[mismatch].type}, expected ${expected[mismatch]}.",
    )
}
