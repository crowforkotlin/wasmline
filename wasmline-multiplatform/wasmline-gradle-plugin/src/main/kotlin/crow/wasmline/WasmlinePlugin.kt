package crow.wasmline

import crow.wasmline.gradle.BuildConfig
import crow.wasmline.loader.internal.crypto.SignatureAlgorithmId
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.slf4j.LoggerFactory
import kotlin.jvm.java

class WasmlinePlugin : KotlinCompilerPluginSupportPlugin {
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
        version = BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        return kotlinCompilation.target.project.provider {
            listOf(
                SubpluginOption(
                    key = ENABLE_WASI_INIT_EXPORT_OPTION,
                    value = shouldEnableWasiInitExport(kotlinCompilation).toString(),
                ),
            )
        }
    }

    override fun apply(target: Project) {
        createGenerateKeyPairTasks(target)
    }

    private fun shouldEnableWasiInitExport(kotlinCompilation: KotlinCompilation<*>): Boolean {
        return kotlinCompilation.target.platformType == KotlinPlatformType.wasm &&
            kotlinCompilation.defaultSourceSet.name == "wasmWasiMain"
    }


    private fun createGenerateKeyPairTasks(project: Project) {
        project.tasks.register("generateZiplineManifestKeyPairEd25519") { task ->
            task.doLast {
                generateKeyPair(SignatureAlgorithmId.Ed25519)
            }
        }
        project.tasks.register("generateZiplineManifestKeyPairEcdsaP256") { task ->
            task.doLast {
                generateKeyPair(SignatureAlgorithmId.EcdsaP256)
            }
        }
    }

    private companion object {
        const val ENABLE_WASI_INIT_EXPORT_OPTION = "enableWasiInitExport"
    }

    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Access :zipline-loader internals.
    private fun generateKeyPair(algorithm: SignatureAlgorithmId) {
        val logger = LoggerFactory.getLogger(WasmlinePlugin::class.java)
        val keyPair = crow.wasmline.loader.internal.crypto.generateKeyPair(algorithm)
        logger.warn("---------------- ----------------------------------------------------------------")
        logger.warn("    ALGORITHM: $algorithm")
        logger.warn("    PUBLIC KEY: ${keyPair.publicKey.hex()}")
        logger.warn("    PRIVATE KEY: ${keyPair.privateKey.hex()}")
        logger.warn("---------------- ----------------------------------------------------------------")
    }
}