@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.aot.WasmlineVersionRange
import crow.wasmline.plugin.core.aot.encodeForTaskInput
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File
import javax.inject.Inject

/**
 * Selects the local AOT generations a Wasmline build compiles.
 *
 * An omitted block, an empty block, and a block that only configures
 * [suppressCompatibilityWarning] all select every generation retained in the
 * packaged catalog. The first [generations] call replaces that implicit
 * default; later calls add their generations to the union.
 *
 * ```kotlin
 * wasmline {
 *     wasmtime {
 *         aotCompatibility {
 *             generations("1.0.0")
 *             generations(from = "1.0.1", through = "latest")
 *         }
 *     }
 * }
 * ```
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
public abstract class AotCompatibilityExtension @Inject constructor(objects: ObjectFactory) {
    private val generationRangesProperty: ListProperty<String> = objects.listProperty(String::class.java)
        .convention(emptyList())

    /** Controls whether the post-build compatibility warning is omitted from Gradle logging. */
    public val suppressCompatibilityWarning: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(false)

    /**
     * Selects the AOT generation that contains one Wasmline version.
     *
     * `"latest"` selects the generation of the catalog's current Wasmline
     * version; any other value must be a stable `x.y.z` version inside the
     * local catalog.
     */
    public fun generations(version: String) {
        generationRangesProperty.add(WasmlineVersionRange(from = version, through = version).encodeForTaskInput())
    }

    /**
     * Selects every AOT generation intersecting the inclusive
     * `from`..`through` Wasmline version range.
     *
     * `from` must be a stable `x.y.z` version; `through` may also be
     * `"latest"`, which resolves to the catalog's current version.
     */
    public fun generations(from: String, through: String) {
        require(from != "latest") {
            "AOT generation range start must be a stable x.y.z version; " +
                "use generations(\"latest\") to select the current generation."
        }
        generationRangesProperty.add(WasmlineVersionRange(from = from, through = through).encodeForTaskInput())
    }

    internal val generationRanges: Provider<List<String>>
        get() = generationRangesProperty
}

/**
 * Configures catalog-backed multi-profile Wasmtime AOT compilation.
 *
 * ```kotlin
 * wasmline {
 *     wasmtime {
 *         aotCompatibility {
 *             generations("latest")
 *         }
 *         targets = listOf(WasmtimeTarget.PULLEY_64, WasmtimeTarget.X86_64_LINUX)
 *         autoDownload.set(true)
 *     }
 * }
 * ```
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
public abstract class WasmtimeExtension @Inject constructor(objects: ObjectFactory) {
    /** Wasmline version selection shared by Core and Component AOT builds. */
    public val aotCompatibility: AotCompatibilityExtension = objects.newInstance(AotCompatibilityExtension::class.java)

    private val configuredTargets: ListProperty<WasmtimeTarget> = objects.listProperty(WasmtimeTarget::class.java)
        .convention(WasmtimeTarget.ALL)

    internal val targetsProvider: Provider<List<WasmtimeTarget>>
        get() = configuredTargets

    /** Physical AOT targets; assigning an empty list restores all known targets. */
    public var targets: List<WasmtimeTarget>
        get() = configuredTargets.get()
        set(value) {
            configuredTargets.set(value.ifEmpty { WasmtimeTarget.ALL }.distinct())
        }

    /** Downloads missing catalog compiler assets after digest verification. */
    public val autoDownload: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /** Content-addressed compiler cache shared by Wasmline builds. */
    public val compilerCacheDirectory: DirectoryProperty = objects.directoryProperty().apply {
        set(File(System.getProperty("user.home"), ".wasmline/toolchains/wasmtime/compiler-assets"))
    }

    /** Maximum concurrent Wasmtime compiler processes. */
    public val maxParallelCompilations: Property<Int> = objects.property(Int::class.java)
        .convention(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))

    /** Optional GitHub token used only when downloading locked compiler assets. */
    public val githubToken: Property<String> = objects.property(String::class.java)

    /** Configures the [aotCompatibility] generation selection. */
    public fun aotCompatibility(action: AotCompatibilityExtension.() -> Unit) {
        aotCompatibility.action()
    }
}
