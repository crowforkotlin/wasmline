/**
 * Identifies the invocation protocol used by an artifact.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

package crow.wasmline

import kotlinx.serialization.Serializable

@Serializable
enum class WasmlineInvocationProtocol {
    /** Wasmline Service invocation over the artifact's selected execution model. */
    WASMLINE_SERVICE,
    COMPONENT_EXPORT,
    RAW_EXPORT,
}
