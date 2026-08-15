@file:Suppress("OPT_IN_USAGE", "unused", "UnstableApiUsage", "SpellCheckingInspection")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.java.test.fixtures)
    idea
}

/**
 * Configuration for Kotlin Compiler Plugin Development Environment.
 *
 * This setup manages the intricate relationship between the compiler plugin,
 * its target runtime, and the simulated compiler testing environment.
 * * Key Logic:
 * 1. [wasmlineRuntimeClasspath]: A "Precision-Filtered" configuration.
 * - Uses [isTransitive = false] to isolate only the primary artifact.
 * - Uses [Attributes] (Usage, Category, LibraryElements) to force-resolve
 * the physical .jar file instead of the default classes directory.
 * - Purpose: Provides a clean, single-path JAR to the compiler's
 * simulated classpath via system properties.
 *
 * 2. [testArtifacts]: A "Dependency Collector" for the Test Sandbox.
 * - Manually aggregates essential libraries (stdlib, reflect) required
 * for the guest code to execute during compiler-driven tests.
 *
 * 3. `jvmRuntimeElements`: Target Variant Selection.
 * - Explicitly targets the JVM-specific output of the :wasmline module,
 * ensuring compatibility even in Multiplatform (KMP) environments.
 *
 * 2026-03-24 00:32:56
 * @author crowforkotlin
 */
val wasmlineRuntimeClasspath: Configuration by configurations.creating {
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}
val testArtifacts: Configuration by configurations.creating
val generateTests: TaskProvider<JavaExec> by tasks.registering(type = JavaExec::class) {
    // Defines 'testData' as task input to enable incremental build (skip if unchanged)
    inputs.dir(layout.projectDirectory.dir("testData"))
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Defines 'test-gen' as the task output directory for Gradle to track generated files
    outputs.dir(layout.projectDirectory.dir("test-gen"))
        .withPropertyName("generatedTests")

    // Uses the runtime classpath of 'testFixtures' where the generator code resides
    classpath = sourceSets["testFixtures"].runtimeClasspath

    // The entry point class that scans 'testData' and writes JUnit files
    mainClass.set("crow.wasmline.kotlin.GenerateTestsKt")

    // Sets the root directory for the generator's internal file operations
    workingDir = project.rootDir

    // Passes absolute paths as system properties for the generator to locate source and target
    systemProperty("wasmline.kotlin.plugin.projectDir", project.projectDir.absolutePath)
    systemProperty("wasmline.kotlin.plugin.testsRootDir", project.projectDir.resolve("test-gen").absolutePath)

    // Ensures the target directory is empty before generation to avoid stale test files
    doFirst {
        delete(project.projectDir.resolve("test-gen"))
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    explicitApi()
}

buildConfig {
    val packageName = libs.plugins.wasmline.kotlin.get()
    useKotlinOutput(configure = { internalVisibility = true })
    packageName(packageName = packageName.toString())
    buildConfigField(type = "String", name = "KOTLIN_PLUGIN_ID", expression = "\"${packageName}\"")
}

sourceSets {
    named("testFixtures") {
        java.setSrcDirs(listOf(layout.projectDirectory.dir("test-fixtures")))
    }
    named("test") {
        java.srcDirs("src/test/kotlin", layout.projectDirectory.dir("test-gen"))
        resources.srcDirs(layout.projectDirectory.dir("testData"))
    }
}

idea {
    module.generatedSourceDirs.add(projectDir.resolve("test-gen"))
}

dependencies {

    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlin.compiler)
    compileOnly(libs.kotlin.stdlib)

    implementation(projects.wasmline)
    implementation(projects.wasmlineLoader)

    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)

    implementation(libs.compress.apache.common)
    implementation(libs.compress.tukaani.xz)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    implementation(libs.okio.core)

    testImplementation(libs.kotlin.test)
    testImplementation(testFixtures(project))

    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.compiler)
    testFixturesApi(libs.kotlin.compiler.internal.test.framework)
    // Command -> ./gradlew :wasmline:outgoingVariants
    wasmlineRuntimeClasspath(dependency = projects.wasmline, dependencyConfiguration = { targetConfiguration = "jvmRuntimeElements" })

    testArtifacts(libs.kotlin.stdlib)
    testArtifacts(libs.kotlin.stdlib.jdk8)
    testArtifacts(libs.kotlin.reflect)
    testArtifacts(libs.kotlin.test)
    testArtifacts(libs.kotlin.script.runtime)
    testArtifacts(libs.jetbrains.annotations)
    testArtifacts(libs.kotlinx.serialization.protobuf)
}

/**
 * Configures the project for Maven publishing using the MavenPublishBaseExtension.
 * This setup defines the publication platform as Kotlin JVM and provides
 * an empty Javadoc JAR to satisfy repository requirements while optimizing
 * build time.
 *
 * After running the 'publishToMavenLocal' task, this artifact will be
 * available at: ~/.m2/repository/crow/wasmline/wasmline-kotlin-plugin/
 *
 * 2026-03-24 00:56:58
 * @author crowforkotlin
 */
configure<MavenPublishBaseExtension> {
    configure(
        platform = KotlinJvm(
            javadocJar = JavadocJar.Empty(),
        ),
    )
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
}
tasks.test {
    fun Test.setLibraryProperty(propName: String, jarName: String) {
        val path = testArtifacts.files
            .find { it.name.startsWith(prefix = jarName) && it.extension == "jar" }
            ?.absolutePath
            ?: return
        systemProperty(propName, path)
    }
    useJUnitPlatform()
    workingDir = project.rootDir
    systemProperty("wasmlineRuntime.classpath", wasmlineRuntimeClasspath.asPath)
    systemProperty("wasmlineTestArtifacts.classpath", testArtifacts.asPath)
    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", project.rootDir)
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "annotations")
}
