@file:Suppress("unused")

package crow.wasmline.internal.bridge

/**
 * Internal runtime contract implemented by compiler-generated Wasmline bridge classes.
 *
 * Generated code uses one bridge class per Wasmline service contract. That bridge instance serves
 * both as the linked proxy (typed contract calls -> endpoint.invoke) and as the binder dispatcher
 * (action,payload -> implementation method).
 */
@PublishedApi
internal interface WasmlineGeneratedBridge {
    operator fun invoke(action: String, payload: ByteArray): ByteArray

    fun bind(registerAction: (String, (ByteArray) -> ByteArray) -> Unit)
}

/** Fails fast when a generated bridge is used before being linked to a live endpoint. */
@PublishedApi
internal object UnlinkedWasmlineEndpoint : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        error(
            "Wasmline generated bridge is not linked to a transport endpoint. " +
                "Did the compiler plugin fail to replace link()/bind() at the call site?",
        )
    }
}

/** Fails fast when a generated binder bridge is invoked without a concrete implementation. */
@PublishedApi
internal fun <T : Any> requireGeneratedImplementation(implementation: T?, contractId: String): T {
    return implementation ?: error(
        "Generated Wasmline bridge for $contractId does not hold a bound implementation. " +
            "Did the compiler plugin wire bind() correctly?",
    )
}

/** Fails fast for unknown generated actions reaching a bridge dispatcher. */
@PublishedApi
internal fun unknownGeneratedAction(contractId: String, action: String): Nothing {
    error("Unknown Wasmline action '$action' for generated bridge $contractId.")
}

