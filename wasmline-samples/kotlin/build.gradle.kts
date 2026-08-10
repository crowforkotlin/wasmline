plugins {
    alias(libs.plugins.app.base.multiplatform.library) apply false
    alias(libs.plugins.app.base.library) apply false
    alias(libs.plugins.app.base.android) apply false

    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.library.kmp) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.jetbrains.compose.compiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.github.fourlastor.construo) apply false
    alias(libs.plugins.conveyor) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildconfig) apply false
}
