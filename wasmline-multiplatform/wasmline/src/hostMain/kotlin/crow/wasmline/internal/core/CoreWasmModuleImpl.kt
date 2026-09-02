package crow.wasmline.internal.core

import crow.wasmline.CoreWasmCapabilities
import crow.wasmline.CoreWasmModule
import crow.wasmline.CoreWasmSession
import crow.wasmline.CoreWasmSessionOptions
import crow.wasmline.RawAbiMetadata
import crow.wasmline.RawExport
import crow.wasmline.RawImport
import crow.wasmline.RawImportDeclaration
import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.coreFailure
import crow.wasmline.internal.runtime.WasmlineRuntimeLock
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Coordinates public module lifecycle with one platform backend module.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal class CoreWasmModuleImpl(private val owner: Wasmline, private val backend: CoreWasmBackendModule) : CoreWasmModule {
    private val lock = WasmlineRuntimeLock()
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
            lock = WasmlineRuntimeLock(),
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
 * Allocates process-local identifiers for isolated Core Wasm sessions.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
private object CoreWasmSessionIds {
    private val lock = WasmlineRuntimeLock()
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
