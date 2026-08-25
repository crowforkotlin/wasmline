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
    fun defaultTargetsIncludePortablePwasmFor32And64BitHosts() {
        assertTrue("pulley32" in WasmtimeCompiler.defaultTargets)
        assertTrue("pulley64" in WasmtimeCompiler.defaultTargets)
        assertTrue(WasmtimeCompiler.defaultTargets.none { compiler.parseTarget(it).second == "ios" })
        assertTrue(WasmtimeCompiler.defaultTargets.none { compiler.parseTarget(it).second == "android" && it.startsWith("armv7") })
        assertTrue(WasmtimeCompiler.defaultTargets.none { compiler.parseTarget(it).second == "android" && it.startsWith("x86-") })
    }

    @Test
    fun parsesExactVersionFromWasmtimeOutput() {
        assertEquals("12.3.4", WasmtimeCompiler.parseWasmtimeVersion("wasmtime 12.3.4 (abc123 2026-01-01)"))
        assertEquals("12.3.4", WasmtimeCompiler.parseWasmtimeVersion("Wasmtime 12.3.4\n"))
        assertNull(WasmtimeCompiler.parseWasmtimeVersion("wasmtime v12.3.4"))
        assertNull(WasmtimeCompiler.parseWasmtimeVersion("unrelated 12.3.4"))
    }

    @Test
    fun fullCompilerDiscoveryNeverReturnsWasmtimeMin() {
        val root = createTempDirectory("wasmtime-compiler-discovery").toFile()
        try {
            val directory = File(root, "wasmtime-v12.3.4-x86_64-linux").apply { mkdirs() }
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

    @Test
    fun fullCompilerDiscoveryRejectsCraneliftMinimalReleaseDirectory() {
        val root = createTempDirectory("wasmtime-min-release").toFile()
        try {
            executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux-min/${executableName(minimal = false)}").apply {
                    parentFile.mkdirs()
                },
            )

            assertNull(WasmtimeCompiler.findWasmtimeCompilerInDirectory(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun minimalCompilerDiscoverySelectsOnlyCraneliftMinimalRelease() {
        val root = createTempDirectory("wasmtime-minimal-release").toFile()
        try {
            val executableName = executableName(minimal = false)
            val minimal = executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux-min/$executableName").apply {
                    parentFile.mkdirs()
                },
            )
            executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux/$executableName").apply {
                    parentFile.mkdirs()
                },
            )
            executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux-pulley/$executableName").apply {
                    parentFile.mkdirs()
                },
            )

            assertEquals(
                minimal.canonicalFile,
                WasmtimeCompiler.findWasmtimeMinimalInDirectory(root)?.canonicalFile,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun minimalCompilerDiscoveryRejectsFullReleaseDirectory() {
        val root = createTempDirectory("wasmtime-full-release").toFile()
        try {
            executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux/${executableName(minimal = false)}").apply {
                    parentFile.mkdirs()
                },
            )

            assertNull(WasmtimeCompiler.findWasmtimeMinimalInDirectory(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun componentCompilerDiscoveryPrefersCraneliftMinimalRelease() {
        val root = createTempDirectory("wasmtime-component-compiler").toFile()
        try {
            val executableName = executableName(minimal = false)
            val minimal = executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux-min/$executableName").apply {
                    parentFile.mkdirs()
                },
            )
            executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux/$executableName").apply {
                    parentFile.mkdirs()
                },
            )
            executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux-pulley/$executableName").apply {
                    parentFile.mkdirs()
                },
            )

            assertEquals(
                minimal.canonicalFile,
                WasmtimeCompiler.findComponentCompilerInDirectory(root)?.canonicalFile,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun componentCompilerDiscoveryPrefersCraneliftFullOverPulleyFull() {
        val root = createTempDirectory("wasmtime-component-compiler-full").toFile()
        try {
            val executableName = executableName(minimal = false)
            val full = executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux/$executableName").apply {
                    parentFile.mkdirs()
                },
            )
            executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux-pulley/$executableName").apply {
                    parentFile.mkdirs()
                },
            )

            assertEquals(
                full.canonicalFile,
                WasmtimeCompiler.findComponentCompilerInDirectory(root)?.canonicalFile,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun componentCompilerDiscoveryAcceptsPulleyFullAsLastFallback() {
        val root = createTempDirectory("wasmtime-component-compiler-pulley").toFile()
        try {
            val executableName = executableName(minimal = false)
            val pulley = executable(
                File(root, "wasmtime-v12.3.4-x86_64-linux-pulley/$executableName").apply {
                    parentFile.mkdirs()
                },
            )

            assertEquals(
                pulley.canonicalFile,
                WasmtimeCompiler.findComponentCompilerInDirectory(root)?.canonicalFile,
            )
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
