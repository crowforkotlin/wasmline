package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.plugin.core.toolchain.FileDigest
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Inputs required to turn a compiled Core Wasm guest into a Component. */
data class ComponentizeRequest(
    val coreWasm: File,
    val witPath: File,
    val wasiPreview1Adapter: File,
    val outputDirectory: File,
    val productName: String,
    val world: String? = null,
    val exportName: String = WasmlineComponentRpcContract.DEFAULT_EXPORT,
    val codec: String = WasmlineComponentRpcContract.DEFAULT_CODEC,
    val rpcProtocolVersion: String = WasmlineComponentRpcContract.VERSION,
    val wasmToolsVersion: String = ToolchainCatalog.WASM_TOOLS_VERSION,
    val witBindgenVersion: String? = null,
    val adapterVersion: String? = null,
    val validate: Boolean = true,
    val writeInspectedWit: Boolean = true,
)

/** Inputs required to validate and package an already-finished Component. */
data class ExistingComponentRequest(
    val componentWasm: File,
    val outputDirectory: File,
    val productName: String,
    val witPath: File? = null,
    val world: String? = null,
    val exportName: String = WasmlineComponentRpcContract.DEFAULT_EXPORT,
    val codec: String = WasmlineComponentRpcContract.DEFAULT_CODEC,
    val rpcProtocolVersion: String = WasmlineComponentRpcContract.VERSION,
    val wasmToolsVersion: String = ToolchainCatalog.WASM_TOOLS_VERSION,
    val validate: Boolean = true,
    val writeInspectedWit: Boolean = true,
)

/** Immutable description of a finished raw Component Wasm build. */
data class ComponentizeResult(
    val coreWasm: File,
    val embeddedWasm: File,
    val componentWasm: File,
    val inspectedWit: File?,
    val world: String?,
    val exportName: String,
    val codec: String,
    val rpcProtocolVersion: String,
    val componentSha256: String,
    val witSha256: String,
    val adapterSha256: String?,
    val adapterVersion: String?,
    val witBindgenVersion: String?,
    val wasmToolsVersion: String,
) {
    /** Creates the portable raw Component artifact stored in a Wasmline package. */
    fun toArtifact(): WasmlineArtifact = ComponentBuildRecord(
        componentFile = componentWasm.name,
        embeddedFile = embeddedWasm.name,
        inspectedWitFile = inspectedWit?.name,
        world = world,
        exportName = exportName,
        codec = codec,
        rpcProtocolVersion = rpcProtocolVersion,
        componentSha256 = componentSha256,
        witSha256 = witSha256,
        adapterSha256 = adapterSha256,
        adapterVersion = adapterVersion,
        witBindgenVersion = witBindgenVersion,
        wasmToolsVersion = wasmToolsVersion,
    ).toArtifact(componentWasm.parentFile)
}

/**
 * Shared Component build implementation used by Gradle and the CLI.
 *
 * Kotlin compilation remains an upstream concern; this pipeline starts from a
 * finished Core Wasm module and owns every wasm-tools step after it.
 */
