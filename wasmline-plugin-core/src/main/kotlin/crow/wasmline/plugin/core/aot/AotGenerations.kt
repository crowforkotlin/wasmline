@file:OptIn(crow.wasmline.plugin.core.InternalWasmlineToolingApi::class)

package crow.wasmline.plugin.core.aot

/** Selects native AOT generations from the catalog packaged with the build tools. */
public sealed interface AotGenerations {
    /** The generation containing the currently introduced Wasmline build-tool version. */
    public data object Current : AotGenerations {
        /** Includes the anchor plus [before] earlier and [after] later AOT generations. */
        public operator fun invoke(before: Int = 0, after: Int = 0): AotGenerations =
            generationWindow(AotGenerationAnchor.CURRENT, before, after)
    }

    /** The newest generation retained in the local catalog, without a remote lookup. */
    public data object Latest : AotGenerations {
        /** Includes the newest generation plus [before] earlier AOT generations. */
        public operator fun invoke(before: Int = 0): AotGenerations = generationWindow(AotGenerationAnchor.LATEST, before, 0)
    }

    /** The oldest generation retained in the local catalog. */
    public data object Earliest : AotGenerations {
        /** Includes the oldest generation plus [after] later AOT generations. */
        public operator fun invoke(after: Int = 0): AotGenerations = generationWindow(AotGenerationAnchor.EARLIEST, 0, after)
    }

    /** Every retained generation; opting in may require many compiler invocations. */
    public data object All : AotGenerations {
        public operator fun invoke(): AotGenerations = this
    }

    /** Concrete stable Wasmline versions, or a closed `min`..`max` version interval. */
    public class Versions internal constructor(internal val ranges: List<WasmlineVersionRange>) : AotGenerations {
        public constructor(version: String) : this(listOf(WasmlineVersionRange(version, version)))

        public constructor(versions: Collection<String>) : this(
            versions.map { version -> WasmlineVersionRange(version, version) },
        )

        public constructor(min: String, max: String) : this(listOf(WasmlineVersionRange(min, max)))

        init {
            require(ranges.isNotEmpty()) { "AOT version selection must contain at least one version." }
        }

        override fun equals(other: Any?): Boolean = other is Versions && ranges == other.ranges

        override fun hashCode(): Int = ranges.hashCode()

        override fun toString(): String = ranges.joinToString(prefix = "Versions(", postfix = ")") { range ->
            if (range.from == range.through) range.from else "${range.from}..${range.through}"
        }
    }
}

internal enum class AotGenerationAnchor(val token: String) {
    CURRENT("current"),
    LATEST("latest"),
    EARLIEST("earliest"),
}

internal data class AotGenerationWindow(val anchor: AotGenerationAnchor, val before: Int, val after: Int) : AotGenerations {
    init {
        require(before >= 0 && after >= 0) { "AOT generation counts must be nonnegative." }
        require(anchor != AotGenerationAnchor.LATEST || after == 0) {
            "Latest AOT generation selection cannot include later generations."
        }
        require(anchor != AotGenerationAnchor.EARLIEST || before == 0) {
            "Earliest AOT generation selection cannot include earlier generations."
        }
    }
}

private fun generationWindow(anchor: AotGenerationAnchor, before: Int, after: Int): AotGenerations = if (before == 0 && after == 0) {
    when (anchor) {
        AotGenerationAnchor.CURRENT -> AotGenerations.Current
        AotGenerationAnchor.LATEST -> AotGenerations.Latest
        AotGenerationAnchor.EARLIEST -> AotGenerations.Earliest
    }
} else {
    AotGenerationWindow(anchor, before, after)
}
