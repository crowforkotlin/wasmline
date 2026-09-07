import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
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
    testImplementation(libs.kotlinx.coroutines.test)
}

val componentServicePackage = project(":sample-component-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.component.sample-1.0.0",
)

tasks.test {
    dependsOn(project(":sample-component-plugin").tasks.named("wasmlineAssembleDebug"))
    useJUnitPlatform()
    systemProperty(
        "wasmline.test.componentService.manifest",
        componentServicePackage.map { it.file("manifest.wlm").asFile.absolutePath }.get(),
    )
}

val samplePluginOutput = project(":sample-plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.demo-1.0.0",
)
val syncSamplePluginArtifact = tasks.register<Sync>("syncWasmlineSamplePlugin") {
    group = "wasmline"
    description = "Build and expose the signed sample plugin package to the application"
    dependsOn(project(":sample-plugin").tasks.named("wasmlineAssembleDebug"))
    from(samplePluginOutput) {
        include("manifest.wlm", "artifacts/**")
        into("wasmline-package")
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
}
