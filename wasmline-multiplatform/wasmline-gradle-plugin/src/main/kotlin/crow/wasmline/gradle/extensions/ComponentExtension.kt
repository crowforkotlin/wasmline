package crow.wasmline.gradle.extensions

import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import java.io.File
import javax.inject.Inject

/** Configures WIT binding generation and Core Wasm componentization. */
open class ComponentExtension @Inject constructor(project: Project) {
    private val objects = project.objects

    /** Directory containing the selected WIT package and world. */
    val witDirectory: DirectoryProperty = objects.directoryProperty()
        .convention(project.layout.projectDirectory.dir("wit"))

    /** WIT world selected for binding generation and embedding. */
    val world: Property<String> = objects.property(String::class.java)
        .convention("plugin")

    /** Kotlin package patterns imported by generated wit-bindgen code. */
    val kotlinImports: Property<String> = objects.property(String::class.java)
        .convention("impl.*")

    /** Locked wit-bindgen release. */
    val witBindgenVersion: Property<String> = objects.property(String::class.java)
        .convention(ToolchainCatalog.WIT_BINDGEN_VERSION)

    /** Locked wasm-tools release. */
    val wasmToolsVersion: Property<String> = objects.property(String::class.java)
        .convention(ToolchainCatalog.WASM_TOOLS_VERSION)

    /** Download missing pinned tools automatically. */
    val autoDownload: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    /** Shared verified tool cache. */
    val toolCacheDirectory: DirectoryProperty = objects.directoryProperty().apply {
        set(File(System.getProperty("user.home"), ".wasmline/tools"))
    }

    /** Optional explicit wit-bindgen executable. */
    val witBindgenExecutable: RegularFileProperty = objects.fileProperty()

    /** Optional explicit wasm-tools executable. */
    val wasmToolsExecutable: RegularFileProperty = objects.fileProperty()

    /** Optional explicit WASI Preview 1 reactor adapter. */
    val wasiPreview1Adapter: RegularFileProperty = objects.fileProperty()

    /** Optional finished Component Wasm to validate and package without rebuilding it. */
    val componentInput: RegularFileProperty = objects.fileProperty()

    /** Export implementing the fixed Wasmline Component RPC envelope. */
    val exportName: Property<String> = objects.property(String::class.java)

    /** Serialization factory id carried by the Component RPC envelope. */
    val codec: Property<String> = objects.property(String::class.java)
        .convention(WasmlineComponentRpcContract.DEFAULT_CODEC)

    /** Wasmline Component RPC envelope version. */
    val rpcProtocolVersion: Property<String> = objects.property(String::class.java)
        .convention(WasmlineComponentRpcContract.VERSION)

    /** Generated Kotlin source directory. */
    val generatedSourcesDirectory: DirectoryProperty = objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("generated/wasmline/wit"))

    /** Enables ordinary Kotlin Host facade generation for the selected WIT world. */
    val hostBindingsEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Kotlin package used by generated Host-only bindings. */
    val hostKotlinPackage: Property<String> = objects.property(String::class.java)
        .convention("crow.wasmline.generated.host")

    /** Host source set receiving generated bindings in a Kotlin Multiplatform project. */
    val hostSourceSet: Property<String> = objects.property(String::class.java).convention("jvmMain")

    /** Generated Host Kotlin source directory, separate from guest wit-bindgen output. */
    val hostGeneratedSourcesDirectory: DirectoryProperty = objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("generated/wasmline/host-wit"))

    /** Enables own/borrow resource facade generation when the runtime supports it. */
    val hostResourceSupport: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Intermediate and final raw Component output root. */
    val outputDirectory: DirectoryProperty = objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("wasmline/component"))
}
