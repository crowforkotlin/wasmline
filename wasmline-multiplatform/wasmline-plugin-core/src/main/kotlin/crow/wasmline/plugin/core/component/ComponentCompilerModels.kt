package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.serialization.Serializable
import java.io.File

/** Selects the native execution backend without introducing another artifact format. */
@Serializable
@InternalWasmlineToolingApi
enum class ComponentAotBackend(val artifactType: WasmlineArtifactType, val fileExtension: String) {
    CRANELIFT(WasmlineArtifactType.CWASM, "cwasm"),
    PULLEY(WasmlineArtifactType.PWASM, "pwasm"),
}

/** Wasmtime engine profile matched by Wasmline's native runtime assets. */
@Serializable
@InternalWasmlineToolingApi
data class ComponentAotEngineOptions(
    val componentModel: Boolean = true,
    val collector: String = "drc",
    val gc: Boolean = true,
    val gcSupport: Boolean = true,
    val referenceTypes: Boolean = true,
    val functionReferences: Boolean = true,
    val exceptions: Boolean = true,
    val threads: Boolean = false,
    val simd: Boolean = false,
    val relaxedSimd: Boolean = false,
    val concurrencySupport: Boolean = true,
    val maxWasmStack: Long = 512 * 1024,
    val memoryGuardSize: Long = 0,
    val signalsBasedTraps: Boolean = false,
    val optimizationLevel: Int = 0,
    val craneliftDebugVerifier: Boolean = false,
) {
    init {
        require(componentModel) { "The Wasmline Component AOT profile requires component-model=y." }
        require(collector == "drc") { "The Wasmline Component AOT profile requires collector=drc." }
        require(gc) { "The Wasmline Component AOT profile requires gc=y." }
        require(gcSupport) { "The Wasmline Component AOT profile requires gc-support=y." }
        require(referenceTypes) { "The Wasmline Component AOT profile requires reference-types=y." }
        require(functionReferences) { "The Wasmline Component AOT profile requires function-references=y." }
        require(exceptions) { "The Wasmline Component AOT profile requires exceptions=y." }
        require(!threads) { "The Wasmline Component AOT profile requires threads=n." }
        require(!simd) { "The Wasmline Component AOT profile requires simd=n." }
        require(!relaxedSimd) { "The Wasmline Component AOT profile requires relaxed-simd=n." }
        require(concurrencySupport) { "The Wasmline Component AOT profile requires concurrency-support=y." }
        require(maxWasmStack == 512L * 1024) { "The Wasmline Component AOT profile requires max-wasm-stack=524288." }
        require(memoryGuardSize == 0L) { "The Wasmline Component AOT profile requires memory-guard-size=0." }
        require(!signalsBasedTraps) { "The Wasmline Component AOT profile requires signals-based-traps=n." }
        require(optimizationLevel == 0) { "The Wasmline Component AOT profile requires opt-level=0." }
        require(!craneliftDebugVerifier) { "The Wasmline Component AOT profile requires cranelift-debug-verifier=no." }
    }
}

/** Contract metadata copied onto every compiled Component artifact. */
@Serializable
@InternalWasmlineToolingApi
data class ComponentAotArtifactMetadata(
    val invocationProtocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
    val exportName: String? = null,
    val contractMetadata: Map<String, String> = emptyMap(),
) {
    val executionModel: WasmlineExecutionModel
        get() = WasmlineExecutionModel.COMPONENT_MODEL

    init {
        val descriptorError = crow.wasmline.WasmlineArtifactDescriptor(
            path = "component-aot",
            executionModel = executionModel,
            invocationProtocol = invocationProtocol,
            exportName = exportName,
        ).validationError()
        require(descriptorError == null) { "Invalid Component AOT metadata: $descriptorError" }
    }
}

/** One requested Component AOT target and its concrete CWASM or PWASM output. */

