@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.jetbrains.compose.compiler)
}

kotlin {
    jvmToolchain(rootProject.extra["jbrVersion"] as Int)
    js {
        browser { commonWebpackConfig { outputFileName = "minimal.js" } }
        binaries.executable()
    }
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "minimal.js" } }
        binaries.executable()
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies { implementation(projects.common) }
        webMain.dependencies { implementation(libs.jetbrains.browser) }
    }
}

@Suppress("UNCHECKED_CAST")
val pluginPackage = rootProject.extra["pluginPackage"] as Provider<Directory>
val syncWebPlugin = tasks.register<Sync>("syncWebPlugin") {
    dependsOn(project(projects.plugin.path).tasks.named("wasmlineAssembleDebug"))
    from(pluginPackage) {
        include("manifest.wlm", "artifacts/**")
        into("plugin")
    }
    into(layout.buildDirectory.dir("generated/web-resources"))
}
tasks.withType<AbstractCopyTask>().matching {
    it.name == "jsProcessResources" || it.name == "wasmJsProcessResources"
}.configureEach {
    from(syncWebPlugin)
}
