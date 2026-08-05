package crow.wasmline.loader.internal

import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies local package artifact selection for browser and native hosts. */
class WasmlineLocalPackageResolutionTest {

    @Test
    fun `browser host prefers raw wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "plugin.pwasm",
                    sha256 = "pwasm",
                    targetCpu = "pulley64",
                    is64Bit = true,
                ),
                WasmlineArtifact(
                    type = WasmlineArtifactType.WASM,
                    url = "plugin.wasm",
                    sha256 = "wasm",
                    targetCpu = "wasmjs",
                    targetOs = "browser",
                    is64Bit = true,
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "browser",
                cpu = "wasmjs",
                is64Bit = true,
            ),
        )

        assertEquals("plugin.wasm", selected?.url)
    }

    @Test
    fun `native host ignores raw wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.WASM,
                    url = "plugin.wasm",
                    sha256 = "wasm",
                    targetCpu = "wasmjs",
                    targetOs = "browser",
                    is64Bit = true,
                ),
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "plugin.pwasm",
                    sha256 = "pwasm",
                    targetCpu = "pulley64",
                    is64Bit = true,
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "linux",
                cpu = "x86_64",
                is64Bit = true,
            ),
        )

        assertEquals("plugin.pwasm", selected?.url)
    }

    @Test
    fun `native host accepts a raw Component Wasm artifact`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.COMPONENT_WASM,
                    url = "plugin.component.wasm",
                    sha256 = "component",
                    executionModel = crow.wasmline.WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = crow.wasmline.WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "plugin/invoke",
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "linux",
                cpu = "x86_64",
                is64Bit = true,
            ),
        )

        assertEquals("plugin.component.wasm", selected?.url)
    }

    @Test
    fun `browser host rejects raw Component Wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.COMPONENT_WASM,
                    url = "plugin.component.wasm",
                    sha256 = "component",
                    executionModel = crow.wasmline.WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = crow.wasmline.WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "plugin/invoke",
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "browser",
                cpu = "wasmjs",
                is64Bit = true,
            ),
        )

        assertEquals(null, selected)
    }
}
