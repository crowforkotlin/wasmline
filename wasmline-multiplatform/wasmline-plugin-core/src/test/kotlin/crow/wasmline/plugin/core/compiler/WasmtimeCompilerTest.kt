package crow.wasmline.plugin.core.compiler

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WasmtimeCompilerTest {
    private val compiler = WasmtimeCompiler()

    @Test
    fun pulleyTargetsUsePortableMetadata() {
        listOf(
            "pulley32" to "pulley32",
            "pulley32-unknown-unknown-elf" to "pulley32",
            "pulley64" to "pulley64",
            "pulley64-unknown-unknown-elf" to "pulley64",
        ).forEach { (target, expectedCpu) ->
            val (cpu, os) = compiler.parseTarget(target)

            assertEquals(expectedCpu, cpu)
            assertNull(os)
        }
    }

    @Test
    fun coreTargetsKeepPlatformMetadata() {
        assertEquals("x86_64" to "linux", compiler.parseTarget("x86_64-linux"))
        assertEquals("aarch64" to "android", compiler.parseTarget("aarch64-android"))
        assertEquals("aarch64" to "ios", compiler.parseTarget("aarch64-apple-ios"))
        assertEquals("x86_64" to "windows", compiler.parseTarget("x86_64-windows"))
    }

    @Test
    fun fullCompilerDiscoveryNeverReturnsWasmtimeMin() {
        val root = createTempDirectory("wasmtime-compiler-discovery").toFile()
        try {
            val directory = File(root, "wasmtime-v47.0.2-x86_64-linux").apply { mkdirs() }
            val minimal = executable(File(directory, executableName(minimal = true)))
            val full = executable(File(directory, executableName(minimal = false)))

            assertEquals(minimal.canonicalFile, WasmtimeCompiler.findWasmtimeInDirectory(root)?.canonicalFile)
            assertEquals(full.canonicalFile, WasmtimeCompiler.findWasmtimeCompilerInDirectory(root)?.canonicalFile)
            assertTrue(WasmtimeCompiler.findWasmtimeCompilerExecutable(directory) != minimal)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fullCompilerDiscoveryReturnsNullForMinimalOnlyDirectory() {
        val root = createTempDirectory("wasmtime-min-only").toFile()
        try {
            executable(File(root, executableName(minimal = true)))

            assertNull(WasmtimeCompiler.findWasmtimeCompilerInDirectory(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun executableName(minimal: Boolean): String {
        val suffix = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        return "wasmtime" + (if (minimal) "-min" else "") + suffix
    }

    private fun executable(file: File): File = file.apply {
        writeText("test")
        if (!System.getProperty("os.name").lowercase().contains("win")) setExecutable(true)
    }
}
