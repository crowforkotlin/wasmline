@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
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
}
