@file:OptIn(ExperimentalCompilerApi::class)

package crow.wasmline.kotlin.services

import crow.wasmline.kotlin.WasmlineCompilerPluginRegistrar
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

private fun readClasspath(propertyName: String): List<File> = System.getProperty(propertyName)
    ?.split(File.pathSeparator)
    ?.filter { it.isNotBlank() }
    ?.map(::File)
    .orEmpty()

private val wasmlineRuntimeClasspath = readClasspath("wasmlineRuntime.classpath")
private val wasmlineTestArtifactsClasspath = readClasspath("wasmlineTestArtifacts.classpath")
private val wasmlineCompilerTestClasspath = (wasmlineRuntimeClasspath + wasmlineTestArtifactsClasspath)
    .distinctBy(File::getAbsolutePath)
    .ifEmpty {
        error(
            "Unable to get a valid classpath from 'wasmlineRuntime.classpath' or 'wasmlineTestArtifacts.classpath' properties",
        )
    }

fun TestConfigurationBuilder.configurePlugin() {
    useConfigurators(::WasmlinePluginConfigurator)
    useCustomRuntimeClasspathProviders(::WasmlineRuntimeClasspathProvider)
}

private class WasmlinePluginConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    private val registrar = WasmlineCompilerPluginRegistrar()

    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        with(registrar) { registerExtensions(configuration) }
    }

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        configuration.addJvmClasspathRoots(wasmlineCompilerTestClasspath)
    }
}

private class WasmlineRuntimeClasspathProvider(testServices: TestServices) : RuntimeClasspathProvider(testServices) {
    override fun runtimeClassPaths(module: TestModule): List<File> = wasmlineCompilerTestClasspath
}
