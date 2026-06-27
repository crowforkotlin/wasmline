@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage")

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
        val commonMain by getting {
            dependencies {
                compileOnly(projects.wasmline)
            }
        }
        // Exclude native libraries from main JVM JAR (published as platform-specific variants)
        val jvmMain by getting {
            resources.excludes.add("jni/**")
        }
    }
}

// Capability conflict: only ONE engine module should be on the classpath.
// Note: mutual exclusion is enforced at the Gradle level via component capabilities.
// Consumers should depend on only ONE engine module (pulley OR cranelift, not both).

// ── Platform-specific native library publishing ──────────────────

val platformMap = mapOf(
    "linux"   to listOf("x86_64" to "x86-64", "aarch64" to "aarch64"),
    "darwin"  to listOf("aarch64" to "aarch64", "x86_64" to "x86-64"),
    "windows" to listOf("x86_64" to "x86-64"),
)

data class NativeVariant(val platform: String, val archDir: String, val archAttr: String, val taskName: String)

val nativeVariants = mutableListOf<NativeVariant>()

platformMap.forEach { (platform, archs) ->
    archs.forEach { (archDir, archAttr) ->
        val capitalPlatform = platform.replaceFirstChar { it.uppercase() }
        val capitalArch = archDir.replaceFirstChar { it.uppercase() }
        val taskName = "craneliftNative${capitalPlatform}${capitalArch}"
        val jniDir = layout.projectDirectory.dir("src/jvmMain/resources/jni/$platform/$archDir")

        val jarTask = tasks.register<Jar>(taskName) {
            archiveClassifier.set("$platform-$archDir")
            from(jniDir)
            into("jni/$platform/$archDir")
        }

        publishing.publications {
            register<MavenPublication>("$taskName") {
                // Publish under the JVM module's artifactId so files land in the correct Maven directory
                artifactId = "${project.name}-jvm"
                artifact(jarTask)
                pom {
                    name.set("Wasmline Engine Cranelift ($platform-$archDir)")
                    description.set("Cranelift native library for $platform $archDir")
                }
            }
        }

        nativeVariants.add(NativeVariant(platform, archDir, archAttr, taskName))
    }
}

// Post-process the generated .module file to inject native variant definitions
// This enables Gradle variant-aware resolution for platform-specific native JARs
tasks.withType<org.gradle.api.publish.tasks.GenerateModuleMetadata>().configureEach {
    doLast {
        val moduleFile = outputFile.get().asFile
        if (!moduleFile.exists()) return@doLast

        val json = groovy.json.JsonSlurper().parse(moduleFile) as MutableMap<String, Any>
        val variants = json["variants"] as? MutableList<Any> ?: return@doLast

        val groupId = project.group.toString()
        // Use JVM module artifactId since native JARs are published under the -jvm Maven coordinates
        val artifactId = "${project.name}-jvm"
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
                        "org.gradle.native.operating-system" to v.platform,
                        "org.gradle.native.architecture" to v.archAttr
                    ),
                    "capabilities" to listOf(
                        mapOf("group" to groupId, "name" to artifactId, "version" to version)
                    ),
                    "files" to listOf(
                        mapOf(
                            "name" to "$artifactId-$version-${v.platform}-${v.archDir}.jar",
                            "url" to "$artifactId-$version-${v.platform}-${v.archDir}.jar"
                        )
                    )
                )
            )
        }

        moduleFile.writeText(groovy.json.JsonOutput.toJson(json))
        logger.lifecycle("Injected ${nativeVariants.size} native variants into ${moduleFile.name}")
    }
}