class ComponentPipeline(
    private val wasmTools: WasmTools,
) {
    /** Embeds WIT, creates a Component, validates it and records its WIT view. */
    fun componentize(request: ComponentizeRequest): ComponentizeResult {
        validateRequest(request)
        val verifiedWasmToolsVersion = wasmTools.verify(request.wasmToolsVersion)
        request.outputDirectory.mkdirs()

        val embeddedWasm = File(request.outputDirectory, request.productName + "-embedded.wasm")
        val componentWasm = File(request.outputDirectory, request.productName + "-component.wasm")
        val inspectedWit = if (request.writeInspectedWit) {
            File(request.outputDirectory, request.productName + "-component.wit")
        } else {
            null
        }

        wasmTools.embedWit(
            witPath = request.witPath,
            inputWasm = request.coreWasm,
            outputWasm = embeddedWasm,
            world = request.world,
        )
        wasmTools.createComponent(
            embeddedWasm = embeddedWasm,
            adapter = request.wasiPreview1Adapter,
            outputComponent = componentWasm,
        )
        if (request.validate) wasmTools.validate(componentWasm)
        inspectedWit?.writeText(wasmTools.inspectWit(componentWasm))

        return ComponentizeResult(
            coreWasm = request.coreWasm,
            embeddedWasm = embeddedWasm,
            componentWasm = componentWasm,
            inspectedWit = inspectedWit,
            world = request.world,
            exportName = request.exportName,
            codec = request.codec,
            rpcProtocolVersion = request.rpcProtocolVersion,
            componentSha256 = FileDigest.sha256Hex(componentWasm),
            witSha256 = hashWitPath(request.witPath),
            adapterSha256 = FileDigest.sha256Hex(request.wasiPreview1Adapter),
            adapterVersion = request.adapterVersion,
            witBindgenVersion = request.witBindgenVersion,
            wasmToolsVersion = verifiedWasmToolsVersion,
        )
    }

    /** Validates and describes a Component supplied directly by a caller. */
    fun describeExisting(request: ExistingComponentRequest): ComponentizeResult {
        require(request.componentWasm.isFile) {
            "Component Wasm does not exist: " + request.componentWasm.absolutePath
        }
        validateProductName(request.productName)
        request.witPath?.let { witPath ->
            require(witPath.exists()) { "WIT path does not exist: " + witPath.absolutePath }
        }
        validateContract(request.exportName, request.codec, request.rpcProtocolVersion)
        val verifiedWasmToolsVersion = wasmTools.verify(request.wasmToolsVersion)
        request.outputDirectory.mkdirs()

        val component = File(request.outputDirectory, request.productName + "-component.wasm")
        if (request.componentWasm.canonicalFile != component.canonicalFile) {
            Files.copy(
                request.componentWasm.toPath(),
                component.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        if (request.validate) wasmTools.validate(component)
        val inspectedText = wasmTools.inspectWit(component)
        val inspectedWit = if (request.writeInspectedWit) {
            File(request.outputDirectory, request.productName + "-component.wit").apply {
                writeText(inspectedText)
            }
        } else {
            null
        }
        return ComponentizeResult(
            coreWasm = component,
            embeddedWasm = component,
            componentWasm = component,
            inspectedWit = inspectedWit,
            world = request.world,
            exportName = request.exportName,
            codec = request.codec,
            rpcProtocolVersion = request.rpcProtocolVersion,
            componentSha256 = FileDigest.sha256Hex(component),
            witSha256 = request.witPath?.let(::hashWitPath) ?: sha256Hex(inspectedText.encodeToByteArray()),
            adapterSha256 = null,
            adapterVersion = null,
            witBindgenVersion = null,
            wasmToolsVersion = verifiedWasmToolsVersion,
        )
    }

    private fun validateRequest(request: ComponentizeRequest) {
        require(request.coreWasm.isFile) { "Core Wasm input does not exist: " + request.coreWasm.absolutePath }
        require(request.witPath.exists()) { "WIT path does not exist: " + request.witPath.absolutePath }
        require(request.wasiPreview1Adapter.isFile) {
            "WASI Preview 1 adapter does not exist: " + request.wasiPreview1Adapter.absolutePath
        }
        validateProductName(request.productName)
        validateContract(request.exportName, request.codec, request.rpcProtocolVersion)
    }

    private fun validateProductName(productName: String) {
        require(productName.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Component product name may only contain letters, digits, dot, underscore and dash."
        }
    }

    private fun validateContract(exportName: String, codec: String, rpcProtocolVersion: String) {
        require(exportName.isNotBlank()) { "Component export name must not be blank." }
        require(codec.isNotBlank()) { "Component RPC codec must not be blank." }
        require(rpcProtocolVersion.isNotBlank()) { "Component RPC protocol version must not be blank." }
    }

    private fun hashWitPath(path: File): String {
        if (path.isFile) return sha256Hex(normalizedWitBytes(path))
        require(path.isDirectory) { "WIT path does not exist: " + path.absolutePath }
        val digestInput = path.walkTopDown()
            .filter { it.isFile && it.extension.equals("wit", ignoreCase = true) }
            .sortedBy { it.relativeTo(path).invariantSeparatorsPath }
            .toList()
        require(digestInput.isNotEmpty()) { "WIT directory contains no .wit files: " + path.absolutePath }

        val digest = MessageDigest.getInstance("SHA-256")
        digestInput.forEach { file ->
            digest.update(file.relativeTo(path).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(normalizedWitBytes(file))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizedWitBytes(file: File): ByteArray = file.readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .encodeToByteArray()

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
