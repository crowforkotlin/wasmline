package crow.wasmline.plugin.core.compiler

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.aot.AotCompatibilityProfileSpec
import crow.wasmline.plugin.core.aot.WasmlineAotCompileOptions
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WasmtimeCompilerTest {
    @Test
    fun pulleyTargetsUsePortableMetadata() {
        assertEquals("pulley32" to null, WasmtimeCompiler.parseTarget("pulley32"))
        assertEquals("pulley64" to null, WasmtimeCompiler.parseTarget("pulley64"))
    }

    @Test
    fun coreTargetsKeepCanonicalPlatformMetadata() {
        assertEquals("x86_64" to "linux", WasmtimeCompiler.parseTarget("x86_64-linux"))
        assertEquals("aarch64" to "android", WasmtimeCompiler.parseTarget("aarch64-android"))
        assertEquals("aarch64" to "ios", WasmtimeCompiler.parseTarget("aarch64-apple-ios"))
        assertEquals("x86_64" to "windows", WasmtimeCompiler.parseTarget("x86_64-windows"))
    }

    @Test
    fun defaultTargetsIncludePortablePwasmForBothPointerWidths() {
        assertTrue("pulley32" in WasmtimeCompiler.defaultTargets)
        assertTrue("pulley64" in WasmtimeCompiler.defaultTargets)
        assertTrue(WasmtimeCompiler.defaultTargets.none { WasmtimeCompiler.parseTarget(it).second == "ios" })
    }

    @Test
    fun parsesOnlyExactSemanticVersionOutput() {
        assertEquals("12.3.4", WasmtimeCompiler.parseWasmtimeVersion("wasmtime 12.3.4 (abc123)"))
        assertEquals("12.3.4", WasmtimeCompiler.parseWasmtimeVersion("Wasmtime 12.3.4\n"))
        assertNull(WasmtimeCompiler.parseWasmtimeVersion("wasmtime v12.3.4"))
        assertNull(WasmtimeCompiler.parseWasmtimeVersion("unrelated 12.3.4"))
    }

    @Test
    fun verifiesVersionAndCompileCapabilityBeforeCompilation() = withCompilerDirectory { root ->
        val executable = executable(File(root, "wasmtime"))
        val invocations = mutableListOf<List<String>>()
        val compiler = WasmtimeCompiler(
            runner = WasmtimeCompilerRunner { _, arguments ->
                invocations += arguments
                when (arguments) {
                    listOf("--version") -> WasmtimeCompilerProcessResult(0, "wasmtime 12.3.4")
                    listOf("compile", "--help") -> WasmtimeCompilerProcessResult(0, "compile")
                    else -> WasmtimeCompilerProcessResult(1, "unexpected")
                }
            },
        )

        compiler.verify(executable, profile())

        assertEquals(listOf(listOf("--version"), listOf("compile", "--help")), invocations)
    }

    @Test
    fun compilesWithFrozenArgumentsAndRequiresNonEmptyOutput() = withCompilerDirectory { root ->
        val executable = executable(File(root, "wasmtime"))
        val input = File(root, "input.wasm").apply { writeBytes(byteArrayOf(0, 97, 115, 109)) }
        val output = File(root, "output.pwasm")
        var capturedArguments = emptyList<String>()
        val compiler = WasmtimeCompiler(
            runner = WasmtimeCompilerRunner { _, arguments ->
                capturedArguments = arguments
                File(arguments[arguments.indexOf("-o") + 1]).writeBytes(byteArrayOf(1, 2, 3))
                WasmtimeCompilerProcessResult(0, "compiled")
            },
        )

        compiler.compile(executable, input, output, "pulley64", WasmlineAotCompileOptions())

        assertTrue(output.isFile && output.length() == 3L)
        assertEquals("compile", capturedArguments.first())
        assertEquals("pulley64", capturedArguments[capturedArguments.indexOf("--target") + 1])
        assertTrue("threads=n" in capturedArguments)
        assertTrue("simd=n" in capturedArguments)
    }

    private fun profile(): AotCompatibilityProfileSpec = AotCompatibilityProfileSpec(
        id = "sha256:${"a".repeat(64)}",
        artifactBackend = WasmlineEngineKind.CRANELIFT,
        wasmtimeVersion = "12.3.4",
        wasmtimeDistributionVersion = "12.3.4.1",
        wasmtimeSourceRevision = "revision",
        serializedArtifactFormatIdentity = "format",
        compileProfileSchemaVersion = 1,
        engineConfigurationProfile = WasmlineAotCompileOptions.FROZEN_DESCRIPTOR,
        introducedInWasmlineVersion = "1.0.0",
    )

    private fun executable(file: File): File = file.apply {
        writeText("test")
        assertTrue(setExecutable(true) || canExecute())
    }
}

private inline fun withCompilerDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmtime-compiler-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
