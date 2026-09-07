@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import crow.wasmline.RawAbiMetadata
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL extension for configuring the wasmline manifest metadata used
 * during the assembly tasks.
 *
 * ```kotlin
 * wasmline {
 *     manifest {
 *         pluginId = "crow.wasmline.demo"
 *         version = "1.0.0"
 *         versionCode = 1L
 *         minSdkVersion = "1.0.0"
 *         displayName = "Demo Plugin"
 *         author = "crow"
 *         description = "A sample wasmline plugin"
 *         signingKey = file("../keys/private.key")
 *     }
 * }
 * ```
 *
 * Date: 2026-06-05
 * Author: crowforkotlin
 */
public abstract class ManifestExtension @Inject constructor(objects: ObjectFactory) {

    /** Unique plugin identifier (e.g., "crow.wasmline.demo"). Required. */
    public val pluginId: Property<String> = objects.property(String::class.java)

    /** Semantic version string. Default: "1.0.0". */
    public val version: Property<String> = objects.property(String::class.java).convention("1.0.0")

    /** Integer version code. Default: 1. */
    public val versionCode: Property<Long> = objects.property(Long::class.java).convention(1L)

    /** Reproducible manifest build timestamp in milliseconds. Default: 0. */
    public val buildTimestamp: Property<Long> = objects.property(Long::class.java).convention(0L)

    /** Minimum wasmline SDK version required by this plugin. */
    public val minSdkVersion: Property<String> = objects.property(String::class.java).convention("1.0.0")

    /** Human-readable display name shown to users. */
    public val displayName: Property<String> = objects.property(String::class.java)

    /** Plugin author name. */
    public val author: Property<String> = objects.property(String::class.java)

    /** Short description of the plugin. */
    public val description: Property<String> = objects.property(String::class.java)

    /** URL or relative path to the plugin icon. */
    public val iconUrl: Property<String> = objects.property(String::class.java)

    /** Plugin home page or repository URL. */
    public val homePageUrl: Property<String> = objects.property(String::class.java)

    /**
     * Ed25519 private key file used to sign the manifest. The file content
     * should be a hex-encoded private key string. The file is read lazily
     * at task execution time, so it does not need to exist during Gradle
     * configuration.
     *
     * Usage: `signingKey = file("../keys/private.key")`
     */
    public val signingKey: RegularFileProperty = objects.fileProperty()

    /** Arbitrary metadata key-value pairs included in the manifest. */
    public val metadata: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)
        .convention(emptyMap())

    /** Runtime execution model of the published binary. */
    public val executionModel: Property<WasmlineExecutionModel> = objects.property(WasmlineExecutionModel::class.java)
        .convention(WasmlineExecutionModel.CORE_WASM)

    /** Invocation protocol used by the published binary. */
    public val invocationProtocol: Property<WasmlineInvocationProtocol> = objects.property(WasmlineInvocationProtocol::class.java)
        .convention(
            executionModel.map { model ->
                if (model == WasmlineExecutionModel.COMPONENT_MODEL) {
                    WasmlineInvocationProtocol.COMPONENT_EXPORT
                } else {
                    WasmlineInvocationProtocol.WASMLINE_SERVICE
                }
            },
        )

    /** Export name required by direct export invocation protocols. */
    public val exportName: Property<String> = objects.property(String::class.java)

    /** Type metadata for direct export invocation. */
    public val contractMetadata: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)
        .convention(emptyMap())

    /** Versioned scalar Core Wasm ABI metadata used by `RAW_EXPORT`. */
    public val rawAbi: Property<RawAbiMetadata> = objects.property(RawAbiMetadata::class.java)
}
