@file:OptIn(ExperimentalForeignApi::class)
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
import crow.wasmline.invocation.WasmlineFailure
import crow.wasmline.native.c.*
import kotlinx.cinterop.*

/** Creates the Kotlin/Native backend for a loaded Core Wasm module. */
internal fun createNativeCoreWasmBackend(
    moduleKey: String,
    descriptor: WasmlineArtifactDescriptor,
): WasmlineCallResult<CoreWasmBackendModule> {
    ensureNativeRuntimeLoaded()
    val encoded = readNativeBuffer { outLen -> wasmline_core_module_exports(moduleKey, outLen) }
        ?: return coreFailure(WasmlineErrorCode.MODULE_FORMAT_INVALID, "Native Core Wasm export metadata is unavailable.")
    return when (val exports = CoreWasmNativeCodec.decodeExports(encoded)) {
        is WasmlineCallResult.Failure -> exports

        is WasmlineCallResult.Success -> WasmlineCallResult.Success(
            NativeCoreWasmModule(moduleKey, descriptor, exports.value),
        )
    }
}

/**
 * Implements a Core Wasm backend module through Kotlin/Native C interop.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class NativeCoreWasmModule(
    private val moduleKey: String,
    private val descriptor: WasmlineArtifactDescriptor,
    override val exports: List<RawExport>,
) : CoreWasmBackendModule {
    override val capabilities = CoreWasmCapabilities(
        multiValue = true,
        i64 = true,
        simd = false,
        threads = false,
        bulkMemory = true,
        referenceTypes = true,
    )
    private val lock = WasmlineRuntimeLock()
    private val sessions = linkedMapOf<String, NativeCoreWasmSession>()
    private var closed = false

    override fun instantiate(
        sessionKey: String,
        options: CoreWasmSessionOptions,
        dispatcher: CoreWasmImportDispatcher,
    ): WasmlineCallResult<CoreWasmBackendSession> {
        if (lock.withLock { closed }) return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Native Core Wasm module is closed.")
        val memory = options.memoryExportName
            ?.takeIf { memoryName -> exports.any { it.name == memoryName && it.kind == RawExportKind.MEMORY } }
            ?.let { NativeCoreWasmMemory(sessionKey) }
        val holder = NativeRawImportDispatcher(options.imports, dispatcher, memory)
        NativeRawImportRegistry.register(sessionKey, holder)
        val imports = CoreWasmNativeCodec.encodeImports(options.imports)
        val carrier = memScoped {
            val outLen = alloc<ULongVar>()
            imports.usePinned { pinned ->
                val pointer = wasmline_core_create_session(
                    moduleKey,
                    sessionKey,
                    if (imports.isEmpty()) null else pinned.addressOf(0),
                    imports.size.toULong(),
                    staticCFunction(::nativeRawImportCallback),
                    staticCFunction(::nativeRawImportFree),
                    null,
                    null,
                    options.memoryExportName,
                    outLen.ptr,
                ) ?: return@usePinned null
                val result = pointer.readBytes(outLen.value.toInt())
                wasmline_free_memory(pointer)
                result
            }
        }
        if (carrier == null) {
            NativeRawImportRegistry.unregister(sessionKey)
            return coreFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native Core Wasm session creation returned no response.")
        }
        when (val created = WasmlineTypedInvocationCodec.decodeRawValues(carrier)) {
            is WasmlineCallResult.Failure -> {
                NativeRawImportRegistry.unregister(sessionKey)
                return created
            }

            is WasmlineCallResult.Success -> if (created.value.isNotEmpty()) {
                NativeRawImportRegistry.unregister(sessionKey)
                return coreFailure(WasmlineErrorCode.INSTANTIATION_FAILED, "Native Core Wasm session returned unexpected values.")
            }
        }
        val session = NativeCoreWasmSession(sessionKey, memory)
        lock.withLock { if (!closed) sessions[sessionKey] = session }
        if (lock.withLock { closed }) {
            session.close()
            return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Native Core Wasm module was closed during instantiation.")
        }
        return WasmlineCallResult.Success(session)
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
        active.forEach(NativeCoreWasmSession::close)
    }
}

/**
 * Implements one isolated Core Wasm backend session through Kotlin/Native C interop.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class NativeCoreWasmSession(private val sessionKey: String, override val memory: NativeCoreWasmMemory?) : CoreWasmBackendSession {
    private var closed = false

    override fun invoke(
        exportName: String,
        arguments: List<RawValue>,
        resultTypes: List<RawValueType>,
    ): WasmlineCallResult<List<RawValue>> {
        if (closed) return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Native Core Wasm session is closed.")
        val encoded = when (val result = WasmlineTypedInvocationCodec.encodeRawArguments(arguments)) {
            is WasmlineCallResult.Failure -> return result
            is WasmlineCallResult.Success -> result.value
        }
        val carrier = readNativeBuffer { outLen ->
            encoded.usePinned { pinned ->
                wasmline_core_invoke(
                    sessionKey,
                    exportName,
                    exportName.length.toULong(),
                    if (encoded.isEmpty()) null else pinned.addressOf(0),
                    encoded.size.toULong(),
                    outLen,
                )
            }
        } ?: return coreFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native Core Wasm invocation returned no response.")
        return WasmlineTypedInvocationCodec.decodeRawValues(carrier)
    }

    override fun close() {
        if (!closed) {
            closed = true
            NativeRawImportRegistry.unregister(sessionKey)
            wasmline_core_release_session(sessionKey)
        }
    }
}

/**
 * Decodes native import calls and dispatches them to registered host handlers.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class NativeRawImportDispatcher(
    imports: Collection<RawImport>,
    private val dispatcher: CoreWasmImportDispatcher,
    private val memory: NativeCoreWasmMemory?,
) {
    private val importsByName = imports.associateBy { it.module to it.name }

    fun dispatch(module: String, name: String, arguments: ByteArray): ByteArray = try {
        val import = importsByName[module to name]
            ?: return encodeFailure(WasmlineErrorCode.IMPORT_MISSING, "Raw import '$module.$name' was not registered.")
        val values = when (val decoded = WasmlineTypedInvocationCodec.decodeRawArguments(arguments)) {
            is WasmlineCallResult.Failure -> return encodeFailure(decoded.failure.code, decoded.failure.message)
            is WasmlineCallResult.Success -> decoded.value
        }
        when (val encoded = WasmlineTypedInvocationCodec.encodeRawResult(dispatcher.dispatch(import, values, memory))) {
            is WasmlineCallResult.Success -> encoded.value
            is WasmlineCallResult.Failure -> encodeFailure(encoded.failure.code, encoded.failure.message)
        }
    } catch (failure: Throwable) {
        encodeFailure(WasmlineErrorCode.IMPORT_HANDLER_FAILED, failure.message ?: "Raw import callback failed.")
    }

    private fun encodeFailure(code: WasmlineErrorCode, message: String): ByteArray = when (
        val result = WasmlineTypedInvocationCodec.encodeRawResult(
            WasmlineCallResult.Failure(WasmlineFailure(code, message)),
        )
    ) {
        is WasmlineCallResult.Success -> result.value
        is WasmlineCallResult.Failure -> byteArrayOf()
    }
}

/**
 * Owns Kotlin/Native import dispatchers for live Core Wasm sessions.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private object NativeRawImportRegistry {
    private val lock = WasmlineRuntimeLock()
    private val values = mutableMapOf<String, NativeRawImportDispatcher>()

    fun register(key: String, value: NativeRawImportDispatcher) = lock.withLock { values[key] = value }
    fun unregister(key: String) = lock.withLock { values.remove(key) }
    fun find(key: String): NativeRawImportDispatcher? = lock.withLock { values[key] }
}

/**
 * Provides refreshed Core Wasm memory operations through Kotlin/Native C interop.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class NativeCoreWasmMemory(private val sessionKey: String) : CoreWasmBackendMemory {
    override val byteSize: Long get() = size(false)
    override val pageCount: Long get() = size(true)

    override fun readInto(destination: ByteArray, destinationOffset: Int, sourceOffset: Long, length: Int) {
        val failure = readNativeMemoryFailure { outSuccess, outLen ->
            destination.usePinned { pinned ->
                wasmline_core_memory_read_into(
                    sessionKey,
                    sourceOffset.toULong(),
                    if (length == 0) null else pinned.addressOf(destinationOffset),
                    length.toULong(),
                    outSuccess,
                    outLen,
                )
            }
        }
        if (failure != null) throw CoreWasmBackendFailure(CoreWasmNativeCodec.decodeOperationFailure(failure))
    }

    override fun writeFrom(source: ByteArray, sourceOffset: Int, destinationOffset: Long, length: Int) {
        val failure = readNativeMemoryFailure { outSuccess, outLen ->
            source.usePinned { pinned ->
                wasmline_core_memory_write_from(
                    sessionKey,
                    destinationOffset.toULong(),
                    if (length == 0) null else pinned.addressOf(sourceOffset),
                    length.toULong(),
                    outSuccess,
                    outLen,
                )
            }
        }
        if (failure != null) throw CoreWasmBackendFailure(CoreWasmNativeCodec.decodeOperationFailure(failure))
    }

    override fun grow(deltaPages: Long): Long {
        val carrier = readNativeBuffer { outLen -> wasmline_core_memory_grow(sessionKey, deltaPages.toULong(), outLen) }
            ?: throw CoreWasmBackendFailure(
                WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native memory grow returned no response."),
            )
        return (decodeValues(carrier).singleOrNull() as? RawValue.I64)?.value
            ?: throw CoreWasmBackendFailure(
                WasmlineFailure(WasmlineErrorCode.RESULT_TYPE_UNSUPPORTED, "Native memory grow result is invalid."),
            )
    }

    private fun size(pages: Boolean): Long {
        val carrier = readNativeBuffer { outLen -> wasmline_core_memory_size(sessionKey, pages, outLen) }
            ?: throw CoreWasmBackendFailure(
                WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native memory size returned no response."),
            )
        return (decodeValues(carrier).singleOrNull() as? RawValue.I64)?.value
            ?: throw CoreWasmBackendFailure(
                WasmlineFailure(WasmlineErrorCode.RESULT_TYPE_UNSUPPORTED, "Native memory size result is invalid."),
            )
    }

    private fun decodeValues(carrier: ByteArray): List<RawValue> =
        when (val result = WasmlineTypedInvocationCodec.decodeRawValues(carrier)) {
            is WasmlineCallResult.Success -> result.value
            is WasmlineCallResult.Failure -> throw CoreWasmBackendFailure(result.failure)
        }
}

private fun nativeRawImportCallback(
    user: COpaquePointer?,
    sessionKey: CPointer<ByteVar>?,
    module: CPointer<ByteVar>?,
    moduleLen: ULong,
    name: CPointer<ByteVar>?,
    nameLen: ULong,
    arguments: COpaquePointer?,
    argumentsLen: ULong,
    outLen: CPointer<ULongVar>?,
): CPointer<ByteVar>? {
    return try {
        val moduleSize = checkedLength(moduleLen) ?: return null
        val nameSize = checkedLength(nameLen) ?: return null
        val argumentSize = checkedLength(argumentsLen) ?: return null
        if (sessionKey == null || (moduleSize > 0 && module == null) ||
            (nameSize > 0 && name == null) || (argumentSize > 0 && arguments == null)
        ) {
            return null
        }
        val key = sessionKey.toKString()
        val moduleText = module?.readBytes(moduleSize)?.decodeToString() ?: ""
        val nameText = name?.readBytes(nameSize)?.decodeToString() ?: ""
        val argumentBytes = if (argumentSize == 0) byteArrayOf() else arguments!!.reinterpret<ByteVar>().readBytes(argumentSize)
        val result = NativeRawImportRegistry.find(key)?.dispatch(moduleText, nameText, argumentBytes) ?: return null
        outLen?.pointed?.value = result.size.toULong()
        val pointer = wasmline_allocate_memory(result.size.toULong()) ?: return null
        result.forEachIndexed { index, value -> pointer[index] = value }
        pointer
    } catch (_: Throwable) {
        null
    }
}

private fun nativeRawImportFree(buffer: CPointer<ByteVar>?) {
    if (buffer != null) wasmline_free_memory(buffer)
}

private fun checkedLength(value: ULong): Int? = if (value > Int.MAX_VALUE.toULong()) null else value.toInt()

private inline fun readNativeBuffer(invoke: (CPointer<ULongVar>) -> CPointer<ByteVar>?): ByteArray? = memScoped {
    val outLen = alloc<ULongVar>()
    val pointer = invoke(outLen.ptr) ?: return@memScoped null
    if (outLen.value > Int.MAX_VALUE.toULong()) {
        wasmline_free_memory(pointer)
        return@memScoped null
    }
    val result = pointer.readBytes(outLen.value.toInt())
    wasmline_free_memory(pointer)
    result
}

/** Reads the optional failure carrier from an allocation-free successful native memory operation. */
private inline fun readNativeMemoryFailure(invoke: (CPointer<BooleanVar>, CPointer<ULongVar>) -> CPointer<ByteVar>?): ByteArray? =
    memScoped {
        val outSuccess = alloc<BooleanVar>()
        val outLen = alloc<ULongVar>()
        outSuccess.value = false
        outLen.value = 0uL
        val pointer = invoke(outSuccess.ptr, outLen.ptr)
        if (outSuccess.value) {
            if (pointer != null) wasmline_free_memory(pointer)
            return@memScoped null
        }
        if (pointer == null) {
            throw CoreWasmBackendFailure(
                WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native memory operation returned no failure response."),
            )
        }
        if (outLen.value > Int.MAX_VALUE.toULong()) {
            wasmline_free_memory(pointer)
            throw CoreWasmBackendFailure(
                WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native memory failure response exceeds Kotlin limits."),
            )
        }
        val result = pointer.readBytes(outLen.value.toInt())
        wasmline_free_memory(pointer)
        result
    }
