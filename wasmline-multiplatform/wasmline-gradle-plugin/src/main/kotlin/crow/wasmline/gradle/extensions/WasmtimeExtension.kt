@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL extension for configuring the wasmtime AOT compiler used
 * during the assembly tasks.
 *
 * ```kotlin
 * wasmline {
 *     wasmtime {
 *         directory = file(System.getenv("WASMTIME_MIN_HOME") ?: "$home/.wasmline/wasmtime")
 *         targets = listOf("pulley64", "aarch64-android", "x86_64-linux")
 *         
 *         // Optional: enable automatic download if wasmtime is not found
 *         autoDownload = true
 *         version = "latest" // or specific version like "v47.0.2"
 *     }
 * }
 * ```
 *
 * 2026/6/5
 * @author crowforkotlin
 */
abstract class WasmtimeExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Directory containing the `wasmtime` executable. This tool is
     * typically downloaded via the `wasmline download` CLI command.
     * 
     * If not configured, the plugin will attempt to use default paths:
     * - Environment variable: WASMTIME_ROOT
     * - User home: ~/.wasmline/wasmtime
     */
    val directory: DirectoryProperty = objects.directoryProperty()

    /**
     * Target architectures for AOT compilation. When empty, all common
     * targets defined in [crow.wasmline.cli.Compile.DEFAULT_TARGETS] are used.
     *
     * Supported values include: "pulley64", "x86_64-linux", "aarch64-linux",
     * "aarch64-android", "aarch64-macos", "aarch64-ios", "x86_64-windows".
     */
    val targets: ListProperty<String> = objects.listProperty(String::class.java)
        .convention(emptyList())

    /**
     * Enable automatic wasmtime download when the toolchain is not found.
     * 
     * Behavior:
     * - `true`: Attempt to download wasmtime before building (requires wasmline-cli accessible)
     * - `false`: Fail build with helpful instructions if wasmtime is missing
     * 
     * Default: `false` (explicit opt-in for safety)
     */
    val autoDownload: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(false)

    /**
     * Wasmtime version to download when autoDownload is enabled.
     * 
     * Examples:
     * - `"latest"` — Download the latest release
     * - `"v47.0.2"` — Specific version tag
     * - `"release-v47.0.2"` — GitHub release tag format
     * 
     * Default: `"latest"`
     */
    val version: Property<String> = objects.property(String::class.java)
        .convention("latest")
}
