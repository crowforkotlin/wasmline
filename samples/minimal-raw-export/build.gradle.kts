import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.jetbrains.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library.kmp) apply false
    alias(libs.plugins.wasmline) apply false
}

val repositoryManifest = JsonSlurper().parse(file("../../versions.json")) as Map<*, *>
val repositoryVersions = repositoryManifest["versions"] as Map<*, *>
extra["sampleVersion"] = requireNotNull(repositoryVersions["sample_plugin_version"]).toString()
extra["jbrVersion"] = requireNotNull(repositoryVersions["jbr_version"]).toString().toInt()
extra["pluginPackage"] = project(":plugin").layout.buildDirectory.dir(
    "wasmline/output/crow.wasmline.minimal.raw-export-${extra["sampleVersion"]}",
)

tasks.register("run") {
    group = "application"
    description = "Run the minimal Desktop application"
    dependsOn(":desktopApp:run")
}
