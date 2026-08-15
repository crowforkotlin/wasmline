@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File
import javax.inject.Inject

/**
 * DSL extension for configuring the wasmtime AOT compiler used
 * during the assembly tasks.
 *
 * ```kotlin
 * wasmline {
 *     wasmtime {
 *         // Optional: defaults to ~/.wasmline/wasmtime (base directory).
 *         // Can point to:
 *         //   - A base directory containing versioned subdirectories
 *         //     (e.g. ~/.wasmline/wasmtime/wasmtime-v47.0.2-x86_64-linux-min/)
 *         //   - A specific versioned directory containing the wasmtime executable
 *         directory = file(System.getenv("WASMTIME_MIN_HOME") ?: "$home/.wasmline/wasmtime")
 *
 *         targets = listOf(
 *             WasmtimeTarget.PULLEY_64,
 *             WasmtimeTarget.AARCH64_ANDROID,
 *             WasmtimeTarget.X86_64_LINUX,
 *         )
 *
 *         // Optional: enable automatic download if wasmtime is not found
 *         autoDownload = true
 *         version = "latest" // or specific version like "v47.0.2"
 *     }
 * }
 * ```
 *
 * Date: 2026-06-05
 * Author: crowforkotlin
 */
abstract class WasmtimeExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Directory for locating the `wasmtime` executable.
     *
     * Resolution strategy (in order):
     * 1. If the directory directly contains the executable, use it.
     * 2. If the directory contains versioned subdirectories
     *    (e.g. `wasmtime-v47.0.2-x86_64-linux-min/`), search them.
     * 3. Fall back to `WASMTIME_ROOT` environment variable.
     * 4. Fall back to `~/.wasmline/wasmtime`.
     *
     * The CLI `wasmline download -o <dir>` downloads into
     * `{dir}/wasmtime-{version}-{platform}-min/`, so pointing [directory]
     * at the CLI output directory is the recommended convention.
     */
    val directory: DirectoryProperty = objects.directoryProperty()

    /**
     * Target architectures for AOT compilation.
     *
     * By default, the plugin compiles every target in [WasmtimeTarget.ALL]. An
     * explicit assignment replaces that convention, similar to an NDK ABI
     * filter.
     *
     * ```kotlin
     * targets = listOf(
     *     WasmtimeTarget.PULLEY_64,
     *     WasmtimeTarget.AARCH64_ANDROID,
     * )
     * ```
     *
     * Use [WasmtimeTarget.custom] for an additional Wasmtime target triple.
     * iOS always uses [WasmtimeTarget.PULLEY_64] because its native runtime is
     * interpreter-only; direct iOS CWASM targets are rejected downstream.
     */
    private val configuredTargets: ListProperty<WasmtimeTarget> = objects.listProperty(WasmtimeTarget::class.java)
        .convention(WasmtimeTarget.ALL)

    internal val targetsProvider: Provider<List<WasmtimeTarget>>
        get() = configuredTargets

    var targets: List<WasmtimeTarget>
        get() = configuredTargets.get()
        set(value) {
            configuredTargets.set(value)
        }

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

    /** Exact full Wasmtime CLI version used only for Component AOT compilation. */
    val compilerVersion: Property<String> = objects.property(String::class.java)
        .convention(ToolchainCatalog.WASMTIME_VERSION)

    /** Separate cache root for the full build-time CLI; runtime-min remains in [directory]. */
    val compilerDirectory: DirectoryProperty = objects.directoryProperty().apply {
        set(File(System.getProperty("user.home"), ".wasmline/wasmtime-compiler"))
    }

    /** Optional explicit full Wasmtime CLI executable. `wasmtime-min` is rejected. */
    val compilerExecutable: RegularFileProperty = objects.fileProperty()

    /**
     * GitHub token for authenticated API requests.
     *
     * When set, all GitHub API calls use Bearer token authentication,
     * increasing the rate limit from 60/hour to 5,000/hour.
     *
     * Configuration methods (in priority order):
     * 1. Direct value: `githubToken.set("your_token_here")`
     * 2. File path: `githubToken.set(file("~/.wasmline/github-token"))`
     * 3. Environment variable (via property): Not required if env var already set globally
     *
     * If neither set, uses environment variables GITHUB_TOKEN, GithubToken, etc.
     *
     * Example:
     * ```kotlin
     * wasmline {
     *     wasmtime {
     *         githubToken.set(System.getenv("GITHUB_TOKEN"))
     *     }
     * }
     * ```
     *
     * Or for CI environments:
     * ```kotlin
     * wasmline {
     *     wasmtime {
     *         githubToken.set(project.providers.environmentVariable("GITHUB_TOKEN"))
     *     }
     * }
     * ```
     */
    val githubToken: Property<String> = objects.property(String::class.java)
}
