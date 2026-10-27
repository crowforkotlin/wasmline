package crow.wasmline.plugin.core.component

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.component.hostgen.WitSources
import java.io.File

/**
 * Computes the `component.wit.sha256` identity for one WIT file or directory.
 *
 * The algorithm is documented in the Component Service reference. This entry
 * point lets external tooling print the same value the build records.
 */
@InternalWasmlineToolingApi
object WitDigest {
    fun sha256(path: File): String = WitSources.load(path).sha256
}
