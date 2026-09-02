package crow.wasmline.web

import crow.wasmline.CoreWasmCapabilities
import crow.wasmline.CoreWasmSessionOptions
import crow.wasmline.RawExport
import crow.wasmline.RawExportKind
import crow.wasmline.RawImport
import crow.wasmline.RawValue
import crow.wasmline.RawValueType
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.coreFailure
import crow.wasmline.internal.core.CoreWasmBackendMemory
import crow.wasmline.internal.core.CoreWasmBackendModule
import crow.wasmline.internal.core.CoreWasmBackendSession
import crow.wasmline.internal.core.CoreWasmImportDispatcher
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

/**
 * WebAssembly JS backend for the platform-neutral Core Wasm module contract.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
internal class WebCoreWasmModule(private val compiled: WebWasmModule, private val descriptor: WasmlineArtifactDescriptor) :
    CoreWasmBackendModule {
    override val exports: List<RawExport> = webWasmModuleExports(compiled).map { export ->
        RawExport(
            name = export.name,
            kind = export.kind.toRawExportKind(),
            signature = descriptor.rawAbi?.exports?.firstOrNull { it.name == export.name }?.signature,
        )
    }

    override val capabilities: CoreWasmCapabilities = CoreWasmCapabilities(
        multiValue = true,
        i64 = true,
        simd = false,
        threads = false,
        bulkMemory = true,
        referenceTypes = true,
    )

    private val imports = webWasmModuleImports(compiled)
    private var closed = false

    override fun instantiate(
        sessionKey: String,
        options: CoreWasmSessionOptions,
        dispatcher: CoreWasmImportDispatcher,
    ): WasmlineCallResult<CoreWasmBackendSession> {
        if (closed) return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Web Core Wasm module is closed.")

        val registered = options.imports.associateBy { it.module to it.name }
        val unsupported = imports.firstOrNull { it.kind != "function" }
        if (unsupported != null) {
            return coreFailure(
                WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH,
                "RAW_EXPORT does not support '${unsupported.kind}' import '${unsupported.module}.${unsupported.name}'.",
            )
        }
        val requiredNames = imports.map { it.module to it.name }.toSet()
        val missing = imports.firstOrNull { (it.module to it.name) !in registered }
        if (missing != null) {
            return coreFailure(
                WasmlineErrorCode.IMPORT_MISSING,
                "Required raw import '${missing.module}.${missing.name}' is not registered.",
            )
        }
        val extra = registered.keys.firstOrNull { it !in requiredNames }
        if (extra != null) {
            return coreFailure(
                WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH,
                "Raw import '${extra.first}.${extra.second}' is not declared by the WebAssembly module.",
            )
        }
        val declaredNames = descriptor.rawAbi?.imports?.map { it.module to it.name }?.toSet().orEmpty()
        if (declaredNames.isNotEmpty() && declaredNames != requiredNames) {
            return coreFailure(
                WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH,
                "rawAbi import declarations do not match the WebAssembly module import inventory.",
            )
        }

        var callbackMemory: WebCoreWasmMemory? = null
        var importFailure: WasmlineCallResult.Failure? = null
        val builder = WebWasmImportsBuilder()
        options.imports.forEach { import ->
            builder.function(
                module = import.module,
                name = import.name,
                paramTypes = import.signature.parameters.map(RawValueType::toWebType),
                resultTypes = import.signature.results.map(RawValueType::toWebType),
            ) { values ->
                when (val result = dispatcher.dispatch(import, values.map(WebWasmValue::toRawValue), callbackMemory)) {
                    is WasmlineCallResult.Success -> result.value.map(RawValue::toWebValue)

                    is WasmlineCallResult.Failure -> {
                        importFailure = result
                        throw WebRawImportException(result.failure.message)
                    }
                }
            }
        }

        val instance = try {
            WebWasmRuntime.instantiate(compiled, builder)
        } catch (failure: Throwable) {
            val captured = importFailure
            if (captured != null) return captured
            return coreFailure(
                WasmlineErrorCode.INSTANTIATION_FAILED,
                "Web Core Wasm instantiation failed: ${failure.message ?: failure}",
            )
        }
        callbackMemory = options.memoryExportName?.let(instance::memoryOrNull)?.let(::WebCoreWasmMemory)
        return WasmlineCallResult.Success(
            WebCoreWasmSession(
                instance = instance,
                memory = callbackMemory,
                importFailure = { importFailure.also { importFailure = null } },
            ),
        )
    }

    override fun close() {
        closed = true
    }
}

/**
 * Live WebAssembly JS instance used by one public Core Wasm session.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class WebCoreWasmSession(
    private val instance: WebWasmInstanceHandle,
    override val memory: WebCoreWasmMemory?,
    private val importFailure: () -> WasmlineCallResult.Failure?,
) : CoreWasmBackendSession {
    private var closed = false

    override fun invoke(
        exportName: String,
        arguments: List<RawValue>,
        resultTypes: List<RawValueType>,
    ): WasmlineCallResult<List<RawValue>> {
        if (closed) return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Web Core Wasm session is closed.")
        val function = instance.functionOrNull(exportName)
            ?: return coreFailure(WasmlineErrorCode.EXPORT_NOT_FOUND, "Core Wasm export '$exportName' was not found.")
        return try {
            WasmlineCallResult.Success(
                function.invoke(
                    args = arguments.map(RawValue::toWebValue),
                    resultTypes = resultTypes.map(RawValueType::toWebType),
                ).map(WebWasmValue::toRawValue),
            )
        } catch (failure: Throwable) {
            importFailure() ?: coreFailure(
                WasmlineErrorCode.WASM_TRAP,
                "Web Core Wasm export '$exportName' trapped: ${failure.message ?: failure}",
            )
        }
    }

    override fun close() {
        closed = true
    }
}

/**
 * Refreshing byte-level wrapper around an exported WebAssembly memory.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class WebCoreWasmMemory(private val memory: WebWasmMemory) : CoreWasmBackendMemory {
    override val byteSize: Long get() = memory.byteSize
    override val pageCount: Long get() = memory.pageCount

    override fun readInto(destination: ByteArray, destinationOffset: Int, sourceOffset: Long, length: Int) {
        memory.readInto(destination, destinationOffset, sourceOffset.toWebOffset(), length)
    }

    override fun writeFrom(source: ByteArray, sourceOffset: Int, destinationOffset: Long, length: Int) {
        memory.writeFrom(source, sourceOffset, destinationOffset.toWebOffset(), length)
    }

    override fun grow(deltaPages: Long): Long {
        require(deltaPages in 0..Int.MAX_VALUE.toLong()) { "Web memory growth exceeds the supported page count." }
        return memory.grow(deltaPages.toInt())
    }
}

/**
 * Marks a structured import failure inside the synchronous JavaScript trampoline.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class WebRawImportException(message: String) : RuntimeException(message)

private fun String.toRawExportKind(): RawExportKind = when (this) {
    "function" -> RawExportKind.FUNCTION
    "memory" -> RawExportKind.MEMORY
    "global" -> RawExportKind.GLOBAL
    "table" -> RawExportKind.TABLE
    else -> RawExportKind.UNKNOWN
}

private fun RawValueType.toWebType(): WebWasmType = when (this) {
    RawValueType.I32 -> WebWasmType.I32
    RawValueType.I64 -> WebWasmType.I64
    RawValueType.F32 -> WebWasmType.F32
    RawValueType.F64 -> WebWasmType.F64
}

private fun RawValue.toWebValue(): WebWasmValue = when (this) {
    is RawValue.I32 -> WebWasmValue.I32(value)
    is RawValue.I64 -> WebWasmValue.I64(value)
    is RawValue.F32 -> WebWasmValue.F32(value)
    is RawValue.F64 -> WebWasmValue.F64(value)
}

private fun WebWasmValue.toRawValue(): RawValue = when (this) {
    is WebWasmValue.I32 -> RawValue.I32(value)
    is WebWasmValue.I64 -> RawValue.I64(value)
    is WebWasmValue.F32 -> RawValue.F32(value)
    is WebWasmValue.F64 -> RawValue.F64(value)
}

private fun Long.toWebOffset(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "Web memory offset exceeds the supported Wasm32 address range." }
    return toInt()
}
