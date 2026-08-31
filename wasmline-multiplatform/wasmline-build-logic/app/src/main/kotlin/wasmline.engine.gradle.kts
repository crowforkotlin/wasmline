@file:Suppress("OPT_IN_USAGE", "UnstableApiUsage", "unused")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish.base")
    id("org.jetbrains.dokka")
}

val engineName = project.name.removePrefix("wasmline-engine-")
require(engineName == "pulley" || engineName == "cranelift") {
    "Unsupported engine project '" + project.name +
        "'; expected wasmline-engine-pulley or wasmline-engine-cranelift"
}

val engineNameCapitalized = engineName.replaceFirstChar { it.uppercase() }
val engineNamespace = "crow.wasmline.engine." + engineName
val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val androidCompileSdk = versionCatalog.findVersion("android-compileSdk").get().requiredVersion.toInt()
val androidMinSdk = versionCatalog.findVersion("android-minSdk").get().requiredVersion.toInt()

configure<MavenPublishBaseExtension> {
    configure(
        platform = KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            androidVariantsToPublish = emptyList(),
        ),
    )
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

extensions.configure<KotlinMultiplatformExtension> {
    jvm()
    android {
        namespace = engineNamespace
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
    }
    val nativeTargets: List<KotlinNativeTarget> = buildList {
        if (HostManager.hostIsMac) {
            // Only Pulley has Wasmtime iOS archives; Cranelift has no iOS archive.
            if (engineName == "pulley") {
                add(iosArm64())
                add(iosSimulatorArm64())
            }
            add(macosArm64())
        }
        add(linuxArm64())
        add(linuxX64())
        add(mingwX64())
    }
    val nativeHeader = rootProject.file("wasmline/src/nativeMain/native")
    val nativeBridgeSources = rootProject.files(
        rootProject.file("../scripts/internal/native/build-kotlin-native.sh"),
        rootProject.file("../scripts/config/wasmtime-targets.json"),
        rootProject.fileTree("../scripts/lib/shell") {
            include("*.sh")
        },
        rootProject.file("../versions.json"),
        rootProject.fileTree("../wasmline-core/include") {
            include("**/*.h", "**/*.hpp")
        },
        rootProject.fileTree("../wasmline-core/src") {
            include("**/*.cpp", "**/*.h", "**/*.hpp")
        },
        rootProject.fileTree(nativeHeader) {
            include("**/*.cpp", "**/*.h", "**/*.hpp")
        },
    )
    val wasmtimeVersion = rootProject.property("wasmtime.version") as String
    val wasmtimeReleaseVersion = rootProject.property("wasmtime.release.version") as String
    val wasmtimeTag = "v$wasmtimeReleaseVersion"
    fun assetRoot(targetName: String): File = when (targetName) {
        "iosArm64" -> rootProject.file("../build/platforms/$wasmtimeTag/$engineName/ios/arm64")
        "iosSimulatorArm64" -> rootProject.file("../build/platforms/$wasmtimeTag/$engineName/ios/simulator-arm64")
        "macosArm64" -> rootProject.file("../build/platforms/$wasmtimeTag/$engineName/mac/aarch64")
        "linuxArm64" -> rootProject.file("../build/platforms/$wasmtimeTag/$engineName/linux/aarch64")
        "linuxX64" -> rootProject.file("../build/platforms/$wasmtimeTag/$engineName/linux/x64")
        "mingwX64" -> rootProject.file("../build/platforms/$wasmtimeTag/$engineName/windows/x64")
        else -> error("Unsupported Native target '$targetName'.")
    }
    nativeTargets.forEach { target ->
        val asset = assetRoot(target.name)
        val bridgeArchive = rootProject.file("wasmline/build/native/${target.name}/$engineName/libwasmline_native.a")
        val bridgeTask = tasks.register<org.gradle.api.tasks.Exec>(
            "build${engineNameCapitalized}${target.name.replaceFirstChar { it.uppercaseChar() }}NativeBridge",
        ) {
            workingDir = rootProject.projectDir
            commandLine("bash", "../scripts/internal/native/build-kotlin-native.sh", target.name, engineName)
            // The bridge compiler uses Kotlin/Native's target toolchain directly.
            // Keep it behind the download task because native link/cinterop tasks
            // may otherwise schedule both tasks concurrently on a clean runner.
            dependsOn(tasks.named("downloadKotlinNativeDistribution"))
            inputs.files(nativeBridgeSources).withPropertyName("nativeBridgeSources")
            inputs.dir(asset.resolve("include")).withPropertyName("wasmtimeHeaders").optional()
            inputs.file(asset.resolve("lib/libwasmtime.a")).withPropertyName("wasmtimeArchive").optional()
            inputs.property("nativeTarget", target.name)
            inputs.property("nativeEngine", engineName)
            inputs.property("wasmtimeVersion", wasmtimeVersion)
            inputs.property("wasmtimeReleaseVersion", wasmtimeReleaseVersion)
            outputs.file(bridgeArchive)
        }
        val systemLinkerOptions = when (target.name) {
            "linuxX64", "linuxArm64" -> listOf("-ldl", "-lpthread", "-lm", "-lstdc++", "-lstdc++fs")
            "mingwX64" -> listOf("-lbcrypt", "-luserenv", "-lole32", "-luuid", "-lstdc++")
            else -> listOf("-lc++")
        }
        val generatedDefinition = project.layout.buildDirectory.file(
            "generated/wasmline-native/${target.name}/wasmlineEngine.def",
        )
        val generateDefinitionTask = tasks.register(
            "generate${engineNameCapitalized}${target.name.replaceFirstChar { it.uppercaseChar() }}NativeCInteropDefinition",
        ) {
            inputs.property("bridgeArchivePath", bridgeArchive.absolutePath)
            inputs.property("systemLinkerOptions", systemLinkerOptions)
            outputs.file(generatedDefinition)
            doLast {
                val definitionFile = generatedDefinition.get().asFile
                definitionFile.parentFile.mkdirs()
                val linkerOptions = buildList {
                    addAll(systemLinkerOptions)
                }
                definitionFile.writeText(
                    buildString {
                        appendLine("package = crow.wasmline.engine.native")
                        appendLine("headers = WasmlineEngineLink.h")
                        appendLine("staticLibraries = ${bridgeArchive.name}")
                        appendLine("libraryPaths = ${bridgeArchive.parentFile.absolutePath.replace('\\', '/')}")
                        appendLine("linkerOpts = ${linkerOptions.joinToString(" ")}")
                    },
                )
            }
        }
        target.compilations.getByName("main") {
            val wasmline by cinterops.creating {
                definitionFile.set(generatedDefinition)
                includeDirs(nativeHeader, asset.resolve("include"))
                compilerOpts("-I${nativeHeader.absolutePath}", "-I${asset.resolve("include").absolutePath}")
            }
        }
        tasks.configureEach {
            if (name != generateDefinitionTask.name &&
                name.contains("cinterop", ignoreCase = true) &&
                name.contains(target.name, ignoreCase = true)
            ) {
                dependsOn(bridgeTask, generateDefinitionTask)
                inputs.file(bridgeArchive).withPropertyName("wasmlineNativeBridge")
            }
        }
        target.binaries.all {
            linkerOpts(*systemLinkerOptions.toTypedArray())
            linkTaskProvider.configure { dependsOn(bridgeTask) }
        }
    }
    applyDefaultHierarchyTemplate()
}

