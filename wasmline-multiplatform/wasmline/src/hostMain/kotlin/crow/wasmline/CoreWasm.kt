package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Reports Core WebAssembly features supported by one runtime backend.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property multiValue Whether functions may return multiple scalar values.
 * @property i64 Whether `i64` values are supported without precision loss.
 * @property simd Whether the backend accepts Core Wasm SIMD instructions.
 * @property threads Whether shared memory and Core Wasm threads are supported.
 * @property bulkMemory Whether bulk-memory instructions are supported.
 * @property referenceTypes Whether reference-type instructions are supported.
 */
data class CoreWasmCapabilities(
    val multiValue: Boolean,
    val i64: Boolean,
    val simd: Boolean,
    val threads: Boolean,
    val bulkMemory: Boolean,
    val referenceTypes: Boolean,
) {
    /** Returns whether the backend supports [feature]. */
    fun supports(feature: CoreWasmFeature): Boolean = when (feature) {
        CoreWasmFeature.MULTI_VALUE -> multiValue
        CoreWasmFeature.I64 -> i64
        CoreWasmFeature.SIMD -> simd
        CoreWasmFeature.THREADS -> threads
        CoreWasmFeature.BULK_MEMORY -> bulkMemory
        CoreWasmFeature.REFERENCE_TYPES -> referenceTypes
    }
}

/**
 * Context available while a synchronous Core Wasm host import is executing.
 *
 * The context and its [memory] view are valid only for the duration of the
 * import callback. A handler must not retain either object.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
interface RawImportContext {
    /** Current instance memory when one is visible to the import callback. */
    val memory: RawMemory?

    /** Session whose Wasm invocation entered the import callback. */
    val session: CoreWasmSession
}

/**
 * Defines one synchronous Core Wasm function import supplied by the host.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property module Import module namespace.
 * @property name Import field name.
 * @property signature Exact scalar function signature.
 * @property handler Synchronous host callback. It must not suspend or reenter [RawImportContext.session].
 */
class RawImport(
    val module: String,
    val name: String,
    val signature: RawFunctionSignature,
    val handler: (RawImportContext, List<RawValue>) -> WasmlineCallResult<List<RawValue>>,
) {
    init {
        require(module.isNotBlank()) { "Raw import module must not be blank." }
        require(name.isNotBlank()) { "Raw import name must not be blank." }
    }
}

/**
 * Configures one isolated Core Wasm instance.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property imports Synchronous host functions registered before instantiation.
 * @property exportSignatures Caller-provided signatures used when module reflection is unavailable.
 * @property memoryExportName Primary exported memory name, or `null` to expose no session memory.
 */
data class CoreWasmSessionOptions(
    val imports: List<RawImport> = emptyList(),
    val exportSignatures: Map<String, RawFunctionSignature> = emptyMap(),
    val memoryExportName: String? = RawAbiMetadata.DEFAULT_MEMORY_EXPORT,
) {
    init {
        require(exportSignatures.keys.none(String::isBlank)) { "Raw export signature names must not be blank." }
        require(memoryExportName == null || memoryExportName.isNotBlank()) { "Raw memory export name must not be blank." }
    }
}

/**
 * Provides checked byte access to one Core WebAssembly linear memory.
 *
 * Every operation refreshes backend storage before access, so a Wasm
 * `memory.grow` cannot leave this object with a stale view or data pointer.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
interface RawMemory {
    /** Returns the current memory size in bytes. */
    fun byteSize(): WasmlineCallResult<Long>

    /** Returns the current memory size in WebAssembly pages. */
    fun pageCount(): WasmlineCallResult<Long>

    /** Reads [length] bytes beginning at [offset]. */
    fun read(offset: Long, length: Int): WasmlineCallResult<ByteArray>

    /** Reads bytes into an existing destination buffer. */
    fun readInto(destination: ByteArray, destinationOffset: Int, sourceOffset: Long, length: Int): WasmlineCallResult<Unit>

    /** Writes all [bytes] beginning at [offset]. */
    fun write(offset: Long, bytes: ByteArray): WasmlineCallResult<Unit>

    /** Writes a range from [source] into linear memory. */
    fun writeFrom(source: ByteArray, sourceOffset: Int, destinationOffset: Long, length: Int): WasmlineCallResult<Unit>

    /** Reads [length] bytes and decodes them as UTF-8 text. */
    fun readUtf8(offset: Long, length: Int): WasmlineCallResult<String>

    /** Grows memory by [deltaPages] and returns the previous page count. */
    fun grow(deltaPages: Long): WasmlineCallResult<Long>
}

