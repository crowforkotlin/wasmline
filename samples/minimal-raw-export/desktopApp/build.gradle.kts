import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.wasmline)
}

val jbrVersion = rootProject.extra["jbrVersion"] as Int
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jbrVersion))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
}
compose.desktop {
    application { mainClass = "crow.wasmline.samples.minimal.rawexport.MainKt" }
}
dependencies {
    implementation(projects.common)
    implementation(compose.desktop.currentOs)
}

@Suppress("UNCHECKED_CAST")
val pluginPackage = rootProject.extra["pluginPackage"] as Provider<Directory>
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    dependsOn(project(projects.plugin.path).tasks.named("wasmlineAssembleDebug"))
    systemProperty("wasmline.sample.manifest", pluginPackage.get().file("manifest.wlm").asFile.absolutePath)
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(jbrVersion))
            vendor.set(JvmVendorSpec.JETBRAINS)
        },
    )
}