// Keep the published JVM API JAR platform-neutral. Native libraries are published
// through the OS/architecture variants below; project dependencies still see all
// resources from jvmMain for local JVM tests.
tasks.named<Jar>("jvmJar") {
    exclude("jni/**")
}

// Both backends expose the same native capability. Depending on both modules is
// therefore a real Gradle conflict instead of an accidental duplicate libwasmline.
val defaultProjectCapability = listOf(
    project.group.toString(),
    project.name,
    project.version.toString(),
).joinToString(":")
val nativeEngineCapability = listOf(
    project.group.toString(),
    "wasmline-native-engine",
    project.version.toString(),
).joinToString(":")
val swiftPmMetadataConfigurationPrefix = "swiftPMDependenciesMetadataElementsFor"
configurations.configureEach {
    if (isCanBeConsumed) {
        outgoing.capability(defaultProjectCapability)
        // SwiftPM lockfile metadata describes dependencies but does not link an engine.
        if (!name.startsWith(swiftPmMetadataConfigurationPrefix)) {
            outgoing.capability(nativeEngineCapability)
        }
    }
}

data class NativeVariant(
    val platform: String,
    val archDir: String,
    val gradleOs: String,
    val gradleArch: String,
    val taskName: String,
    val jarTask: org.gradle.api.tasks.TaskProvider<Jar>,
)

