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
    WASMLINE_CORE_V1,
    COMPONENT_EXPORT,
    RAW_EXPORT,
}
