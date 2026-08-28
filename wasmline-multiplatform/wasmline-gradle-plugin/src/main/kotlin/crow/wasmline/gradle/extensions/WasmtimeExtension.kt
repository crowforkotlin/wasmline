@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.WasmtimeTarget
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File
import javax.inject.Inject

/**
 * Selects immutable AOT compatibility profiles from the Wasmline catalog.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
public abstract class AotCompatibilityExtension @Inject constructor(objects: ObjectFactory) {
    /** Complete Wasmtime x.y.z versions resolved once for each requested backend. */
    public val wasmtimeVersions: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())

    /** Exact backend-specific AOT compatibility profile IDs. */
    public val profileIds: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
}

/**
 * Configures catalog-backed multi-profile Wasmtime AOT compilation.
 *
 * ```kotlin
 * wasmline {
 *     wasmtime {
 *         aotCompatibility {
 *             wasmtimeVersions.set(listOf("47.0.3", "48.0.0"))
 *         }
 *         targets = listOf(WasmtimeTarget.PULLEY_64, WasmtimeTarget.X86_64_LINUX)
 *         autoDownload.set(true)
 *     }
 * }
 * ```
 *
 * Date: 2026-08-28
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
