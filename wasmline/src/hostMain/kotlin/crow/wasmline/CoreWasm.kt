package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Declares the Core WebAssembly feature set one runtime backend supports.
 *
 * This is a statement of the features the Wasmline Core ABI exposes for that
 * backend, not a runtime probe of the host engine or browser. Instantiation
 * compares it with `runtimeContract.rawAbi.requiredFeatures` and fails with
 * `WASM_FEATURE_UNSUPPORTED` before creating a session. Current backends
 * declare `multiValue`, `i64`, `bulkMemory`, and `referenceTypes` as supported,
 * and `simd` and `threads` as unsupported.
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
    /**
     * Current instance memory when one is visible to the import callback.
     *
     * Availability is backend-defined. The browser backend builds its import
     * functions before the instance exists, so an import invoked during
     * instantiation, including the guest start function, receives `null` even
     * when the module exports a memory; calls after instantiation receive the
     * configured memory.
     */
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
 *   Duplicate `(module, name)` pairs are rejected with
 *   `IMPORT_SIGNATURE_MISMATCH`. When `runtimeContract.rawAbi.imports` declares
 *   imports, a missing entry returns `IMPORT_MISSING`, and an undeclared entry
 *   or a signature difference returns `IMPORT_SIGNATURE_MISMATCH`, all before
 *   the instance exists.
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
 * Each range is validated against the current memory size before a view is
 * created or bytes are copied.
 *
 * The browser backend addresses memory with a signed 32-bit offset, so each
 * operation rejects an offset outside `0..Int.MAX_VALUE`; the length limit and
 * the memory-size limit are checked separately. A near-2 GiB length is neither
 * exercised nor claimed to work.
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

    /** Feature set this backend declares for [WasmlineArtifactDescriptor] validation. */
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
 * A session accepts one active call at a time. Concurrent calls fail with
 * `CONCURRENT_ACCESS`, and callback reentry fails with `REENTRANT_CALL`
 * instead of blocking.
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

    /**
     * Invokes a scalar Core Wasm function export.
     *
     * Returned values are checked against the export's declared result
     * signature. A wrong result count returns `ARGUMENT_COUNT_MISMATCH`, a
     * wrong result type returns `ARGUMENT_TYPE_MISMATCH`, and a genuine guest
     * trap returns `WASM_TRAP`. A failing host import is reported with its own
     * code, such as `IMPORT_HANDLER_FAILED`, and takes precedence over trap
     * classification.
     */
    fun invoke(exportName: String, arguments: List<RawValue> = emptyList()): WasmlineCallResult<List<RawValue>>

    /** Releases this instance. This operation is idempotent. */
    override fun close()
}

internal fun coreFailure(code: WasmlineErrorCode, message: String, details: ByteArray? = null): WasmlineCallResult.Failure =
    WasmlineCallResult.Failure(WasmlineFailure(code, message, details))
