@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage", "SpellCheckingInspection")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.konan.target.HostManager


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

configure<MavenPublishBaseExtension> {
    configure(
        platform = KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            androidVariantsToPublish = emptyList(),
        ),
    )
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvm()
    android {
        namespace = "crow.wasmline"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    wasmJs {
        browser()
        binaries.library()
    }
    js {
        browser()
        binaries.library()
    }
    wasmWasi {
        nodejs()
        binaries.library()
    }
    val nativeHeaderDir = project.file("src/nativeMain/native")
    val nativeCoreHeaderDir = rootProject.file("../wasmline-core/include")
    val nativeTargets: List<KotlinNativeTarget> = buildList {
        if (HostManager.hostIsMac) {
            add(iosArm64())
            add(iosSimulatorArm64())
            add(macosArm64())
        }
        add(linuxArm64())
        add(linuxX64())
        add(mingwX64())
    }
    nativeTargets.forEach { target ->
        target.compilations.getByName("main") {
            val wasmline by cinterops.creating {
                definitionFile.set(project.file("src/nativeMain/native/cinterop/wasmline.def"))
                includeDirs(nativeHeaderDir, nativeCoreHeaderDir)
                compilerOpts("-I${nativeHeaderDir.absolutePath}", "-I${nativeCoreHeaderDir.absolutePath}")
            }
        }
        val nativeLinkerOptions = when (target.name) {
            "linuxX64", "linuxArm64" -> listOf("-ldl", "-lpthread", "-lm", "-lstdc++", "-lstdc++fs")
            "mingwX64" -> listOf("-lbcrypt", "-luserenv", "-lole32", "-luuid", "-lstdc++")
            else -> listOf("-lc++")
        }
        target.binaries.all {
            linkerOpts(*nativeLinkerOptions.toTypedArray())
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    applyDefaultHierarchyTemplate()
    sourceSets {

        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines)
                api(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }
        val hostMain by creating { dependsOn(other = commonMain) }
        val jniMain by creating { dependsOn(other = hostMain) }
        val jvmMain by getting { dependsOn(other = jniMain) }
        // jsMain/wasmJsMain already depend on webMain via the default hierarchy template.
        val webMain by getting { dependsOn(other = hostMain) }
        val nativeMain by getting { dependsOn(other = hostMain) }
        val androidMain by getting {
            dependsOn(other = jniMain)
            dependencies {
                implementation(projects.wasmlineAndroid)
            }
        }

        // commonTest: base test dependencies available on ALL platforms (including wasmWasi)
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // hostTest: extends commonTest with WasmlineLoader (only for host platforms)
        // WASMLINE LOADER IS HOST-SPECIFIC: It handles plugin loading, validation, and platform-specific artifact resolution
        // This includes: JVM, JS, Android, iOS, Desktop targets
        // EXCLUDES: wasmWasi (pure WASM runtime, not a host environment for loading other plugins)
        val hostTest by creating {
            dependsOn(other = commonTest)
            dependencies {
                implementation(projects.wasmlineLoader)
            }
        }

        // JVM-specific JNI tests (uses native Wasmtime library loaded via JNI)
        val jvmTest by getting {
            dependsOn(other = hostTest)
            dependencies {
                implementation(projects.wasmlineEngineCranelift) // Provides libwasmline.so for testing
                implementation(projects.wasmlineNativeTestFixtures)
            }
        }

        val nativeTest by getting { dependsOn(other = hostTest) }
        if (HostManager.hostIsMac) {
            val iosTest by getting {
                dependencies {
                    implementation(projects.wasmlineEnginePulley)
                }
            }
            val macosTest by getting {
                dependencies {
                    implementation(projects.wasmlineEngineCranelift)
                }
            }
        }
        val linuxTest by getting {
            dependencies {
                implementation(projects.wasmlineEngineCranelift)
            }
        }
        val mingwTest by getting {
            dependencies {
                implementation(projects.wasmlineEngineCranelift)
            }
        }
//        val androidInstrumentedTest by getting { dependsOn(other = hostTest) }
    }
}

val nativeAotFixtureTask = ":wasmline-native-test-fixtures:assembleNativeTestFixtures"
val nativeAotFixtureIndex = project(":wasmline-native-test-fixtures")
    .layout
    .buildDirectory
    .file("wasmline/native-fixtures/fixture-index.json")
val nativeAotJvmLibrary = run {
    val osName = System.getProperty("os.name").lowercase()
    val platform = when {
        osName.contains("linux") -> "linux"
        osName.contains("mac") || osName.contains("darwin") -> "darwin"
        osName.contains("windows") -> "windows"
        else -> error("Unsupported native AOT JVM test operating system: $osName")
    }
    val extension = when (platform) {
        "linux" -> "so"
        "darwin" -> "dylib"
        "windows" -> "dll"
        else -> error("Unsupported native AOT JVM test platform: $platform")
    }
    val architecture = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        else -> error("Unsupported native AOT JVM test architecture: ${System.getProperty("os.arch")}")
    }
    project(":wasmline-engine-cranelift").file(
        "src/jvmMain/resources/jni/$platform/$architecture/libwasmline.$extension",
    )
}
val nativeAotJvmLibraryPath = providers.environmentVariable("WASMLINE_NATIVE_LIBRARY_PATH")
    .orElse(nativeAotJvmLibrary.absolutePath)