@InternalWasmlineToolingApi
data class ComponentAotTarget(val target: String, val backend: ComponentAotBackend, val outputFile: File) {
    init {
        require(target.isNotBlank()) { "Component AOT target must not be blank." }
        require(outputFile.extension.equals(backend.fileExtension, ignoreCase = true)) {
            "${backend.name} Component output must use .${backend.fileExtension}: ${outputFile.path}"
        }
    }
}

/** Inputs for compiling a finished Component Wasm with a compile-capable Wasmtime CLI. */

@InternalWasmlineToolingApi
data class ComponentAotCompileRequest(
    val wasmtimeCompiler: File,
    val inputComponent: File,
    val targets: List<ComponentAotTarget>,
    val wasmtimeVersion: String,
    val engineOptions: ComponentAotEngineOptions = ComponentAotEngineOptions(),
    val artifactMetadata: ComponentAotArtifactMetadata = ComponentAotArtifactMetadata(),
) {
    init {
        require(targets.isNotEmpty()) { "At least one Component AOT target is required." }
        require(wasmtimeVersion.matches(SEMANTIC_VERSION)) {
            "Component AOT Wasmtime version must use x.y.z: $wasmtimeVersion"
        }
    }

    private companion object {
        val SEMANTIC_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    }
}

/** One verified native Component output and the manifest artifact it produces. */

@InternalWasmlineToolingApi
data class ComponentAotCompileOutput(
    val requestedTarget: String,
    val normalizedTarget: String,
    val backend: ComponentAotBackend,
    val outputFile: File,
    val artifact: WasmlineArtifact,
) {
    init {
        require(requestedTarget.isNotBlank()) { "Requested Component AOT target must not be blank." }
        require(normalizedTarget.isNotBlank()) { "Normalized Component AOT target must not be blank." }
        require(outputFile.extension.equals(backend.fileExtension, ignoreCase = true)) {
            "${backend.name} Component output must use .${backend.fileExtension}: ${outputFile.path}"
        }
        require(artifact.type == backend.artifactType) {
            "${backend.name} must produce ${backend.artifactType}, not ${artifact.type}."
        }
        require(artifact.executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
            "Component AOT artifacts must use executionModel=COMPONENT_MODEL."
        }
        require(
            artifact.invocationProtocol == WasmlineInvocationProtocol.COMPONENT_EXPORT ||
                artifact.invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE,
        ) {
            "Component AOT artifacts must use a Component invocation protocol."
        }
    }
}

/** Verified outputs produced for one raw Component input. */

@InternalWasmlineToolingApi
data class ComponentAotCompileResult(
    val inputComponent: File,
    val inputComponentSha256: String,
    val wasmtimeVersion: String,
    val engineOptions: ComponentAotEngineOptions,
    val artifactMetadata: ComponentAotArtifactMetadata,
    val outputs: List<ComponentAotCompileOutput>,
) {
    init {
        require(inputComponentSha256.matches(SHA_256)) { "Component input SHA-256 must contain 64 hexadecimal characters." }
        require(wasmtimeVersion.matches(SEMANTIC_VERSION)) {
            "Component AOT Wasmtime version must use x.y.z: $wasmtimeVersion"
        }
        require(outputs.isNotEmpty()) { "Component AOT compilation must produce at least one output." }
        outputs.forEach { output ->
            require(output.artifact.invocationProtocol == artifactMetadata.invocationProtocol) {
                "Component AOT artifact protocol does not match the compile request."
            }
            require(output.artifact.targetCompilerVersion == "wasmtime-$wasmtimeVersion") {
                "Component AOT artifact compiler version must be wasmtime-$wasmtimeVersion."
            }
            require(output.artifact.exportName == artifactMetadata.exportName) {
                "Component AOT artifact export metadata does not match the compile request."
            }
            require(output.artifact.contractMetadata == artifactMetadata.contractMetadata) {
                "Component AOT artifact contract metadata does not match the compile request."
            }
        }
    }

    private companion object {
        val SEMANTIC_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        val SHA_256 = Regex("[0-9a-fA-F]{64}")
    }
}
