package crow.wasmline.internal.bridge

import kotlin.reflect.KClass

/** Legacy internal bridge contract kept only for runtime-local compatibility. */
internal interface ServiceDefinition<T : Any> {
    val contract: KClass<T>
    val serviceId: String

    fun link(invokeAction: (String, ByteArray) -> ByteArray): T

    fun bind(implementation: T, registerAction: (String, (ByteArray) -> ByteArray) -> Unit)
}

