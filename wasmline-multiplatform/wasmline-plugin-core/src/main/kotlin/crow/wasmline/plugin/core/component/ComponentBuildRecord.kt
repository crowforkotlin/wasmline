package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineTypedComponentContract
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.toolchain.FileDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Records a verified raw Component used only as native AOT build input.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class ComponentBuildRecord(
    val componentFile: String,
    val embeddedFile: String,
    val inspectedWitFile: String? = null,
    val world: String? = null,
    val witPackage: String? = null,
    val invocationProtocol: WasmlineInvocationProtocol,
    val exportName: String? = null,
    val codec: String? = null,
    val serviceProtocolVersion: String? = null,
    val componentSha256: String,
    val witSha256: String,
    val adapterSha256: String? = null,
    val adapterVersion: String? = null,
    val witBindgenVersion: String? = null,
    val wasmToolsVersion: String,
) {
    /** Resolves the raw Component artifact relative to its build directory. */
    fun resolveComponentFile(directory: File): File = resolveChild(directory, componentFile)

    fun validateComponentFile(directory: File): File {
        val component = resolveComponentFile(directory)
        require(component.isFile) { "Component result file does not exist: " + component.absolutePath }
        val actualDigest = FileDigest.sha256Hex(component)
        require(actualDigest.equals(componentSha256, ignoreCase = true)) {
            "Component result SHA-256 mismatch for " + component.absolutePath +
                ": expected " + componentSha256 + ", actual " + actualDigest + "."
        }
        val descriptorError = crow.wasmline.WasmlineArtifactDescriptor(
            path = component.absolutePath,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = invocationProtocol,
            exportName = exportName,
        ).validationError()
        require(descriptorError == null) { "Invalid Component build record: $descriptorError" }
        return component
    }

    /** Converts raw Component metadata into the package-wide runtime contract. */
    fun runtimeContract(): WasmlineRuntimeContract = WasmlineRuntimeContract(
        executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
        invocationProtocol = invocationProtocol,
        exportName = exportName,
        contractMetadata = buildMap {
            world?.let { put(WasmlineTypedComponentContract.METADATA_WORLD, it) }
            put(WasmlineTypedComponentContract.METADATA_WIT_SHA256, witSha256)
            adapterSha256?.let { put("component.adapter.sha256", it) }
            adapterVersion?.let { put("component.adapter.version", it) }
            witBindgenVersion?.let { put("component.wit-bindgen.version", it) }
            put("component.wasm-tools.version", wasmToolsVersion)
            if (invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE) {
                put(WasmlineComponentServiceContract.METADATA_WIT_PACKAGE, WasmlineComponentServiceContract.WIT_PACKAGE)
                put(WasmlineComponentServiceContract.METADATA_PROFILE, WasmlineComponentServiceContract.PROFILE)
                put(
                    WasmlineComponentServiceContract.METADATA_CODEC,
                    requireNotNull(codec) { "Wasmline Service build record is missing codec metadata." },
                )
                put(
                    WasmlineComponentServiceContract.METADATA_VERSION,
                    requireNotNull(serviceProtocolVersion) { "Wasmline Service build record is missing version metadata." },
                )
            } else {
                witPackage?.let { put(WasmlineTypedComponentContract.METADATA_WIT_PACKAGE, it) }
            }
        },
    )

    private fun resolveChild(directory: File, relativePath: String): File {
        val root = directory.toPath().toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Component result path escapes its output directory: " + relativePath }
        return resolved.toFile()
    }
}

/**
 * Reads and writes raw Component build-stage records.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */

@InternalWasmlineToolingApi
object ComponentBuildRecords {
    const val FILE_NAME = "component-result.json"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun write(result: ComponentizeResult, outputFile: File): ComponentBuildRecord {
        val directory = outputFile.parentFile ?: error("Component result file requires a parent directory.")
        directory.mkdirs()
        val record = ComponentBuildRecord(
            componentFile = result.componentWasm.relativeTo(directory).invariantSeparatorsPath,
            embeddedFile = result.embeddedWasm.relativeTo(directory).invariantSeparatorsPath,
            inspectedWitFile = result.inspectedWit?.relativeTo(directory)?.invariantSeparatorsPath,
            world = result.world,
            witPackage = result.witPackage,
            invocationProtocol = result.invocationProtocol,
            exportName = result.exportName,
            codec = result.codec,
            serviceProtocolVersion = result.serviceProtocolVersion,
            componentSha256 = result.componentSha256,
            witSha256 = result.witSha256,
            adapterSha256 = result.adapterSha256,
            adapterVersion = result.adapterVersion,
            witBindgenVersion = result.witBindgenVersion,
            wasmToolsVersion = result.wasmToolsVersion,
        )
        outputFile.writeText(json.encodeToString(record))
        return record
    }

    fun read(inputFile: File): ComponentBuildRecord {
        require(inputFile.isFile) { "Component build result does not exist: " + inputFile.absolutePath }
        return json.decodeFromString<ComponentBuildRecord>(inputFile.readText())
    }
}
