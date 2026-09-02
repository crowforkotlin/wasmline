import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/** Runs the native fixture assembler with declared Gradle inputs and outputs.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
@CacheableTask
abstract class AssembleNativeFixturesTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val fixtureSourceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureSourceFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directComponentFixture: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val componentServiceWitDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aotCompatibilityCatalog: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aotCompatibilityLock: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val toolchainLock: RegularFileProperty

    @get:Input
    abstract val targets: ListProperty<String>

    @get:Input
    abstract val fixtureIds: ListProperty<String>

    @get:Input
    abstract val buildHost: Property<String>

    @get:Input
    abstract val autoDownload: Property<Boolean>

    @get:Input
    abstract val maxParallelCompilations: Property<Int>

    @get:Classpath
    abstract val fixtureClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        group = "verification"
        description = "Builds verified native AOT fixtures and their immutable test index."
    }

    /** Executes the fixture assembler in an isolated Java process. */
    @TaskAction
    fun assemble() {
        execOperations.javaexec {
            classpath(fixtureClasspath)
            mainClass.set("crow.wasmline.test.fixtures.NativeFixtureMainKt")
            args(
                "--fixture-source", fixtureSourceDirectory.get().asFile.absolutePath,
                "--direct-component", directComponentFixture.get().asFile.absolutePath,
                "--service-wit", componentServiceWitDirectory.get().asFile.absolutePath,
                "--aot-catalog", aotCompatibilityCatalog.get().asFile.absolutePath,
                "--aot-lock", aotCompatibilityLock.get().asFile.absolutePath,
                "--toolchain-lock", toolchainLock.get().asFile.absolutePath,
                "--output", outputDirectory.get().asFile.absolutePath,
                "--targets", targets.get().joinToString(","),
                "--fixture-ids", fixtureIds.get().joinToString(","),
                "--build-host", buildHost.get(),
            )
            systemProperty("wasmline.native.fixtures.autoDownload", autoDownload.get())
            systemProperty("wasmline.native.fixtures.maxParallelCompilations", maxParallelCompilations.get())
        }
    }
}

dependencies {
    implementation(projects.wasmlinePluginCore)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("crow.wasmline.plugin.core.InternalWasmlineToolingApi")
    }
}

val fixtureTargets = providers.gradleProperty("wasmline.native.fixtures.targets")
    .map { value ->
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .also { targets -> require(targets.isNotEmpty()) { "wasmline.native.fixtures.targets must contain at least one target." } }
    }
    .orElse(listOf("x86_64-linux", "pulley64"))

val configuredFixtureIds = providers.gradleProperty("wasmline.native.fixtures.ids")
    .map { value ->
        value.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .also { ids ->
                require(ids.isNotEmpty()) { "wasmline.native.fixtures.ids must contain at least one fixture ID." }
                require(ids.distinct().size == ids.size) { "wasmline.native.fixtures.ids must not contain duplicate fixture IDs." }
            }
    }
    .orElse(emptyList())

/** Resolves the catalog build-host identifier without depending on implementation classes in the build script. */
fun fixtureBuildHost(): String {
    val operatingSystem = when {
        System.getProperty("os.name").contains("win", ignoreCase = true) -> "windows"
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
        System.getProperty("os.name").contains("linux", ignoreCase = true) -> "linux"
        else -> error("Unsupported fixture build operating system: ${System.getProperty("os.name")}")
    }
    val architecture = when {
        System.getProperty("os.arch").contains("amd64", ignoreCase = true) ||
            System.getProperty("os.arch").contains("x86_64", ignoreCase = true) -> "x86_64"

        System.getProperty("os.arch").contains("aarch64", ignoreCase = true) ||
            System.getProperty("os.arch").contains("arm64", ignoreCase = true) -> "aarch64"

        else -> error("Unsupported fixture build architecture: ${System.getProperty("os.arch")}")
    }
    return "$architecture-$operatingSystem"
}

tasks.register<AssembleNativeFixturesTask>("assembleNativeTestFixtures") {
    dependsOn("classes")

    val fixtureSourceDir = layout.projectDirectory.dir("src/fixtures")
    val directComponentFile = project(":wasmline")
        .layout.projectDirectory.file("src/jvmTest/resources/fixtures/component-export.wasm")
    val componentServiceWitDir = project(":wasmline-plugin-core").layout.projectDirectory.dir(
        "src/main/resources/META-INF/wasmline/wit/wasmline-service",
    )
    val aotCatalogFile = rootProject.layout.projectDirectory.file("../aot-compatibility.json")
    val aotLockFile = project(":wasmline-plugin-core").layout.projectDirectory.file(
        "src/main/resources/META-INF/wasmline/aot/aot-compatibility-lock.json",
    )
    val toolchainLockFile = project(":wasmline-plugin-core").layout.projectDirectory.file(
        "src/main/resources/META-INF/wasmline/toolchain/toolchain-lock.json",
    )
    val outputDir = layout.buildDirectory.dir("wasmline/native-fixtures")
    val buildHostProvider = providers.systemProperty("wasmline.native.fixtures.build.host")
        .orElse(fixtureBuildHost())

    fixtureSourceDirectory.set(fixtureSourceDir)
    fixtureSourceFiles.from(
        fileTree(fixtureSourceDir) {
            exclude("**/target/**")
        },
    )
    directComponentFixture.set(directComponentFile)
    componentServiceWitDirectory.set(componentServiceWitDir)
    aotCompatibilityCatalog.set(aotCatalogFile)
    aotCompatibilityLock.set(aotLockFile)
    toolchainLock.set(toolchainLockFile)
    targets.set(fixtureTargets)
    fixtureIds.set(configuredFixtureIds)
    buildHost.set(buildHostProvider)
    autoDownload.set(true)
    maxParallelCompilations.set(1)
    fixtureClasspath.from(sourceSets.main.get().runtimeClasspath)
    outputDirectory.set(outputDir)
}
