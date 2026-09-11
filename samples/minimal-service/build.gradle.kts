import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.jetbrains.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library.kmp) apply false
}

val repositoryManifest = JsonSlurper().parse(file("../../versions.json")) as Map<*, *>
val repositoryVersions = repositoryManifest["versions"] as Map<*, *>
extra["sampleVersion"] = requireNotNull(repositoryVersions["sample_plugin_version"]).toString()
extra["jbrVersion"] = requireNotNull(repositoryVersions["jbr_version"]).toString().toInt()
extra["pluginPackage"] = project(projects.plugin.path).layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.minimal.service-${extra["sampleVersion"]}",
)

tasks.register("run") {
    group = "application"
    description = "Run the minimal Desktop application"
    dependsOn(project(projects.desktopApp.path).tasks.named("run"))
}

tasks.register("desktopRun") {
    group = "application"
    description = "Run the minimal Desktop application"
    dependsOn(project(projects.desktopApp.path).tasks.named("run"))
}