/**
 * Represents one compiled or deserialized Core WebAssembly module.
 *
 * A module may create multiple isolated sessions. Closing the module closes
 * all of its sessions and releases the underlying artifact handle.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
interface CoreWasmModule : AutoCloseable {
    /** Artifact metadata used to load this module. */
    val descriptor: WasmlineArtifactDescriptor

    /** Export inventory merged with any versioned ABI metadata. */
    val exports: List<RawExport>

    /** Features supported by this module's backend. */
    val capabilities: CoreWasmCapabilities

    /** Whether this module has been closed. */
    val isClosed: Boolean

    /** Finds an export by its exact name. */
    fun findExport(name: String): RawExport?

    /** Instantiates an isolated Core Wasm session. */
    fun instantiate(options: CoreWasmSessionOptions = CoreWasmSessionOptions()): WasmlineCallResult<CoreWasmSession>

    /** Instantiates a session with only the supplied host imports. */
    fun instantiate(imports: Collection<RawImport>): WasmlineCallResult<CoreWasmSession> =
        instantiate(CoreWasmSessionOptions(imports = imports.toList()))

    /** Closes all sessions and releases the compiled module. This operation is idempotent. */
    override fun close()
}

/**
 * Represents one isolated live instance of a [CoreWasmModule].
 *
 * A session accepts one active call at a time. Concurrent calls and import
 * callback reentry fail with stable error codes instead of blocking.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
interface CoreWasmSession : AutoCloseable {
    /** Module that created this session. */
    val module: CoreWasmModule

    /** Configured primary linear memory, or `null` when the module exposes none. */
    val memory: RawMemory?

    /** Whether this session has been closed. */
    val isClosed: Boolean

    /** Finds an export by its exact name. */
    fun findExport(name: String): RawExport?

    /** Invokes a scalar Core Wasm function export. */
    fun invoke(exportName: String, arguments: List<RawValue> = emptyList()): WasmlineCallResult<List<RawValue>>

    /** Releases this instance. This operation is idempotent. */
    override fun close()
}

/** Adapts a loaded `CORE_WASM + RAW_EXPORT` handle to the module/session API. */
internal fun createCoreWasmModule(owner: Wasmline): WasmlineCallResult<CoreWasmModule> {
    val descriptor = owner.descriptor
    if (descriptor.executionModel != WasmlineExecutionModel.CORE_WASM ||
        descriptor.invocationProtocol != WasmlineInvocationProtocol.RAW_EXPORT
    ) {
        return coreFailure(
            WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
            "CoreWasmModule requires CORE_WASM with RAW_EXPORT.",
        )
    }
    return when (val backend = owner.createCoreWasmBackend()) {
        is WasmlineCallResult.Failure -> backend
        is WasmlineCallResult.Success -> WasmlineCallResult.Success(CoreWasmModuleImpl(owner, backend.value))
    }
}

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

