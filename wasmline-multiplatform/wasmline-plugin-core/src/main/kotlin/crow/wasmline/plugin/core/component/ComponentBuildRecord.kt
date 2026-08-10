package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.toolchain.FileDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Serializable boundary between componentization and package assembly. */
@Serializable
data class ComponentBuildRecord(
    val componentFile: String,
    val embeddedFile: String,
    val inspectedWitFile: String? = null,
    val world: String? = null,
    val exportName: String = WasmlineComponentRpcContract.DEFAULT_EXPORT,
    val codec: String = WasmlineComponentRpcContract.DEFAULT_CODEC,
    val rpcProtocolVersion: String = WasmlineComponentRpcContract.VERSION,
    val componentSha256: String,
    val witSha256: String,
    val adapterSha256: String? = null,
    val adapterVersion: String? = null,
    val witBindgenVersion: String? = null,
    val wasmToolsVersion: String,
) {
    /** Resolves the raw Component artifact relative to its build directory. */
    fun resolveComponentFile(directory: File): File = resolveChild(directory, componentFile)

    fun toArtifact(directory: File): WasmlineArtifact {
        val component = resolveComponentFile(directory)
        require(component.isFile) { "Component result file does not exist: " + component.absolutePath }
        val actualDigest = FileDigest.sha256Hex(component)
        require(actualDigest.equals(componentSha256, ignoreCase = true)) {
            "Component result SHA-256 mismatch for " + component.absolutePath +
                ": expected " + componentSha256 + ", actual " + actualDigest + "."
        }
        return WasmlineArtifact(
            type = WasmlineArtifactType.COMPONENT_WASM,
            url = componentFile,
            sha256 = componentSha256,
            targetCompilerVersion = wasmToolsVersion,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            exportName = exportName,
            contractMetadata = buildMap {
                world?.let { put("component.world", it) }
                put("component.wit.sha256", witSha256)
                adapterSha256?.let { put("component.adapter.sha256", it) }
                adapterVersion?.let { put("component.adapter.version", it) }
                witBindgenVersion?.let { put("component.wit-bindgen.version", it) }
                put("component.wasm-tools.version", wasmToolsVersion)
                put(WasmlineComponentRpcContract.METADATA_WIT_PACKAGE, WasmlineComponentRpcContract.WIT_PACKAGE)
                put(WasmlineComponentRpcContract.METADATA_PROFILE, WasmlineComponentRpcContract.PROFILE)
                put(WasmlineComponentRpcContract.METADATA_CODEC, codec)
                put(WasmlineComponentRpcContract.METADATA_VERSION, rpcProtocolVersion)
            },
        )
    }

    private fun resolveChild(directory: File, relativePath: String): File {
        val root = directory.toPath().toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Component result path escapes its output directory: " + relativePath }
        return resolved.toFile()
    }
}

/** Reads and writes Component build stage records. */
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
            exportName = result.exportName,
            codec = result.codec,
            rpcProtocolVersion = result.rpcProtocolVersion,
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
        return json.decodeFromString(inputFile.readText())
    }
}
