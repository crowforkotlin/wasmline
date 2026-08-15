package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentToolchainIntegrationTest {
    @Test
    fun generatesAndComponentizesWithPinnedReleaseTools() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val witBindgen = requireExecutable(WIT_BINDGEN_ENV)
        val wasmTools = requireExecutable(WASM_TOOLS_ENV)
        val adapter = requireFile(WASI_ADAPTER_ENV)
        val witBindgenVersion = ToolchainCatalog.WIT_BINDGEN_VERSION
        val wasmToolsVersion = ToolchainCatalog.WASM_TOOLS_VERSION
        val adapterVersion = ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION
        val root = createTempDirectory("wasmline-component-live").toFile()
        try {
            val witDirectory = File(root, "wit").apply { mkdirs() }
            copyResource(CANONICAL_WIT_RESOURCE, File(witDirectory, "wasmline.wit"))

            val generatedDirectory = File(root, "generated")
            val verificationLogs = mutableListOf<String>()
            val witBindgenTool = WitBindgenTool(
                witBindgen,
                ExternalToolRunner(logger = verificationLogs::add),
            )
            witBindgenTool.verify(witBindgenVersion)
            assertTrue(verificationLogs.isEmpty())
            witBindgenTool.generateKotlin(
                KotlinBindingsRequest(
                    witDirectory = witDirectory,
                    outputDirectory = generatedDirectory,
                    world = "plugin",
                    witBindgenVersion = witBindgenVersion,
                ),
            )
            assertTrue(generatedDirectory.walkTopDown().any { it.isFile && it.extension == "kt" })
            assertTrue(verificationLogs.any { it.contains("Generating") })
            verificationLogs.clear()

            val wat = File(root, "component-service-core.wat")
            val coreWasm = File(root, "component-service-core.wasm")
            copyResource(CORE_WAT_RESOURCE, wat)
            ExternalToolRunner().run(
                executable = wasmTools,
                arguments = listOf("parse", wat.absolutePath, "-o", coreWasm.absolutePath),
            )

            val wasmToolsTool = WasmToolsTool(
                wasmTools,
                ExternalToolRunner(logger = verificationLogs::add),
            )
            wasmToolsTool.verify(wasmToolsVersion)
            assertTrue(verificationLogs.isEmpty())
            val result = ComponentPipeline(wasmToolsTool).componentize(
                ComponentizeRequest(
                    coreWasm = coreWasm,
                    witPath = witDirectory,
                    wasiPreview1Adapter = adapter,
                    outputDirectory = File(root, "component"),
                    productName = "live-fixture",
                    world = "plugin",
                    invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
                    exportName = WasmlineComponentServiceContract.DEFAULT_EXPORT,
                    codec = WasmlineComponentServiceContract.DEFAULT_CODEC,
                    serviceProtocolVersion = WasmlineComponentServiceContract.VERSION,
                    wasmToolsVersion = wasmToolsVersion,
                    witBindgenVersion = witBindgenVersion,
                    adapterVersion = adapterVersion,
                ),
            )

            assertTrue(result.componentWasm.isFile)
            assertTrue(result.componentWasm.length() > 0)
            val inspectedWit = requireNotNull(result.inspectedWit).readText()
            assertTrue(inspectedWit.contains("import host:"))
            assertTrue(inspectedWit.contains("export plugin:"))
            assertTrue(inspectedWit.contains("invoke: func"))
            assertEquals("protobuf", result.codec)
            assertEquals("1", result.serviceProtocolVersion)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun requireExecutable(name: String): File = requireFile(name).also { file ->
        require(file.canExecute()) { "$name is not executable: ${file.absolutePath}" }
    }

    private fun requireFile(name: String): File {
        val path = requireNotNull(System.getenv(name)) {
            "$name must be set when $LIVE_TESTS_ENV=1."
        }
        return File(path).also { file ->
            require(file.isFile) { "$name does not point to a file: ${file.absolutePath}" }
        }
    }

    private fun copyResource(name: String, destination: File) {
        val input = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "Missing test resource: $name"
        }
        destination.parentFile?.mkdirs()
        input.use { source -> destination.outputStream().use(source::copyTo) }
    }

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val WIT_BINDGEN_ENV = "WASMLINE_TEST_WIT_BINDGEN"
        const val WASM_TOOLS_ENV = "WASMLINE_TEST_WASM_TOOLS"
        const val WASI_ADAPTER_ENV = "WASMLINE_TEST_WASI_ADAPTER"
        const val CANONICAL_WIT_RESOURCE = "META-INF/wasmline/wit/wasmline-service/wasmline.wit"
        const val CORE_WAT_RESOURCE = "fixtures/component-service-core.wat"
    }
}
