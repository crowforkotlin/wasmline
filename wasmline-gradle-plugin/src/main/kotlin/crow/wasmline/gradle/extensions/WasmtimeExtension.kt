@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.aot.AotGenerations
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
 * [suppressCompatibilityWarning] all select [AotGenerations.Current]: the
 * generation containing the currently introduced Wasmline build-tool
 * version. The first [generations] call replaces that implicit default; later
 * calls add their strategies to the union, which is deduplicated across
 * generations and profiles. Selecting [AotGenerations.All] is an explicit
 * opt-in that may compile every retained generation.
 *
 * ```kotlin
 * wasmline {
 *     wasmtime {
 *         aotCompatibility {
 *             generations(AotGenerations.Current)
 *             generations(AotGenerations.Current(before = 1))
 *             generations("1.0.0")
 *             generations(versions = listOf("1.0.0", "1.0.1"))
 *             generations(min = "1.0.0", max = "1.0.1")
 *         }
 *     }
 * }
 * ```
 *
 * `Current(after = 1)` is the symmetric forward-looking form; it requires a
 * locally retained successor generation and fails before tool preparation
 * when none exists yet.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
public abstract class AotCompatibilityExtension @Inject constructor(objects: ObjectFactory) {
    private val generationStrategiesProperty: ListProperty<String> = objects.listProperty(String::class.java)
        .convention(emptyList())

    /** Controls whether the post-build compatibility warning is omitted from Gradle logging. */
    public val suppressCompatibilityWarning: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(false)

    /**
     * Selects the generations described by one typed [AotGenerations] strategy.
     *
     * The named singleton objects select the current release generation, the
     * newest retained generation, the oldest retained generation, or every
     * retained generation. `Current(before, after)`, `Latest(before)`, and
     * `Earliest(after)` include that many *additional* neighboring generations
     * around the anchor, which is always included and is not counted. Those
     * counts are AOT generations, not Maven release versions.
     * `AotGenerations.Versions` selects one concrete `x.y.z` version, a
     * collection of concrete versions, or a closed `min`..`max` interval.
     */
    public fun generations(strategy: AotGenerations) {
        generationStrategiesProperty.add(strategy.encodeForTaskInput())
    }

    /** Selects the AOT generation that contains one concrete `x.y.z` Wasmline version. */
    public fun generations(version: String) {
        generations(AotGenerations.Versions(version))
    }

    /** Selects the AOT generations containing each concrete `x.y.z` version in [versions]. */
    public fun generations(versions: Collection<String>) {
        generations(AotGenerations.Versions(versions))
    }

    /**
     * Selects every AOT generation intersecting the inclusive
     * concrete `min`..`max` Wasmline version interval.
     */
    public fun generations(min: String, max: String) {
        generations(AotGenerations.Versions(min, max))
    }

    internal val generationStrategies: Provider<List<String>>
        get() = generationStrategiesProperty
}

/**
 * Configures catalog-backed multi-profile Wasmtime AOT compilation.
 *
 * ```kotlin
 * wasmline {
 *     wasmtime {
 *         aotCompatibility {
 *             generations(AotGenerations.Current)
 *             generations(AotGenerations.Latest(before = 1))
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
