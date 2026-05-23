@file:OptIn(ExperimentalKotlinGradlePluginApi::class)
@file:Suppress("unused")

package buildlogic

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyBuilder
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

val KotlinMultiplatformExtension.nonWebCommonMain: KotlinSourceSet
    get() {
        return this.sourceSets.getByName("buildlogic.nonWebCommonMain")
    }

fun KotlinMultiplatformExtension.applyBaseHierarchyTemplate(
    common: (KotlinHierarchyBuilder.() -> Unit)? = null
) {
    this.applyHierarchyTemplate(template = KotlinHierarchyTemplate {
        this.withSourceSetTree(tree = arrayOf(KotlinSourceSetTree.main, KotlinSourceSetTree.test))
        this.common {
            this.withCompilations { true }
            this.nonWebCommon()//            this.buildlogic.native()
//            this.buildlogic.webCommon()
//            this.buildlogic.nonWasmCommon()

        }
    })
}

fun KotlinHierarchyBuilder.native() {
    group("buildlogic.native") {
        group("apple") {
            group("ios") { withIos() }
            group("macos") { withMacos() }
            withApple()
        }
        withNative()
    }
}

fun KotlinHierarchyBuilder.webCommon() {
    group(name = "buildlogic.webCommon") {
        withJs()
        withWasmJs()
    }
}

fun KotlinHierarchyBuilder.nonWebCommon() {
    group(name = "buildlogic.nonWebCommon") {
        withJvm()
        withAndroidTarget()
    }
}

fun KotlinHierarchyBuilder.nonWasmCommon() {
    group(name = "buildlogic.nonWasmCommon") {
        withJvm()
        withAndroidTarget()
        native()
        withJs()
    }
}