val nativeAotTestClassPatterns = listOf(
    "**/NativeDirectInvocationTest.class",
    "**/NativeComponentServiceIntegrationTest.class",
    "**/NativeComponentResourceIntegrationTest.class",
    "**/NativeTypedComponentFlagsHostImportIntegrationTest.class",
    "**/NativeTypedComponentHostImportIntegrationTest.class",
    "**/NativeTypedComponentOptionResultHostImportIntegrationTest.class",
    "**/NativeTypedComponentShapesHostImportIntegrationTest.class",
    "**/NativeTypedComponentStringHostImportIntegrationTest.class",
    "**/NativeTypedComponentStringInputHostImportIntegrationTest.class",
    "**/NativeTypedComponentVariantEnumHostImportIntegrationTest.class",
)
val standardJvmTest = tasks.named<Test>("jvmTest") {
    exclude(*nativeAotTestClassPatterns.toTypedArray())
}

tasks.register<Test>("nativeAotJvmTest") {
    group = "verification"
    description = "Runs native JVM tests against generated and verified AOT fixture artifacts."
    dependsOn("jvmTestClasses", nativeAotFixtureTask)
    inputs.file(nativeAotFixtureIndex)
    testClassesDirs = standardJvmTest.get().testClassesDirs
    classpath = standardJvmTest.get().classpath
    include(*nativeAotTestClassPatterns.toTypedArray())
    systemProperty("wasmline.native.fixtures.index", nativeAotFixtureIndex.get().asFile.absolutePath)
    systemProperty("wasmline.native.library.path", nativeAotJvmLibraryPath.get())
}

if (HostManager.hostIsMac) {
    tasks.withType<KotlinNativeTest>().configureEach {
        if (name == "iosSimulatorArm64Test") {
            dependsOn(nativeAotFixtureTask)
            inputs.file(nativeAotFixtureIndex)
            environment("WASMLINE_NATIVE_FIXTURE_INDEX", nativeAotFixtureIndex.get().asFile.absolutePath)
        }
    }
}

tasks.register<JavaExec>("wasmlineBenchmark") {
    dependsOn("jvmTestClasses")
    val jvmTestTask = tasks.named<Test>("jvmTest")
    mainClass.set("crow.wasmline.test.wasmtime.WasmlineInvocationBenchmark")
    classpath = jvmTestTask.get().classpath
    systemProperty("wasmline.benchmark.mode", providers.gradleProperty("benchmark.mode").orNull ?: "invocation")
    systemProperty("wasmline.benchmark.warmup", providers.gradleProperty("benchmark.warmup").orNull ?: "32")
    systemProperty("wasmline.benchmark.iterations", providers.gradleProperty("benchmark.iterations").orNull ?: "256")
    systemProperty("wasmline.benchmark.coldSamples", providers.gradleProperty("benchmark.coldSamples").orNull ?: "5")
    systemProperty("wasmline.benchmark.supportConcurrent", providers.gradleProperty("benchmark.supportConcurrent").orNull ?: "false")
    providers.gradleProperty("benchmark.wasmlineCoreAot").orNull?.let {
        systemProperty("wasmline.benchmark.wasmlineCoreAot", it)
    }
    providers.gradleProperty("benchmark.rawExportAot").orNull?.let {
        systemProperty("wasmline.benchmark.rawExportAot", it)
    }
    providers.gradleProperty("benchmark.componentAot").orNull?.let {
        systemProperty("wasmline.benchmark.componentAot", it)
    }
}
