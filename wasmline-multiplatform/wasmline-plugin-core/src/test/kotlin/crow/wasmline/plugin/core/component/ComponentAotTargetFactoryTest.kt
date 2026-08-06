package crow.wasmline.plugin.core.component

import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentAotTargetFactoryTest {
    @Test
    fun mapsConfiguredTargetsToExistingPhysicalFormats() {
        val output = File("build/component-aot")

        val targets = ComponentAotTargetFactory.create(
            outputDirectory = output,
            productName = "plugin",
            targets = listOf("x86_64-linux", "pulley32-unknown-unknown-elf", "pulley64"),
        )

        assertEquals(
            listOf(WasmlineArtifactType.CWASM, WasmlineArtifactType.PWASM, WasmlineArtifactType.PWASM),
            targets.map { it.backend.artifactType },
        )
        assertEquals(
            listOf("plugin-x86_64-linux.cwasm", "plugin-pulley32-unknown-unknown-elf.pwasm", "plugin-pulley64.pwasm"),
            targets.map { it.outputFile.name },
        )
    }

    @Test
    fun emptyConfigurationUsesTheExistingDefaultTargetSet() {
        val targets = ComponentAotTargetFactory.create(File("out"), "plugin", emptyList())

        assertEquals(
            WasmtimeCompiler.defaultTargets.filterNot { it.contains("ios") },
            targets.map { it.target },
        )
        assertTrue(targets.any { it.backend == ComponentAotBackend.CRANELIFT })
        assertTrue(targets.any { it.target == "pulley64" && it.backend == ComponentAotBackend.PULLEY })
    }

    @Test
    fun rejectsUnsafeNamesAndUnsupportedPulleyTargets() {
        assertFailsWith<IllegalArgumentException> {
            ComponentAotTargetFactory.create(File("out"), "../plugin", listOf("pulley64"))
        }
        assertFailsWith<IllegalArgumentException> {
            ComponentAotTargetFactory.create(File("out"), "plugin", listOf("pulley128"))
        }
    }

    @Test
    fun rejectsDirectIosCwasmAndKeepsOtherNativeTargets() {
        listOf("aarch64-ios", "aarch64-ios-sim", "aarch64-apple-ios").forEach { target ->
            val error = assertFailsWith<IllegalArgumentException> {
                ComponentAotTargetFactory.create(File("out"), "plugin", listOf(target))
            }
            assertTrue(error.message.orEmpty().contains("pulley64 PWASM"))
        }

        val targets = ComponentAotTargetFactory.create(
            File("out"),
            "plugin",
            listOf("x86_64-linux", "aarch64-android", "aarch64-macos", "pulley64"),
        )
        assertEquals(
            listOf(ComponentAotBackend.CRANELIFT, ComponentAotBackend.CRANELIFT, ComponentAotBackend.CRANELIFT, ComponentAotBackend.PULLEY),
            targets.map { it.backend },
        )
    }
}
