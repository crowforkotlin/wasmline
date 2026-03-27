@file:Suppress("unused")

package crow.wasmline.internal.bridge

import kotlin.jvm.JvmInline

/** Strongly typed identifiers used by generated Wasmline service glue. */
@JvmInline
value class ServiceId(val value: String)

/** Strongly typed identifiers used by generated Wasmline service glue. */
@JvmInline
value class MethodId(val value: String)

/** Fully qualified action key used on the low-level action/payload transport. */
data class Action(
    val service: ServiceId,
    val method: MethodId,
) {
    val value: String get() = "${service.value}#${method.value}"
}