val platformMap = mapOf(
    "linux" to listOf("x86_64" to "x86-64", "aarch64" to "aarch64"),
    "darwin" to listOf("aarch64" to "aarch64", "x86_64" to "x86-64"),
    "windows" to listOf("x86_64" to "x86-64"),
)
val osAttrMap = mapOf("linux" to "linux", "darwin" to "macos", "windows" to "windows")
val nativeVariants = mutableListOf<NativeVariant>()

platformMap.forEach { (platform, archs) ->
    val gradleOs = osAttrMap.getValue(platform)
    archs.forEach { (archDir, gradleArch) ->
        val capitalPlatform = platform.replaceFirstChar { it.uppercase() }
        val capitalArch = archDir.replaceFirstChar { it.uppercase() }
        val taskName = engineName + "Native" + capitalPlatform + capitalArch
        val jniDir = layout.projectDirectory.dir("src/jvmMain/resources/jni/$platform/$archDir")
        val jarTask = tasks.register<Jar>(taskName) {
            archiveClassifier.set("$platform-$archDir")
            from(jniDir)
            into("jni/$platform/$archDir")
        }
        nativeVariants.add(NativeVariant(platform, archDir, gradleOs, gradleArch, taskName, jarTask))
    }
}

val androidAbis = if (engineName == "pulley") {
    listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
} else {
    listOf("arm64-v8a", "x86_64")
}
val requiredAssets = buildList {
    androidAbis.forEach { abi ->
        add(project.file("src/androidMain/jniLibs/$abi/libwasmline.so"))
    }
    platformMap.forEach { (platform, archs) ->
        archs.forEach { (archDir, _) ->
            val extension = when (platform) {
                "linux" -> "so"
                "darwin" -> "dylib"
                "windows" -> "dll"
                else -> error("Unsupported platform '$platform'")
            }
            add(project.file("src/jvmMain/resources/jni/$platform/$archDir/libwasmline.$extension"))
        }
    }
}

val verifyEngineAssets = tasks.register("verify" + engineNameCapitalized + "Assets") {
    inputs.files(requiredAssets)
    doLast {
        val missing = requiredAssets.filterNot { it.isFile }
        check(missing.isEmpty()) {
            buildString {
                appendLine("Missing native assets for the " + engineName + " engine:")
                missing.forEach { file -> appendLine("  - " + file.relativeTo(project.projectDir)) }
                append("Run './scripts/wasmline jni build --engine " + engineName + "' before publishing.")
            }
        }
    }
}
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(verifyEngineAssets)
}

afterEvaluate {
    extensions.getByType<PublishingExtension>().publications.named<MavenPublication>("jvm") {
        nativeVariants.forEach { variant ->
            artifact(variant.jarTask)
        }
    }
}

