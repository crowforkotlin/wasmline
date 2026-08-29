@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.aot.AotCompatibilitySelection
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
 * Defines explicit native AOT compatibility selection for a Wasmline plugin.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
public abstract class AotCompatibilityExtension @Inject constructor(objects: ObjectFactory) {
    private val selectorKindProperty: Property<String> = objects.property(String::class.java)
    private val selectorRangesProperty: ListProperty<String> = objects.listProperty(String::class.java)
        .convention(emptyList())

    /** Controls whether the post-build compatibility warning is omitted from Gradle logging. */
    public val suppressCompatibilityWarning: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(false)

    /** Selects only the AOT generation used by the current Wasmline release. */
    public fun current() {
        select("current")
    }

    /** Selects generations from the effective minimum supported Wasmline release. */
    public fun minimum() {
        select("minimum")
    }

    /** Selects every generation in the packaged formal release catalog. */
    public fun all() {
        select("all")
    }

    /** Selects generations intersecting explicit closed Wasmline version ranges. */
    public fun versionRanges(action: VersionRangesSpec.() -> Unit) {
        val specification = VersionRangesSpec()
        specification.action()
        require(specification.ranges.isNotEmpty()) {
            "versionRanges { } must include at least one include(from = ..., through = ...) entry."
        }
        select(
            kind = "versionRanges",
            ranges = specification.ranges.map(WasmlineVersionRange::encodeForTaskInput),
        )
    }

    /** Returns the configured selector, or null when native AOT selection is missing. */
    internal fun selectionOrNull(): AotCompatibilitySelection? {
        val kind = selectorKindProperty.orNull ?: return null
        return when (kind) {
            "current" -> AotCompatibilitySelection.Current

            "minimum" -> AotCompatibilitySelection.Minimum

            "all" -> AotCompatibilitySelection.All

            "versionRanges" -> AotCompatibilitySelection.VersionRanges(
                selectorRangesProperty.get().map { encoded ->
                    val parts = encoded.split('\u0000')
                    require(parts.size == 2) { "Invalid encoded AOT version range." }
                    WasmlineVersionRange(parts[0], parts[1])
                },
            )

            else -> error("Unknown AOT compatibility selector '$kind'.")
        }
    }

    internal val selectorKind: Provider<String>
        get() = selectorKindProperty

    internal val selectorRanges: Provider<List<String>>
        get() = selectorRangesProperty

    private fun select(kind: String, ranges: List<String> = emptyList()) {
        check(!selectorKindProperty.isPresent) {
            "AOT compatibility selector is already configured. Select exactly one of current(), minimum(), all(), or versionRanges { ... }."
        }
        selectorKindProperty.set(kind)
        selectorRangesProperty.set(ranges)
    }
}

/**
 * Collects closed Wasmline version ranges for the Gradle DSL.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
public class VersionRangesSpec {
    internal val ranges: MutableList<WasmlineVersionRange> = mutableListOf()

    /** Adds one inclusive Wasmline version range. */
    public fun include(from: String, through: String) {
        ranges += WasmlineVersionRange(from = from, through = through)
    }
}

/**
 * Configures catalog-backed multi-profile Wasmtime AOT compilation.
 *
 * ```kotlin
 * wasmline {
 *     wasmtime {
 *         aotCompatibility {
 *             current()
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
    /** Profile selectors shared by Core and Component AOT builds. */
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

    /** Configures the [aotCompatibility] selector block. */
    public fun aotCompatibility(action: AotCompatibilityExtension.() -> Unit) {
        aotCompatibility.action()
    }
}
