package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
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
        val root = createTempDirectory("wasmline-component-live").toFile()
        try {
            val witDirectory = File(root, "wit").apply { mkdirs() }
            copyResource(CANONICAL_WIT_RESOURCE, File(witDirectory, "world.wit"))

            val generatedDirectory = File(root, "generated")
            val verificationLogs = mutableListOf<String>()
            val witBindgenTool = WitBindgenTool(
                witBindgen,
                ExternalToolRunner(logger = verificationLogs::add),
            )
            witBindgenTool.verify("0.57.1")
            assertTrue(verificationLogs.isEmpty())
            witBindgenTool.generateKotlin(
                KotlinBindingsRequest(
                    witDirectory = witDirectory,
                    outputDirectory = generatedDirectory,
                    world = "plugin",
                    witBindgenVersion = "0.57.1",
                ),
            )
            assertTrue(generatedDirectory.walkTopDown().any { it.isFile && it.extension == "kt" })
            assertTrue(verificationLogs.any { it.contains("Generating") })
            verificationLogs.clear()

            val wat = File(root, "component-rpc-core.wat")
            val coreWasm = File(root, "component-rpc-core.wasm")
            copyResource(CORE_WAT_RESOURCE, wat)
            ExternalToolRunner().run(
                executable = wasmTools,
                arguments = listOf("parse", wat.absolutePath, "-o", coreWasm.absolutePath),
            )

            val wasmToolsTool = WasmToolsTool(
                wasmTools,
                ExternalToolRunner(logger = verificationLogs::add),
            )
            wasmToolsTool.verify("1.255.0")
            assertTrue(verificationLogs.isEmpty())
            val result = ComponentPipeline(wasmToolsTool).componentize(
                ComponentizeRequest(
                    coreWasm = coreWasm,
                    witPath = witDirectory,
                    wasiPreview1Adapter = adapter,
                    outputDirectory = File(root, "component"),
                    productName = "live-fixture",
                    world = "plugin",
                    invocationProtocol = WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC,
                    exportName = WasmlineComponentRpcContract.DEFAULT_EXPORT,
                    codec = WasmlineComponentRpcContract.DEFAULT_CODEC,
                    rpcProtocolVersion = WasmlineComponentRpcContract.VERSION,
                    witBindgenVersion = "0.57.1",
                    adapterVersion = "47.0.2",
                ),
            )

            assertTrue(result.componentWasm.isFile)
            assertTrue(result.componentWasm.length() > 0)
            val inspectedWit = requireNotNull(result.inspectedWit).readText()
            assertTrue(inspectedWit.contains("import host:"))
            assertTrue(inspectedWit.contains("export plugin:"))
            assertTrue(inspectedWit.contains("invoke: func"))
            assertEquals("protobuf", result.codec)
            assertEquals("1", result.rpcProtocolVersion)
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
        const val CANONICAL_WIT_RESOURCE = "META-INF/wasmline/wit/wasmline-rpc/world.wit"
        const val CORE_WAT_RESOURCE = "fixtures/component-rpc-core.wat"
    }
}
