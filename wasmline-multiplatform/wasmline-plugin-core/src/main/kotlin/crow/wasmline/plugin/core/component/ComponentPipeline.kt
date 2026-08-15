package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.component.hostgen.WitParser
import crow.wasmline.plugin.core.component.hostgen.WitSources
import crow.wasmline.plugin.core.toolchain.FileDigest
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Inputs required to turn a compiled Core Wasm guest into a Component. */

@InternalWasmlineToolingApi
data class ComponentizeRequest(
    val coreWasm: File,
    val witPath: File,
    val wasiPreview1Adapter: File,
    val outputDirectory: File,
    val productName: String,
    val world: String? = null,
    val invocationProtocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
    val exportName: String? = null,
    val codec: String? = null,
    val serviceProtocolVersion: String? = null,
    val wasmToolsVersion: String = ToolchainCatalog.WASM_TOOLS_VERSION,
    val witBindgenVersion: String? = null,
    val adapterVersion: String? = null,
    val validate: Boolean = true,
    val writeInspectedWit: Boolean = true,
)

/** Inputs required to validate and package an already-finished Component. */

@InternalWasmlineToolingApi
data class ExistingComponentRequest(
    val componentWasm: File,
    val outputDirectory: File,
    val productName: String,
    val witPath: File? = null,
    val world: String? = null,
    val invocationProtocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
    val exportName: String? = null,
    val codec: String? = null,
    val serviceProtocolVersion: String? = null,
    val wasmToolsVersion: String = ToolchainCatalog.WASM_TOOLS_VERSION,
    val validate: Boolean = true,
    val writeInspectedWit: Boolean = true,
)

/** Immutable description of a finished raw Component Wasm build. */

@InternalWasmlineToolingApi
data class ComponentizeResult(
    val coreWasm: File,
    val embeddedWasm: File,
    val componentWasm: File,
    val inspectedWit: File?,
    val world: String?,
    val witPackage: String? = null,
    val invocationProtocol: WasmlineInvocationProtocol,
    val exportName: String?,
    val codec: String?,
    val serviceProtocolVersion: String?,
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
        witPackage = witPackage,
        invocationProtocol = invocationProtocol,
        exportName = exportName,
        codec = codec,
        serviceProtocolVersion = serviceProtocolVersion,
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

@InternalWasmlineToolingApi
class ComponentPipeline(private val wasmTools: WasmTools) {
    /** Embeds WIT, creates a Component, validates it and records its WIT view. */
    fun componentize(request: ComponentizeRequest): ComponentizeResult {
        validateRequest(request)
        val verifiedWasmToolsVersion = wasmTools.verify(request.wasmToolsVersion)
        if (request.invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE) {
            validateComponentServiceCoreAbi(wasmTools.printCoreModule(request.coreWasm))
        }
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
            witPackage = findWitPackage(request.witPath),
            invocationProtocol = request.invocationProtocol,
            exportName = request.exportName,
            codec = request.codec,
            serviceProtocolVersion = request.serviceProtocolVersion,
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
        validateContract(request.invocationProtocol, request.exportName, request.codec, request.serviceProtocolVersion)
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
            witPackage = request.witPath?.let(::findWitPackage),
            invocationProtocol = request.invocationProtocol,
            exportName = request.exportName,
            codec = request.codec,
            serviceProtocolVersion = request.serviceProtocolVersion,
            componentSha256 = FileDigest.sha256Hex(component),
            witSha256 = request.witPath?.let(::hashWitPath) ?: sha256Text(inspectedText),
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
        validateContract(request.invocationProtocol, request.exportName, request.codec, request.serviceProtocolVersion)
    }

    private fun validateProductName(productName: String) {
        require(productName.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Component product name may only contain letters, digits, dot, underscore and dash."
        }
    }

    private fun findWitPackage(path: File): String? = WitParser.parse(path).packageId

    private fun validateComponentServiceCoreAbi(wat: String) {
        val imports = CORE_IMPORT.findAll(wat).map { it.groupValues[1] to it.groupValues[2] }.toSet()
        val exports = CORE_EXPORT.findAll(wat).map { it.groupValues[1] }.toSet()
        require("host" to "invoke" in imports) {
            "Wasmline Service Core Wasm must import the canonical WIT function host/invoke."
        }
        require("plugin#invoke" in exports) {
            "Wasmline Service Core Wasm must export the canonical WIT function plugin#invoke."
        }
        require("cabi_realloc" in exports && "memory" in exports) {
            "Wasmline Service Core Wasm must export cabi_realloc and memory for Canonical ABI."
        }
        val forbiddenImports = imports.filter { (module, name) ->
            module == "env" && (name.startsWith("bridge_inbound_") || name.startsWith("bridge_outbound_"))
        }
        require(forbiddenImports.isEmpty()) {
            "Wasmline Service Core Wasm contains forbidden Core transport imports: " +
                forbiddenImports.joinToString { (module, name) -> "$module.$name" } + "."
        }
        val forbiddenExports = exports.filter { name ->
            name == "__wasmline_wasi_entry" || name == "__wasmline_wasi_init"
        }
        require(forbiddenExports.isEmpty()) {
            "Wasmline Service Core Wasm contains forbidden Core transport exports: " +
                forbiddenExports.joinToString() + "."
        }
    }

    private fun validateContract(
        invocationProtocol: WasmlineInvocationProtocol,
        exportName: String?,
        codec: String?,
        serviceProtocolVersion: String?,
    ) {
        require(
            invocationProtocol == WasmlineInvocationProtocol.COMPONENT_EXPORT ||
                invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE,
        ) {
            "Component builds require COMPONENT_EXPORT or WASMLINE_SERVICE, not $invocationProtocol."
        }
        when (invocationProtocol) {
            WasmlineInvocationProtocol.COMPONENT_EXPORT -> {
                require(codec == null) { "Typed Component builds cannot declare a Wasmline Service codec." }
                require(serviceProtocolVersion == null) { "Typed Component builds cannot declare a Wasmline Service version." }
                require(exportName == null || exportName.isNotBlank()) { "Component export name must not be blank." }
            }

            WasmlineInvocationProtocol.WASMLINE_SERVICE -> {
                require(exportName == WasmlineComponentServiceContract.DEFAULT_EXPORT) {
                    "Wasmline Service export must be '${WasmlineComponentServiceContract.DEFAULT_EXPORT}'."
                }
                require(!codec.isNullOrBlank()) { "Wasmline Service codec must not be blank." }
                require(!serviceProtocolVersion.isNullOrBlank()) { "Wasmline Service protocol version must not be blank." }
            }
        }
    }

    private fun hashWitPath(path: File): String = WitSources.load(path).sha256

    private fun sha256Text(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val CORE_IMPORT = Regex("""\(import\s+\"([^\"]+)\"\s+\"([^\"]+)\"""")
        val CORE_EXPORT = Regex("""\(export\s+\"([^\"]+)\"""")
    }
}
