import buildlogic.libsEx
import gradle.kotlin.dsl.accessors._fb079f171776054018bb93a43cbfc29b.composeCompiler

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

composeCompiler {
    // Emit Compose stability report; run outputCompilerReports task
    // https://developer.android.com/jetpack/compose/performance/stability/diagnose#compose-compiler
    reportsDestination.set(
        layout.buildDirectory.get().asFile.resolve("compose_compiler"),
    )

    // Configure stability for external classes used in Compose
    // Only classes from existing third-party libraries are allowed; for your own classes, annotate with @Stable
    // See https://android-review.googlesource.com/c/platform/frameworks/support/+/2668595 for configuration rules
    stabilityConfigurationFiles.set(
        listOf(
            layout.projectDirectory.file(rootDir.resolve("config/compose-stability-config.txt").absolutePath),
        ),
    )
}

android {
    dependencies {

        // Essential dependencies, equivalent to stdlib
        implementation(libsEx.`androidx-compose-runtime`)
        implementation(libsEx.`androidx-compose-foundation`)
        implementation(libsEx.`androidx-compose-ui`)
        implementation(libsEx.`androidx-compose-ui-graphics`)

        debugImplementation(libsEx.`androidx-compose-ui-tooling`)
        implementation(libsEx.`androidx-compose-ui-tooling-preview`)
    }
}
