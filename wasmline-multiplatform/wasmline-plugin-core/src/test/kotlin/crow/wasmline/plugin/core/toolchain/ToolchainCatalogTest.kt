package crow.wasmline.plugin.core.toolchain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolchainCatalogTest {
    @Test
    fun everyLockedAssetHasImmutableMetadata() {
        val assets = ToolchainCatalog.allAssets()

        assertTrue(assets.isNotEmpty())
        assets.forEach { asset ->
            assertTrue(asset.downloadUrl.startsWith("https://"))
            assertTrue(asset.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(asset.version.isNotBlank())
            assertTrue(asset.entryFileName.isNotBlank())
        }
    }

    @Test
    fun normalizesRiscvPlatformNamesPerTool() {
        assertEquals(
            "riscv64gc-linux",
            ToolchainCatalog.requireAsset(WasmlineTool.WIT_BINDGEN, platform = "riscv64-linux").platform,
        )
        assertEquals(
            "riscv64-linux",
            ToolchainCatalog.requireAsset(WasmlineTool.WASM_TOOLS, platform = "riscv64gc-linux").platform,
        )
    }

    @Test
    fun rejectsUnlockedVersions() {
        assertFailsWith<IllegalStateException> {
            ToolchainCatalog.requireAsset(WasmlineTool.WASM_TOOLS, version = "latest", platform = "x86_64-linux")
        }
    }
}
