import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wasmline)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application { mainClass = "crow.wasmline.sample.application.MainKt" }

dependencies {
    implementation(libs.crow.wasmline.loader)
    implementation(libs.crow.wasmline.network.ktor)
    // Cranelift includes both native CWASM and portable PWASM loading.
    implementation(libs.crow.wasmline.engine.cranelift)
    implementation(libs.ktor.client.cio)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(projects.sampleCommon)
    testImplementation(libs.kotlin.test)

}

val componentServiceAotOutput = project(":sample-component-plugin").layout.buildDirectory.dir(
    "wasmline/component-aot/debug",
)

tasks.test {
    dependsOn(project(":sample-component-plugin").tasks.named("wasmlineComponentAotDebug"))
    useJUnitPlatform()
    systemProperty(
        "wasmline.test.componentService.cwasm",
        componentServiceAotOutput.map { it.file("sample-aarch64-macos.cwasm").asFile.absolutePath }.get(),
    )
    systemProperty(
        "wasmline.test.componentService.pwasm",
        componentServiceAotOutput.map { it.file("sample-pulley64.pwasm").asFile.absolutePath }.get(),
    )
}

val defaultCwasmTarget = when {
    System.getProperty("os.name").lowercase().contains("mac") &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-macos"
    System.getProperty("os.name").lowercase().contains("mac") -> "x86_64-macos"
    System.getProperty("os.name").lowercase().contains("linux") &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64") -> "aarch64-linux"
    System.getProperty("os.name").lowercase().contains("linux") -> "x86_64-linux"
    System.getProperty("os.name").lowercase().contains("windows") -> "x86_64-windows"
    else -> error("Unsupported Wasmtime host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
}

val requestedArtifactFormat = providers.gradleProperty("wasmline.artifact.format")
    .orElse(providers.environmentVariable("WASMLINE_ARTIFACT_FORMAT"))
    .map { it.lowercase() }
    .orElse("cwasm")
    .map { if (it == "pwasm") "pwasm64" else it }
    .get()
require(requestedArtifactFormat in setOf("pwasm32", "pwasm64", "cwasm")) {
    "Unsupported wasmline.artifact.format '$requestedArtifactFormat'. Expected pwasm32, pwasm64, or cwasm."
}

val requestedCwasmTarget = providers.gradleProperty("wasmline.compile.target")
    .orElse(defaultCwasmTarget)
    .get()
val samplePluginOutput = project(":sample-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.demo-1.0.0",
)
val samplePluginArtifactName = when (requestedArtifactFormat) {
    "pwasm32" -> "demo-pulley32.pwasm"
    "pwasm64" -> "demo-pulley64.pwasm"
    "cwasm" -> "demo-$requestedCwasmTarget.cwasm"
    else -> error("Unsupported wasmline artifact format: $requestedArtifactFormat")
}
val samplePluginArtifactExtension = samplePluginArtifactName.substringAfterLast('.')
val syncSamplePluginArtifact = tasks.register<Sync>("syncWasmlineSamplePlugin") {
    group = "wasmline"
    description = "Build and expose the sample plugin artifact to the application"
    dependsOn(project(":sample-plugin").tasks.named("wasmlineAssembleDebug"))
    from(samplePluginOutput) {
        include(samplePluginArtifactName)
        rename { "plugin.$samplePluginArtifactExtension" }
    }
    into(layout.buildDirectory.dir("generated/application-resources"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(syncSamplePluginArtifact)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(syncSamplePluginArtifact)
}

tasks.named<JavaExec>("run") {
    dependsOn(syncSamplePluginArtifact)
    systemProperty("wasmline.artifact.format", requestedArtifactFormat)
}
