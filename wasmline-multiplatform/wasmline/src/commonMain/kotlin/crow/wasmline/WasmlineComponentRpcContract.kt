/**
 * Stable identifiers for the Wasmline byte-envelope Component contract.
 *
 * Date: 2026-08-05
 * Author: crowforkotlin
 */
package crow.wasmline

object WasmlineComponentRpcContract {
    const val WIT_PACKAGE = "wasmline:rpc@1.0.0"
    const val WORLD = "plugin"
    const val DEFAULT_EXPORT = "plugin/invoke"
    const val DEFAULT_CODEC = "protobuf"
    const val PROFILE = "wasmline-envelope"
    const val VERSION = "1"

    const val METADATA_WIT_PACKAGE = "wasmline.rpc.wit-package"
    const val METADATA_PROFILE = "wasmline.rpc.profile"
    const val METADATA_CODEC = "wasmline.rpc.codec"
    const val METADATA_VERSION = "wasmline.rpc.version"
}
