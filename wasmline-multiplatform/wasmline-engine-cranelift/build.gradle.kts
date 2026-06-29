@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

import org.gradle.api.publish.tasks.GenerateModuleMetadata

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library.kmp)
    alias(libs.plugins.maven.publish)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvm()
    android {
        namespace = "crow.wasmline.engine.cranelift"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        // Include only the current host platform's native library in the standard JVM JAR.
        // This ensures the JAR is platform-specific (not bloated with all platforms' binaries)
        // while still making the native library available at runtime via getResource().
        val jvmMain by getting {
            val hostOs = when {
                "linux" in System.getProperty("os.name").lowercase() -> "linux"
                "mac" in System.getProperty("os.name").lowercase() -> "darwin"
                "windows" in System.getProperty("os.name").lowercase() -> "windows"
                else -> null
            }
            val hostArch = when (System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> null
            }
            if (hostOs != null && hostArch != null) {
                resources.filter.include("jni/$hostOs/$hostArch/**")
            }
        }
    }
}

// Capability conflict: only ONE engine module should be on the classpath.
// Note: mutual exclusion is enforced at the Gradle level via component capabilities.
// Consumers should depend on only ONE engine module (pulley OR cranelift, not both).

// ── Platform-specific native library publishing ──────────────────
// Each platform/arch combination is published as a standalone Maven artifact with a
// classifier (e.g. wasmline-engine-cranelift-jvm:1.0.0:linux-x86_64).
// The JVM sub-module's .module file is then patched to advertise these as Gradle
// variants with OS/arch attributes, enabling variant-aware resolution so consumers
// only need: implementation("crow.wasmline:wasmline-engine-cranelift:version")

val platformMap = mapOf(
    "linux"   to listOf("x86_64" to "x86-64", "aarch64" to "aarch64"),
    "darwin"  to listOf("aarch64" to "aarch64", "x86_64" to "x86-64"),
    "windows" to listOf("x86_64" to "x86-64"),
)

// Mapping from directory name to Gradle native OS attribute value
val osAttrMap = mapOf("linux" to "linux", "darwin" to "macos", "windows" to "windows")

data class NativeVariant(
    val platform: String,
    val archDir: String,
    val gradleOs: String,
    val gradleArch: String,
    val taskName: String,
)

val nativeVariants = mutableListOf<NativeVariant>()

platformMap.forEach { (platform, archs) ->
    val gradleOs = osAttrMap.getValue(platform)
    archs.forEach { (archDir, gradleArch) ->
        val capitalPlatform = platform.replaceFirstChar { it.uppercase() }
        val capitalArch = archDir.replaceFirstChar { it.uppercase() }
        val taskName = "craneliftNative${capitalPlatform}${capitalArch}"
        val jniDir = layout.projectDirectory.dir("src/jvmMain/resources/jni/$platform/$archDir")

        val jarTask = tasks.register<Jar>(taskName) {
            archiveClassifier.set("$platform-$archDir")
            from(jniDir)
            into("jni/$platform/$archDir")
        }

        // Publish each native JAR as a standalone Maven artifact (classified JAR of the JVM sub-module)
        publishing.publications {
            register<MavenPublication>(taskName) {
                artifactId = "${project.name}-jvm"
                artifact(jarTask)
                pom {
                    name.set("Wasmline Engine Cranelift ($platform-$archDir)")
                    description.set("Cranelift native library for $platform $archDir")
                }
            }
        }

        nativeVariants.add(NativeVariant(platform, archDir, gradleOs, gradleArch, taskName))
    }
}

// Inject native variant entries into the JVM sub-module's .module metadata.
// We hook into GenerateModuleMetadata.doLast to modify the file after Gradle generates it.
// When signing is configured, the Sign task runs AFTER GenerateModuleMetadata and will
// sign the already-modified file, ensuring a valid .asc signature.
tasks.named<GenerateModuleMetadata>("generateMetadataFileForJvmPublication") {
    doLast {
        val moduleFile = outputFile.get().asFile
        if (!moduleFile.exists()) return@doLast

        val json = groovy.json.JsonSlurper().parse(moduleFile) as MutableMap<String, Any>
        val variants = json["variants"] as? MutableList<Any> ?: return@doLast

        // Check if already injected (idempotent)
        val existingNames = variants.filterIsInstance<Map<*, *>>().map { it["name"] }
        if (existingNames.any { (it as? String)?.startsWith("native") == true }) return@doLast

        val jvmModuleName = "${project.name}-jvm"
        val version = project.version.toString()

        nativeVariants.forEach { v ->
            variants.add(
                mapOf(
                    "name" to v.taskName,
                    "attributes" to mapOf(
                        "org.gradle.category" to "library",
                        "org.gradle.usage" to "java-runtime",
                        "org.gradle.jvm.environment" to "standard-jvm",
                        "org.gradle.libraryelements" to "jar",
                        "org.jetbrains.kotlin.platform.type" to "jvm",
                        "org.gradle.native.operatingSystem" to v.gradleOs,
                        "org.gradle.native.architecture" to v.gradleArch,
                    ),
                    "files" to listOf(
                        mapOf(
                            "name" to "$jvmModuleName-$version-${v.platform}-${v.archDir}.jar",
                            "url" to "$jvmModuleName-$version-${v.platform}-${v.archDir}.jar",
                        )
                    )
                )
            )
        }

        moduleFile.writeText(groovy.json.JsonOutput.toJson(json))
        logger.lifecycle("Injected ${nativeVariants.size} native variants into ${moduleFile.name}")
    }
}
