/**
 * Stable identifiers for the Wasmline Service envelope Component contract.
 *
 * Date: 2026-08-05
 * Author: crowforkotlin
 */
package crow.wasmline

object WasmlineComponentServiceContract {
    const val WIT_PACKAGE = "wasmline:service@1.0.0"
    const val WORLD = "plugin"
    const val DEFAULT_EXPORT = "plugin/invoke"
    const val DEFAULT_CODEC = "protobuf"
    const val PROFILE = "wasmline-envelope"
    const val VERSION = "1"

    const val METADATA_WIT_PACKAGE = "wasmline.service.wit-package"
    const val METADATA_PROFILE = "wasmline.service.profile"
    const val METADATA_CODEC = "wasmline.service.codec"
    const val METADATA_VERSION = "wasmline.service.version"
}