tasks.named<GenerateModuleMetadata>("generateMetadataFileForJvmPublication") {
    doLast {
        val moduleFile = outputFile.get().asFile
        if (!moduleFile.exists()) return@doLast

        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(moduleFile) as MutableMap<String, Any>
        @Suppress("UNCHECKED_CAST")
        val variants = json["variants"] as? MutableList<Any> ?: return@doLast
        val existingNames = variants.filterIsInstance<Map<*, *>>().map { it["name"] }
        val nativePrefix = engineName + "Native"
        if (existingNames.any { (it as? String)?.startsWith(nativePrefix) == true }) return@doLast

        val jvmModuleName = project.name + "-jvm"
        val version = project.version.toString()
        nativeVariants.forEach { variant ->
            val nativeJarName = listOf(
                jvmModuleName,
                version,
                variant.platform,
                variant.archDir + ".jar",
            ).joinToString("-")
            variants.add(
                mapOf(
                    "name" to variant.taskName,
                    "attributes" to mapOf(
                        "org.gradle.category" to "library",
                        "org.gradle.usage" to "java-runtime",
                        "org.gradle.jvm.environment" to "standard-jvm",
                        "org.gradle.libraryelements" to "jar",
                        "org.jetbrains.kotlin.platform.type" to "jvm",
                        "org.gradle.native.operatingSystem" to variant.gradleOs,
                        "org.gradle.native.architecture" to variant.gradleArch,
                    ),
                    "files" to listOf(
                        mapOf(
                            "name" to nativeJarName,
                            "url" to nativeJarName,
                        ),
                    ),
                ),
            )
        }
        moduleFile.writeText(JsonOutput.toJson(json))
        logger.lifecycle(
            "Injected " + nativeVariants.size + " native variants into " + moduleFile.name,
        )
    }
}

// Custom capabilities replace Gradle's implicit module capability. Restore the
// component's own capability so target publications remain directly resolvable.
tasks.withType<GenerateModuleMetadata>().configureEach {
    val publicationName = name
        .removePrefix("generateMetadataFileFor")
        .removeSuffix("Publication")
    val publicationSuffix = publicationName
        .takeUnless { it == "KotlinMultiplatform" }
        ?.lowercase()
        ?.let { "-$it" }
        .orEmpty()
    doLast {
        val moduleFile = outputFile.get().asFile
        if (!moduleFile.exists()) return@doLast

        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(moduleFile) as MutableMap<String, Any>
        @Suppress("UNCHECKED_CAST")
        val component = json["component"] as? Map<String, Any> ?: return@doLast
        val group = component["group"] as? String ?: return@doLast
        val module = component["module"] as? String ?: return@doLast
        val version = component["version"] as? String ?: return@doLast
        val publicationModule = "$module$publicationSuffix"
        @Suppress("UNCHECKED_CAST")
        val variants = json["variants"] as? MutableList<Any> ?: return@doLast
        val componentCapability = mapOf(
            "group" to group,
            "name" to module,
            "version" to version,
        )
        val publicationCapability = mapOf(
            "group" to group,
            "name" to publicationModule,
            "version" to version,
        )
        var changed = false
        variants.filterIsInstance<MutableMap<String, Any>>().forEach { variant ->
            @Suppress("UNCHECKED_CAST")
            val capabilities = variant["capabilities"] as? MutableList<Any> ?: return@forEach
            if (publicationSuffix.isNotEmpty()) {
                if (capabilities != mutableListOf(publicationCapability)) {
                    variant["capabilities"] = mutableListOf<Any>(publicationCapability)
                    changed = true
                }
            } else {
                if (capabilities.none { it == componentCapability }) {
                    capabilities.add(componentCapability)
                    changed = true
                }
                if (capabilities.none { it == publicationCapability }) {
                    capabilities.add(publicationCapability)
                    changed = true
                }
            }
        }
        if (changed) {
            moduleFile.writeText(JsonOutput.toJson(json))
        }
    }
}
