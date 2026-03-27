package crow.wasmline

import kotlin.reflect.KClass

@PublishedApi
internal fun <T : WasmlineService> linkInternal(
    contract: KClass<T>,
    invokeAction: (String, ByteArray) -> ByteArray,
): T {
    return WasmlineServiceRegistry.require(contract).link(invokeAction)
}

@PublishedApi
internal inline fun <reified T : WasmlineService> linkInternal(
    noinline invokeAction: (String, ByteArray) -> ByteArray,
): T {
    return linkInternal(T::class, invokeAction)
}

@PublishedApi
internal fun <T : WasmlineService> bindInternal(
    contract: KClass<T>,
    implementation: T,
    registerAction: (String, (ByteArray) -> ByteArray) -> Unit,
) {
    WasmlineServiceRegistry.require(contract).bind(implementation, registerAction)
}

@PublishedApi
internal inline fun <reified T : WasmlineService> bindAsInternal(
    implementation: WasmlineService,
    noinline registerAction: (String, (ByteArray) -> ByteArray) -> Unit,
) {
    check(T::class.isInstance(implementation)) {
        "Implementation ${implementation::class.qualifiedName} is not an instance of service contract ${T::class.qualifiedName}."
    }
    bindInternal(T::class, implementation as T, registerAction)
}

@PublishedApi
internal fun bindInternal(
    implementation: WasmlineService,
    registerAction: (String, (ByteArray) -> ByteArray) -> Unit,
) {
    val matches = WasmlineServiceRegistry.matching(implementation)
    when (matches.size) {
        0 -> error(
            "No Wasmline service definition matches implementation ${implementation::class.qualifiedName}. " +
                "Did the compiler plugin generate and register its contract definition?",
        )

        1 -> bindUnchecked(matches.single(), implementation, registerAction)

        else -> error(
            buildString {
                append("Multiple Wasmline service contracts match implementation ")
                append(implementation::class.qualifiedName)
                append(": ")
                append(matches.joinToString { it.contract.qualifiedName ?: it.contract.toString() })
                append(". Use bindAs<Contract>(implementation) or bind(Contract::class, implementation) to disambiguate.")
            },
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun bindUnchecked(
    definition: RegisteredServiceEntry<out WasmlineService>,
    implementation: WasmlineService,
    registerAction: (String, (ByteArray) -> ByteArray) -> Unit,
) {
    (definition as RegisteredServiceEntry<WasmlineService>).bind(implementation, registerAction)
}


