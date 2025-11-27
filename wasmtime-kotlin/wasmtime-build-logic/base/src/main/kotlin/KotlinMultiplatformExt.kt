@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyBuilder
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

val KotlinMultiplatformExtension.nonWebCommonMain: KotlinSourceSet get() {
    return this.sourceSets.getByName("nonWebCommonMain")
}

fun KotlinMultiplatformExtension.applyBaseHierarchyTemplate(
    common: (KotlinHierarchyBuilder.() -> Unit)? = null
) {
    this.applyHierarchyTemplate(template = KotlinHierarchyTemplate {
        this.withSourceSetTree(tree = arrayOf(KotlinSourceSetTree.main, KotlinSourceSetTree.test))
        this.common {
            this.withCompilations { true }
            this.nonWebCommon()
//            this.native()
//            this.webCommon()
//            this.nonWasmCommon()
        }
    })
}
fun KotlinHierarchyBuilder.native() {
    group("native") {
        group("apple") {
            group("ios") { withIos() }
            group("macos") { withMacos() }
            withApple()
        }
        withNative()
    }
}

fun KotlinHierarchyBuilder.webCommon() {
    group(name = "webCommon") {
        withJs()
        withWasmJs()
    }
}

fun KotlinHierarchyBuilder.nonWebCommon() {
    group(name = "nonWebCommon") {
        withJvm()
        withAndroidTarget()
//        native()
    }
}

fun KotlinHierarchyBuilder.nonWasmCommon() {
    group(name = "nonWasmCommon") {
        withJvm()
        withAndroidTarget()
        native()
        withJs()
    }
}
