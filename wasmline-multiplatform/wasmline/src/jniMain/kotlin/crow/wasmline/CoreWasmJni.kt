@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.core.CoreWasmBackendFailure
import crow.wasmline.internal.core.CoreWasmBackendMemory
import crow.wasmline.internal.core.CoreWasmBackendModule
import crow.wasmline.internal.core.CoreWasmBackendSession
import crow.wasmline.internal.core.CoreWasmImportDispatcher
import crow.wasmline.internal.core.CoreWasmNativeCodec
import crow.wasmline.internal.invocation.WasmlineTypedInvocationCodec
import crow.wasmline.internal.runtime.WasmlineRuntimeLock
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

/** Creates the JNI backend for a loaded Core Wasm module. */
internal fun createJniCoreWasmBackend(
    moduleKey: String,
    descriptor: WasmlineArtifactDescriptor,
): WasmlineCallResult<CoreWasmBackendModule> {
    ensureJniRuntimeLoaded()
    val encoded = JniWasmlineBindings.coreModuleExports(moduleKey)
        ?: return coreFailure(WasmlineErrorCode.MODULE_FORMAT_INVALID, "Native Core Wasm export metadata is unavailable.")
    return when (val exports = CoreWasmNativeCodec.decodeExports(encoded)) {
        is WasmlineCallResult.Failure -> exports

        is WasmlineCallResult.Success -> WasmlineCallResult.Success(
            JniCoreWasmModule(moduleKey, descriptor, exports.value),
        )
    }
}

