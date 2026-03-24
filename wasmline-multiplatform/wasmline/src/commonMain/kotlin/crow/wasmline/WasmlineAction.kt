package crow.wasmline

import kotlin.jvm.JvmInline

/** Strongly typed identifiers used by generated Wasmline service glue. */
@JvmInline
value class WasmlineServiceId(val value: String)

/** Strongly typed identifiers used by generated Wasmline service glue. */
@JvmInline
value class WasmlineMethodId(val value: String)

/** Fully qualified action key used on the low-level action/payload transport. */
data class WasmlineAction(
    val service: WasmlineServiceId,
    val method: WasmlineMethodId,
) {
    val value: String get() = "${service.value}#${method.value}"
}

