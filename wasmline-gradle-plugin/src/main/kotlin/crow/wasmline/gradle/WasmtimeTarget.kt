package crow.wasmline.gradle

import java.io.Serializable

/**
 * A Wasmtime AOT compilation target.
 *
 * Known targets are exposed as named values for IDE completion. Use [custom]
 * only when Wasmtime requires a target triple that is not predefined here.
 */
public class WasmtimeTarget private constructor(public val targetName: String) : Serializable {
    override fun equals(other: Any?): Boolean = other is WasmtimeTarget && targetName == other.targetName

    override fun hashCode(): Int = targetName.hashCode()

    override fun toString(): String = targetName

    public companion object {
        public val PULLEY_32: WasmtimeTarget = WasmtimeTarget("pulley32")
        public val PULLEY_64: WasmtimeTarget = WasmtimeTarget("pulley64")
        public val X86_64_LINUX: WasmtimeTarget = WasmtimeTarget("x86_64-linux")
        public val AARCH64_LINUX: WasmtimeTarget = WasmtimeTarget("aarch64-linux")
        public val AARCH64_ANDROID: WasmtimeTarget = WasmtimeTarget("aarch64-android")
        public val X86_64_ANDROID: WasmtimeTarget = WasmtimeTarget("x86_64-android")
        public val AARCH64_MACOS: WasmtimeTarget = WasmtimeTarget("aarch64-macos")
        public val X86_64_MACOS: WasmtimeTarget = WasmtimeTarget("x86_64-macos")
        public val X86_64_WINDOWS: WasmtimeTarget = WasmtimeTarget("x86_64-windows")

        /** All targets used by the Gradle DSL when `wasmtime.targets` is not assigned. */
        public val ALL: List<WasmtimeTarget> = listOf(
            PULLEY_32,
            PULLEY_64,
            X86_64_LINUX,
            AARCH64_LINUX,
            AARCH64_ANDROID,
            X86_64_ANDROID,
            AARCH64_MACOS,
            X86_64_MACOS,
            X86_64_WINDOWS,
        )

        private val knownTargets = ALL.associateBy(WasmtimeTarget::targetName)

        /** The native CWASM target matching the machine running Gradle. */
        public val currentHost: WasmtimeTarget
            get() = fromHost(
                osName = System.getProperty("os.name"),
                osArch = System.getProperty("os.arch"),
            )

        /** Creates a target for an additional Wasmtime target name or triple. */
        public fun custom(targetName: String): WasmtimeTarget {
            val normalizedName = targetName.trim()
            require(normalizedName.isNotEmpty()) { "Wasmtime target name must not be blank." }
            return knownTargets[normalizedName] ?: WasmtimeTarget(normalizedName)
        }

        internal fun fromHost(osName: String, osArch: String): WasmtimeTarget {
            val os = osName.lowercase()
            val arch = osArch.lowercase()
            val isArm64 = arch == "aarch64" || arch == "arm64"
            val isX64 = arch == "x86_64" || arch == "amd64" || arch == "x64"

            return when {
                ("mac" in os || "darwin" in os) && isArm64 -> AARCH64_MACOS
                ("mac" in os || "darwin" in os) && isX64 -> X86_64_MACOS
                "linux" in os && isArm64 -> AARCH64_LINUX
                "linux" in os && isX64 -> X86_64_LINUX
                "windows" in os && isX64 -> X86_64_WINDOWS
                else -> error("Unsupported Wasmtime host: $osName $osArch")
            }
        }

        private const val serialVersionUID = 1L
    }
}
