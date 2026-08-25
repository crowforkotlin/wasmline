package crow.wasmline.plugin.core.diagnostics

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val FIXTURE_WASM_TOOLS_VERSION = "0.0.0-test"

class ArtifactDiagnosticsTest {
    @Test
    fun `describes core cwasm without changing its execution model`() {
        val diagnostic = WasmlineArtifactDiagnostics.describe(
            artifact(
                type = WasmlineArtifactType.CWASM,
                targetCpu = "x86_64",
                targetOs = "linux",
                compiler = "wasmtime-12.3.4",
            ),
        )

        assertEquals(WasmlineArtifactFormat.CWASM, diagnostic.format)
        assertEquals(WasmlineExecutionModel.CORE_WASM, diagnostic.executionModel)
        assertEquals(WasmlineArtifactBackend.CRANELIFT, diagnostic.backend)
        assertEquals("x86_64-linux", diagnostic.target)
        assertEquals("12.3.4", diagnostic.wasmtimeVersion)
    }

    @Test
    fun `renders component pwasm as pulley with portable target`() {
        val line = WasmlineArtifactDiagnostics.format(
            artifact(
                type = WasmlineArtifactType.PWASM,
                targetCpu = "pulley64",
                compiler = "wasmtime-12.3.4",
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            ),
        )

        assertEquals(
            "artifact=plugin.pwasm format=PWASM executionModel=COMPONENT_MODEL " +
                "backend=PULLEY target=pulley64 wasmtime=12.3.4",
            line,
        )
    }

    @Test
    fun `raw component remains a raw physical format without a wasmtime version`() {
        val diagnostic = WasmlineArtifactDiagnostics.describe(
            artifact(
                type = WasmlineArtifactType.COMPONENT_WASM,
                compiler = "wasm-tools-$FIXTURE_WASM_TOOLS_VERSION",
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            ),
        )

        assertEquals(WasmlineArtifactFormat.RAW_WASM, diagnostic.format)
        assertEquals(WasmlineArtifactBackend.RAW, diagnostic.backend)
        assertEquals("unspecified", diagnostic.target)
        assertNull(diagnostic.wasmtimeVersion)
        assertEquals("n/a", diagnostic.render().substringAfterLast('='))
    }

    @Test
    fun `extracts only an exact wasmtime semantic version`() {
        val diagnostic = WasmlineArtifactDiagnostics.describe(
            artifact(type = WasmlineArtifactType.CWASM, compiler = "wasmtime-12.3.4-dev"),
        )

        assertNull(diagnostic.wasmtimeVersion)
    }

    private fun artifact(
        type: WasmlineArtifactType,
        targetCpu: String? = null,
        targetOs: String? = null,
        compiler: String? = null,
        executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM,
    ): WasmlineArtifact = WasmlineArtifact(
        type = type,
        url = when (type) {
            WasmlineArtifactType.WASM -> "plugin.wasm"
            WasmlineArtifactType.COMPONENT_WASM -> "plugin.component.wasm"
            WasmlineArtifactType.CWASM -> "plugin.cwasm"
            WasmlineArtifactType.PWASM -> "plugin.pwasm"
        },
        sha256 = "a".repeat(64),
        targetCpu = targetCpu,
        targetOs = targetOs,
        targetCompilerVersion = compiler,
        executionModel = executionModel,
        invocationProtocol = if (executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
            WasmlineInvocationProtocol.COMPONENT_EXPORT
        } else {
            WasmlineInvocationProtocol.WASMLINE_SERVICE
        },
        exportName = if (executionModel == WasmlineExecutionModel.COMPONENT_MODEL) "plugin/invoke" else null,
    )
}
