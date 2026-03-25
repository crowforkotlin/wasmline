package crow.wasmline

import crow.wasmline.spi.ServiceDefinition
import crow.wasmline.spi.WasmlineBindingScope
import crow.wasmline.spi.WasmlineEndpoint
import kotlin.reflect.KClass

@PublishedApi
internal fun <T : WasmlineService> WasmlineEndpoint.linkInternal(contract: KClass<T>): T {
    return WasmlineServiceRegistry.require(contract).link(this)
}

@PublishedApi
internal fun <T : WasmlineService> WasmlineBindingScope.bindInternal(contract: KClass<T>, implementation: T) {
    WasmlineServiceRegistry.require(contract).bind(implementation, this)
}

@PublishedApi
internal fun WasmlineBindingScope.bindInternal(implementation: WasmlineService) {
    val matches = WasmlineServiceRegistry.matching(implementation)
    when (matches.size) {
        0 -> error(
            "No Wasmline service definition matches implementation ${implementation::class.qualifiedName}. " +
                "Did the compiler plugin generate and register its contract definition?",
        )

        1 -> bindUnchecked(matches.single(), implementation)

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
private fun WasmlineBindingScope.bindUnchecked(
    definition: ServiceDefinition<out WasmlineService>,
    implementation: WasmlineService,
) {
    (definition as ServiceDefinition<WasmlineService>).bind(implementation, this)
}


