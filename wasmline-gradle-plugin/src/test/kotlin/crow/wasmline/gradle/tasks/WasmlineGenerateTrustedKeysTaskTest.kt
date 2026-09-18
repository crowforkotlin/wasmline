package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlinePlugin
import crow.wasmline.gradle.extensions.WasmlineExtension
import crow.wasmline.gradle.trust.WasmlineTrustedPublicKeySpecCodec
import org.gradle.api.tasks.CacheableTask
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WasmlineGenerateTrustedKeysTaskTest {
    @Test
    fun generatesTrustedKeyObjectFromHexAndPublicKeyFile() = withTaskDirectory { root ->
        val publicKeyFile = File(root, "keys/rotated.public").apply {
            parentFile.mkdirs()
            writeText("ccdd\n")
        }
        val output = File(root, "generated-trust")
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register(
            "generateTrustedKeys",
            WasmlineGenerateTrustedKeysTask::class.java,
        ).get().apply {
            inlinePublicKeySpecs.set(
                listOf(
                    WasmlineTrustedPublicKeySpecCodec.inline(
                        algorithm = "Ed25519",
                        keyId = null,
                        publicKeyHex = "aabb",
                    ),
                ),
            )
            publicKeyFileSpecs.set(
                listOf(
                    WasmlineTrustedPublicKeySpecCodec.file(
                        algorithm = "Ed25519",
                        keyId = "rotated-2026",
                        file = publicKeyFile,
                    ),
                ),
            )
            publicKeyFiles.from(publicKeyFile)
            kotlinPackage.set("generated.trust")
            objectName.set("PackageTrust")
            outputDirectory.set(output)
        }

        task.generate()

        val generated = File(output, "generated/trust/PackageTrust.kt")
        assertTrue(generated.isFile)
        val source = generated.readText()
        assertContains(source, "package generated.trust")
        assertContains(source, "public object PackageTrust : WasmlineTrustedKeys")
        assertContains(source, "keyId = null")
        assertContains(source, "publicKeyHex = \"aabb\"")
        assertContains(source, "keyId = \"rotated-2026\"")
        assertContains(source, "publicKeyHex = \"ccdd\"")
        assertTrue(WasmlineGenerateTrustedKeysTask::class.java.isAnnotationPresent(CacheableTask::class.java))
    }

    @Test
    @Suppress("DEPRECATION")
    fun configuresCommonMainAndKeepsThePrivateKeyAlias() = withTaskDirectory { root ->
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(WasmlinePlugin::class.java)
        val extension = project.extensions.getByType(WasmlineExtension::class.java)
        extension.trust.publicKey(publicKeyHex = "aabb")
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.jvm()

        WasmlinePlugin().configureHostTrust(
            project = project,
            ext = extension,
            kotlinExtension = kotlin,
            kotlinJvmExtension = null,
        )

        val task = project.tasks.named("wasmlineGenerateTrustedKeys", WasmlineGenerateTrustedKeysTask::class.java).get()
        val commonMain = kotlin.sourceSets.getByName("commonMain")
        assertEquals(
            listOf(WasmlineTrustedPublicKeySpecCodec.inline("Ed25519", null, "aabb")),
            task.inlinePublicKeySpecs.get(),
        )
        assertTrue(commonMain.kotlin.srcDirs.contains(task.outputDirectory.get().asFile))
        assertSame(extension.manifest.privateKeyFile, extension.manifest.signingKey)
    }
}

private inline fun withTaskDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-trusted-keys-task-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
