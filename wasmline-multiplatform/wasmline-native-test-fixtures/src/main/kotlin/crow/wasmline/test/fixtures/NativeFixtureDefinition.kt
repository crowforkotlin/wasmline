package crow.wasmline.test.fixtures

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineRuntimeContract

/**
 * Describes the source form used to construct one internal native test fixture.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
enum class NativeFixtureSourceKind {
    CORE_WAT,
    COMPONENT_WAT,
    COMPONENT_WASM,
    COMPONENT_SERVICE_CORE_WAT,
    RUST_COMPONENT,
}

/**
 * Defines one source fixture and the runtime contract used when compiling it.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
data class NativeFixtureDefinition(
    val id: String,
    val sourceKind: NativeFixtureSourceKind,
    val sourceRelativePath: String? = null,
    val runtimeContract: WasmlineRuntimeContract,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "Fixture ID must use lowercase letters, digits, and dashes: '$id'." }
        require(sourceKind == NativeFixtureSourceKind.COMPONENT_WASM || !sourceRelativePath.isNullOrBlank()) {
            "Fixture '$id' must declare a source path."
        }
    }
}

/**
 * Contains every source fixture required by native AOT runtime tests.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
object NativeFixtureDefinitions {
    val all: List<NativeFixtureDefinition> = listOf(
        coreRawExport("raw-export-basic", "core/raw-export-basic.wat"),
        coreRawExport("raw-export-import-memory", "core/raw-export-import-memory.wat"),
        componentExport("component-direct", NativeFixtureSourceKind.COMPONENT_WASM),
        componentExport("component-typed-host", NativeFixtureSourceKind.COMPONENT_WAT, "component/typed-host-import.component.wat"),
        componentExport(
            "component-typed-shapes",
            NativeFixtureSourceKind.COMPONENT_WAT,
            "component/typed-host-import-shapes.component.wat",
        ),
        componentExport("component-typed-flags", NativeFixtureSourceKind.COMPONENT_WAT, "component/typed-host-import-flags.component.wat"),
        componentExport(
            "component-typed-option-result",
            NativeFixtureSourceKind.COMPONENT_WAT,
            "component/typed-host-import-option-result.component.wat",
        ),
        componentExport(
            "component-typed-string",
            NativeFixtureSourceKind.COMPONENT_WAT,
            "component/typed-host-import-string.component.wat",
        ),
        componentExport(
            "component-typed-string-input",
            NativeFixtureSourceKind.COMPONENT_WAT,
            "component/typed-host-import-string-input.component.wat",
        ),
        componentExport(
            "component-typed-variant-enum",
            NativeFixtureSourceKind.COMPONENT_WAT,
            "component/typed-host-import-variant-enum.component.wat",
        ),
        NativeFixtureDefinition(
            id = "component-service",
            sourceKind = NativeFixtureSourceKind.COMPONENT_SERVICE_CORE_WAT,
            sourceRelativePath = "service/component-rpc-core.wat",
            runtimeContract = WasmlineRuntimeContract(
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
                exportName = WasmlineComponentServiceContract.DEFAULT_EXPORT,
                contractMetadata = mapOf(
                    WasmlineComponentServiceContract.METADATA_PROFILE to WasmlineComponentServiceContract.PROFILE,
                    WasmlineComponentServiceContract.METADATA_CODEC to WasmlineComponentServiceContract.DEFAULT_CODEC,
                    WasmlineComponentServiceContract.METADATA_VERSION to WasmlineComponentServiceContract.VERSION,
                ),
            ),
        ),
        NativeFixtureDefinition(
            id = "component-resource",
            sourceKind = NativeFixtureSourceKind.RUST_COMPONENT,
            sourceRelativePath = "resource/Cargo.toml",
            runtimeContract = WasmlineRuntimeContract(
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            ),
        ),
    )

    /** Returns one fixture definition by its stable ID. */
    fun require(id: String): NativeFixtureDefinition = all.singleOrNull { definition -> definition.id == id }
        ?: error("Unknown native fixture '$id'.")

    private fun coreRawExport(id: String, source: String): NativeFixtureDefinition = NativeFixtureDefinition(
        id = id,
        sourceKind = NativeFixtureSourceKind.CORE_WAT,
        sourceRelativePath = source,
        runtimeContract = WasmlineRuntimeContract(
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "add",
        ),
    )

    private fun componentExport(id: String, sourceKind: NativeFixtureSourceKind, source: String? = null): NativeFixtureDefinition =
        NativeFixtureDefinition(
            id = id,
            sourceKind = sourceKind,
            sourceRelativePath = source,
            runtimeContract = WasmlineRuntimeContract(
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                exportName = if (id == "component-direct") "add" else "run",
            ),
        )
}