/**
 * Implements a Core Wasm backend module through JNI.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class JniCoreWasmModule(
    private val moduleKey: String,
    private val descriptor: WasmlineArtifactDescriptor,
    override val exports: List<RawExport>,
) : CoreWasmBackendModule {
    override val capabilities: CoreWasmCapabilities = CoreWasmCapabilities(
        multiValue = true,
        i64 = true,
        simd = false,
        threads = false,
        bulkMemory = true,
        referenceTypes = true,
    )

    private val lock = WasmlineRuntimeLock()
    private val sessions = linkedMapOf<String, JniCoreWasmSession>()
    private var closed = false

    override fun instantiate(
        sessionKey: String,
        options: CoreWasmSessionOptions,
        dispatcher: CoreWasmImportDispatcher,
    ): WasmlineCallResult<CoreWasmBackendSession> {
        if (lock.withLock { closed }) return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Native Core Wasm module is closed.")
        val memory = options.memoryExportName
            ?.takeIf { memoryName -> exports.any { it.name == memoryName && it.kind == RawExportKind.MEMORY } }
            ?.let { JniCoreWasmMemory(sessionKey) }
        val importDispatcher = JniRawImportDispatcher(options.imports, dispatcher, memory)
        val imports = CoreWasmNativeCodec.encodeImports(options.imports)
        val carrier = JniWasmlineBindings.coreCreateSession(
            artifactKey = moduleKey,
            sessionKey = sessionKey,
            imports = imports,
            dispatcher = importDispatcher,
            memoryExportName = options.memoryExportName,
        ) ?: return coreFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native Core Wasm session creation returned no response.")
        when (val created = WasmlineTypedInvocationCodec.decodeRawValues(carrier)) {
            is WasmlineCallResult.Failure -> return created

            is WasmlineCallResult.Success -> if (created.value.isNotEmpty()) {
                return coreFailure(WasmlineErrorCode.INSTANTIATION_FAILED, "Native Core Wasm session returned unexpected values.")
            }
        }
        val session = JniCoreWasmSession(sessionKey, memory)
        lock.withLock {
            if (closed) {
                JniWasmlineBindings.coreReleaseSession(sessionKey)
            } else {
                sessions[sessionKey] = session
            }
        }
        return if (lock.withLock { closed }) {
            coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Native Core Wasm module was closed during instantiation.")
        } else {
            WasmlineCallResult.Success(session)
        }
    }

    override fun close() {
        val active = lock.withLock {
            if (closed) {
                null
            } else {
                closed = true
                sessions.values.toList().also { sessions.clear() }
            }
        } ?: return
        active.forEach(JniCoreWasmSession::close)
    }
}

/**
 * Implements one isolated Core Wasm backend session through JNI.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class JniCoreWasmSession(private val sessionKey: String, override val memory: JniCoreWasmMemory?) : CoreWasmBackendSession {
    private var closed = false

    override fun invoke(
        exportName: String,
        arguments: List<RawValue>,
        resultTypes: List<RawValueType>,
    ): WasmlineCallResult<List<RawValue>> {
        if (closed) return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Native Core Wasm session is closed.")
        val encodedArguments = when (val encoded = WasmlineTypedInvocationCodec.encodeRawArguments(arguments)) {
            is WasmlineCallResult.Failure -> return encoded
            is WasmlineCallResult.Success -> encoded.value
        }
        val carrier = JniWasmlineBindings.coreInvoke(sessionKey, exportName, encodedArguments)
            ?: return coreFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native Core Wasm invocation returned no response.")
        return WasmlineTypedInvocationCodec.decodeRawValues(carrier)
    }

    override fun close() {
        if (!closed) {
            closed = true
            JniWasmlineBindings.coreReleaseSession(sessionKey)
        }
    }
}

/**
 * Decodes JNI import calls and dispatches them to registered host handlers.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class JniRawImportDispatcher(
    imports: Collection<RawImport>,
    private val dispatcher: CoreWasmImportDispatcher,
    private val memory: JniCoreWasmMemory?,
) {
    private val importsByName = imports.associateBy { it.module to it.name }

    fun dispatchRaw(module: String, name: String, arguments: ByteArray): ByteArray {
        val import = importsByName[module to name]
            ?: return encodeImportFailure(WasmlineErrorCode.IMPORT_MISSING, "Raw import '$module.$name' was not registered.")
        val values = when (val decoded = WasmlineTypedInvocationCodec.decodeRawArguments(arguments)) {
            is WasmlineCallResult.Failure -> return encodeImportFailure(decoded.failure.code, decoded.failure.message)
            is WasmlineCallResult.Success -> decoded.value
        }
        val result = dispatcher.dispatch(import, values, memory)
        return when (val encoded = WasmlineTypedInvocationCodec.encodeRawResult(result)) {
            is WasmlineCallResult.Success -> encoded.value
            is WasmlineCallResult.Failure -> encodeImportFailure(encoded.failure.code, encoded.failure.message)
        }
    }

    private fun encodeImportFailure(code: WasmlineErrorCode, message: String): ByteArray = when (
        val encoded = WasmlineTypedInvocationCodec.encodeRawResult(
            WasmlineCallResult.Failure(
                crow.wasmline.invocation.WasmlineFailure(code, message),
            ),
        )
    ) {
        is WasmlineCallResult.Success -> encoded.value
        is WasmlineCallResult.Failure -> byteArrayOf()
    }
}

/**
 * Provides refreshed Core Wasm memory operations through JNI.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class JniCoreWasmMemory(private val sessionKey: String) : CoreWasmBackendMemory {
    override val byteSize: Long get() = readSize(pages = false)
    override val pageCount: Long get() = readSize(pages = true)

    override fun readInto(destination: ByteArray, destinationOffset: Int, sourceOffset: Long, length: Int) {
        JniWasmlineBindings.coreMemoryReadInto(sessionKey, sourceOffset, destination, destinationOffset, length)?.let { carrier ->
            throw CoreWasmBackendFailure(CoreWasmNativeCodec.decodeOperationFailure(carrier))
        }
    }

    override fun writeFrom(source: ByteArray, sourceOffset: Int, destinationOffset: Long, length: Int) {
        JniWasmlineBindings.coreMemoryWriteFrom(sessionKey, source, sourceOffset, destinationOffset, length)?.let { carrier ->
            throw CoreWasmBackendFailure(CoreWasmNativeCodec.decodeOperationFailure(carrier))
        }
    }

    override fun grow(deltaPages: Long): Long {
        val carrier = JniWasmlineBindings.coreMemoryGrow(sessionKey, deltaPages)
            ?: throw CoreWasmBackendFailure(
                crow.wasmline.invocation.WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native memory grow returned no response."),
            )
        val values = decodeValues(carrier)
        val value = values.singleOrNull() as? RawValue.I64
            ?: throw CoreWasmBackendFailure(
                crow.wasmline.invocation.WasmlineFailure(
                    WasmlineErrorCode.RESULT_TYPE_UNSUPPORTED,
                    "Native memory grow result is invalid.",
                ),
            )
        return value.value
    }

    private fun readSize(pages: Boolean): Long {
        val carrier = JniWasmlineBindings.coreMemorySize(sessionKey, pages)
            ?: throw CoreWasmBackendFailure(
                crow.wasmline.invocation.WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native memory size returned no response."),
            )
        val value = decodeValues(carrier).singleOrNull() as? RawValue.I64
            ?: throw CoreWasmBackendFailure(
                crow.wasmline.invocation.WasmlineFailure(
                    WasmlineErrorCode.RESULT_TYPE_UNSUPPORTED,
                    "Native memory size result is invalid.",
                ),
            )
        return value.value
    }

    private fun decodeValues(carrier: ByteArray): List<RawValue> =
        when (val result = WasmlineTypedInvocationCodec.decodeRawValues(carrier)) {
            is WasmlineCallResult.Success -> result.value
            is WasmlineCallResult.Failure -> throw CoreWasmBackendFailure(result.failure)
        }
}