/**
 * Coordinates public module lifecycle with one platform backend module.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class CoreWasmModuleImpl(private val owner: Wasmline, private val backend: CoreWasmBackendModule) : CoreWasmModule {
    private val lock = WasmlineHostServiceLock()
    private val sessions = mutableListOf<CoreWasmSessionImpl>()
    private var closed = false

    override val descriptor: WasmlineArtifactDescriptor = owner.descriptor
    override val capabilities: CoreWasmCapabilities = backend.capabilities
    override val exports: List<RawExport> = mergeExports(backend.exports, descriptor.rawAbi?.exports.orEmpty())
    override val isClosed: Boolean get() = lock.withLock { closed }

    override fun findExport(name: String): RawExport? = exports.firstOrNull { it.name == name }

    override fun instantiate(options: CoreWasmSessionOptions): WasmlineCallResult<CoreWasmSession> {
        if (lock.withLock { closed }) return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Core Wasm module is closed.")
        val duplicate = options.imports.groupBy { it.module to it.name }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            return coreFailure(
                WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH,
                "Duplicate raw import '${duplicate.key.first}.${duplicate.key.second}'.",
            )
        }
        validateDeclaredImports(options.imports, descriptor.rawAbi?.imports.orEmpty())?.let {
            return WasmlineCallResult.Failure(it)
        }
        val missingFeature = descriptor.rawAbi?.requiredFeatures?.firstOrNull { !capabilities.supports(it) }
        if (missingFeature != null) {
            return coreFailure(
                WasmlineErrorCode.WASM_FEATURE_UNSUPPORTED,
                "Core Wasm backend does not support required feature '$missingFeature'.",
            )
        }

        val session = CoreWasmSessionImpl(
            module = this,
            options = options.withAbiDefaults(descriptor.rawAbi),
            lock = WasmlineHostServiceLock(),
        )
        val sessionKey = CoreWasmSessionIds.next(descriptor.path)
        val created = try {
            backend.instantiate(sessionKey, session.options, session::dispatchImport)
        } catch (failure: Throwable) {
            coreFailure(
                WasmlineErrorCode.INSTANTIATION_FAILED,
                "Core Wasm instantiation failed: ${failure.message ?: failure}",
            )
        }
        return when (created) {
            is WasmlineCallResult.Failure -> created

            is WasmlineCallResult.Success -> {
                session.attach(created.value)
                val moduleWasClosed = lock.withLock {
                    if (closed) {
                        true
                    } else {
                        sessions += session
                        false
                    }
                }
                if (moduleWasClosed) {
                    session.close()
                    return coreFailure(WasmlineErrorCode.SESSION_CLOSED, "Core Wasm module was closed during instantiation.")
                }
                WasmlineCallResult.Success(session)
            }
        }
    }

    override fun close() {
        val toClose = lock.withLock {
            if (closed) {
                null
            } else {
                closed = true
                sessions.toList().also { sessions.clear() }
            }
        } ?: return
        toClose.forEach(CoreWasmSessionImpl::close)
        backend.close()
        owner.close()
    }

    private fun CoreWasmSessionOptions.withAbiDefaults(metadata: RawAbiMetadata?): CoreWasmSessionOptions {
        if (metadata == null) return this
        val signatures = metadata.exports.mapNotNull { export -> export.signature?.let { export.name to it } }.toMap() + exportSignatures
        val memoryName = if (memoryExportName == RawAbiMetadata.DEFAULT_MEMORY_EXPORT) metadata.memoryExport else memoryExportName
        return copy(exportSignatures = signatures, memoryExportName = memoryName)
    }
}

/**
 * Enforces public session validation, concurrency, reentry, and lifecycle rules.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class CoreWasmSessionImpl(
    override val module: CoreWasmModule,
    internal val options: CoreWasmSessionOptions,
    private val lock: WasmlineHostServiceLock,
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

/**
 * Applies checked public memory operations to a backend memory implementation.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class RawMemoryImpl(
    private val backend: CoreWasmBackendMemory,
    private val beginOperation: () -> WasmlineFailure?,
    private val endOperation: () -> Unit,
) : RawMemory {
    override fun byteSize(): WasmlineCallResult<Long> = memoryOperation { backend.byteSize }

    override fun pageCount(): WasmlineCallResult<Long> = memoryOperation { backend.pageCount }

    override fun read(offset: Long, length: Int): WasmlineCallResult<ByteArray> = memoryOperation {
        val failure = checkMemoryInputRange(offset, length)
        if (failure != null) throw RawMemoryRangeException(failure)
        ByteArray(length).also { destination ->
            backend.readInto(destination, 0, offset, length)
        }
    }

    override fun readInto(destination: ByteArray, destinationOffset: Int, sourceOffset: Long, length: Int): WasmlineCallResult<Unit> {
        checkArrayRange(destination.size, destinationOffset, length)?.let { return WasmlineCallResult.Failure(it) }
        return memoryOperation {
            val failure = checkMemoryInputRange(sourceOffset, length)
            if (failure != null) throw RawMemoryRangeException(failure)
            backend.readInto(destination, destinationOffset, sourceOffset, length)
        }
    }

    override fun write(offset: Long, bytes: ByteArray): WasmlineCallResult<Unit> = memoryOperation {
        val failure = checkMemoryInputRange(offset, bytes.size)
        if (failure != null) throw RawMemoryRangeException(failure)
        backend.writeFrom(bytes, 0, offset, bytes.size)
    }

    override fun writeFrom(source: ByteArray, sourceOffset: Int, destinationOffset: Long, length: Int): WasmlineCallResult<Unit> {
        checkArrayRange(source.size, sourceOffset, length)?.let { return WasmlineCallResult.Failure(it) }
        return memoryOperation {
            val failure = checkMemoryInputRange(destinationOffset, length)
            if (failure != null) throw RawMemoryRangeException(failure)
            backend.writeFrom(source, sourceOffset, destinationOffset, length)
        }
    }

    override fun readUtf8(offset: Long, length: Int): WasmlineCallResult<String> = when (val bytes = read(offset, length)) {
        is WasmlineCallResult.Failure -> bytes
        is WasmlineCallResult.Success -> WasmlineCallResult.Success(bytes.value.decodeToString())
    }

    override fun grow(deltaPages: Long): WasmlineCallResult<Long> {
        if (deltaPages < 0) return coreFailure(WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS, "Memory growth must not be negative.")
        return memoryOperation { backend.grow(deltaPages) }
    }

    private inline fun <T> memoryOperation(block: () -> T): WasmlineCallResult<T> {
        beginOperation()?.let { return WasmlineCallResult.Failure(it) }
        return try {
            WasmlineCallResult.Success(block())
        } catch (failure: RawMemoryRangeException) {
            WasmlineCallResult.Failure(failure.failure)
        } catch (failure: CoreWasmBackendFailure) {
            WasmlineCallResult.Failure(failure.failure)
        } catch (failure: Throwable) {
            coreFailure(
                WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
                "Core Wasm memory operation failed: ${failure.message ?: failure}",
            )
        } finally {
            endOperation()
        }
    }
}

/**
 * Carries a checked memory-range failure through an internal operation block.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private class RawMemoryRangeException(val failure: WasmlineFailure) : RuntimeException(failure.message)

/**
 * Allocates process-local identifiers for isolated Core Wasm sessions.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
private object CoreWasmSessionIds {
    private val lock = WasmlineHostServiceLock()
    private var nextId = 0L

    fun next(moduleKey: String): String = lock.withLock {
        nextId += 1
        "$moduleKey#raw-session-$nextId"
    }
}

private fun mergeExports(reflected: List<RawExport>, declared: List<RawExport>): List<RawExport> {
    val result = linkedMapOf<String, RawExport>()
    reflected.forEach { result[it.name] = it }
    declared.forEach { declaration ->
        val current = result[declaration.name]
        result[declaration.name] = if (current == null) {
            declaration
        } else {
            current.copy(
                signature = declaration.signature ?: current.signature,
            )
        }
    }
    return result.values.toList()
}

private fun validateDeclaredImports(registered: Collection<RawImport>, declared: Collection<RawImportDeclaration>): WasmlineFailure? {
    if (declared.isEmpty()) return null
    val registeredByName = registered.associateBy { it.module to it.name }
    val declaredByName = declared.associateBy { it.module to it.name }
    val missing = declaredByName.keys.firstOrNull { it !in registeredByName }
    if (missing != null) {
        return WasmlineFailure(
            WasmlineErrorCode.IMPORT_MISSING,
            "Required raw import '${missing.first}.${missing.second}' is not registered.",
        )
    }
    val extra = registeredByName.keys.firstOrNull { it !in declaredByName }
    if (extra != null) {
        return WasmlineFailure(
            WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH,
            "Raw import '${extra.first}.${extra.second}' is not declared by rawAbi metadata.",
        )
    }
    val mismatch = declaredByName.entries.firstOrNull { (key, declaration) ->
        registeredByName.getValue(key).signature != declaration.signature
    }
    return mismatch?.let { (key, _) ->
        WasmlineFailure(
            WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH,
            "Raw import '${key.first}.${key.second}' does not match its rawAbi signature.",
        )
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

private fun checkMemoryInputRange(offset: Long, length: Int): WasmlineFailure? {
    if (offset < 0 || length < 0) {
        return WasmlineFailure(
            WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
            "Linear memory range offset=$offset length=$length is invalid.",
        )
    }
    return null
}

private fun checkArrayRange(size: Int, offset: Int, length: Int): WasmlineFailure? {
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        return WasmlineFailure(
            WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
            "ByteArray range offset=$offset length=$length exceeds size=$size.",
        )
    }
    return null
}

internal fun coreFailure(code: WasmlineErrorCode, message: String, details: ByteArray? = null): WasmlineCallResult.Failure =
    WasmlineCallResult.Failure(WasmlineFailure(code, message, details))
