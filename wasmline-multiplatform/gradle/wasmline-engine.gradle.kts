@file:Suppress("OPT_IN_USAGE", "UnstableApiUsage", "unused")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
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

val engineName = extra["wasmline.engine"] as? String
    ?: error("wasmline.engine must be set to pulley or cranelift before applying this script")
require(engineName == "pulley" || engineName == "cranelift") {
    "Unsupported wasmline.engine '$engineName'; expected pulley or cranelift"
}

val engineNameCapitalized = engineName.replaceFirstChar { it.uppercase() }
val engineNamespace = "crow.wasmline.engine.$engineName"
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
val nativeEngineCapability = "${project.group}:wasmline-native-engine:${project.version}"
configurations.configureEach {
    if (isCanBeConsumed) {
        outgoing.capability(nativeEngineCapability)
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
        val taskName = "${engineName}Native$capitalPlatform$capitalArch"
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

val verifyEngineAssets = tasks.register("verify${engineNameCapitalized}Assets") {
    inputs.files(requiredAssets)
    doLast {
        val missing = requiredAssets.filterNot { it.isFile }
        check(missing.isEmpty()) {
            buildString {
                appendLine("Missing native assets for the $engineName engine:")
                missing.forEach { appendLine("  - ${it.relativeTo(project.projectDir)}") }
                append("Run 'bash scripts/build-native-assets.sh $engineName' before publishing.")
            }
        }
    }
}
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(verifyEngineAssets)
}

afterEvaluate {
    publishing.publications.named<MavenPublication>("jvm") {
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
        val nativePrefix = "${engineName}Native"
        if (existingNames.any { (it as? String)?.startsWith(nativePrefix) == true }) return@doLast

        val jvmModuleName = "${project.name}-jvm"
        val version = project.version.toString()
        nativeVariants.forEach { variant ->
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
                            "name" to "${jvmModuleName}-$version-${variant.platform}-${variant.archDir}.jar",
                            "url" to "${jvmModuleName}-$version-${variant.platform}-${variant.archDir}.jar",
                        ),
                    ),
                ),
            )
        }
        moduleFile.writeText(JsonOutput.toJson(json))
        logger.lifecycle("Injected ${nativeVariants.size} native variants into ${moduleFile.name}")
    }
}
